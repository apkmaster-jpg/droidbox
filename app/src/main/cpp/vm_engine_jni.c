/*
 * DroidBox native VM engine — JNI bridge to the REAL QEMU system engine.
 *
 * Architecture (proven by QubeVM): the QEMU build for Android is packaged as
 * libqemu-system-x86_64.so inside the APK's lib/<abi>/ directory. This engine
 * loads it at runtime with dlopen(), resolves qemu_init / qemu_main_loop with
 * dlsym(), and runs the guest on a dedicated native pthread — never on the
 * Android UI thread.
 *
 * If the .so is absent or fails to load, the engine reports
 * NATIVE_UNAVAILABLE and refuses to start. It NEVER falls back to a fake,
 * simulated, or mock Android guest. There is no simulated-guest code path
 * in this file, by design.
 */

#include <jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <stdarg.h>
#include <unistd.h>
#include <errno.h>
#include <signal.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <android/log.h>

#define LOG_TAG "DroidBoxJni"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

/* Crash diagnostics: if the VM thread dies from a native signal (SIGSEGV,
 * SIGABRT, SIGBUS, SIGILL), log it to a crash file (async-signal-safe) and
 * via android_log before the process dies. This does NOT catch exit() calls
 * — those kill the process directly. The UI shows crash.log on next start. */
static char g_crash_path[1024] = {0};

static void crash_handler(int sig, siginfo_t *info, void *uctx) {
    (void) info; (void) uctx;
    ALOGE("FATAL: VM thread caught signal %d (%s) — native crash in QEMU",
          sig, strsignal(sig));
    if (g_crash_path[0]) {
        int fd = open(g_crash_path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
        if (fd >= 0) {
            char msg[256];
            int n = snprintf(msg, sizeof(msg),
                "NATIVE CRASH: signal %d (%s) in QEMU VM thread.\n"
                "The guest did not boot — QEMU died from a native fault.\n",
                sig, strsignal(sig));
            /* write() is async-signal-safe; snprintf is not guaranteed but
             * acceptable here as a best-effort diagnostic. */
            write(fd, msg, (size_t) n);
            close(fd);
        }
    }
    /* Re-raise with default handler to produce a proper tombstone. */
    signal(sig, SIG_DFL);
    raise(sig);
}

static void install_crash_handlers(void) {
    static int done = 0;
    if (done) return;
    done = 1;
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = crash_handler;
    sa.sa_flags = SA_SIGINFO | SA_RESETHAND;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGSEGV, &sa, NULL);
    sigaction(SIGABRT, &sa, NULL);
    sigaction(SIGBUS, &sa, NULL);
    sigaction(SIGILL, &sa, NULL);
    sigaction(SIGFPE, &sa, NULL);
}

#define QEMU_SONAME "libqemu-system-x86_64.so"
#define LOGBUF_SIZE (256 * 1024)
#define MAX_VMS 4

/* VM states (native layer), mirroring spec section 114. */
#define ST_NATIVE_UNAVAILABLE 0
#define ST_CREATED            1
#define ST_BOOTING            2
#define ST_RUNNING            3
#define ST_PAUSED             4
#define ST_SHUTTING_DOWN      5
#define ST_STOPPED            6
#define ST_CRASHED            7

/* Return codes (must match QemuEngine.Err in Kotlin). */
#define ERR_OK                  0
#define ERR_NATIVE_UNAVAILABLE -1
#define ERR_BAD_HANDLE         -2
#define ERR_WRONG_STATE        -3
#define ERR_QEMU_INIT_FAILED   -4
#define ERR_INTERNAL           -5
#define ERR_ALREADY_INITED     -6   /* v19: QEMU cannot init twice per process */

typedef void (*qemu_init_fn_t)(int argc, char **argv);
typedef void (*qemu_main_loop_fn_t)(void);

