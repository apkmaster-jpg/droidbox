#!/usr/bin/env python3
"""Populate a local file-based Maven repository using curl (which works
through the sandbox egress proxy, unlike Java's TLS stack).

Resolves the full transitive POM closure for the given root artifacts and
downloads every .pom and .jar into Maven layout under the output dir.

Usage: python3 fetch-maven-repo.py <output-dir>
"""
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

OUT = sys.argv[1]

# (group, artifact, version) roots. Repos tried in order.
REPOS = [
    "https://dl.google.com/dl/android/maven2",
    "https://repo.maven.apache.org/maven2",
    "https://maven-central.storage-download.googleapis.com/maven2",
]

ROOTS = [
    # Gradle plugin markers (we generate these; listed for completeness)
    # Real plugin artifacts:
    ("com.android.tools.build", "gradle", "8.5.2"),
    ("org.jetbrains.kotlin", "kotlin-gradle-plugin", "2.0.20"),
    ("org.jetbrains.kotlin", "compose-compiler-gradle-plugin", "2.0.20"),
    # App dependencies (BOM members resolved via the BOM pom):
    ("androidx.compose", "compose-bom", "2024.09.00"),
    ("androidx.core", "core-ktx", "1.13.1"),
    ("androidx.activity", "activity-compose", "1.9.2"),
]

NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def curl(url, out_path):
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    if os.path.exists(out_path) and os.path.getsize(out_path) > 0:
        return True
    r = subprocess.run(
        ["curl", "-sS", "-L", "--retry", "3", "--max-time", "60", "-o", out_path,
         "-w", "%{http_code}", url],
        capture_output=True, text=True)
    code = r.stdout.strip()[-3:]
    if code != "200":
        if os.path.exists(out_path):
            os.remove(out_path)
        return False
    return True


def repo_path(group, artifact, version, ext):
    g = group.replace(".", "/")
    return f"{OUT}/{g}/{artifact}/{version}/{artifact}-{version}.{ext}"


def fetch_pom(group, artifact, version):
    """Return local pom path, or None."""
    for repo in REPOS:
        url = f"{repo}/{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.pom"
        lp = repo_path(group, artifact, version, "pom")
        if curl(url, lp):
            return lp
    return None


def text(el, name):
    c = el.find(f"m:{name}", NS)
    return c.text.strip() if c is not None and c.text else None


def parse_pom(path):
    tree = ET.parse(path)
    root = tree.getroot()
    return root


def get_props(root):
    props = {}
    pp = root.find("m:properties", NS)
    if pp is not None:
        for c in pp:
            tag = c.tag
            if tag.startswith("{"):
                tag = tag.split("}", 1)[1]
            if c.text:
                props[tag] = c.text.strip()
    return props


SUBST = re.compile(r"\$\{([^}]+)\}")


def subst(s, props, project_version, project_group):
    def rep(m):
        k = m.group(1)
        if k == "project.version":
            return project_version or m.group(0)
        if k == "project.groupId":
            return project_group or m.group(0)
        return props.get(k, m.group(0))
    prev = None
    while prev != s:
        prev = s
        s = SUBST.sub(rep, s)
    return s


