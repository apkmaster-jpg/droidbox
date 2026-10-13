# Third-Party Notices — DroidBox v28

## QEMU 11.1.2
- **License:** GPL-2.0-or-later
- **Source:** https://download.qemu.org/qemu-11.1.2.tar.xz
- **SHA256:** 731b5681e4bb18be313231579b8efd0296c5b015fa36dc533874b639ba838016
- **Modifications:** NDK cross-compilation patches for Android (bionic libc shims, coroutine assembly for arm64). See `native/build-qemu-android.sh` and `docs/qemu-android-recipe.md`.
- **Reference:** QubeVM (https://github.com/rhn-1k/qubevm), GPL-3.0 — architectural reference for NDK .so packaging and JNI dlopen/dlsym pattern.

## Android-x86 ISOs (guest media, not distributed)
- android-x86_64-9.0-r2.iso — Apache 2.0 / GPL (Android Open Source Project)
- android-x86_64-8.1-r6-k49.iso — Apache 2.0 / GPL
- These are user-supplied boot media; not included in the APK.

## Native Dependencies (built from source)
- **glib 2.0** — LGPL-2.1-or-later
- **pixman** — MIT
- **libslirp** — BSD-3-Clause

## Android Libraries (via local Maven repo)
- androidx.compose, androidx.core, etc. — Apache 2.0

## Fonts/Keymaps
- QEMU keymap files (35) — GPL-2.0-or-later (part of QEMU)
- SeaBIOS firmware — LGPL-3.0 / GPL-3.0
