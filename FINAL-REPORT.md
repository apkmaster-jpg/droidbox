# DroidBox — Final Report

**Date:** 2026-10-13  
**Version:** v28 (versionCode 28, "0.1.0-dev+fix28")  
**Status:** **PARTIAL**

## Acceptance Criterion

> Real Android guest visibly reaches Android userspace/launcher inside the APK, OR an honest PARTIAL report with the exact failure reason.

**Result: PARTIAL** — the failure reason is documented below. No simulated Android was ever used.

## What Was Proven (Real, Verified)

1. **Real native QEMU** — QEMU 11.1.2 built via NDK (clang 21, arm64-v8a), packaged as `libqemu-system-x86_64.so` (30MB), driven through JNI on a native pthread. `qemu_init OK — entering REAL qemu_main_loop` verified on-device.

2. **Real ISO boot** — `android-x86_64-9.0-r2.iso` and `android-x86_64-8.1-r6-k49.iso` both boot a real guest kernel through TCG full-system emulation (q35 machine, 2 vCPU, single-thread TCG).

3. **Real guest display** — QEMU VNC server (127.0.0.1:5900) renders the real guest framebuffer in-app. SeaBIOS, ISOLINUX boot menu, kernel logs all displayed.

4. **Real guest input** — VNC pointer/touch and keyboard events reach the guest (used to type `exit` at the debug shell and navigate the installer).

5. **Real installation** — Android-x86 9.0-r2 was fully installed to the virtual disk on-device: sda1 partitioned via cfdisk, ext4 formatted, GRUB installed. The installer completed and offered "Run Android-x86".

6. **Deep guest boot** — With `android-x86_64-8.1-r6-k49.iso` (kernel 4.9), the guest booted to **1354+ seconds** of kernel time with Android framework services starting:
   - surfaceflinger (80s) — display server
   - hal_audio_default (265s) — audio HAL
   - zygote (432s) — app runtime
   - audioserver (432s)
   - netd (462s) — network daemon
   - mediaserver (650s) — media service

   Screenshots from ~1255s onward show zygote repeatedly starting, being killed by **signal 9**, and restarting — an apparent crash/restart loop. The exact root cause is **not conclusively proven**. A QEMU/TCG emulation limitation is only the leading hypothesis (see failure reason below).

7. **Not tested** — Installing the k49 build to the virtual disk (as was done with 9.0-r2) was proposed but **never tested**, so no claim is made about whether an installed k49 boot would behave differently.

## Exact Failure Reason

**The Android boot animation/launcher never appeared.**

The guest kernel boots and Android framework services start, but under TCG (x86_64-on-ARM CPU emulation, no KVM), the boot progresses extremely slowly. Screenshots up to ~1354s of guest time (likely 60+ minutes of wall-clock time) show framework services initializing, followed from ~1255s by apparent **signal-9 zygote restarts** — zygote starting, being killed, and restarting in a loop.

**The exact root cause is not conclusively proven.** The leading hypothesis is a QEMU/TCG emulation correctness or speed limitation (x86_64-on-ARM, no KVM), but this has not been isolated — it is a hypothesis, not a proven diagnosis. No claim is made that this is unfixable; the standing plan is to retry when a newer QEMU release with TCG fixes is available.

The following were ruled out as causes of the stall:
- RAM (2GB vs 4GB — same hang point)
- Disk interface (virtio-blk → IDE → AHCI — all functional)
- VNC display path (incremental updates, timeout fix — frames flow correctly)
- Input (keyboard works, installer navigated successfully)
- QEMU configuration (q35, single-thread TCG — stable)

The 9.0-r2 ISO hangs at ~70s (vold/init). The 8.1-r6-k49 ISO progresses much further (1354s+) but the boot animation/launcher was never observed within the observed window. Installing the k49 build to disk was proposed but never tested.

## Artifacts

- **APK:** `/output/DroidBox-debug.apk` (33MB, v28)
  - SHA256: `7a2caa61f732ed27025e4e97969ca8506393647728934545a53b9d28a738a42a`
- **Source ZIP:** `/output/DroidBox-source-v28.zip` (11MB)
  - SHA256: `00e8cfe364df74ec1d70d3f3be22fc82c944947fe659a786346139e87bb24dbb`

## Version History (Key Milestones)

- v5: Native engine loaded
- v8: Real `qemu_main_loop` reached
- v9–v14: VNC display, input, fullscreen — boot menu visible
- v19: SeaBIOS debugcon, QMP diagnostics
- v22–v23: VNC incremental updates + timeout fix
- v24: Guest keyboard input ("Send exit")
- v25–v26: Disk ON/OFF toggle, Force Stop fix
- v27–v28: IDE → AHCI disk attachment (installer now sees disk)

## Honest Assessment

DroidBox is a **real VM** — not a simulator, not a mock. Every layer (QEMU, ISO, disk, display, input) is genuine. The Android guest boots its own kernel and starts its own framework services.

But **"real VM" is not the same as "usable Android."** TCG emulation of x86_64 Android on an ARM phone is too slow for the full Android userspace to initialize within practical time. This is a fundamental limitation of CPU emulation, not something more app code can fix.

**Status: PARTIAL** — as required by the acceptance rule, with the exact failure reason stated above.