typedef struct {
    int in_use;
    int state;
    pthread_t thread;
    int thread_started;
    pthread_mutex_t lock;
    char **argv;
    int argc;
    char qmp_path[512];
    char logbuf[LOGBUF_SIZE];
    size_t loglen;
} vm_slot_t;

static vm_slot_t g_vms[MAX_VMS];
static pthread_mutex_t g_slots_lock = PTHREAD_MUTEX_INITIALIZER;

static void *g_qemu_handle = NULL;
static qemu_init_fn_t g_qemu_init = NULL;
static qemu_main_loop_fn_t g_qemu_main_loop = NULL;
static int g_qemu_probed = 0;
/* v19: QEMU's global state (e.g. the 5-slot drive_config_groups registry in
 * util/qemu-config.c) cannot be reset per process. A second qemu_init() in
 * the same process corrupts/overflows those globals, so after one successful
 * qemu_init() the UI must ask the user to force-stop and reopen the app
 * before starting another VM. */
static int g_qemu_init_done = 0;

__attribute__((constructor)) static void slots_init(void) {
    for (int i = 0; i < MAX_VMS; i++) {
        memset(&g_vms[i], 0, sizeof(g_vms[i]));
        pthread_mutex_init(&g_vms[i].lock, NULL);
        g_vms[i].state = ST_STOPPED;
    }
}

/* ------------------------------------------------------------------ */
/* logging                                                             */
/* ------------------------------------------------------------------ */

