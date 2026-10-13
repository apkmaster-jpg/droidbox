# DroidBox — Verified QEMU-on-Android Build Recipe

Researched 2026-10-12. All claims checked against primary sources.

## Baseline (verified)
- **QEMU 11.1.2** — real, released Sep 28 2026. Tarball: `https://download.qemu.org/qemu-11.1.2.tar.xz` (HTTP 200 verified). Git branch `stable-11.1`.
- **Reference implementation: QubeVM** (`github.com/rhn-1k/qubevm`, GPL-3.0, fork of Limbo). Actively maintained, already rebased to QEMU 11.1.2 (Oct 2026). Its `README.developers` is the reproducible recipe.
- **Rejected reference: Vectras-VM-Android (open-source edition)** — proot-based: downloads an Alpine bootstrap and `apk`-installs distro QEMU inside proot. This is exactly what the DroidBox spec forbids. Out.

## QubeVM recipe (the proven architecture)
1. Build glib 2.88.3 (patched), libffi 3.8.0, pixman 0.46.4 from source with NDK clang. libslirp 4.9.4 unzipped into `qemu/subprojects/` (meson subproject, mandatory for QEMU 7.2+).
2. Apply `qemu-11.1.2.patch` to the QEMU tree (adds `qube_android_so` meson option, bionic workarounds, display/input hook calls in `system/vl.c`).
3. QEMU `./configure` with `--cc=<ndk-clang> --cpu=aarch64 --cross-prefix=<llvm-triple>- --target-list=x86_64-softmmu` + `--extra-cflags/--extra-ldflags` for deps and sysroot. No hand-written meson cross file — configure generates it.
4. Objects archived, `objcopy --redefine-sym` for storage-framework compat (`open→android_open` etc.), then linked as **`libqemu-system-x86_64.so`** with `-shared --whole-archive`.
5. JNI bridge (`vm-executor-jni.c`, Limbo pattern): `dlopen("libqemu-system-x86_64.so")` → `dlsym("qemu_init")` (fallback `"main"`) → run on a **native worker thread** with synthesized argc/argv → `dlsym("qemu_main_loop")`.
6. Display: SDL/GTK/curses all off; VNC on, plus QubeVM's own QGE native display backend feeding Android surfaces; AAudio for sound.

## Configure flags (from QubeVM `android-qemu-config.mak`)
- Use: `--target-list=x86_64-softmmu --enable-slirp --enable-vnc --enable-fdt --enable-virtfs --with-coroutine=sigaltstack --enable-lto --disable-pie`
- Disable: `--disable-sdl --disable-gtk --disable-curses --disable-cocoa --disable-spice --disable-opengl --disable-tools --disable-guest-agent --disable-user --disable-xen --disable-numa --disable-vhost-net --disable-linux-aio --disable-rbd --disable-libiscsi --disable-libnfs --disable-gnutls --disable-nettle --disable-seccomp --disable-docs --disable-werror`
- NOTE: `--disable-tools` drops `qemu-img` — qcow2 creation must then go through the QEMU block layer from native code, or keep tools for a host-side step.
- NDK **r30**, clang-only, ThinLTO, minSdk 30, **arm64-v8a host only** ("x86_64 host NOT supported yet").
- KVM option enabled in build but default TCG; stock Android has no `/dev/kvm` for normal apps.

## Real APK size (QubeVM 7.0.8 releases)
- x86_64 guest target, QEMU 11.1.1: **53.1 MB**. Expect ~50–65 MB per guest target at 11.1.x.

## Top pitfalls
1. **Bionic ≠ glibc.** Patch around missing/broken syscalls (`CONFIG_SIGNALFD` off, `CONFIG_CLOSE_RANGE` off < API 34, `<linux/mman.h>` vs `<sys/uio.h>` conflict). QubeVM ships `libcompat-musl.so`/`libcompat-qube.so` shims + objcopy-renamed libc wrappers.
2. **glib must be patched for Android** — use QubeVM's pinned 2.88.3 + their patch, not a random newer version.
3. The fragile part is the `./configure` invocation (sysroot, `-I` paths, `-llog -landroid` linkage). Copy QubeVM's `.mak` files as the template.
4. **Display/audio/input are our code, not QEMU's.** With SDL/GTK/SPICE off, frame delivery (VNC client or custom display backend) and input injection (QMP or custom input backend) must be written.
5. Android 10+ execution rules *favor* the .so approach — no `exec()` from app-private storage; the JNI `dlopen` pattern is the compliant architecture and keeps the VM off the UI thread.

## Feasibility verdict (x86_64 Android guest, TCG, arm64 phone)
Feasible and precedented — QubeVM ships exactly this today. Honest caveats: TCG x86-on-ARM is ~an order of magnitude slower than native; Android-x86 boots and runs but sluggishly. Follow QubeVM's advice: pre-install the guest to qcow2 on a desktop, then transfer the image. Guest compatibility is per-image — first sane target is Android-x86/Bliss OS ISO on `-M pc`/`q35`. No universal-ISO claims.

## Sources
- https://www.qemu.org/download/ · https://github.com/rhn-1k/qubevm (`README.developers`, `android-qemu-build.mak`, `android-qemu-config.mak`, `vm-executor-jni.c`) · https://github.com/rhn-1k/QubeVM/releases/tag/7.0.8 · https://github.com/hendyngo/Vectras-VM-Android
