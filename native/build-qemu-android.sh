#!/bin/bash
#
# DroidBox: build QEMU 11.1.2 as a native Android shared library.
#
# Produces: libqemu-system-x86_64.so  (arm64-v8a host, x86_64-softmmu guest, TCG)
# Method: adapted from the proven QubeVM recipe (GPL-3.0, attributed in
#         DroidBox-third-party-notices.txt). Key mechanism: QEMU's meson build
#         gains a `droidbox_android_so` option that builds the system emulator
#         as shared_library() instead of executable(), plus bionic workarounds.
#
# Prerequisites: ANDROID_HOME with ndk;30.0.16248370, cmake not needed here,
#                meson + ninja on PATH, curl, patch, python3.
#
# This script is idempotent: completed phases are skipped on re-run.
#
set -euo pipefail

QEMU_VERSION="11.1.2"
APP_ABI="arm64-v8a"
BUILD_GUEST="x86_64-softmmu"
NDK_VERSION="30.0.16248370"
NDK_PLATFORM_API="30"
BUILD_THREADS="$(nproc)"

DROIDBOX_ROOT="$HOME/workspace/droidbox"
NATIVE_ROOT="$DROIDBOX_ROOT/native"
THIRD_PARTY="$DROIDBOX_ROOT/third-party"
REF="$DROIDBOX_ROOT/reference/qubevm"
WORK="$NATIVE_ROOT/work"
DEPS_SRC="$WORK/deps-src"
DEPS_OUT="$WORK/deps-out"          # installed dep artifacts (headers+libs)
QEMU_PRISTINE="$THIRD_PARTY/qemu-$QEMU_VERSION"
QEMU_WORK="$WORK/qemu-$QEMU_VERSION-droidbox"
QEMU_BUILD="$QEMU_WORK/build"
LOG="$DROIDBOX_ROOT/logs/qemu-android-build.log"

ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
NDK_ROOT="$ANDROID_HOME/ndk/$NDK_VERSION"
TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64"
SYSROOT="$TOOLCHAIN/sysroot"
CLANG="$TOOLCHAIN/bin/clang"
CLANGXX="$TOOLCHAIN/bin/clang++"
WRAPPER="$TOOLCHAIN/bin/aarch64-linux-android$NDK_PLATFORM_API-clang"
TARGET_FLAG="-target aarch64-linux-android$NDK_PLATFORM_API"

# Pinned dependency sources (all URLs verified HTTP 200 on 2026-10-12)
LIBFFI_VER="3.8.0"
LIBFFI_URL="https://github.com/libffi/libffi/releases/download/v${LIBFFI_VER}/libffi-${LIBFFI_VER}.tar.gz"
GLIB_VER="2.88.3"
GLIB_URL="https://download.gnome.org/sources/glib/2.88/glib-${GLIB_VER}.tar.xz"
PIXMAN_VER="0.46.4"
PIXMAN_URL="https://cairographics.org/releases/pixman-${PIXMAN_VER}.tar.gz"
LIBSLIRP_VER="4.9.4"
LIBSLIRP_URL="https://gitlab.freedesktop.org/slirp/libslirp/-/archive/v${LIBSLIRP_VER}/libslirp-v${LIBSLIRP_VER}.tar.gz"
DTC_REV="b6910bec11614980a21e46fbccc35934b671bd81"   # pinned by qemu subprojects/dtc.wrap
DTC_URL="https://gitlab.com/qemu-project/dtc/-/archive/${DTC_REV}/dtc-${DTC_REV}.tar.gz"
DTC_FALLBACK_URL="https://git.kernel.org/pub/scm/utils/dtc/dtc.git/snapshot/dtc-v1.7.2.tar.gz"

mkdir -p "$WORK" "$DEPS_SRC" "$DEPS_OUT" "$DROIDBOX_ROOT/logs"
exec > >(tee -a "$LOG") 2>&1

log() { echo "[droidbox-build] $*"; }
die() { echo "[droidbox-build] FATAL: $*" >&2; exit 1; }

[ -x "$WRAPPER" ] || die "NDK clang wrapper missing: $WRAPPER (is the NDK installed?)"
command -v meson >/dev/null || die "meson not on PATH"
command -v ninja >/dev/null || die "ninja not on PATH"
[ -d "$QEMU_PRISTINE" ] || die "pristine QEMU source missing: $QEMU_PRISTINE"