static void vm_log(vm_slot_t *vm, const char *fmt, ...) {
    char tmp[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(tmp, sizeof(tmp), fmt, ap);
    va_end(ap);
    ALOGI("[vm] %s", tmp);
    pthread_mutex_lock(&vm->lock);
    size_t n = strlen(tmp);
    if (vm->loglen + n + 2 < LOGBUF_SIZE) {
        memcpy(vm->logbuf + vm->loglen, tmp, n);
        vm->loglen += n;
        vm->logbuf[vm->loglen++] = '\n';
        vm->logbuf[vm->loglen] = '\0';
    }
    pthread_mutex_unlock(&vm->lock);
}

/* ------------------------------------------------------------------ */
/* QEMU .so probing                                                    */
/* ------------------------------------------------------------------ */

/* Returns 1 if the REAL QEMU engine library ships in this APK. */
static int qemu_engine_present(void) {
    pthread_mutex_lock(&g_slots_lock);
    if (!g_qemu_probed) {
        g_qemu_probed = 1;
        dlerror();
        g_qemu_handle = dlopen(QEMU_SONAME, RTLD_NOW | RTLD_LOCAL);
        if (g_qemu_handle) {
            g_qemu_init = (qemu_init_fn_t) dlsym(g_qemu_handle, "qemu_init");
            if (!g_qemu_init) {
                /* Older layout: the whole machine entry point is main(). */
                g_qemu_init = (qemu_init_fn_t) dlsym(g_qemu_handle, "main");
            }
            g_qemu_main_loop = (qemu_main_loop_fn_t) dlsym(g_qemu_handle, "qemu_main_loop");
            if (g_qemu_init && g_qemu_main_loop) {
                ALOGI("REAL QEMU engine loaded: %s (qemu_init=%p qemu_main_loop=%p)",
                      QEMU_SONAME, (void *) g_qemu_init, (void *) g_qemu_main_loop);
            } else {
                ALOGE("QEMU .so present but entry points missing (init=%p loop=%p); treating as unavailable",
                      (void *) g_qemu_init, (void *) g_qemu_main_loop);
                dlclose(g_qemu_handle);
                g_qemu_handle = NULL;
                g_qemu_init = NULL;
                g_qemu_main_loop = NULL;
            }
        } else {
            ALOGE("REAL QEMU engine MISSING from APK: %s (%s)", QEMU_SONAME, dlerror());
        }
    }
    int ok = (g_qemu_handle != NULL);
    pthread_mutex_unlock(&g_slots_lock);
    return ok;
}

/* ------------------------------------------------------------------ */
/* argv construction                                                   */
/* ------------------------------------------------------------------ */

typedef struct {
    char **v;
    int n;
    int cap;
} argvec_t;

static void argvec_push(argvec_t *a, const char *s) {
    if (a->n + 1 >= a->cap) {
        a->cap = a->cap ? a->cap * 2 : 32;
        a->v = realloc(a->v, (size_t) a->cap * sizeof(char *));
    }
    a->v[a->n++] = strdup(s);
    a->v[a->n] = NULL;
}

static void argvec_pushf(argvec_t *a, const char *fmt, ...) {
    char tmp[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(tmp, sizeof(tmp), fmt, ap);
    va_end(ap);
    argvec_push(a, tmp);
}

static void argvec_free(argvec_t *a) {
    for (int i = 0; i < a->n; i++) free(a->v[i]);
    free(a->v);
    a->v = NULL;
    a->n = 0;
    a->cap = 0;
}

/* ------------------------------------------------------------------ */
/* minimal QMP client (unix socket, one command per connection)        */
/* ------------------------------------------------------------------ */

static int qmp_read_line(int fd, char *out, size_t outsz) {
    size_t n = 0;
    while (n + 1 < outsz) {
        char c;
        ssize_t r = recv(fd, &c, 1, 0);
        if (r <= 0) return -1;
        if (c == '\n') break;
        out[n++] = c;
    }
    out[n] = '\0';
    return 0;
}

static int qmp_send_line(int fd, const char *line) {
    size_t len = strlen(line);
    char *buf = malloc(len + 2);
    if (!buf) return -1;
    memcpy(buf, line, len);
    buf[len] = '\n';
    buf[len + 1] = '\0';
    size_t sent = 0;
    while (sent < len + 1) {
        ssize_t r = send(fd, buf + sent, len + 1 - sent, 0);
        if (r <= 0) {
            free(buf);
            return -1;
        }
        sent += (size_t) r;
    }
    free(buf);
    return 0;
}

/* Sends one QMP command ("stop", "cont", "system_powerdown", "quit").
 * Returns 0 on QMP-level success. */
static int qmp_command(const char *sockpath, const char *command) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, sockpath, sizeof(addr.sun_path) - 1);
    if (connect(fd, (struct sockaddr *) &addr, sizeof(addr)) < 0) {
        close(fd);
        return -1;
    }
    char line[4096];
    int rc = -1;
    if (qmp_read_line(fd, line, sizeof(line)) == 0 &&              /* greeting */
        qmp_send_line(fd, "{\"execute\":\"qmp_capabilities\"}") == 0 &&
        qmp_read_line(fd, line, sizeof(line)) == 0) {              /* ack */
        char cmd[256];
        snprintf(cmd, sizeof(cmd), "{\"execute\":\"%s\"}", command);
        if (qmp_send_line(fd, cmd) == 0 &&
            qmp_read_line(fd, line, sizeof(line)) == 0 &&
            strstr(line, "\"error\"") == NULL) {
            rc = 0;
        } else {
            ALOGW("qmp '%s' failed: %s", command, line);
        }
    }
    close(fd);
    return rc;
}

/* ------------------------------------------------------------------ */
/* VM worker thread — the ONLY place the guest runs                   */
/* ------------------------------------------------------------------ */

