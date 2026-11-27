package io.aethervpn.android.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * ByeDPI (`ciadpi`) desync strategy presets.
 */
enum class ByeDpiPreset(
    val label: String,
    val strategyArgs: List<String>
) {
    AUTO("Auto (Test All 3)", emptyList()),
    BALANCED("Balanced: Split + TLSRec", listOf("-s1", "-r1+s", "-a1")),
    AGGRESSIVE("Aggressive: Disorder + TLSRec", listOf("-d1", "-r1+s", "-a1")),
    OOB_DISORDER("OOB + Disorder", listOf("-o1", "-d1", "-r1+s"));

    companion object {
        val CONCRETE_PRESETS: List<ByeDpiPreset> = listOf(BALANCED, AGGRESSIVE, OOB_DISORDER)
    }
}

/**
 * Manages the isolated ByeDPI (`libbyedpi.so`) local SOCKS5 DPI-bypass subprocess.
 *
 * Strictly zero-coupling: only launched when `sb_byedpi_enabled == true` in Sing-box Direct mode.
 * Forwards stdout/stderr to `DiagnosticsLog` under the `ByeDPI` tag.
 */
class ByeDpiEngineManager(private val privateDataDir: File) {

    private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var activeProcess: Process? = null

    private var logReaderJob: Job? = null

    suspend fun start(
        context: Context,
        preset: ByeDpiPreset = ByeDpiPreset.AUTO,
        listenPort: Int = DEFAULT_PORT,
        lastWinningPreset: ByeDpiPreset? = null,
        readinessTimeoutMs: Long = 5_000L
    ): Result<ByeDpiPreset> {
        val binary = findByeDpiBinary(context)
            ?: return Result.failure(
                IllegalStateException("ByeDPI binary (libbyedpi.so) not found in nativeLibraryDir")
            )

        stop()

        val effectivePreset = resolveEffectivePreset(preset, lastWinningPreset)
        val args = buildCommandArgs(
            preset = preset,
            listenPort = listenPort,
            lastWinningPreset = lastWinningPreset
        )
        val workDir = File(privateDataDir, "byedpi").apply { mkdirs() }

        DiagnosticsLog.record(
            TAG,
            "Starting ByeDPI on ${VpnConfig.LOOPBACK_HOST}:$listenPort (${effectivePreset.label}: ${args.joinToString(" ")})"
        )

        val command = listOf(binary.absolutePath) + args
        val pb = ProcessBuilder(command)
            .directory(workDir)
            .redirectErrorStream(true)

        val proc = runCatching { pb.start() }.getOrElse { err ->
            return Result.failure(IllegalStateException("Failed to launch libbyedpi.so: ${err.message}", err))
        }
        activeProcess = proc
        startLogPump(proc)

        val deadline = System.currentTimeMillis() + readinessTimeoutMs
        var backoffMs = 50L
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive) {
                val exitCode = runCatching { proc.exitValue() }.getOrDefault(-1)
                stop()
                return Result.failure(IllegalStateException("ByeDPI exited prematurely (code $exitCode)"))
            }
            if (isSocks5Ready(listenPort)) {
                DiagnosticsLog.record(TAG, "Ready on ${VpnConfig.LOOPBACK_HOST}:$listenPort (${effectivePreset.name})")
                return Result.success(effectivePreset)
            }
            delay(backoffMs)
            backoffMs = (backoffMs + 50L).coerceAtMost(250L)
        }

        stop()
        return Result.failure(
            IllegalStateException("Timed out waiting for ByeDPI listener on ${VpnConfig.LOOPBACK_HOST}:$listenPort")
        )
    }

    fun isRunning(): Boolean = activeProcess?.isAlive == true

    fun stop() {
        val proc = activeProcess
        activeProcess = null
        logReaderJob?.cancel()
        logReaderJob = null
        if (proc != null) {
            runCatching {
                proc.destroy()
                if (!proc.waitFor(1_000, TimeUnit.MILLISECONDS)) {
                    proc.destroyForcibly()
                }
            }
        }
    }

    private fun startLogPump(proc: Process) {
        logReaderJob?.cancel()
        logReaderJob = logScope.launch {
            runCatching {
                BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { reader ->
                    var line: String? = reader.readLine()
                    while (isActive && line != null) {
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty()) {
                            DiagnosticsLog.record(TAG, trimmed)
                        }
                        line = reader.readLine()
                    }
                }
            }
        }
    }

    companion object {
        const val TAG = "ByeDPI"
        const val DEFAULT_PORT = 1827

        /**
         * Resolves [ByeDpiPreset.AUTO] to [lastWinningPreset] if it is a concrete preset,
         * or [ByeDpiPreset.BALANCED] otherwise.
         */
        fun resolveEffectivePreset(
            preset: ByeDpiPreset,
            lastWinningPreset: ByeDpiPreset? = null
        ): ByeDpiPreset {
            if (preset != ByeDpiPreset.AUTO) return preset
            return if (lastWinningPreset != null && lastWinningPreset != ByeDpiPreset.AUTO) {
                lastWinningPreset
            } else {
                ByeDpiPreset.BALANCED
            }
        }

        /**
         * Builds exact `ciadpi` CLI arguments for [preset] and [listenPort].
         */
        fun buildCommandArgs(
            preset: ByeDpiPreset,
            listenPort: Int = DEFAULT_PORT,
            lastWinningPreset: ByeDpiPreset? = null
        ): List<String> {
            val effective = resolveEffectivePreset(preset, lastWinningPreset)
            return listOf("-i", VpnConfig.LOOPBACK_HOST, "-p", listenPort.toString()) + effective.strategyArgs
        }

        fun findByeDpiBinary(context: Context): File? {
            val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
            val candidates = listOf(
                File(nativeLibDir, "libbyedpi.so"),
                File(nativeLibDir, "ciadpi"),
                File(context.filesDir, "ciadpi")
            )
            return candidates.firstOrNull { it.exists() && it.canExecute() }
        }

        fun isSocks5Ready(port: Int): Boolean {
            return try {
                Socket().use { sock ->
                    sock.soTimeout = 300
                    sock.connect(VpnConfig.loopbackSocketAddress(port), 300)
                    val out = sock.getOutputStream()
                    out.write(byteArrayOf(0x05, 0x01, 0x00))
                    out.flush()
                    val inStream = sock.getInputStream()
                    val response = ByteArray(2)
                    val read = inStream.read(response)
                    read == 2 && response[0] == 0x05.toByte() && response[1] == 0x00.toByte()
                }
            } catch (_: Exception) {
                false
            }
        }
    }
}