fetch() { # url destdir
    local url="$1" destdir="$2" fname="$3"
    mkdir -p "$destdir"
    if [ -s "$destdir/$fname" ]; then log "already have $fname"; return 0; fi
    log "fetching $fname"
    curl -sSL --retry 3 -o "$destdir/$fname" "$url" || die "download failed: $url"
    log "got $fname ($(du -h "$destdir/$fname" | cut -f1))"
}

extract() { # archive destdir; echoes only the extracted dir path on stdout
    local archive="$1" destdir="$2" top
    top="$(tar tf "$archive" 2>/dev/null | head -1 | cut -d/ -f1)"
    if [ -d "$destdir/$top" ]; then echo "[droidbox-build] already extracted $top" >&2; echo "$destdir/$top"; return 0; fi
    echo "[droidbox-build] extracting $(basename "$archive")" >&2
    mkdir -p "$destdir" && tar --no-same-owner -xf "$archive" -C "$destdir" || die "extract failed: $archive"
    echo "$destdir/$top"
}

# ---------------------------------------------------------------- phase 1: dep sources
log "=== phase 1: fetching dependency sources ==="
fetch "$LIBFFI_URL"  "$DEPS_SRC" "libffi-$LIBFFI_VER.tar.gz"
fetch "$GLIB_URL"    "$DEPS_SRC" "glib-$GLIB_VER.tar.xz"
fetch "$PIXMAN_URL"  "$DEPS_SRC" "pixman-$PIXMAN_VER.tar.gz"
fetch "$LIBSLIRP_URL" "$DEPS_SRC" "libslirp-$LIBSLIRP_VER.tar.gz"
if [ ! -s "$DEPS_SRC/dtc.tar.gz" ]; then
    curl -sSL --retry 3 -o "$DEPS_SRC/dtc.tar.gz" "$DTC_URL" || \
    curl -sSL --retry 3 -o "$DEPS_SRC/dtc.tar.gz" "$DTC_FALLBACK_URL" || \
    die "dtc download failed"
fi

LIBFFI_SRC="$(extract "$DEPS_SRC/libffi-$LIBFFI_VER.tar.gz" "$DEPS_SRC")"
GLIB_SRC="$(extract "$DEPS_SRC/glib-$GLIB_VER.tar.xz" "$DEPS_SRC")"
PIXMAN_SRC="$(extract "$DEPS_SRC/pixman-$PIXMAN_VER.tar.gz" "$DEPS_SRC")"

# ---------------------------------------------------------------- phase 2: libffi (autotools, static)
if [ ! -f "$DEPS_OUT/lib/libffi.a" ]; then
log "=== phase 2: building libffi $LIBFFI_VER ==="
cd "$LIBFFI_SRC"
./configure --host=aarch64-linux-android \
    --disable-shared --enable-static --disable-docs \
    CC="$WRAPPER" CFLAGS="--sysroot=$SYSROOT -O2" \
    > "$WORK/libffi-configure.log" 2>&1 || die "libffi configure failed (see $WORK/libffi-configure.log)"
make -j"$BUILD_THREADS" > "$WORK/libffi-make.log" 2>&1 || die "libffi build failed"
mkdir -p "$DEPS_OUT/lib" "$DEPS_OUT/include"
cp -f "$(find "$LIBFFI_SRC" -name 'libffi.a' | head -1)" "$DEPS_OUT/lib/"
# Install the generated headers exactly as `make install` would: the
# arch-specific include dir holds both ffi.h and ffitarget.h (ffi.h includes
# <ffitarget.h>). Copy both; never the msvc_build variants.
FFI_INC_DIR="$LIBFFI_SRC/aarch64-unknown-linux-android/include"
[ -f "$FFI_INC_DIR/ffi.h" ] || die "libffi headers not found in $FFI_INC_DIR"
cp -f "$FFI_INC_DIR/ffi.h" "$FFI_INC_DIR/ffitarget.h" "$DEPS_OUT/include/"
log "libffi done: $(ls -la "$DEPS_OUT/lib/libffi.a" | awk '{print $5}') bytes"
else log "phase 2: libffi already built, skipping"; fi

