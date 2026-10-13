package com.droidbox.vm

import android.net.LocalSocket
import android.net.LocalSocketAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter

/**
 * Minimal QMP (QEMU Machine Protocol) client.
 * Connects to QEMU's Unix socket to query VM status.
 */
class QmpClient(private val socketPath: String) {

    /**
     * Query the VM status. Returns a human-readable string like:
     * "running: true, status: running" or an error message.
     */
    suspend fun queryStatus(): String = withContext(Dispatchers.IO) {
        var socket: LocalSocket? = null
        try {
            socket = LocalSocket()
            socket.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
            socket.soTimeout = 5000

            val reader = BufferedReader(InputStreamReader(socket.inputStream))
            val writer = PrintWriter(socket.outputStream, true)

            // Read greeting
            val greeting = reader.readLine() ?: return@withContext "QMP: no greeting"

            // Enable capabilities
            writer.println("""{"execute":"qmp_capabilities"}""")
            val capAck = reader.readLine() ?: return@withContext "QMP: no cap ack"

            // Query status
            writer.println("""{"execute":"query-status"}""")
            val statusResp = reader.readLine() ?: return@withContext "QMP: no status"

            // Query version (to confirm QMP works)
            writer.println("""{"execute":"query-version"}""")
            val versionResp = reader.readLine() ?: ""

            // Parse status (simple string search, avoid JSON lib)
            val running = if (statusResp.contains("\"running\":true")) "RUNNING"
                         else if (statusResp.contains("\"running\":false")) "PAUSED/STOPPED"
                         else "UNKNOWN"
            val status = Regex(""""status":"([^"]+)"""").find(statusResp)?.groupValues?.get(1) ?: "?"

            "QMP: vm=$running (status=$status)"
        } catch (e: Exception) {
            "QMP: failed (${e.message})"
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Query VNC server info.
     */
    suspend fun queryVnc(): String = withContext(Dispatchers.IO) {
        var socket: LocalSocket? = null
        try {
            socket = LocalSocket()
            socket.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
            socket.soTimeout = 5000

            val reader = BufferedReader(InputStreamReader(socket.inputStream))
            val writer = PrintWriter(socket.outputStream, true)

            reader.readLine() // greeting
            writer.println("""{"execute":"qmp_capabilities"}""")
            reader.readLine() // ack

            writer.println("""{"execute":"query-vnc"}""")
            val resp = reader.readLine() ?: return@withContext "QMP VNC: no response"

            val enabled = if (resp.contains("\"enabled\":true")) "enabled" else "disabled"
            val host = Regex(""""host":"([^"]+)"""").find(resp)?.groupValues?.get(1) ?: "?"
            val port = Regex(""""port":"([^"]+)"""").find(resp)?.groupValues?.get(1) ?: "?"

            "QMP VNC: $enabled ($host:$port)"
        } catch (e: Exception) {
            "QMP VNC: failed (${e.message})"
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }
}