def effective_model(group, artifact, version, seen_poms):
    """Return (props, depMgmt dict, dependencies list). Follows parents."""
    key = (group, artifact, version)
    if key in seen_poms:
        return {}, {}, []
    seen_poms.add(key)
    lp = fetch_pom(group, artifact, version)
    if not lp:
        print(f"  WARN: no POM for {group}:{artifact}:{version}")
        return {}, {}, []
    root = parse_pom(lp)
    props, depMgmt, deps = {}, {}, []

    parent = root.find("m:parent", NS)
    if parent is not None:
        pg, pa, pv = (text(parent, "groupId"), text(parent, "artifactId"),
                      text(parent, "version"))
        if pg and pa and pv:
            pprops, pdepMgmt, _ = effective_model(pg, pa, pv, seen_poms)
            props.update(pprops)
            depMgmt.update(pdepMgmt)

    props.update(get_props(root))
    gv = text(root, "groupId") or group
    av = text(root, "artifactId") or artifact
    vv = text(root, "version") or version

    dm = root.find("m:dependencyManagement", NS)
    if dm is not None:
        for d in dm.findall("m:dependencies/m:dependency", NS):
            dg, da, dv = (text(d, "groupId"), text(d, "artifactId"), text(d, "version"))
            if dg and da and dv:
                dg = subst(dg, props, vv, gv)
                da = subst(da, props, vv, gv)
                dv = subst(dv, props, vv, gv)
                # skip import-scoped BOMs here (handled as roots); keep versions
                depMgmt[(dg, da)] = dv

    for d in root.findall("m:dependencies/m:dependency", NS):
        dg, da, dv = (text(d, "groupId"), text(d, "artifactId"), text(d, "version"))
        scope = text(d, "scope") or "compile"
        optional = text(d, "optional") == "true"
        dtype = text(d, "type") or "jar"
        if not dg or not da:
            continue
        dg = subst(dg, props, vv, gv)
        da = subst(da, props, vv, gv)
        if dv:
            dv = subst(dv, props, vv, gv)
        else:
            dv = depMgmt.get((dg, da))
        if not dv or "${" in dv:
            continue
        if scope in ("test",) or optional:
            continue
        if dtype not in ("jar", "bundle"):
            continue
        deps.append((dg, da, dv))
    return props, depMgmt, deps


def main():
    os.makedirs(OUT, exist_ok=True)
    queue = list(ROOTS)
    done = set()
    # also fetch poms for depMgmt-only lookups
    while queue:
        group, artifact, version = queue.pop(0)
        key = (group, artifact, version)
        if key in done:
            continue
        done.add(key)
        print(f"resolving {group}:{artifact}:{version} ({len(done)} total)")
        _, depMgmt, deps = effective_model(group, artifact, version, set())
        # download the jar for this artifact (unless pom-only packaging)
        got_jar = False
        for repo in REPOS:
            url = (f"{repo}/{group.replace('.', '/')}/{artifact}/{version}/"
                   f"{artifact}-{version}.jar")
            if curl(url, repo_path(group, artifact, version, "jar")):
                got_jar = True
                break
        # Gradle Module Metadata: Gradle prefers .module over .pom when both
        # exist; without it, resolution can demand versions the POMs don't list.
        for repo in REPOS:
            url = (f"{repo}/{group.replace('.', '/')}/{artifact}/{version}/"
                   f"{artifact}-{version}.module")
            if curl(url, repo_path(group, artifact, version, "module")):
                break
        if not got_jar and depMgmt:
            # pom-only artifact (BOM): expand its managed deps as roots
            n = 0
            for (dg, da), dv in sorted(depMgmt.items()):
                if (dg, da, dv) not in done:
                    queue.append((dg, da, dv))
                    n += 1
            print(f"  pom-only: expanded {n} managed artifacts")
        for d in deps:
            if d not in done:
                queue.append(d)
    print(f"DONE: {len(done)} artifacts in {OUT}")

    # Generate plugin marker POMs so the `plugins {}` block resolves locally.
    markers = [
        ("com.android.application", "com.android.application.gradle.plugin",
         "8.5.2", "com.android.tools.build", "gradle", "8.5.2"),
        ("org.jetbrains.kotlin.android", "org.jetbrains.kotlin.android.gradle.plugin",
         "2.0.20", "org.jetbrains.kotlin", "kotlin-gradle-plugin", "2.0.20"),
        ("org.jetbrains.kotlin.plugin.compose",
         "org.jetbrains.kotlin.plugin.compose.gradle.plugin",
         "2.0.20", "org.jetbrains.kotlin", "compose-compiler-gradle-plugin", "2.0.20"),
    ]
    for mg, ma, mv, tg, ta, tv in markers:
        p = repo_path(mg, ma, mv, "pom")
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w") as f:
            f.write(f"""<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>{mg}</groupId><artifactId>{ma}</artifactId><version>{mv}</version>
  <packaging>pom</packaging>
  <dependencies><dependency>
    <groupId>{tg}</groupId><artifactId>{ta}</artifactId><version>{tv}</version>
  </dependency></dependencies>
</project>""")
        print(f"marker: {mg}:{ma}:{mv}")
    print("markers done")


if __name__ == "__main__":
    main()