# ---------------------------------------------------------------- phase 3: glib (meson, patched)
if [ ! -f "$DEPS_OUT/lib/libglib-2.0.so" ]; then
log "=== phase 3: building glib $GLIB_VER ==="
cd "$GLIB_SRC"
if ! patch -p1 --dry-run -s < "$REF/glib-2.88.3.patch" >/dev/null 2>&1; then
    log "glib patch already applied (or not applicable), continuing"
else
    patch -p1 < "$REF/glib-2.88.3.patch" || die "glib patch failed"
fi
GLIB_BUILD="$WORK/glib-build"
GLIB_CROSS="$WORK/glib-android-cross.ini"
cat > "$GLIB_CROSS" <<EOF
[binaries]
c = '$WRAPPER'
cpp = '$TOOLCHAIN/bin/aarch64-linux-android$NDK_PLATFORM_API-clang++'
ar = '$TOOLCHAIN/bin/llvm-ar'
strip = '$TOOLCHAIN/bin/llvm-strip'
pkgconfig = 'pkg-config'

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF
FAKE_PC="$WORK/glib-pkgconfig"
mkdir -p "$FAKE_PC"
cat > "$FAKE_PC/libffi.pc" <<EOF
Name: libffi
Description: libffi
Version: $LIBFFI_VER
Cflags: -I$DEPS_OUT/include
Libs: $DEPS_OUT/lib/libffi.a
EOF
# Meson's own downloader can't use this environment's egress proxy, so
# pre-seed fallback subproject sources with curl (verified URLs).
SUB_TARBALLS="$WORK/subproject-tarballs"
mkdir -p "$SUB_TARBALLS"
seed_subproject() { # url filename dirname [patch_url patch_filename]
    local url="$1" fname="$2" dname="$3"
    local patch_url="${4:-}" patch_fname="${5:-}"
    if [ ! -s "$SUB_TARBALLS/$fname" ]; then
        log "fetching subproject $fname"
        curl -sSL --retry 3 -o "$SUB_TARBALLS/$fname" "$url" || die "subproject download failed: $url"
    fi
    if [ ! -d "$GLIB_SRC/subprojects/$dname" ]; then
        log "seeding subproject $dname"
        mkdir -p "$GLIB_SRC/subprojects/$dname"
        tar --no-same-owner -xf "$SUB_TARBALLS/$fname" -C "$GLIB_SRC/subprojects/$dname" --strip-components=1 \
            || die "subproject extract failed: $fname"
    fi
    # Wrapdb patch zips supply the meson.build files upstream tarballs lack.
    if [ -n "$patch_url" ] && [ ! -f "$GLIB_SRC/subprojects/$dname/meson.build" ]; then
        if [ ! -s "$SUB_TARBALLS/$patch_fname" ]; then
            log "fetching wrapdb patch $patch_fname"
            curl -sSL --retry 3 -o "$SUB_TARBALLS/$patch_fname" "$patch_url" \
                || die "wrapdb patch download failed: $patch_url"
        fi
        log "applying wrapdb patch to $dname"
        python3 - "$SUB_TARBALLS/$patch_fname" "$GLIB_SRC/subprojects/$dname" <<'EOF'
import sys, zipfile, os
z = zipfile.ZipFile(sys.argv[1]); dest = sys.argv[2]
for info in z.infolist():
    parts = [p for p in info.filename.split('/') if p]
    if len(parts) < 2: continue
    target = os.path.join(dest, *parts[1:])
    os.makedirs(os.path.dirname(target), exist_ok=True)
    with z.open(info) as src, open(target, 'wb') as dst: dst.write(src.read())
EOF
    fi
}
seed_subproject "https://github.com/PCRE2Project/pcre2/releases/download/pcre2-10.46/pcre2-10.46.tar.bz2" \
    "pcre2-10.46.tar.bz2" "pcre2-10.46" \
    "https://wrapdb.mesonbuild.com/v2/pcre2_10.46-1/get_patch" "pcre2_10.46-1_patch.zip"
seed_subproject "https://github.com/mesonbuild/wrapdb/releases/download/zlib_1.3.1-1/zlib-1.3.1.tar.gz" \
    "zlib-1.3.1.tar.gz" "zlib-1.3.1" \
    "https://wrapdb.mesonbuild.com/v2/zlib_1.3.1-1/get_patch" "zlib_1.3.1-1_patch.zip"