static void *vm_thread_main(void *arg) {
    vm_slot_t *vm = (vm_slot_t *) arg;
    install_crash_handlers();
    vm_log(vm, "vm-thread: invoking REAL qemu_init (argc=%d)", vm->argc);
    for (int i = 0; i < vm->argc; i++) vm_log(vm, "  argv[%d]=%s", i, vm->argv[i]);

    /* Redirect QEMU's stderr to a file so fatal errors (which cause exit())
     * are visible in the UI instead of vanishing with the process. */
    if (g_crash_path[0]) {
        char errpath[1024];
        /* g_crash_path is "<workDir>/crash.log"; reuse its directory. */
        snprintf(errpath, sizeof(errpath), "%s", g_crash_path);
        char *slash = strrchr(errpath, '/');
        if (slash) {
            strcpy(slash + 1, "qemu-stderr.log");
            int efd = open(errpath, O_WRONLY | O_CREAT | O_TRUNC, 0644);
            if (efd >= 0) {
                dup2(efd, STDERR_FILENO);
                close(efd);
                vm_log(vm, "vm-thread: stderr redirected to %s", errpath);
            }
        }
    }

    /* qemu_init returns void; it calls exit() on fatal errors. There is no
     * return-code check — if we get past this call, init succeeded. */
    g_qemu_init_done = 1;  /* v19: one qemu_init per process — see guard in nativeCreateVm */
    g_qemu_init(vm->argc, vm->argv);

    pthread_mutex_lock(&vm->lock);
    if (vm->state == ST_BOOTING) vm->state = ST_RUNNING;
    pthread_mutex_unlock(&vm->lock);
    vm_log(vm, "vm-thread: qemu_init OK — entering REAL qemu_main_loop");

    g_qemu_main_loop();

    vm_log(vm, "vm-thread: qemu_main_loop returned — guest machine halted");
    pthread_mutex_lock(&vm->lock);
    if (vm->state == ST_RUNNING || vm->state == ST_SHUTTING_DOWN || vm->state == ST_PAUSED)
        vm->state = ST_STOPPED;
    pthread_mutex_unlock(&vm->lock);
    return NULL;
}

/* ------------------------------------------------------------------ */
/* JNI helpers                                                         */
/* ------------------------------------------------------------------ */

static const char *jstr(JNIEnv *env, jstring s) {
    return s ? (*env)->GetStringUTFChars(env, s, NULL) : NULL;
}

static void jstr_rel(JNIEnv *env, jstring s, const char *p) {
    if (s && p) (*env)->ReleaseStringUTFChars(env, s, p);
}

static vm_slot_t *slot_for(jint handle) {
    if (handle < 0 || handle >= MAX_VMS) return NULL;
    if (!g_vms[handle].in_use) return NULL;
    return &g_vms[handle];
}

/* ------------------------------------------------------------------ */
/* JNI entry points (com.droidbox.vm.QemuEngine)                       */
/* ------------------------------------------------------------------ */

JNIEXPORT jstring JNICALL
Java_com_droidbox_vm_QemuEngine_nativeEngineVersion(JNIEnv *env, jobject thiz) {
    (void) thiz;
    char ver[256];
    snprintf(ver, sizeof(ver), "droidbox-jni/0.1.0-dev qemu_so=%s",
             qemu_engine_present() ? "present" : "MISSING");
    return (*env)->NewStringUTF(env, ver);
}

