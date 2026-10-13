package com.droidbox.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.droidbox.vm.QemuEngine
import com.droidbox.vm.VncClient
import com.droidbox.vm.QmpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Minimal DEVELOPER screen for Milestone A: attach a real ISO, start the real
 * QEMU engine, and observe real native logs / serial console evidence.
 *
 * This screen shows only real state reported by the native engine. It never
 * renders a fake Android guest.
 */
@Composable
fun DevVmScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isoPath by remember { mutableStateOf<String?>(null) }
    var diskPath by remember {
        mutableStateOf(File(context.filesDir, "vm/disk.raw").absolutePath)
    }
    // Adjustable VM resources (user asked for more RAM/disk). Persisted in
    // SharedPreferences so the app restart required between QEMU runs
    // doesn't silently reset them (v29 fix).
    val prefs = context.getSharedPreferences("droidbox", 0)
    var ramMb by remember { mutableStateOf(prefs.getInt("ramMb", 2048)) }
    var diskGb by remember { mutableStateOf(prefs.getInt("diskGb", 8)) }
    // v25: allow booting without the virtual disk (isolates vold/disk hangs).
    var useDisk by remember { mutableStateOf(prefs.getBoolean("useDisk", true)) }
    var handle by remember { mutableStateOf(-1) }
    var state by remember { mutableStateOf(QemuEngine.VmState.NATIVE_UNAVAILABLE) }
    var logs by remember { mutableStateOf("") }
    var stepLogs by remember { mutableStateOf("") }
    var serialOut by remember { mutableStateOf("") }
    var qemuStderr by remember { mutableStateOf("") }
    // v19: SeaBIOS early-boot output captured via isa-debugcon (decisive for
    // the black-screen diagnosis: output = guest CPU runs; empty = it never did).
    var debugconOut by remember { mutableStateOf("") }
    // v19: live VNC health line (framebuffer size + delivered frame count).
    var vncStats by remember { mutableStateOf("") }
    var vncBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var vncFullscreen by remember { mutableStateOf(false) }
    var vncError by remember { mutableStateOf<String?>(null) }
    val vncClient = remember {
        VncClient(
            onFrame = { bmp -> vncBitmap = bmp },
            onError = { err -> vncError = err },
        )
    }
    var busy by remember { mutableStateOf(false) }
    var engineVersion by remember { mutableStateOf("") }

    var crashReport by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        // Check preload error FIRST — if a native lib failed to load,
        // show that instead of a confusing JNI lookup error.
        val pre = QemuEngine.preloadError
        engineVersion = if (pre != null) {
            "PRELOAD FAILED: $pre"
        } else try {
            QemuEngine.nativeEngineVersion()
        } catch (t: Throwable) {
            "jni load failed: ${t.message}"
        }
        // Show native crash report from a previous run, if any.
        val crashFile = File(context.filesDir, "vm/work/crash.log")
        if (crashFile.exists()) {
            crashReport = crashFile.readText()
        }
        // Show QEMU's stderr from a previous run (fatal init errors).
        val stderrFile = File(context.filesDir, "vm/work/qemu-stderr.log")
        if (stderrFile.exists()) {
            try { qemuStderr = stderrFile.readText().takeLast(4096) } catch (t: Throwable) {}
        }
        // v19: show SeaBIOS debugcon capture from a previous run too.
        val debugconFile = File(context.filesDir, "vm/work/debugcon.log")
        if (debugconFile.exists()) {
            try { debugconOut = debugconFile.readText().takeLast(8192) } catch (t: Throwable) {}
        }
        // Restore the previously copied ISO (it lives in app-private storage,
        // so it survives app restarts/crashes — only the UI state was lost).
        // Without this, START stays disabled after every restart and the user
        // has to re-pick the ISO without knowing why.
        try {
            val isoDir = File(context.filesDir, "iso")
            val existing = isoDir.listFiles()?.filter { it.isFile }
                ?.maxByOrNull { it.lastModified() }
            if (existing != null) isoPath = existing.absolutePath
        } catch (t: Throwable) { /* keep isoPath null */ }
    }

    // Poll real engine state + logs while a VM exists.
    LaunchedEffect(handle) {
        if (handle < 0) return@LaunchedEffect
        val serialFile = File(context.filesDir, "vm/work/serial.log")
        val debugconFile = File(context.filesDir, "vm/work/debugcon.log")
        while (true) {
            state = QemuEngine.VmState.fromCode(QemuEngine.nativeGetState(handle))
            logs = QemuEngine.nativeGetLogs(handle)
            // Show guest serial console (real boot messages from the VM).
            if (serialFile.exists()) {
                try {
                    val text = serialFile.readText()
                    // Keep the last ~8KB so the UI stays responsive.
                    serialOut = if (text.length > 8192) text.takeLast(8192) else text
                } catch (t: Throwable) { /* keep previous */ }
            }
            // v19: show SeaBIOS debugcon capture (early guest boot output).
            if (debugconFile.exists()) {
                try {
                    val text = debugconFile.readText()
                    debugconOut = if (text.length > 8192) text.takeLast(8192) else text
                } catch (t: Throwable) { /* keep previous */ }
            }
            // v19: live VNC health (helps tell "guest renders black" apart
            // from "no frames arriving").
            vncStats = "fb ${vncClient.framebufferWidth()}x${vncClient.framebufferHeight()}, " +
                "frames ${vncClient.framesReceived()}"
            delay(1000)
        }
    }

    val isoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            busy = true
            scope.launch {
                // Copy the user-selected ISO into app-private storage so QEMU
                // gets a real, stable file path. The ISO itself is untouched
                // and becomes the actual guest boot media.
                isoPath = copyUriToPrivateFile(context, uri, "iso")
                busy = false
            }
        }
    }

    // Fullscreen VNC mode: show only the guest display, filling the screen.
    // Auto-enters when VNC first connects (user asked for auto-fullscreen).
    LaunchedEffect(vncBitmap) {
        if (vncBitmap != null && !vncFullscreen) {
            vncFullscreen = true
        }
    }

    if (vncFullscreen && vncBitmap != null) {
        // FULLSCREEN GUEST DISPLAY
        val bmp = vncBitmap!!
        var imgW by remember { mutableStateOf(0) }
        var imgH by remember { mutableStateOf(0) }
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Button(onClick = { vncFullscreen = false }) { Text("Console") }
                Text("Guest — touch to control", modifier = Modifier.padding(top = 12.dp))
            }
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Guest VM display (fullscreen, touch to control)",
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .onSizeChanged { size ->
                        imgW = size.width
                        imgH = size.height
                    }
                    .pointerInput(bmp) {
                        detectTapGestures(
                            onPress = { offset ->
                                val fw = vncClient.framebufferWidth()
                                val fh = vncClient.framebufferHeight()
                                if (fw > 0 && fh > 0 && imgW > 0 && imgH > 0) {
                                    val gx = (offset.x / imgW * fw).toInt().coerceIn(0, fw - 1)
                                    val gy = (offset.y / imgH * fh).toInt().coerceIn(0, fh - 1)
                                    vncClient.sendPointerEvent(gx, gy, 1)
                                    tryAwaitRelease()
                                    vncClient.sendPointerEvent(gx, gy, 0)
                                }
                            }
                        )
                    }
                    .pointerInput(bmp) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                val fw = vncClient.framebufferWidth()
                                val fh = vncClient.framebufferHeight()
                                if (fw > 0 && fh > 0 && imgW > 0 && imgH > 0) {
                                    val gx = (offset.x / imgW * fw).toInt().coerceIn(0, fw - 1)
                                    val gy = (offset.y / imgH * fh).toInt().coerceIn(0, fh - 1)
                                    vncClient.sendPointerEvent(gx, gy, 1)
                                }
                            },
                            onDrag = { change, _ ->
                                val fw = vncClient.framebufferWidth()
                                val fh = vncClient.framebufferHeight()
                                if (fw > 0 && fh > 0 && imgW > 0 && imgH > 0) {
                                    val gx = (change.position.x / imgW * fw).toInt().coerceIn(0, fw - 1)
                                    val gy = (change.position.y / imgH * fh).toInt().coerceIn(0, fh - 1)
                                    vncClient.sendPointerEvent(gx, gy, 1)
                                }
                                change.consume()
                            },
                            onDragEnd = {
                                val fw = vncClient.framebufferWidth()
                                val fh = vncClient.framebufferHeight()
                                if (fw > 0 && fh > 0) {
                                    vncClient.sendPointerEvent(fw / 2, fh / 2, 0)
                                }
                            },
                        )
                    },
            )
            // Text input for typing in guest (e.g. "exit" in debug shell).
            var typeText by remember { mutableStateOf("") }
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = typeText,
                    onValueChange = { typeText = it },
                    label = { Text("Type here") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Button(onClick = {
                    // Send each char as key press+release, then Enter.
                    for (c in typeText) {
                        val ks = c.code
                        vncClient.sendKeyEvent(ks, true)
                        vncClient.sendKeyEvent(ks, false)
                    }
                    vncClient.sendKeyEvent(0xFF0D, true)
                    vncClient.sendKeyEvent(0xFF0D, false)
                    typeText = ""
                }) { Text("Send") }
            }
            // Quick "exit" button for debug shell.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = {
                    for (c in "exit") {
                        vncClient.sendKeyEvent(c.code, true)
                        vncClient.sendKeyEvent(c.code, false)
                    }
                    vncClient.sendKeyEvent(0xFF0D, true)
                    vncClient.sendKeyEvent(0xFF0D, false)
                }) { Text("Send 'exit'") }
            }
            // D-pad for navigation (also in fullscreen)
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = {
                    vncClient.sendKeyEvent(0xFF51, true); vncClient.sendKeyEvent(0xFF51, false)
                }) { Text("◀") }
                Button(onClick = {
                    vncClient.sendKeyEvent(0xFF52, true); vncClient.sendKeyEvent(0xFF52, false)
                }) { Text("▲") }
                Button(onClick = {
                    vncClient.sendKeyEvent(0xFF54, true); vncClient.sendKeyEvent(0xFF54, false)
                }) { Text("▼") }
                Button(onClick = {
                    vncClient.sendKeyEvent(0xFF53, true); vncClient.sendKeyEvent(0xFF53, false)
                }) { Text("▶") }
                Button(onClick = {
                    vncClient.sendKeyEvent(0xFF0D, true); vncClient.sendKeyEvent(0xFF0D, false)
                }) { Text("Enter") }
            }
        }
    } else Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("DroidBox — Developer VM Console")
        Text("App v28 (fix28) — AHCI disk attach fix — if older, reinstall")
        Text("Engine: $engineVersion")
        Text("State: ${state.name}")

        Button(onClick = { isoPicker.launch(arrayOf("*/*")) }, enabled = !busy) {
            Text(if (busy) "Copying ISO…" else "Select real ISO")
        }
        Text("ISO: ${isoPath ?: "(none selected)"}")

        // VM resources (adjustable).
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("RAM:", modifier = Modifier.padding(top = 12.dp))
            listOf(2048, 4096, 6144).forEach { mb ->
                Button(
                    onClick = { ramMb = mb; prefs.edit().putInt("ramMb", mb).apply() },
                    enabled = !busy && handle < 0,
                ) {
                    Text(
                        "${mb / 1024}G",
                        color = if (ramMb == mb) androidx.compose.ui.graphics.Color.Green
                        else androidx.compose.ui.graphics.Color.White
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Disk:", modifier = Modifier.padding(top = 12.dp))
            listOf(8, 16, 32).forEach { gb ->
                Button(
                    onClick = { diskGb = gb; prefs.edit().putInt("diskGb", gb).apply() },
                    enabled = !busy && handle < 0 && useDisk,
                ) {
                    Text(
                        "${gb}G",
                        color = if (diskGb == gb) androidx.compose.ui.graphics.Color.Green
                        else androidx.compose.ui.graphics.Color.White
                    )
                }
            }
            Button(
                onClick = { useDisk = !useDisk; prefs.edit().putBoolean("useDisk", useDisk).apply() },
                enabled = !busy && handle < 0,
            ) {
                Text(if (useDisk) "Disk ON" else "Disk OFF")
            }
        }
        Text("Note: changing disk size recreates the virtual disk on next START (data lost). Disk OFF boots without virtual disk (test for disk hangs).")

        OutlinedTextField(
            value = diskPath,
            onValueChange = { diskPath = it },
            label = { Text("virtual disk path") },
            modifier = Modifier.fillMaxWidth()
        )
        Text("Note: the virtual disk is created automatically as a 16 GiB sparse raw image on first START.")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            // If a VM from a previous attempt still exists in this
                            // session, tear it down first — otherwise START stays
                            // disabled forever after one attempt ("nothing happens").
                            if (handle >= 0) {
                                stepLogs = "cleaning up previous VM..."
                                withContext(Dispatchers.IO) {
                                    QemuEngine.nativeForceStopVm(handle)
                                    QemuEngine.nativeDestroyVm(handle)
                                }
                                handle = -1
                            }
                            stepLogs = "step 1/5: preparing firmware dir..."
                            // 1. Extract QEMU firmware blobs (pc-bios) from APK assets.
                            val dataDir = File(context.filesDir, "qemu-data").apply { mkdirs() }
                            val biosFile = File(dataDir, "bios.bin")
                            val keymapFile = File(dataDir, "keymaps/en-us")
                            // Re-extract if BIOS or keymaps are missing (e.g. after app update).
                            if (!biosFile.exists() || !keymapFile.exists()) {
                                withContext(Dispatchers.IO) {
                                    // Recursively extract assets/roms (including keymaps/ subdir).
                                    fun extractDir(assetPath: String, destDir: File) {
                                        destDir.mkdirs()
                                        context.assets.list(assetPath)?.forEach { name ->
                                            val src = "$assetPath/$name"
                                            val dst = File(destDir, name)
                                            // Try as file; if it fails, try as directory.
                                            try {
                                                context.assets.open(src).use { ins ->
                                                    dst.outputStream().use { outs ->
                                                        ins.copyTo(outs)
                                                    }
                                                }
                                            } catch (_: Exception) {
                                                extractDir(src, dst)
                                            }
                                        }
                                    }
                                    extractDir("roms", dataDir)
                                }
                            }
                            stepLogs = "step 2/5: firmware ready, preparing disk..."
                            // 2. Ensure the virtual disk exists (sparse raw image).
                            //    v25: skip if useDisk is false (isolate disk hangs).
                            if (useDisk) {
                            val diskFile = File(diskPath)
                            val wantDiskSize = diskGb.toLong() * 1024 * 1024 * 1024
                            // Recreate if missing or not the expected size.
                            if (!diskFile.exists() || diskFile.length() != wantDiskSize) {
                                withContext(Dispatchers.IO) {
                                    diskFile.parentFile?.mkdirs()
                                    if (diskFile.exists()) diskFile.delete()
                                    java.io.RandomAccessFile(diskFile, "rw").use {
                                        it.setLength(wantDiskSize)
                                    }
                                }
                            }
                            } // end if (useDisk)
                            stepLogs = "step 3/5: disk ready, creating VM..."
                            val workDir = File(context.filesDir, "vm/work").apply { mkdirs() }
                            // Clear any stale crash report from a previous run.
                            File(workDir, "crash.log").delete()
                            val h = QemuEngine.nativeCreateVm(
                            machine = "q35",
                            vcpus = 2,
                            ramMb = ramMb,
                            isoPath = isoPath,
                            diskPath = if (useDisk) diskPath else "",
                            accel = "tcg,thread=single",
                            display = "none",
                            network = "user",
                            workDir = workDir.absolutePath,
                            dataDir = dataDir.absolutePath,
                        )
                        if (h >= 0) {
                            handle = h
                            stepLogs = "step 4/5: VM created (handle=$h), starting..."
                            val rc = QemuEngine.nativeStartVm(h)
                            if (rc != QemuEngine.Err.OK) {
                                logs = "start failed: err=$rc"
                            } else {
                                stepLogs = "step 5/5: start requested, booting..."
                                // Start VNC client to show REAL guest display.
                                // QEMU's VNC server starts after qemu_init; the
                                // client retries for 30s.
                                vncError = null
                                vncClient.start(scope)
                            }
                        } else {
                            logs = if (h == QemuEngine.Err.ALREADY_INITED)
                                // v19: QEMU cannot init twice per process (verified
                                // root cause of the v17 drive_config_groups crash).
                                "create failed: QEMU already ran once in this app session. " +
                                "Force-stop the app (swipe it away / Settings → Apps → Force stop), " +
                                "reopen it, then press START again."
                            else "create failed: err=$h " +
                                "(NATIVE_UNAVAILABLE means libqemu-system-x86_64.so is not in the APK yet)"
                        }
                        } catch (t: Throwable) {
                            stepLogs = "KOTLIN EXCEPTION: ${t.javaClass.simpleName}: ${t.message}"
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = isoPath != null && !busy
            ) { Text(if (busy) "Working…" else "START (real boot)") }
            if (isoPath == null && !busy) {
                Text("Tip: pehle 'Select real ISO' dabao, phir START enable hoga.")
            }

            Button(
                onClick = { scope.launch { QemuEngine.nativeShutdownVm(handle) } },
                enabled = handle >= 0
            ) { Text("Shutdown") }

            Button(
                onClick = {
                    val h = handle
                    // v25 fix: don't block UI on force stop. The QEMU thread
                    // may be hung (pthread_join blocks forever). Update UI
                    // immediately, do native cleanup in background.
                    handle = -1
                    vncClient.stop()
                    vncFullscreen = false
                    scope.launch(Dispatchers.IO) {
                        try {
                            QemuEngine.nativeForceStopVm(h)
                        } catch (_: Exception) {}
                        try {
                            QemuEngine.nativeDestroyVm(h)
                        } catch (_: Exception) {}
                    }
                },
                enabled = handle >= 0
            ) { Text("Force stop") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { scope.launch { QemuEngine.nativePauseVm(handle) } },
                enabled = handle >= 0
            ) { Text("Pause") }
            Button(
                onClick = { scope.launch { QemuEngine.nativeResumeVm(handle) } },
                enabled = handle >= 0
            ) { Text("Resume") }
        }

        // Status is shown DIRECTLY under the buttons so the user sees it
        // without scrolling.
        if (stepLogs.isNotEmpty()) {
            Text("STATUS: $stepLogs")
        }

        // VNC controls — always visible when VM exists, so user can
        // (re)connect and go fullscreen even if auto-connect failed.
        if (handle >= 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    vncError = null
                    vncClient.start(scope)
                }) { Text("Reconnect VNC") }
                Button(
                    onClick = { vncFullscreen = true },
                    enabled = vncBitmap != null,
                ) { Text("VNC Fullscreen") }
            }
            if (vncError != null) Text("VNC status: $vncError")
            // VNC debug info
            Text("VNC debug: ${vncClient.connectionState()}, frames=${vncClient.framesReceived()}")
            // QMP diagnostics
            var qmpStatus by remember { mutableStateOf("not queried") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        val workDir = File(context.filesDir, "vm/work")
                        val qmp = QmpClient(File(workDir, "qmp.sock").absolutePath)
                        val s = qmp.queryStatus()
                        val v = qmp.queryVnc()
                        qmpStatus = "$s | $v"
                    }
                }) { Text("Check VM Status") }
            }
            Text("VM status: $qmpStatus")
        }

        // REAL guest display via VNC (QEMU's built-in server).
        // Touch the display to send pointer events to the guest.
        if (vncBitmap != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Guest display (REAL, via VNC) — touch to control:")
                Button(onClick = { vncFullscreen = true }) { Text("Fullscreen") }
            }
            val bmp = vncBitmap!!
            var imgW by remember { mutableStateOf(0) }
            var imgH by remember { mutableStateOf(0) }
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Guest VM display (touch to control)",
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { size ->
                        imgW = size.width
                        imgH = size.height
                    }
                    .pointerInput(bmp) {
                        detectTapGestures(
                            onPress = { offset ->
                                // Press = button down + up (click)
                                val fw = vncClient.framebufferWidth()
                                val fh = vncClient.framebufferHeight()
                                if (fw > 0 && fh > 0 && imgW > 0 && imgH > 0) {
                                    val gx = (offset.x / imgW * fw).toInt().coerceIn(0, fw - 1)
                                    val gy = (offset.y / imgH * fh).toInt().coerceIn(0, fh - 1)
                                    vncClient.sendPointerEvent(gx, gy, 1) // button down
                                    tryAwaitRelease()
                                    vncClient.sendPointerEvent(gx, gy, 0) // button up
                                }
                            }
                        )
                    }
                    .pointerInput(bmp) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                val fw = vncClient.framebufferWidth()
                                val fh = vncClient.framebufferHeight()
                                if (fw > 0 && fh > 0 && imgW > 0 && imgH > 0) {
                                    val gx = (offset.x / imgW * fw).toInt().coerceIn(0, fw - 1)
                                    val gy = (offset.y / imgH * fh).toInt().coerceIn(0, fh - 1)
                                    vncClient.sendPointerEvent(gx, gy, 1)
                                }
                            },
                            onDrag = { change, _ ->
                                val fw = vncClient.framebufferWidth()
                                val fh = vncClient.framebufferHeight()
                                if (fw > 0 && fh > 0 && imgW > 0 && imgH > 0) {
                                    val gx = (change.position.x / imgW * fw).toInt().coerceIn(0, fw - 1)
                                    val gy = (change.position.y / imgH * fh).toInt().coerceIn(0, fh - 1)
                                    vncClient.sendPointerEvent(gx, gy, 1)
                                }
                                change.consume()
                            },
                            onDragEnd = {
                                // Release button at last position (use center as fallback)
                                val fw = vncClient.framebufferWidth()
                                val fh = vncClient.framebufferHeight()
                                if (fw > 0 && fh > 0) {
                                    vncClient.sendPointerEvent(fw / 2, fh / 2, 0)
                                }
                            },
                        )
                    },
            )
            // Simple D-pad for boot menu navigation (arrow keys + Enter).
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    vncClient.sendKeyEvent(0xFF51, true); vncClient.sendKeyEvent(0xFF51, false)
                }) { Text("◀") }
                Button(onClick = {
                    vncClient.sendKeyEvent(0xFF52, true); vncClient.sendKeyEvent(0xFF52, false)
                }) { Text("▲") }
                Button(onClick = {
                    vncClient.sendKeyEvent(0xFF54, true); vncClient.sendKeyEvent(0xFF54, false)
                }) { Text("▼") }
                Button(onClick = {
                    vncClient.sendKeyEvent(0xFF53, true); vncClient.sendKeyEvent(0xFF53, false)
                }) { Text("▶") }
                Button(onClick = {
                    vncClient.sendKeyEvent(0xFF0D, true); vncClient.sendKeyEvent(0xFF0D, false)
                }) { Text("Enter") }
            }
        } else if (vncError != null) {
            Text("VNC: $vncError")
        } else if (handle >= 0) {
            Text("VNC: connecting to guest display...")
        }

        Text("Native engine log (real output only):")
        Text(logs)
        if (serialOut.isNotEmpty()) {
            Text("Guest serial console (real boot output):")
            Text(serialOut)
        }
        // v19: SeaBIOS early-boot capture — the decisive black-screen evidence.
        if (debugconOut.isNotEmpty()) {
            Text("SeaBIOS debugcon (early guest boot — real):")
            Text(debugconOut)
        }
        if (vncStats.isNotEmpty()) {
            Text("VNC health: $vncStats")
        }
        if (crashReport != null) {
            Text("PREVIOUS NATIVE CRASH:")
            Text(crashReport!!)
        }
        if (qemuStderr.isNotEmpty()) {
            Text("QEMU STDERR (fatal errors):")
            Text(qemuStderr)
        }
    }
}

private suspend fun copyUriToPrivateFile(
    context: android.content.Context,
    uri: Uri,
    subdir: String,
): String = withContext(Dispatchers.IO) {
    val dir = File(context.filesDir, subdir).apply { mkdirs() }
    val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
    } ?: "guest.iso"
    val out = File(dir, name)
    context.contentResolver.openInputStream(uri)?.use { input ->
        out.outputStream().use { output -> input.copyTo(output) }
    }
    out.absolutePath
}