seed_subproject "https://github.com/frida/proxy-libintl/archive/refs/tags/0.5.tar.gz" \
    "proxy-libintl-0.5.tar.gz" "proxy-libintl-0.5"
# A previous failed meson setup leaves a stale build dir; start clean.
rm -rf "$GLIB_BUILD"
PKG_CONFIG_PATH="$FAKE_PC" PKG_CONFIG_LIBDIR="$FAKE_PC" \
meson setup "$GLIB_BUILD" \
    --cross-file "$GLIB_CROSS" \
    --default-library=both \
    --prefix=/ \
    --wrap-mode=nodownload \
    -Dlibmount=disabled \
    -Ddtrace=disabled \
    -Dsystemtap=disabled \
    -Dxattr=false \
    -Dtests=false \
    -Dintrospection=disabled \
    -Dlibelf=disabled \
    -Dnls=disabled \
    -Dlibffi:tests=false \
    -Dlibffi:default_library=static \
    -Dzlib:tests=disabled \
    -Dzlib:default_library=static \
    -Dpcre2:default_library=static \
    -Dproxy-libintl:default_library=static \
    -Dc_args="--sysroot=$SYSROOT -I$DEPS_OUT/include -Wno-error=format-nonliteral -Wno-unused-parameter" \
    -Dc_link_args="--sysroot=$SYSROOT -L$DEPS_OUT/lib -lc -llog" \
    > "$WORK/glib-meson-setup.log" 2>&1 || die "glib meson setup failed (see $WORK/glib-meson-setup.log)"
ninja -C "$GLIB_BUILD" -j"$BUILD_THREADS" \
    glib/libglib-2.0.so gobject/libgobject-2.0.a gio/libgio-2.0.a \
    gmodule/libgmodule-2.0.a gthread/libgthread-2.0.a \
    > "$WORK/glib-ninja.log" 2>&1 || die "glib build failed (see $WORK/glib-ninja.log)"