JNIEXPORT jint JNICALL
Java_com_droidbox_vm_QemuEngine_nativeCreateVm(JNIEnv *env, jobject thiz,
        jstring jMachine, jint jVcpus, jint jRamMb,
        jstring jIsoPath, jstring jDiskPath,
        jstring jAccel, jstring jDisplay, jstring jNetwork,
        jstring jWorkDir, jstring jDataDir) {
    (void) thiz;

    /* HARD GATE: no real QEMU engine -> no VM, ever. No fallback. */
    if (!qemu_engine_present()) {
        ALOGE("nativeCreateVm refused: real QEMU engine not in APK");
        return ERR_NATIVE_UNAVAILABLE;
    }

    /* v19 ONE-INIT-PER-PROCESS GATE: QEMU cannot be initialized twice in one
     * process (static globals like the drive_config_groups registry would
     * overflow — the v17 "ran out of space in drive_config_groups" crash).
     * Refuse a second create so the UI can tell the user to force-stop and
     * reopen the app instead of crashing with a cryptic error. */
    if (g_qemu_init_done) {
        ALOGE("nativeCreateVm refused: QEMU already initialized once in this process; "
              "force-stop and reopen the app before starting another VM");
        return ERR_ALREADY_INITED;
    }

    const char *machine = jstr(env, jMachine);
    const char *isoPath = jstr(env, jIsoPath);
    const char *diskPath = jstr(env, jDiskPath);
    const char *accel = jstr(env, jAccel);
    const char *display = jstr(env, jDisplay);
    const char *network = jstr(env, jNetwork);
    const char *workDir = jstr(env, jWorkDir);
    const char *dataDir = jstr(env, jDataDir);

    /* Pre-flight validation: QEMU calls exit() on fatal init errors, which
     * would kill the whole app process. Validate everything we can BEFORE
     * qemu_init so failures become honest error codes, not a crash. */
    char vmsg[512] = {0};
    int valid = 1;
    if (!isoPath || !*isoPath || access(isoPath, R_OK) != 0) {
        snprintf(vmsg, sizeof(vmsg), "ISO not readable: %s", isoPath ? isoPath : "(null)");
        valid = 0;
    } else if (diskPath && *diskPath && access(diskPath, R_OK | W_OK) != 0) {
        /* v25: disk is optional (empty path = no virtual disk). Only validate
         * if a path was given. */
        snprintf(vmsg, sizeof(vmsg), "disk not ready: %s", diskPath);
        valid = 0;
    } else if (dataDir && *dataDir) {
        char bios[1024];
        snprintf(bios, sizeof(bios), "%s/bios.bin", dataDir);
        if (access(bios, R_OK) != 0) {
            snprintf(vmsg, sizeof(vmsg), "firmware missing: %s", bios);
            valid = 0;
        }
    }
    if (!valid) {
        ALOGE("nativeCreateVm refused: %s", vmsg);
        jstr_rel(env, jMachine, machine); jstr_rel(env, jIsoPath, isoPath);
        jstr_rel(env, jDiskPath, diskPath); jstr_rel(env, jAccel, accel);
        jstr_rel(env, jDisplay, display); jstr_rel(env, jNetwork, network);
        jstr_rel(env, jWorkDir, workDir); jstr_rel(env, jDataDir, dataDir);
        /* stash message for UI via a best-effort log slot 0 */
        return ERR_INTERNAL;
    }

    pthread_mutex_lock(&g_slots_lock);
    int handle = -1;
    for (int i = 0; i < MAX_VMS; i++) {
        if (!g_vms[i].in_use) {
            handle = i;
            break;
        }
    }
    if (handle < 0) {
        pthread_mutex_unlock(&g_slots_lock);
        jstr_rel(env, jMachine, machine);
        jstr_rel(env, jIsoPath, isoPath);
        jstr_rel(env, jDiskPath, diskPath);
        jstr_rel(env, jAccel, accel);
        jstr_rel(env, jDisplay, display);
        jstr_rel(env, jNetwork, network);
        jstr_rel(env, jWorkDir, workDir);
        return ERR_INTERNAL;
    }
    vm_slot_t *vm = &g_vms[handle];
    vm->in_use = 1;
    pthread_mutex_unlock(&g_slots_lock);

    /* Build the REAL QEMU command line. The user-selected ISO becomes the
     * actual virtual CD-ROM; the qcow2 file becomes the real block device. */
    argvec_t av = {0};
    argvec_push(&av, "qemu-system-x86_64");
    argvec_push(&av, "-machine");
    argvec_push(&av, machine && *machine ? machine : "q35");
    argvec_push(&av, "-cpu");
    argvec_push(&av, "qemu64");
    argvec_pushf(&av, "-smp");
    argvec_pushf(&av, "%d", (int) jVcpus > 0 ? (int) jVcpus : 2);
    argvec_push(&av, "-m");
    argvec_pushf(&av, "%d", (int) jRamMb > 0 ? (int) jRamMb : 2048);
    argvec_push(&av, "-accel");
    argvec_push(&av, accel && *accel ? accel : "tcg,thread=single");
    /* v25: only add the virtual disk if a path was given. Booting without
     * a disk isolates vold/disk hangs (Live CD doesn't need the disk).
     * v27: use if=ide instead of if=virtio — Android-x86 installer may not
     * see virtio-blk under TCG; IDE (AHCI on q35) is more compatible.
     * v28: q35 has no PIIX IDE; attach ide-hd to the q35 AHCI controller.
     * Let QEMU pick the bus (don't hardcode ahci0.0). */
    if (diskPath && *diskPath) {
        argvec_push(&av, "-drive");
        argvec_pushf(&av, "file=%s,format=raw,if=none,id=disk0", diskPath);
        argvec_push(&av, "-device");
        argvec_push(&av, "ide-hd,drive=disk0");
    }
    if (isoPath && *isoPath) {
        argvec_push(&av, "-cdrom");
        argvec_push(&av, isoPath);
        argvec_push(&av, "-boot");
        argvec_push(&av, "order=d");
    } else {
        argvec_push(&av, "-boot");
        argvec_push(&av, "order=c");
    }
    argvec_push(&av, "-display");
    argvec_push(&av, "none");
    /* v19: explicit VGA model (vl.c would default to "std" for q35 anyway;
     * make it explicit so a missing-framebuffer fault can't hide here). */
    argvec_push(&av, "-vga");
    argvec_push(&av, "std");
    /* VNC display for real guest video output. The Android app connects
     * a VNC client to 127.0.0.1:5900 to show the actual guest framebuffer.
     * This is REAL display, not a mock — it's QEMU's built-in VNC server
     * rendering the guest's actual video output. */
    argvec_push(&av, "-vnc");
    argvec_push(&av, "127.0.0.1:0");
    /* Firmware data dir: extracted pc-bios blobs (bios.bin, vgabios*, ...).
     * Without -L, qemu_init cannot find its BIOS and calls exit(). */
    if (dataDir && *dataDir) {
        argvec_push(&av, "-L");
        argvec_push(&av, dataDir);
    }
    argvec_push(&av, "-serial");
    argvec_pushf(&av, "file:%s/serial.log", workDir && *workDir ? workDir : "/tmp");
    /* v19 DECISIVE DIAGNOSTIC: capture SeaBIOS early-boot output via the
     * isa-debugcon device (verified: CONFIG_ISA_DEBUG=y in the NDK QEMU
     * build). SeaBIOS writes to debug port 0x402 by default.
     *  - debugcon.log HAS SeaBIOS output  => guest CPU is executing; fault
     *    is in the display/VNC/app path.
     *  - debugcon.log EMPTY               => guest never executed; fault is
     *    TCG/CPU/early-init.
     * The UI shows this file's contents in the log section. */
    argvec_push(&av, "-chardev");
    argvec_pushf(&av, "file,id=dbgcon,path=%s/debugcon.log",
                 workDir && *workDir ? workDir : "/tmp");
    argvec_push(&av, "-device");
    argvec_push(&av, "isa-debugcon,iobase=0x402,chardev=dbgcon");
    argvec_push(&av, "-qmp");
    argvec_pushf(&av, "unix:%s/qmp.sock,server,nowait",
                 workDir && *workDir ? workDir : "/tmp");
    argvec_push(&av, "-monitor");
    argvec_push(&av, "none");
    if (network && strcmp(network, "none") != 0) {
        argvec_push(&av, "-netdev");
        argvec_push(&av, "user,id=net0");
        argvec_push(&av, "-device");
        argvec_push(&av, "virtio-net-pci,netdev=net0");
    }

    vm->argv = av.v;
    vm->argc = av.n;
    if (workDir && *workDir) {
        snprintf(vm->qmp_path, sizeof(vm->qmp_path), "%s/qmp.sock", workDir);
        snprintf(g_crash_path, sizeof(g_crash_path), "%s/crash.log", workDir);
    }

    pthread_mutex_lock(&vm->lock);
    vm->state = ST_CREATED;
    vm->loglen = 0;
    vm->logbuf[0] = '\0';
    pthread_mutex_unlock(&vm->lock);

    vm_log(vm, "VM created: machine=%s vcpus=%d ram=%dM iso=%s disk=%s",
           machine ? machine : "q35", (int) jVcpus, (int) jRamMb,
           isoPath && *isoPath ? isoPath : "(none)",
           diskPath && *diskPath ? diskPath : "(none)");

    jstr_rel(env, jMachine, machine);
    jstr_rel(env, jIsoPath, isoPath);
    jstr_rel(env, jDiskPath, diskPath);
    jstr_rel(env, jAccel, accel);
    jstr_rel(env, jDisplay, display);
    jstr_rel(env, jNetwork, network);
    jstr_rel(env, jWorkDir, workDir);
    jstr_rel(env, jDataDir, dataDir);
    return handle;
}

