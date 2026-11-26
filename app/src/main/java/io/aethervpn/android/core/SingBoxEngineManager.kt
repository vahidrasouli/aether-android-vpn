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
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Manages the official SagerNet/sing-box ARM64 core subprocess (`libsing-box.so`).
 *
 * Strict resource isolation:
 * - When `VpnConfig.singBoxEnabled` is false, guarantees zero subprocesses,
 *   zero config file generation, and zero RAM/CPU overhead.
 * - Applies Go runtime caps (`GOMEMLIMIT`, `GOMAXPROCS`, `GOGC`) from `VpnConfig`.
 * - Forwards stdout/stderr to `DiagnosticsLog` under the `[SingBox]` tag.
 */
class SingBoxEngineManager(private val privateDataDir: File) {

    private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var activeProcess: Process? = null

    private var logReaderJob: Job? = null
    private var activeConfigFile: File? = null

    suspend fun start(
        context: Context,
        config: VpnConfig,
        rawUriOrJson: String,
        listenPort: Int = VpnConfig.SING_BOX_SOCKS_PORT,
        upstreamSocksPort: Int? = null,
        readinessTimeoutMs: Long = 15_000L
    ): Result<Unit> {
        if (!config.singBoxEnabled) {
            return Result.failure(IllegalStateException("Sing-box is disabled (master switch OFF)"))
        }

        val binary = findSingBoxBinary(context)
            ?: return Result.failure(
                IllegalStateException("Sing-box binary (libsing-box.so) not found in nativeLibraryDir")
            )

        val runtimeJsonResult = SingBoxConfigParser.buildRuntimeConfig(
            rawUriOrJson = rawUriOrJson,
            listenPort = listenPort,
            upstreamSocksPort = upstreamSocksPort
        )
        if (runtimeJsonResult.isFailure) {
            return Result.failure(
                runtimeJsonResult.exceptionOrNull()
                    ?: IllegalStateException("Failed to build sing-box runtime config")
            )
        }

        stop()

        val stateDir = File(privateDataDir, "singbox").apply { mkdirs() }
        val configFile = File(stateDir, "singbox-config.json")
        configFile.writeText(runtimeJsonResult.getOrThrow(), Charsets.UTF_8)
        activeConfigFile = configFile

        val envMap = resolveGoEnvironment(config)
        DiagnosticsLog.record(
            "SingBox",
            "Starting sing-box on 127.0.0.1:$listenPort [Go: mem=${envMap["GOMEMLIMIT"] ?: "auto"}, procs=${envMap["GOMAXPROCS"] ?: "auto"}, gc=${envMap["GOGC"] ?: "50"}]"
        )

        val pb = ProcessBuilder(binary.absolutePath, "run", "-c", configFile.absolutePath)
            .directory(stateDir)
            .redirectErrorStream(true)
        pb.environment().apply {
            envMap.forEach { (k, v) ->
                if (v != null) put(k, v) else remove(k)
            }
        }

        val proc = runCatching { pb.start() }.getOrElse { err ->
            cleanupConfigFile()
            return Result.failure(IllegalStateException("Failed to launch libsing-box.so: ${err.message}", err))
        }
        activeProcess = proc
        startLogPump(proc, "SingBox")

        val deadline = System.currentTimeMillis() + readinessTimeoutMs
        var backoffMs = 100L
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive) {
                val exitCode = runCatching { proc.exitValue() }.getOrDefault(-1)
                stop()
                return Result.failure(IllegalStateException("sing-box exited prematurely (code $exitCode)"))
            }
            if (isSocks5Ready(listenPort)) {
                DiagnosticsLog.record("SingBox", "Ready on 127.0.0.1:$listenPort")
                return Result.success(Unit)
            }
            delay(backoffMs)
            backoffMs = (backoffMs + 50L).coerceAtMost(350L)
        }

        stop()
        return Result.failure(
            IllegalStateException("Timed out waiting for sing-box listener on 127.0.0.1:$listenPort")
        )
    }

    fun isRunning(): Boolean {
        return activeProcess?.isAlive == true
    }

    fun stop() {
        val proc = activeProcess
        activeProcess = null
        logReaderJob?.cancel()
        logReaderJob = null
        if (proc != null) {
            runCatching {
                proc.destroy()
                if (!proc.waitFor(1500, TimeUnit.MILLISECONDS)) {
                    proc.destroyForcibly()
                }
            }
        }
        cleanupConfigFile()
    }

    private fun cleanupConfigFile() {
        val f = activeConfigFile
        activeConfigFile = null
        runCatching { f?.delete() }
    }

    private fun startLogPump(proc: Process, tag: String) {
        logReaderJob?.cancel()
        logReaderJob = logScope.launch {
            runCatching {
                BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { reader ->
                    var line: String? = reader.readLine()
                    while (isActive && line != null) {
                        val cleaned = stripAnsiCodes(line).trim()
                        if (cleaned.isNotEmpty()) {
                            DiagnosticsLog.record(tag, cleaned)
                        }
                        line = reader.readLine()
                    }
                }
            }
        }
    }

    companion object {
        private val ANSI_ESCAPE_REGEX = Regex("\\u001B\\[[0-9;]*m")

        internal fun stripAnsiCodes(line: String): String {
            return ANSI_ESCAPE_REGEX.replace(line, "")
        }

        internal fun resolveGoEnvironment(config: VpnConfig): Map<String, String?> {
            val env = linkedMapOf<String, String?>()
            env["GOMEMLIMIT"] = if (config.goMemLimit.equals("Auto", ignoreCase = true) || config.goMemLimit.isBlank()) {
                null
            } else {
                config.goMemLimit
            }
            env["GOGC"] = config.goGc.ifBlank { "50" }
            env["GOMAXPROCS"] = if (config.goMaxProcs.equals("Auto", ignoreCase = true) || config.goMaxProcs.isBlank()) {
                null
            } else {
                config.goMaxProcs
            }
            return env
        }

        fun findSingBoxBinary(context: Context): File? {
            val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
            val candidates = listOf(
                File(nativeLibDir, "libsing-box.so"),
                File(nativeLibDir, "sing-box"),
                File(context.filesDir, "sing-box")
            )
            return candidates.firstOrNull { it.exists() && it.canExecute() }
        }

        fun isSocks5Ready(port: Int): Boolean {
            return try {
                Socket().use { sock ->
                    sock.soTimeout = 350
                    sock.connect(VpnConfig.loopbackSocketAddress(port), 350)
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

        /**
         * Runs an isolated, short-lived sing-box instance on an ephemeral localhost port,
         * performs a real HTTPS GET to https://www.cloudflare.com/cdn-cgi/trace,
         * and immediately tears down the test process and temp config.
         */
        suspend fun runEphemeralTraceTest(
            context: Context,
            config: VpnConfig,
            rawUriOrJson: String,
            timeoutMs: Long = 12_000L
        ): Result<ValidationResult> {
            val binary = findSingBoxBinary(context)
                ?: return Result.failure(IllegalStateException("libsing-box.so not found in nativeLibraryDir"))

            val ephemeralPort = runCatching {
                ServerSocket(0, 1, VpnConfig.LOOPBACK_INET4).use { it.localPort }
            }.getOrElse {
                return Result.failure(IllegalStateException("Failed to allocate ephemeral port: ${it.message}"))
            }

            val runtimeJsonResult = SingBoxConfigParser.buildRuntimeConfig(
                rawUriOrJson = rawUriOrJson,
                listenPort = ephemeralPort
            )
            if (runtimeJsonResult.isFailure) {
                return Result.failure(
                    runtimeJsonResult.exceptionOrNull()
                        ?: IllegalStateException("Invalid sing-box profile")
                )
            }

            val testDir = File(context.cacheDir, "singbox-test").apply { mkdirs() }
            val testConfig = File(testDir, "test-$ephemeralPort.json")
            testConfig.writeText(runtimeJsonResult.getOrThrow(), Charsets.UTF_8)

            var proc: Process? = null
            val envMap = resolveGoEnvironment(config)
            return try {
                val pb = ProcessBuilder(binary.absolutePath, "run", "-c", testConfig.absolutePath)
                    .directory(testDir)
                    .redirectErrorStream(true)
                pb.environment().apply {
                    envMap.forEach { (k, v) -> if (v != null) put(k, v) else remove(k) }
                }
                proc = pb.start()

                val deadline = System.currentTimeMillis() + 8_000L
                var ready = false
                while (System.currentTimeMillis() < deadline) {
                    if (!proc.isAlive) {
                        val output = runCatching {
                            stripAnsiCodes(proc.inputStream.bufferedReader().readText()).trim().take(300)
                        }.getOrDefault("")
                        return Result.failure(
                            IllegalStateException(
                                if (output.isNotBlank()) "sing-box exited: $output" else "sing-box exited during test startup"
                            )
                        )
                    }
                    if (isSocks5Ready(ephemeralPort)) {
                        ready = true
                        break
                    }
                    delay(100L)
                }

                if (!ready) {
                    return Result.failure(IllegalStateException("Timed out waiting for test sing-box listener"))
                }

                TunnelValidator.verifyThroughSocks(
                    socksPort = ephemeralPort,
                    timeoutMs = timeoutMs.toInt(),
                    maxAttempts = 1,
                    requireWarpOverride = false
                )
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                runCatching {
                    proc?.destroy()
                    if (proc != null && !proc.waitFor(800, TimeUnit.MILLISECONDS)) {
                        proc.destroyForcibly()
                    }
                }
                runCatching { testConfig.delete() }
            }
        }
    }
}
