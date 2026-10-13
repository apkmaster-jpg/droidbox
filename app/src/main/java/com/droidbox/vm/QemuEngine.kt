package com.droidbox.vm

/**
 * Kotlin facade over the native VM engine (libdroidbox_jni.so).
 *
 * This is a thin bridge: every call crosses JNI into C code that drives the
 * REAL QEMU system engine (libqemu-system-x86_64.so) via dlopen/dlsym on a
 * dedicated native thread. States mirror spec section 114.
 *
 * If the real QEMU .so is absent from the APK, the native layer returns
 * ERR_NATIVE_UNAVAILABLE and the UI must show "NATIVE VM ENGINE UNAVAILABLE".
 * There is no fallback path to a simulated Android guest anywhere in this file.
 */
object QemuEngine {

    /** Result of the native library preload; null when all loaded. */
    var preloadError: String? = null
        private set

    init {
        // The REAL QEMU engine ships as libqemu-system-x86_64.so plus its
        // NDK-built dependencies. A bare dlopen("libqemu-...") from native
        // code does not reliably search the app's private lib dir on all
        // devices, so preload everything via System.loadLibrary (which uses
        // the app's known native-library path). The native dlopen() then
        // resolves to the already-loaded handle. Order: leaves first.
        val libs = arrayOf("glib-2.0", "pixman-1", "slirp", "qemu-system-x86_64", "droidbox_jni")
        for (lib in libs) {
            try {
                System.loadLibrary(lib)
            } catch (e: UnsatisfiedLinkError) {
                preloadError = "loadLibrary($lib) failed: ${e.message}"
                break
            }
        }
    }

    /** VM states, mirroring spec section 114 (native subset). */
    enum class VmState(val code: Int) {
        NATIVE_UNAVAILABLE(0),
        CREATED(1),
        BOOTING(2),
        RUNNING(3),
        PAUSED(4),
        SHUTTING_DOWN(5),
        STOPPED(6),
        CRASHED(7),
        UNKNOWN(-99);

        companion object {
            fun fromCode(code: Int): VmState =
                entries.firstOrNull { it.code == code } ?: UNKNOWN
        }
    }

    /** Error codes returned by native calls. */
    object Err {
        const val OK = 0
        const val NATIVE_UNAVAILABLE = -1
        const val BAD_HANDLE = -2
        const val WRONG_STATE = -3
        const val QEMU_INIT_FAILED = -4
        const val INTERNAL = -5
        const val ALREADY_INITED = -6 // v19: QEMU ran once in this process; restart app before START
    }

    data class VmConfig(
        val machine: String = "q35",
        val vcpus: Int = 2,
        val ramMb: Int = 4096,
        val isoPath: String? = null,   // real user-selected ISO, copied to app-private storage
        val diskPath: String,          // real qcow2 virtual disk
        val accel: String = "tcg,thread=single",
        val display: String = "none",  // milestone A: headless + serial console evidence
        val network: String = "user",  // user-mode NAT, no root
        val workDir: String,           // app-private dir for serial.log + qmp.sock
    )

    external fun nativeEngineVersion(): String
    external fun nativeCreateVm(
        machine: String,
        vcpus: Int,
        ramMb: Int,
        isoPath: String?,
        diskPath: String,
        accel: String,
        display: String,
        network: String,
        workDir: String,
        dataDir: String,  // extracted pc-bios firmware dir (for QEMU -L)
    ): Int // returns VM handle (>=0) or negative Err code

    external fun nativeStartVm(handle: Int): Int
    external fun nativePauseVm(handle: Int): Int
    external fun nativeResumeVm(handle: Int): Int
    external fun nativeShutdownVm(handle: Int): Int
    external fun nativeForceStopVm(handle: Int): Int
    external fun nativeGetState(handle: Int): Int
    external fun nativeGetLogs(handle: Int): String
    external fun nativeDestroyVm(handle: Int)
}