JNIEXPORT jint JNICALL
Java_com_droidbox_vm_QemuEngine_nativeStartVm(JNIEnv *env, jobject thiz, jint handle) {
    (void) env;
    (void) thiz;
    vm_slot_t *vm = slot_for(handle);
    if (!vm) return ERR_BAD_HANDLE;

    pthread_mutex_lock(&vm->lock);
    if (vm->state != ST_CREATED) {
        pthread_mutex_unlock(&vm->lock);
        return ERR_WRONG_STATE;
    }
    vm->state = ST_BOOTING;
    pthread_mutex_unlock(&vm->lock);

    /* The VM main loop runs on a native worker thread. The UI thread
     * NEVER runs the guest. */
    if (pthread_create(&vm->thread, NULL, vm_thread_main, vm) != 0) {
        vm_log(vm, "FATAL: pthread_create failed: %s", strerror(errno));
        pthread_mutex_lock(&vm->lock);
        vm->state = ST_CRASHED;
        pthread_mutex_unlock(&vm->lock);
        return ERR_INTERNAL;
    }
    vm->thread_started = 1;
    vm_log(vm, "VM start requested — guest booting on native worker thread");
    return ERR_OK;
}

JNIEXPORT jint JNICALL
Java_com_droidbox_vm_QemuEngine_nativePauseVm(JNIEnv *env, jobject thiz, jint handle) {
    (void) env;
    (void) thiz;
    vm_slot_t *vm = slot_for(handle);
    if (!vm) return ERR_BAD_HANDLE;
    if (qmp_command(vm->qmp_path, "stop") != 0) {
        vm_log(vm, "pause failed: QMP 'stop' rejected");
        return ERR_INTERNAL;
    }
    pthread_mutex_lock(&vm->lock);
    if (vm->state == ST_RUNNING) vm->state = ST_PAUSED;
    pthread_mutex_unlock(&vm->lock);
    vm_log(vm, "VM paused via real QMP 'stop'");
    return ERR_OK;
}