mkdir -p "$DEPS_OUT/lib"
cp -Lf "$GLIB_BUILD/glib/libglib-2.0.so" "$DEPS_OUT/lib/"
cp -f "$GLIB_BUILD/gobject/libgobject-2.0.a" "$GLIB_BUILD/gio/libgio-2.0.a" "$DEPS_OUT/lib/"
# Install glib headers in the layout QEMU expects:
#   <glib.h>, <glib/*.h>        from include/glib/
#   <glib-object.h>             from include/
#   <gobject/*.h>               from include/gobject/
#   <gio/*.h>                   from include/gio/
#   <gmodule.h>, <gmodule/*.h>  from include[/gmodule]/
# plus the generated headers from the build tree (gversionmacros.h,
# glibconfig.h, *-visibility.h, *enumtypes.h, ...).
rm -rf "$DEPS_OUT/include/glib" "$DEPS_OUT/include/gobject" "$DEPS_OUT/include/gio" "$DEPS_OUT/include/gmodule"
mkdir -p "$DEPS_OUT/include/glib" "$DEPS_OUT/include/gobject" "$DEPS_OUT/include/gio" "$DEPS_OUT/include/gmodule"
cp -f "$GLIB_SRC/glib"/*.h "$DEPS_OUT/include/glib/"
cp -f "$GLIB_BUILD/glib"/*.h "$DEPS_OUT/include/glib/"
mkdir -p "$DEPS_OUT/include/glib/deprecated"
cp -f "$GLIB_SRC/glib/deprecated"/*.h "$DEPS_OUT/include/glib/deprecated/"
cp -f "$GLIB_SRC/gobject"/*.h "$DEPS_OUT/include/gobject/"
cp -f "$GLIB_BUILD/gobject"/*.h "$DEPS_OUT/include/gobject/"
cp -f "$GLIB_SRC/glib/glib-object.h" "$DEPS_OUT/include/"
cp -f "$GLIB_SRC/gio"/*.h "$DEPS_OUT/include/gio/"
cp -f "$GLIB_BUILD/gio"/*.h "$DEPS_OUT/include/gio/"
cp -f "$GLIB_SRC/gmodule"/*.h "$DEPS_OUT/include/gmodule/"
cp -f "$GLIB_BUILD/gmodule"/*.h "$DEPS_OUT/include/gmodule/"
cp -f "$GLIB_SRC/gmodule/gmodule.h" "$DEPS_OUT/include/"
log "glib done"
else log "phase 3: glib already built, skipping"; fi

# ---------------------------------------------------------------- phase 4: pixman (meson)
if [ ! -f "$DEPS_OUT/lib/libpixman-1.so" ]; then
log "=== phase 4: building pixman $PIXMAN_VER ==="
cd "$PIXMAN_SRC"
PIXMAN_BUILD="$WORK/pixman-build"
PIXMAN_CROSS="$WORK/pixman-android-cross.ini"
cat > "$PIXMAN_CROSS" <<EOF
[binaries]
c = '$WRAPPER'
ar = '$TOOLCHAIN/bin/llvm-ar'
strip = '$TOOLCHAIN/bin/llvm-strip'

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF
meson setup "$PIXMAN_BUILD" \
    --cross-file "$PIXMAN_CROSS" \
    --default-library=shared \
    --prefix=/ \
    -Dmmx=disabled -Dsse2=disabled -Dssse3=disabled \
    -Dloongson-mmi=disabled -Dmips-dspr2=disabled -Drvv=disabled \
    -Dgtk=disabled -Dlibpng=disabled -Dtests=disabled -Ddemos=disabled \
    -Dc_args="--sysroot=$SYSROOT" \
    -Dc_link_args="--sysroot=$SYSROOT -lc -llog" \
    > "$WORK/pixman-meson-setup.log" 2>&1 || die "pixman meson setup failed"
ninja -C "$PIXMAN_BUILD" -j"$BUILD_THREADS" > "$WORK/pixman-ninja.log" 2>&1 || die "pixman build failed"
cp -Lf "$PIXMAN_BUILD/pixman"/libpixman-1.so* "$DEPS_OUT/lib/" 2>/dev/null || cp -Lf "$PIXMAN_BUILD/pixman/libpixman-1.so" "$DEPS_OUT/lib/"
mkdir -p "$DEPS_OUT/include/pixman"
cp -f "$PIXMAN_SRC/pixman/pixman.h" "$DEPS_OUT/include/pixman/"
cp -f "$PIXMAN_BUILD/pixman/pixman-version.h" "$DEPS_OUT/include/pixman/" \
    || die "pixman-version.h install failed"
log "pixman done"
else log "phase 4: pixman already built, skipping"; fi

# ---------------------------------------------------------------- phase 5: QEMU tree prep
if [ ! -f "$QEMU_WORK/.droidbox-prepared" ]; then
log "=== phase 5: preparing QEMU $QEMU_VERSION tree ==="
rm -rf "$QEMU_WORK"
# NOTE: --no-preserve=ownership: this environment cannot chown; ownership
# preservation is irrelevant for a build tree. Without this flag cp -a exits
# 1 on the chown warnings and `set -e` kills the script.
cp -a --no-preserve=ownership "$QEMU_PRISTINE" "$QEMU_WORK"
cd "$QEMU_WORK"
log "applying QubeVM qemu-$QEMU_VERSION patch (bionic fixes + android .so machinery)"
patch -p1 < "$REF/qemu-11.1.2.patch" > "$WORK/qemu-patch.log" 2>&1 || die "QEMU patch failed (see $WORK/qemu-patch.log)"
log "renaming qube identifiers to droidbox"
grep -rl "qube_android_so" --include="*.build" --include="*.txt" --include="configure" . | \
    xargs sed -i 's/qube_android_so/droidbox_android_so/g'
sed -i 's/--enable-qube-android-so/--enable-droidbox-android-so/g; s/--disable-qube-android-so/--disable-droidbox-android-so/g' configure
grep -rl "__QUBE__" . 2>/dev/null | xargs sed -i 's/__QUBE__/__DROIDBOX__/g' 2>/dev/null || true
# QubeVM's patch adds ui/qge.c + ui/qge-input.c to system_ss unconditionally
# but never ships those sources (unpublished EGL display backend). Guard them
# behind the same condition as CONFIG_QUBE_QGE so configure does not fail
# looking for files that do not exist. Re-applied on every fresh tree prep.
python3 - "$QEMU_WORK/ui/meson.build" <<'PYEOF' || die "ui/meson.build qge guard failed"
import sys
p = sys.argv[1]
s = open(p).read()
old = """  'util.c',
  'qge.c',
  'qge-input.c',
))"""
new = """  'util.c',
))
# DROIDBOX: qge.c / qge-input.c are QubeVM's unpublished EGL display backend
# (their patch references these files but never ships them). Build them only
# when the QGE display is actually enabled -- same condition as
# CONFIG_QUBE_QGE in the root meson.build. Not enabled for Milestone A.
if opengl.found() and get_option('droidbox_android_so')
  system_ss.add(files('qge.c', 'qge-input.c'))
endif"""
if old in s:
    open(p, "w").write(s.replace(old, new))
    print("qge guard applied to ui/meson.build")
PYEOF
# QubeVM's patch forces stub virglrenderer/epoxy (-lEGL -lGLESv2 -lepoxy)
# dependencies for the android .so. We have no virglrenderer/epoxy on the NDK
# and need no GL for Milestone A (display=none + VNC), so neutralize those
# two branches and fall through to real detection (finds nothing).
python3 - "$QEMU_WORK/meson.build" <<'PYEOF' || die "meson.build GL neutralize failed"
import sys
p = sys.argv[1]
s = open(p).read()
old_virgl = """  if get_option('droidbox_android_so')
    # QUBE: virglrenderer via extra-cflags/ldflags, no pkg-config in NDK"""
new_virgl = """  if false  # DROIDBOX: no virglrenderer/epoxy on Android NDK; GL off for Milestone A
    # QUBE: virglrenderer via extra-cflags/ldflags, no pkg-config in NDK"""
assert old_virgl in s, "virgl anchor not found"
s = s.replace(old_virgl, new_virgl)
old_gl = """  if get_option('droidbox_android_so')
    # QUBE: epoxy via extra-cflags/ldflags, no pkg-config in NDK"""
new_gl = """  if false  # DROIDBOX: no epoxy on Android NDK; GL off for Milestone A
    # QUBE: epoxy via extra-cflags/ldflags, no pkg-config in NDK"""
assert old_gl in s, "epoxy anchor not found"
s = s.replace(old_gl, new_gl)
open(p, "w").write(s)
print("GL stub declarations neutralized")
PYEOF
# QubeVM's patch lists audio/qube-audio.c (their unpublished Android audio
# backend) in audio/meson.build but never ships the source. Nothing
# references its symbols, so drop the entry until a real DroidBox audio
# backend lands.
python3 - "$QEMU_WORK/audio/meson.build" <<'PYEOF' || die "audio/meson.build qube-audio fix failed"
import sys
p = sys.argv[1]
s = open(p).read()
old = "  'qube-audio.c',\n"
new = ("  # DROIDBOX: qube-audio.c is QubeVM's unpublished Android audio backend;\n"
       "  # omitted until a real DroidBox audio backend lands.\n")
if old in s:
    open(p, "w").write(s.replace(old, new))
    print("qube-audio.c entry removed from audio/meson.build")
PYEOF
# NOTE: __DROIDBOX__ is intentionally NOT defined: the display/input engine
# hooks stay compiled out until the real DroidBox display backend lands.
if grep -rqi "qube" --include="*.c" --include="*.h" --include="*.build" --include="*.txt" --include="configure" . ; then
    log "WARNING: residual qube identifiers:"; grep -rli "qube" --include="*.c" --include="*.h" . | head -5
else
    log "identifier rename clean"
fi
# libslirp as meson subproject source
mkdir -p subprojects/libslirp
tar --no-same-owner -xf "$DEPS_SRC/libslirp-$LIBSLIRP_VER.tar.gz" -C subprojects/libslirp --strip-components=1 \
    || die "libslirp placement failed"
# dtc source for the fdt subproject
mkdir -p subprojects/dtc
tar --no-same-owner -xf "$DEPS_SRC/dtc.tar.gz" -C subprojects/dtc --strip-components=1 \
    || die "dtc placement failed"
touch .droidbox-prepared
log "QEMU tree prepared"
else log "phase 5: QEMU tree already prepared, skipping"; fi

# QubeVM's patch references util/coroutine-swap-arm64.S from util/meson.build
# but never ships the file; install our clean-room implementation so the
# missing file does not break configure. (ABI documented in the file header.)
if [ ! -f "$QEMU_WORK/util/coroutine-swap-arm64.S" ]; then
log "installing coroutine-swap-arm64.S (absent from QubeVM patch)"
cp -f "$NATIVE_ROOT/coroutine-swap-arm64.S" "$QEMU_WORK/util/coroutine-swap-arm64.S" \
    || die "coroutine-swap-arm64.S install failed"
fi

# ---------------------------------------------------------------- phase 6: configure
if [ ! -f "$QEMU_BUILD/build.ninja" ]; then
log "=== phase 6: configuring QEMU ==="
cd "$QEMU_WORK"
EXTRA_CFLAGS="\
 $TARGET_FLAG \
 -I$DEPS_OUT/include \
 -I$DEPS_OUT/include/glib \
 -I$DEPS_OUT/include/pixman \
 -Wno-redundant-decls -Wno-unused-variable -Wno-uninitialized -Wno-unused-function \
 -Wno-unknown-warning-option -Wno-unknown-attributes"
EXTRA_LDFLAGS="\
 -L$DEPS_OUT/lib \
 -lglib-2.0 -lgobject-2.0 -lgio-2.0 -lffi -lpixman-1 \
 -lc -lm -ldl -lz -llog -latomic \
 -shared"
# NDK r30 ships only llvm-* binutils (no aarch64-linux-android-ar etc.),
# but configure derives them from --cross-prefix unless overridden.
# QubeVM does the same AR override.
export AR="$TOOLCHAIN/bin/llvm-ar"
export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
export STRIP="$TOOLCHAIN/bin/llvm-strip"
export OBJCOPY="$TOOLCHAIN/bin/llvm-objcopy"
export NM="$TOOLCHAIN/bin/llvm-nm"
./configure \
    --cc="$CLANG" \
    --cpu=aarch64 \
    --cross-prefix="$TOOLCHAIN/bin/aarch64-linux-android-" \
    --target-list="$BUILD_GUEST" \
    --enable-slirp \
    --enable-vnc --disable-vnc-jpeg --disable-vnc-sasl \
    --enable-fdt \
    --enable-virtfs \
    --with-coroutine=sigaltstack \
    --enable-coroutine-pool \
    --enable-lto \
    --disable-sdl --disable-gtk --disable-curses --disable-cocoa \
    --disable-spice --disable-opengl --disable-virglrenderer \
    --disable-tools \
    --disable-guest-agent --disable-user \
    --disable-xen --disable-numa \
    --disable-vhost-net --disable-vhost-kernel --disable-vhost-user \
    --disable-linux-aio \
    --disable-rbd --disable-libiscsi --disable-libnfs \
    --disable-gnutls --disable-nettle \
    --disable-seccomp --disable-docs --disable-werror \
    --disable-kvm \
    --disable-pie \
    --disable-plugins --disable-capstone --disable-libssh --disable-png \
    --disable-brlapi --disable-curl --disable-vde --disable-netmap --disable-cap-ng \
    --disable-rdma --disable-lzo --disable-snappy --disable-bzip2 --disable-vte \
    --disable-multiprocess --disable-dbus-display --disable-linux-io-uring \
    --disable-fuse --disable-bpf --disable-selinux --disable-libudev \
    --disable-membarrier --disable-replication --disable-blkio \
    --disable-libcbor --disable-igvm --disable-qpl --disable-uadk --disable-qatzip \
    --disable-libvduse --disable-safe-stack \
    --audio-drv-list= \
    --extra-cflags="$EXTRA_CFLAGS" \
    --extra-ldflags="$EXTRA_LDFLAGS" \
    --enable-droidbox-android-so \
    > "$WORK/qemu-configure.log" 2>&1 || die "QEMU configure failed (see $WORK/qemu-configure.log)"
log "QEMU configured"
else log "phase 6: QEMU already configured, skipping"; fi

# ---------------------------------------------------------------- phase 7: build
log "=== phase 7: building QEMU (this takes a while on 2 cores) ==="
ninja -C "$QEMU_BUILD" -j"$BUILD_THREADS" > "$WORK/qemu-ninja.log" 2>&1 || die "QEMU build failed (see $WORK/qemu-ninja.log)"
QEMU_SO="$(find "$QEMU_BUILD" -maxdepth 1 -name 'libqemu-system-*.so' | head -1)"
[ -n "$QEMU_SO" ] || die "build finished but libqemu-system-*.so not found"
log "built: $QEMU_SO ($(du -h "$QEMU_SO" | cut -f1))"

# ---------------------------------------------------------------- phase 8: verify + install
log "=== phase 8: verifying native library ==="
"$TOOLCHAIN/bin/llvm-readelf" -d "$QEMU_SO" | grep -E "NEEDED|SONAME" | tee "$WORK/qemu-so-needed.txt"
log "checking for undefined symbols that would fail at dlopen time (allowed: none fatal here; dlopen uses RTLD_NOW)"
"$TOOLCHAIN/bin/llvm-strip" --strip-unneeded "$QEMU_SO" || true
JNILIBS="$DROIDBOX_ROOT/app/src/main/jniLibs/$APP_ABI"
mkdir -p "$JNILIBS"
cp -f "$QEMU_SO" "$JNILIBS/"
cp -Lf "$DEPS_OUT/lib/libglib-2.0.so" "$DEPS_OUT/lib/libpixman-1.so" "$JNILIBS/" 2>/dev/null || true
# libslirp is built as a meson subproject .so; the QEMU .so has
# NEEDED libslirp.so.0. AGP only packages files ending in ".so" from jniLibs,
# so we binary-patch the DT_NEEDED to "libslirp.so" (null-padded, same length)
# and ship it as libslirp.so.
SLIRP_SO="$(find "$QEMU_BUILD/subprojects/libslirp" -maxdepth 1 -name 'libslirp.so.0*' | head -1)"
if [ -n "$SLIRP_SO" ]; then
    cp -f "$SLIRP_SO" "$JNILIBS/libslirp.so"
    python3 - "$JNILIBS/libqemu-system-x86_64.so" <<'PYEOF'
import sys
p = sys.argv[1]
data = bytearray(open(p, "rb").read())
old, new = b"libslirp.so.0\x00", b"libslirp.so\x00\x00\x00"
assert len(old) == len(new) == 14 and data.count(old) >= 1
open(p, "wb").write(data.replace(old, new))
print("patched DT_NEEDED libslirp.so.0 -> libslirp.so")
PYEOF
    # Also patch the SONAME inside libslirp.so itself: the Android linker
    # verifies the SONAME against DT_NEEDED. Without this, dlopen fails with
    # 'cannot find "libslirp.so" from verneed'.
    python3 - "$JNILIBS/libslirp.so" <<'PYEOF2'
import sys
p = sys.argv[1]
data = bytearray(open(p, "rb").read())
old, new = b"libslirp.so.0\x00", b"libslirp.so\x00\x00\x00"
assert len(old) == len(new) == 14 and data.count(old) >= 1
open(p, "wb").write(data.replace(old, new))
print("patched SONAME libslirp.so.0 -> libslirp.so")
PYEOF2
fi
log "installed to $JNILIBS:"
ls -la "$JNILIBS"
# pc-bios blobs for the APK assets
ASSETS="$DROIDBOX_ROOT/app/src/main/assets/roms"
mkdir -p "$ASSETS"
cp -f "$QEMU_BUILD"/pc-bios/*.bin "$QEMU_BUILD"/pc-bios/*.rom "$ASSETS/" 2>/dev/null || true
cp -f "$QEMU_WORK/VERSION" "$DROIDBOX_ROOT/app/src/main/assets/QEMU_VERSION" 2>/dev/null || true

cat > "$WORK/BUILD-MANIFEST.txt" <<EOF
DroidBox QEMU Android build manifest
date: $(date -u +%FT%TZ)
qemu: $QEMU_VERSION (https://download.qemu.org/qemu-$QEMU_VERSION.tar.xz)
qemu sha256: $(cat "$THIRD_PARTY/qemu-$QEMU_VERSION.tar.xz.sha256")
patch base: QubeVM qemu-11.1.2.patch (GPL-3.0, identifiers renamed qube->droidbox)
libffi: $LIBFFI_VER | glib: $GLIB_VER | pixman: $PIXMAN_VER | libslirp: $LIBSLIRP_VER | dtc: $DTC_REV
ndk: r$NDK_VERSION | host abi: $APP_ABI | guest target: $BUILD_GUEST
configure flags: see $WORK/qemu-configure.log
output: $QEMU_SO
EOF
log "=== DONE ==="
log "manifest: $WORK/BUILD-MANIFEST.txt"
