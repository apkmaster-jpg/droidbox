#!/usr/bin/env python3
"""Symlink-aware zip extractor.

Python's zipfile module extracts symlink entries as regular files containing
the link target text, which breaks NDK toolchains (bin/clang -> clang-21 etc).
This extractor recreates symlinks properly and preserves exec bits.
"""
import os
import stat
import sys
import zipfile


def extract(zip_path, dest):
    with zipfile.ZipFile(zip_path) as z:
        for info in z.infolist():
            # Zip stores paths with / separators; guard against traversal.
            parts = [p for p in info.filename.split('/') if p not in ('', '.', '..')]
            if not parts:
                continue
            target = os.path.join(dest, *parts)
            mode = (info.external_attr >> 16)
            if stat.S_ISLNK(mode):
                link_target = z.read(info).decode('utf-8')
                os.makedirs(os.path.dirname(target), exist_ok=True)
                if os.path.lexists(target):
                    os.remove(target)
                os.symlink(link_target, target)
            elif info.is_dir():
                os.makedirs(target, exist_ok=True)
            else:
                os.makedirs(os.path.dirname(target), exist_ok=True)
                with z.open(info) as src, open(target, 'wb') as dst:
                    dst.write(src.read())
                if mode & 0o111:
                    os.chmod(target, 0o755)


if __name__ == '__main__':
    extract(sys.argv[1], sys.argv[2])
    print(f"extracted {sys.argv[1]} -> {sys.argv[2]}")