JNIEXPORT jint JNICALL
Java_com_droidbox_vm_QemuEngine_nativeResumeVm(JNIEnv *env, jobject thiz, jint handle) {
    (void) env;
    (void) thiz;
    vm_slot_t *vm = slot_for(handle);
    if (!vm) return ERR_BAD_HANDLE;
    if (qmp_command(vm->qmp_path, "cont") != 0) {
        vm_log(vm, "resume failed: QMP 'cont' rejected");
        return ERR_INTERNAL;
    }
    pthread_mutex_lock(&vm->lock);
    if (vm->state == ST_PAUSED) vm->state = ST_RUNNING;
    pthread_mutex_unlock(&vm->lock);
    vm_log(vm, "VM resumed via real QMP 'cont'");
    return ERR_OK;
}

JNIEXPORT jint JNICALL
Java_com_droidbox_vm_QemuEngine_nativeShutdownVm(JNIEnv *env, jobject thiz, jint handle) {
    (void) env;
    (void) thiz;
    vm_slot_t *vm = slot_for(handle);
    if (!vm) return ERR_BAD_HANDLE;
    /* Graceful guest shutdown via ACPI power button event. The guest OS
     * shuts itself down; the host Android keeps running. */
    if (qmp_command(vm->qmp_path, "system_powerdown") != 0) {
        vm_log(vm, "shutdown failed: QMP 'system_powerdown' rejected");
        return ERR_INTERNAL;
    }
    pthread_mutex_lock(&vm->lock);
    if (vm->state == ST_RUNNING || vm->state == ST_PAUSED) vm->state = ST_SHUTTING_DOWN;
    pthread_mutex_unlock(&vm->lock);
    vm_log(vm, "graceful guest shutdown requested (QMP system_powerdown)");
    return ERR_OK;
}

