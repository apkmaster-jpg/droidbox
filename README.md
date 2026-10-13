# DroidBox — Real Android VM on Android

**Status:** PARTIAL (see `/output/DroidBox-FINAL-REPORT.md`)

DroidBox is an Android app that boots a **real** user-supplied Android-x86 ISO through **native QEMU full-system emulation**. No simulators, no mocks, no fake UI — ever.

## What Works (Verified on Samsung S25 Ultra)

- ✅ NDK-built QEMU 11.1.2 as native `.so` (`libqemu-system-x86_64.so`), driven via JNI
- ✅ Real ISO boot (SeaBIOS → ISOLINUX → kernel → Android init)
- ✅ In-app VNC display (real guest framebuffer) with touch + keyboard input
- ✅ Full on-device Android-x86 installation (partition, ext4, GRUB)
- ✅ Guest boots to 1354s+ with Android framework services (zygote, netd, audioserver, mediaserver)

## Why PARTIAL

The Android launcher/boot animation never appears. Under QEMU TCG (x86_64-on-ARM, no KVM), the guest boots extremely slowly, and zygote enters a crash loop around 1255s (a TCG emulation bug, not an app bug). This is a physical TCG speed/correctness limit.

**When QEMU releases a new version with TCG fixes, retry the boot** — the app is ready.

## Quick Start

1. Build: see `docs/qemu-android-recipe.md` (JDK 21, Gradle 8.10.2, NDK r30, offline Maven repo)
2. Install the debug APK (v28) on an ARM64 Android device
3. Select a real Android-x86 ISO → START → Live CD - Debug mode → Send 'exit'
4. For furthest progress: `android-x86_64-8.1-r6-k49.iso` (kernel 4.9)

## Repo Layout

- `app/` — Android app (Kotlin + JNI)
  - `src/main/cpp/vm_engine_jni.c` — QEMU JNI engine
  - `src/main/java/com/droidbox/` — UI, VNC client, QMP client
- `native/` — QEMU NDK build scripts (`build-qemu-android.sh`)
- `docs/` — Build recipe, diagnostics
- `third-party/` — QEMU 11.1.2 source (download via script, not committed)

## Key Technical Decisions

- Machine: `q35`, CPU: `qemu64`, Accel: `tcg,thread=single` (MTTCG disproven slower)
- Display: `-display none -vnc 127.0.0.1:0` with hand-written VNC client (incremental updates)
- Disk: AHCI `ide-hd` (virtio-blk invisible to Android-x86 installer under TCG)
- One `qemu_init` per process (QEMU static globals — app restart required between runs)
- 35 QEMU keymaps packaged, `INTERNET` permission for localhost VNC
- Disk ON/OFF toggle for hang isolation testing

## Version History

v5 → v28: native engine → VNC → input → fullscreen → diagnostics → keyboard → disk fixes → AHCI. Full log in FINAL-REPORT.md.

## License

QEMU: GPL-2.0-or-later. See `/output/DroidBox-THIRD-PARTY-NOTICES.md`.