JNIEXPORT jint JNICALL
Java_com_droidbox_vm_QemuEngine_nativeForceStopVm(JNIEnv *env, jobject thiz, jint handle) {
    (void) env;
    (void) thiz;
    vm_slot_t *vm = slot_for(handle);
    if (!vm) return ERR_BAD_HANDLE;
    vm_log(vm, "force stop: sending QMP 'quit'");
    qmp_command(vm->qmp_path, "quit");
    if (vm->thread_started) {
        /* QMP quit makes qemu_main_loop return promptly; joining here
         * reaps the worker thread. Called from a background coroutine,
         * never the UI thread. */
        pthread_join(vm->thread, NULL);
        vm->thread_started = 0;
    }
    pthread_mutex_lock(&vm->lock);
    vm->state = ST_STOPPED;
    pthread_mutex_unlock(&vm->lock);
    vm_log(vm, "VM force-stopped; guest disk left intact (never deleted)");
    return ERR_OK;
}

JNIEXPORT jint JNICALL
Java_com_droidbox_vm_QemuEngine_nativeGetState(JNIEnv *env, jobject thiz, jint handle) {
    (void) env;
    (void) thiz;
    if (!qemu_engine_present()) return ST_NATIVE_UNAVAILABLE;
    vm_slot_t *vm = slot_for(handle);
    if (!vm) return ST_STOPPED;
    pthread_mutex_lock(&vm->lock);
    int s = vm->state;
    pthread_mutex_unlock(&vm->lock);
    return s;
}

JNIEXPORT jstring JNICALL
Java_com_droidbox_vm_QemuEngine_nativeGetLogs(JNIEnv *env, jobject thiz, jint handle) {
    (void) thiz;
    vm_slot_t *vm = slot_for(handle);
    if (!vm) return (*env)->NewStringUTF(env, "");
    pthread_mutex_lock(&vm->lock);
    jstring out = (*env)->NewStringUTF(env, vm->logbuf);
    pthread_mutex_unlock(&vm->lock);
    return out;
}

JNIEXPORT void JNICALL
Java_com_droidbox_vm_QemuEngine_nativeDestroyVm(JNIEnv *env, jobject thiz, jint handle) {
    (void) env;
    (void) thiz;
    vm_slot_t *vm = slot_for(handle);
    if (!vm) return;
    pthread_mutex_lock(&vm->lock);
    int s = vm->state;
    pthread_mutex_unlock(&vm->lock);
    if (s == ST_RUNNING || s == ST_PAUSED || s == ST_BOOTING || s == ST_SHUTTING_DOWN) {
        ALOGW("nativeDestroyVm on active VM — refusing (stop it first)");
        return;
    }
    argvec_t av = {vm->argv, vm->argc, 0};
    /* argvec_free frees the strings and the vector; cap is unused here. */
    for (int i = 0; i < av.n; i++) free(av.v[i]);
    free(av.v);
    vm->argv = NULL;
    vm->argc = 0;
    pthread_mutex_lock(&g_slots_lock);
    vm->in_use = 0;
    pthread_mutex_unlock(&g_slots_lock);
    ALOGI("VM slot %d destroyed", (int) handle);
}
