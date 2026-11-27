package io.aethervpn.android.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import java.io.BufferedReader
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket

data class SpeedProbeResult(
    val bytesDownloaded: Int,
    val elapsedMs: Long,
    val kbPerSec: Long
)

data class SpeedProbeProgress(
    val bytesRead: Long,
    val totalBytes: Long,
    val percent: Int,
    val kbPerSec: Long
)

class SpeedProbeStalledException(
    message: String,
    val bytesDownloaded: Int,
    val totalBytes: Long,
    val percentReached: Int,
    val partialKbPerSec: Long,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

data class SpeedCompareResult(
    val directSocks5: Result<SpeedProbeResult>,
    val throughTun: Result<SpeedProbeResult>
)

data class EphemeralTestWithSpeedResult(
    val validation: ValidationResult,
    val speedProbe: Result<SpeedProbeResult>,
    val isolationLogs: List<String> = emptyList()
)

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
            upstreamSocksPort = upstreamSocksPort,
            config = config
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
        val runtimeJson = runtimeJsonResult.getOrThrow()
        configFile.writeText(runtimeJson, Charsets.UTF_8)
        activeConfigFile = configFile

        val envMap = resolveGoEnvironment(config)
        val versionInfo = queryBinaryVersion(binary)
        if (!versionInfo.isNullOrBlank()) {
            DiagnosticsLog.record("SingBox", "Binary version: $versionInfo")
        }
        DiagnosticsLog.record(
            "SingBox",
            "Starting sing-box on 127.0.0.1:$listenPort [Go: mem=${envMap["GOMEMLIMIT"] ?: "unset"}, procs=${envMap["GOMAXPROCS"] ?: "auto"}, gc=${envMap["GOGC"] ?: "100"}, log=${config.sbLogLevel.ifBlank { "warn" }}, dns=${config.sbRemoteDns}, tfo=${config.sbTcpFastOpen}]"
        )
        DiagnosticsLog.record("SingBox", SingBoxConfigParser.summarizeRuntimeConfig(runtimeJson))

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
                        val cleaned = cleanSingBoxLogLine(line)
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
        internal const val EPHEMERAL_TEST_TIMEOUT_MS = 6_000L

        private val ANSI_ESCAPE_REGEX = Regex("\\u001B\\[[0-9;]*m")
        private val SINGBOX_PREFIX_REGEX = Regex(
            "^\\s*(TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL)\\[\\d+\\]\\s*(?:\\[\\d+\\s+[^\\]]+\\]\\s*)?(.*)$",
            RegexOption.IGNORE_CASE
        )
        private val SINGBOX_CONN_PREFIX_REGEX = Regex("^\\[\\d+\\s+[^\\]]+\\]\\s*")

        internal fun stripAnsiCodes(line: String): String {
            return ANSI_ESCAPE_REGEX.replace(line, "")
        }

        internal fun cleanSingBoxLogLine(rawLine: String): String {
            val noAnsi = stripAnsiCodes(rawLine).trim()
            if (noAnsi.isEmpty()) return ""
            val match = SINGBOX_PREFIX_REGEX.matchEntire(noAnsi)
            if (match != null) {
                val rawLevel = match.groupValues[1].uppercase(Locale.US)
                val label = when (rawLevel) {
                    "ERROR" -> "[Error]"
                    "FATAL" -> "[Fatal]"
                    "WARN", "WARNING" -> "[Warn]"
                    "INFO" -> "[Info]"
                    "DEBUG" -> "[Debug]"
                    "TRACE" -> "[Trace]"
                    else -> "[$rawLevel]"
                }
                val rest = match.groupValues[2].trim()
                return if (rest.isNotEmpty()) "$label $rest" else label
            }
            return SINGBOX_CONN_PREFIX_REGEX.replace(noAnsi, "").trim()
        }

        internal fun resolveGoEnvironment(config: VpnConfig): Map<String, String?> {
            val env = linkedMapOf<String, String?>()
            // Unset GOMEMLIMIT so Go's GC never pauses TCP streams during downloads.
            env["GOMEMLIMIT"] = null
            env["GOGC"] = config.sbGoGc.ifBlank { "100" }
            env["GOMAXPROCS"] = if (config.sbGoMaxProcs.equals("Auto", ignoreCase = true) || config.sbGoMaxProcs.isBlank()) {
                null
            } else {
                config.sbGoMaxProcs
            }
            return env
        }

        internal fun queryBinaryVersion(binary: File): String? {
            return runCatching {
                val proc = ProcessBuilder(binary.absolutePath, "version")
                    .redirectErrorStream(true)
                    .start()
                val finished = proc.waitFor(2, TimeUnit.SECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    return@runCatching null
                }
                val output = proc.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                output.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .take(2)
                    .joinToString(" | ")
                    .takeIf { it.isNotBlank() }
            }.getOrNull()
        }

        internal fun resolveEphemeralTestGoEnvironment(): Map<String, String> = linkedMapOf(
            "GOMEMLIMIT" to "24MiB",
            "GOMAXPROCS" to "1",
            "GOGC" to "25"
        )

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
            timeoutMs: Long = EPHEMERAL_TEST_TIMEOUT_MS
        ): Result<ValidationResult> {
            return withEphemeralSingBox(
                context = context,
                config = config,
                rawUriOrJson = rawUriOrJson,
                timeoutMs = timeoutMs
            ) { ephemeralPort, totalDeadline ->
                val remainingTimeoutMs = (totalDeadline - System.currentTimeMillis()).coerceIn(1_500L, timeoutMs).toInt()
                TunnelValidator.verifyThroughSocks(
                    socksPort = ephemeralPort,
                    timeoutMs = remainingTimeoutMs,
                    maxAttempts = 1,
                    requireWarpOverride = false
                )
            }
        }

        /**
         * Runs an isolated ephemeral sing-box trace test followed by a 256KB direct SOCKS5
         * throughput probe while VpnService is OFF (A/B isolation vs. VPN ON).
         * If the normal 256KB probe stalls/fails, runs a 3-way isolation diagnostic:
         * 1. Bare-bones proxy config (no DNS, no sniff, no route rules, debug log)
         * 2. Last 5 sing-box debug lines if bare-bones also stalls
         * 3. Local sing-box direct-outbound probe (no remote proxy)
         */
        suspend fun runEphemeralTraceAndSpeedTest(
            context: Context,
            config: VpnConfig,
            rawUriOrJson: String,
            traceTimeoutMs: Long = EPHEMERAL_TEST_TIMEOUT_MS,
            speedProbeTimeoutMs: Int = 25_000,
            onSpeedProgress: ((SpeedProbeProgress) -> Unit)? = null
        ): Result<EphemeralTestWithSpeedResult> {
            val baseResult = withEphemeralSingBox(
                context = context,
                config = config,
                rawUriOrJson = rawUriOrJson,
                timeoutMs = traceTimeoutMs
            ) { ephemeralPort, totalDeadline ->
                val remainingTimeoutMs = (totalDeadline - System.currentTimeMillis()).coerceIn(1_500L, traceTimeoutMs).toInt()
                val validationResult = TunnelValidator.verifyThroughSocks(
                    socksPort = ephemeralPort,
                    timeoutMs = remainingTimeoutMs,
                    maxAttempts = 1,
                    requireWarpOverride = false
                )
                if (validationResult.isFailure) {
                    return@withEphemeralSingBox Result.failure(
                        validationResult.exceptionOrNull() ?: IllegalStateException("Trace test failed")
                    )
                }
                val speedResult = runDirectSocks5SpeedProbeUrl(
                    socksPort = ephemeralPort,
                    speedTestUrl = config.effectiveSbSpeedTestUrl(),
                    timeoutMs = speedProbeTimeoutMs,
                    onProgress = onSpeedProgress
                )
                Result.success(
                    EphemeralTestWithSpeedResult(
                        validation = validationResult.getOrThrow(),
                        speedProbe = speedResult
                    )
                )
            }
            return baseResult
        }

        private suspend fun runStallIsolationProbes(
            context: Context,
            rawUriOrJson: String,
            timeoutMs: Int
        ): List<String> {
            val logs = mutableListOf<String>()

            // 1. Bare-bones JSON check (no DNS, no sniff, no route rules) with debug logging
            val bareConfigJson = SingBoxConfigParser.buildBareBonesConfig(
                rawUriOrJson = rawUriOrJson,
                logLevel = "debug"
            )
            if (bareConfigJson.isFailure) {
                logs += "Bare-bones proxy probe: config error (${bareConfigJson.exceptionOrNull()?.message})"
            } else {
                var capturedDebugTail: List<String> = emptyList()
                val bareResult = withEphemeralSingBoxJson(
                    context = context,
                    configJsonProvider = { port ->
                        SingBoxConfigParser.buildBareBonesConfig(
                            rawUriOrJson = rawUriOrJson,
                            listenPort = port,
                            logLevel = "debug"
                        ).getOrThrow()
                    },
                    timeoutMs = EPHEMERAL_TEST_TIMEOUT_MS,
                    maxLogLines = 30
                ) { ephemeralPort, _, recentLogs ->
                    val probeRes = runDirectSocks5SpeedProbe(
                        socksPort = ephemeralPort,
                        timeoutMs = timeoutMs
                    )
                    if (probeRes.isFailure) {
                        capturedDebugTail = recentLogs().takeLast(5)
                    }
                    probeRes
                }
                logs += "Bare-bones proxy probe: ${formatIsolationOutcome(bareResult)}"
                if (bareResult.isFailure) {
                    val tailSummary = if (capturedDebugTail.isNotEmpty()) {
                        capturedDebugTail.joinToString(" | ")
                    } else {
                        "<no debug output>"
                    }
                    logs += "Bare-bones debug tail (last ${capturedDebugTail.size}): $tailSummary"
                }
            }

            // 2. Local sing-box direct-outbound check (no remote proxy)
            val directResult = withEphemeralSingBoxJson(
                context = context,
                configJsonProvider = { port ->
                    SingBoxConfigParser.buildDirectOutboundConfig(
                        listenPort = port,
                        logLevel = "warn"
                    )
                },
                timeoutMs = EPHEMERAL_TEST_TIMEOUT_MS,
                maxLogLines = 15
            ) { ephemeralPort, _, _ ->
                val primary = runDirectSocks5SpeedProbe(
                    socksPort = ephemeralPort,
                    host = SPEED_PROBE_HOST,
                    port = 80,
                    path = "/__down?bytes=$SPEED_PROBE_BYTES",
                    useTls = false,
                    timeoutMs = timeoutMs
                )
                if (primary.isSuccess || extractStalledKb(primary.exceptionOrNull()) != null) {
                    primary
                } else {
                    runDirectSocks5SpeedProbe(
                        socksPort = ephemeralPort,
                        host = "www.google.com",
                        port = 443,
                        path = "/images/branding/googlelogo/2x/googlelogo_color_272x92dp.png",
                        useTls = true,
                        exactBytesRequired = false,
                        timeoutMs = timeoutMs
                    )
                }
            }
            logs += "Direct-outbound probe: ${formatIsolationOutcome(directResult)}"

            return logs
        }

        private suspend fun <T> withEphemeralSingBox(
            context: Context,
            config: VpnConfig,
            rawUriOrJson: String,
            timeoutMs: Long,
            block: suspend (ephemeralPort: Int, totalDeadline: Long) -> Result<T>
        ): Result<T> = withEphemeralSingBoxJson(
            context = context,
            configJsonProvider = { ephemeralPort ->
                SingBoxConfigParser.buildRuntimeConfig(
                    rawUriOrJson = rawUriOrJson,
                    listenPort = ephemeralPort,
                    config = config
                ).getOrThrow()
            },
            timeoutMs = timeoutMs
        ) { ephemeralPort, totalDeadline, _ ->
            block(ephemeralPort, totalDeadline)
        }

        private suspend fun <T> withEphemeralSingBoxJson(
            context: Context,
            configJsonProvider: (ephemeralPort: Int) -> String,
            timeoutMs: Long,
            maxLogLines: Int = 20,
            block: suspend (ephemeralPort: Int, totalDeadline: Long, recentLogs: () -> List<String>) -> Result<T>
        ): Result<T> {
            val binary = findSingBoxBinary(context)
                ?: return Result.failure(IllegalStateException("libsing-box.so not found in nativeLibraryDir"))

            val ephemeralPort = runCatching {
                ServerSocket(0, 1, VpnConfig.LOOPBACK_INET4).use { it.localPort }
            }.getOrElse {
                return Result.failure(IllegalStateException("Failed to allocate ephemeral port: ${it.message}"))
            }

            val runtimeJson = runCatching { configJsonProvider(ephemeralPort) }.getOrElse {
                return Result.failure(it)
            }

            val testDir = File(context.cacheDir, "singbox-test").apply { mkdirs() }
            val testConfig = File(testDir, "test-$ephemeralPort.json")
            testConfig.writeText(runtimeJson, Charsets.UTF_8)

            var proc: Process? = null
            var drainJob: Job? = null
            val outputTail = StringBuilder()
            val recentLines = ArrayDeque<String>()
            val envMap = resolveEphemeralTestGoEnvironment()
            val cancelHandle = coroutineContext[Job]?.invokeOnCompletion {
                runCatching { proc?.destroyForcibly() }
            }
            return try {
                val pb = ProcessBuilder(binary.absolutePath, "run", "-c", testConfig.absolutePath)
                    .directory(testDir)
                    .redirectErrorStream(true)
                pb.environment().putAll(envMap)
                proc = pb.start()

                // Continuously drain combined stdout/stderr so a full OS pipe buffer can never deadlock libsing-box.so
                drainJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    runCatching {
                        BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { reader ->
                            var line: String? = reader.readLine()
                            while (isActive && line != null) {
                                val cleaned = cleanSingBoxLogLine(line)
                                if (cleaned.isNotEmpty()) {
                                    synchronized(outputTail) {
                                        if (outputTail.isNotEmpty()) outputTail.append('\n')
                                        outputTail.append(cleaned)
                                        if (outputTail.length > 600) {
                                            outputTail.delete(0, outputTail.length - 300)
                                        }
                                        recentLines.addLast(cleaned)
                                        while (recentLines.size > maxLogLines) {
                                            recentLines.removeFirst()
                                        }
                                    }
                                }
                                line = reader.readLine()
                            }
                        }
                    }
                }

                val totalDeadline = System.currentTimeMillis() + timeoutMs
                val startupBudgetMs = minOf(timeoutMs - 1_500L, 3_500L).coerceAtLeast(1_500L)
                val startupDeadline = System.currentTimeMillis() + startupBudgetMs
                var ready = false
                while (System.currentTimeMillis() < startupDeadline) {
                    if (!proc.isAlive) {
                        val output = synchronized(outputTail) { outputTail.toString().trim().takeLast(300) }
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
                    delay(75L)
                }

                if (!ready) {
                    return Result.failure(IllegalStateException("Timed out waiting for test sing-box listener"))
                }

                val recentLogsProvider: () -> List<String> = {
                    synchronized(outputTail) { recentLines.toList() }
                }
                block(ephemeralPort, totalDeadline, recentLogsProvider)
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                cancelHandle?.dispose()
                drainJob?.cancel()
                runCatching {
                    proc?.destroy()
                    if (proc != null && !proc.waitFor(400, TimeUnit.MILLISECONDS)) {
                        proc.destroyForcibly()
                    }
                }
                runCatching { testConfig.delete() }
            }
        }

        const val SPEED_PROBE_BYTES = 262_144
        const val SPEED_COMPARE_BYTES = 524_288
        const val SPEED_TEST_1MB_BYTES = 1_048_576
        private const val SPEED_PROBE_HOST = "speed.cloudflare.com"
        private const val SPEED_PROBE_PORT = 443
        private val STALLED_KB_REGEX = Regex("stalled at (\\d+)KB")
        private val INCOMPLETE_BYTES_REGEX = Regex("Incomplete download: (\\d+)B")

        fun formatSpeedProbeLog(
            result: Result<SpeedProbeResult>,
            requestedBytes: Int = SPEED_PROBE_BYTES
        ): String {
            return result.fold(
                onSuccess = { probe ->
                    val kb = (probe.bytesDownloaded / 1024).coerceAtLeast(1)
                    "Direct SOCKS5 throughput: ${kb}KB in ${probe.elapsedMs}ms (${probe.kbPerSec} KB/s)"
                },
                onFailure = { err ->
                    "Direct SOCKS5 throughput failed (${requestedBytes / 1024}KB): ${err.message ?: err.javaClass.simpleName}"
                }
            )
        }

        private fun extractStalledKb(err: Throwable?): Int? {
            val msg = err?.message.orEmpty()
            val stalledMatch = STALLED_KB_REGEX.find(msg)
            if (stalledMatch != null) {
                return stalledMatch.groupValues[1].toIntOrNull()
            }
            val incompleteMatch = INCOMPLETE_BYTES_REGEX.find(msg)
            if (incompleteMatch != null) {
                val bytes = incompleteMatch.groupValues[1].toIntOrNull() ?: 0
                return bytes / 1024
            }
            return null
        }

        private fun formatIsolationOutcome(result: Result<SpeedProbeResult>): String {
            return result.fold(
                onSuccess = { probe -> "${probe.kbPerSec} KB/s" },
                onFailure = { err ->
                    val stalledKb = extractStalledKb(err)
                    if (stalledKb != null) {
                        "stalled at $stalledKb KB"
                    } else {
                        "failed (${err.message ?: err.javaClass.simpleName})"
                    }
                }
            )
        }

        fun formatCompactSpeedBadge(result: Result<SpeedProbeResult>): String {
            return result.fold(
                onSuccess = { probe -> "${probe.kbPerSec} KB/s" },
                onFailure = { err ->
                    val stalledKb = extractStalledKb(err)
                    if (stalledKb != null) "stalled at $stalledKb KB" else "speed err"
                }
            )
        }

        fun formatSpeedCompareLog(
            result: SpeedCompareResult,
            requestedBytes: Int = SPEED_COMPARE_BYTES
        ): String {
            val kbLabel = (requestedBytes / 1024).coerceAtLeast(1)
            val socksText = result.directSocks5.fold(
                onSuccess = { "${it.kbPerSec} KB/s" },
                onFailure = { err -> "ERR(${err.message ?: err.javaClass.simpleName})" }
            )
            val tunText = result.throughTun.fold(
                onSuccess = { "${it.kbPerSec} KB/s" },
                onFailure = { err -> "ERR(${err.message ?: err.javaClass.simpleName})" }
            )
            return "Speed Compare (${kbLabel}KB): Direct SOCKS5=$socksText | Through TUN=$tunText"
        }

        /**
         * Manual A/B throughput check when connected in Sing-box Only mode:
         * 1. Downloads 512KB directly via 127.0.0.1:[socksPort] (bypassing HEV TUN).
         * 2. Downloads 512KB via normal tun0 (through HEV TUN -> sing-box).
         */
        fun runSocksVsTunSpeedCompare(
            socksPort: Int = VpnConfig.SING_BOX_SOCKS_PORT,
            bytesToDownload: Int = SPEED_COMPARE_BYTES,
            timeoutMs: Int = 25_000
        ): SpeedCompareResult {
            val directResult = runDirectSocks5SpeedProbe(
                socksPort = socksPort,
                bytesToDownload = bytesToDownload,
                timeoutMs = timeoutMs
            )
            val tunResult = runTunSpeedProbe(
                bytesToDownload = bytesToDownload,
                timeoutMs = timeoutMs
            )
            return SpeedCompareResult(
                directSocks5 = directResult,
                throughTun = tunResult
            )
        }

        /**
         * Downloads [bytesToDownload] bytes from `https://speed.cloudflare.com/__down?bytes=...`
         * over the active system/TUN interface (through HEV TUN -> sing-box).
         */
        fun runTunSpeedProbe(
            bytesToDownload: Int = SPEED_COMPARE_BYTES,
            timeoutMs: Int = 25_000,
            host: String = SPEED_PROBE_HOST,
            path: String = "/__down?bytes=$bytesToDownload"
        ): Result<SpeedProbeResult> {
            var conn: HttpsURLConnection? = null
            var bodyBytesRead = 0
            return try {
                val url = URL("https://$host$path")
                conn = (url.openConnection() as HttpsURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    useCaches = false
                    setRequestProperty("User-Agent", "AetherAndroid/2.4.0")
                    setRequestProperty("Connection", "close")
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    return Result.failure(IllegalStateException("HTTP $code"))
                }
                val startNs = System.nanoTime()
                val buf = ByteArray(16 * 1024)
                conn.inputStream.use { inStream ->
                    while (bodyBytesRead < bytesToDownload) {
                        val n = inStream.read(buf)
                        if (n < 0) break
                        bodyBytesRead += n
                    }
                }
                val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                if (bodyBytesRead < bytesToDownload) {
                    return Result.failure(
                        EOFException("Incomplete download: ${bodyBytesRead}B of ${bytesToDownload}B after ${elapsedMs}ms")
                    )
                }
                val kbPerSec = (bodyBytesRead.toLong() * 1000L) / (elapsedMs * 1024L)
                Result.success(
                    SpeedProbeResult(
                        bytesDownloaded = bodyBytesRead,
                        elapsedMs = elapsedMs,
                        kbPerSec = kbPerSec
                    )
                )
            } catch (e: Exception) {
                val stalledSuffix = if (bodyBytesRead > 0) {
                    " (stalled at ${bodyBytesRead / 1024}KB / ${bodyBytesRead}B)"
                } else {
                    ""
                }
                Result.failure(IllegalStateException("${e.message ?: e.javaClass.simpleName}$stalledSuffix", e))
            } finally {
                runCatching { conn?.disconnect() }
            }
        }

        internal data class ParsedSpeedProbeTarget(
            val host: String,
            val port: Int,
            val path: String,
            val useTls: Boolean,
            val expectedBytes: Int,
            val exactBytesRequired: Boolean
        )

        internal fun parseSpeedProbeUrl(rawUrl: String): ParsedSpeedProbeTarget {
            val normalized = rawUrl.trim().ifBlank { VpnConfig.DEFAULT_SB_SPEED_TEST_URL }
            val parsed = runCatching { URL(normalized) }.getOrNull()
            if (parsed == null || parsed.host.isNullOrBlank()) {
                return ParsedSpeedProbeTarget(
                    host = SPEED_PROBE_HOST,
                    port = SPEED_PROBE_PORT,
                    path = "/__down?bytes=${SPEED_TEST_1MB_BYTES}",
                    useTls = true,
                    expectedBytes = SPEED_TEST_1MB_BYTES,
                    exactBytesRequired = true
                )
            }
            val useTls = !parsed.protocol.equals("http", ignoreCase = true)
            val port = if (parsed.port > 0) parsed.port else if (useTls) 443 else 80
            val rawPath = parsed.path.orEmpty().ifBlank { "/" }
            val query = parsed.query?.takeIf { it.isNotBlank() }
            val fullPath = if (query != null) "$rawPath?$query" else rawPath
            val bytesFromQuery = query
                ?.split('&')
                ?.firstNotNullOfOrNull { param ->
                    val kv = param.split('=', limit = 2)
                    if (kv.size == 2 && kv[0].equals("bytes", ignoreCase = true)) {
                        kv[1].toIntOrNull()?.takeIf { it > 0 }
                    } else {
                        null
                    }
                }
            return ParsedSpeedProbeTarget(
                host = parsed.host,
                port = port,
                path = fullPath,
                useTls = useTls,
                expectedBytes = bytesFromQuery ?: SPEED_TEST_1MB_BYTES,
                exactBytesRequired = bytesFromQuery != null
            )
        }

        fun runDirectSocks5SpeedProbeUrl(
            socksPort: Int,
            speedTestUrl: String = VpnConfig.DEFAULT_SB_SPEED_TEST_URL,
            timeoutMs: Int = 25_000,
            onProgress: ((SpeedProbeProgress) -> Unit)? = null
        ): Result<SpeedProbeResult> {
            val target = parseSpeedProbeUrl(speedTestUrl)
            return runDirectSocks5SpeedProbe(
                socksPort = socksPort,
                bytesToDownload = target.expectedBytes,
                timeoutMs = timeoutMs,
                host = target.host,
                port = target.port,
                path = target.path,
                useTls = target.useTls,
                exactBytesRequired = target.exactBytesRequired,
                onProgress = onProgress
            )
        }

        /**
         * Downloads [bytesToDownload] bytes from [host]:[port][path] directly through
         * the local SOCKS5 listener on 127.0.0.1:[socksPort] to isolate sing-box vs HEV TUN stalls.
         */
        fun runDirectSocks5SpeedProbe(
            socksPort: Int,
            bytesToDownload: Int = SPEED_PROBE_BYTES,
            timeoutMs: Int = 20_000,
            host: String = SPEED_PROBE_HOST,
            port: Int = SPEED_PROBE_PORT,
            path: String = "/__down?bytes=$bytesToDownload",
            useTls: Boolean = true,
            exactBytesRequired: Boolean = true,
            onProgress: ((SpeedProbeProgress) -> Unit)? = null
        ): Result<SpeedProbeResult> {
            var rawSocket: Socket? = null
            var sslSocket: Socket? = null
            var bodyBytesRead = 0
            var expectedTotalBytes = bytesToDownload.toLong().coerceAtLeast(1L)
            var startNs = System.nanoTime()
            return try {
                rawSocket = Socket()
                rawSocket.tcpNoDelay = true
                rawSocket.soTimeout = timeoutMs
                rawSocket.connect(VpnConfig.loopbackSocketAddress(socksPort), timeoutMs)

                val outStream = rawSocket.getOutputStream()
                val inStream = rawSocket.getInputStream()

                // 1. SOCKS5 Greeting (VER=5, NMETHODS=1, NO_AUTH=0x00)
                outStream.write(byteArrayOf(0x05, 0x01, 0x00))
                outStream.flush()

                val greeting = ByteArray(2)
                readExactBytes(inStream, greeting)
                if (greeting[0] != 0x05.toByte() || greeting[1] != 0x00.toByte()) {
                    return Result.failure(IllegalStateException("SOCKS5 greeting rejected (${greeting[0]}, ${greeting[1]})"))
                }

                // 2. SOCKS5 CONNECT to host:port (ATYP=0x03 domain)
                val hostBytes = host.toByteArray(Charsets.US_ASCII)
                val connectReq = ByteArray(4 + 1 + hostBytes.size + 2)
                connectReq[0] = 0x05
                connectReq[1] = 0x01
                connectReq[2] = 0x00
                connectReq[3] = 0x03
                connectReq[4] = hostBytes.size.toByte()
                System.arraycopy(hostBytes, 0, connectReq, 5, hostBytes.size)
                connectReq[5 + hostBytes.size] = (port shr 8).toByte()
                connectReq[6 + hostBytes.size] = (port and 0xFF).toByte()
                outStream.write(connectReq)
                outStream.flush()

                val replyHeader = ByteArray(4)
                readExactBytes(inStream, replyHeader)
                if (replyHeader[0] != 0x05.toByte() || replyHeader[1] != 0x00.toByte()) {
                    return Result.failure(
                        IllegalStateException("SOCKS5 CONNECT failed (rep=0x%02X)".format(replyHeader[1].toInt() and 0xFF))
                    )
                }
                when (replyHeader[3].toInt() and 0xFF) {
                    0x01 -> skipExactBytes(inStream, 6)
                    0x03 -> {
                        val len = inStream.read()
                        if (len < 0) return Result.failure(EOFException("Premature EOF reading SOCKS5 domain"))
                        skipExactBytes(inStream, len + 2)
                    }
                    0x04 -> skipExactBytes(inStream, 18)
                    else -> return Result.failure(IllegalStateException("Unknown SOCKS5 ATYP: ${replyHeader[3]}"))
                }

                // 3. Optional TLS upgrade + HTTP/1.1 GET
                val httpOut: java.io.OutputStream
                val httpIn: InputStream
                if (useTls) {
                    val sslFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
                    sslSocket = (sslFactory.createSocket(rawSocket, host, port, true) as SSLSocket).apply {
                        tcpNoDelay = true
                        soTimeout = timeoutMs
                        startHandshake()
                    }
                    httpOut = sslSocket.getOutputStream()
                    httpIn = sslSocket.getInputStream()
                } else {
                    httpOut = outStream
                    httpIn = inStream
                }
                val req = "GET $path HTTP/1.1\r\n" +
                    "Host: $host\r\n" +
                    "User-Agent: AetherAndroid/2.4.0\r\n" +
                    "Connection: close\r\n\r\n"
                httpOut.write(req.toByteArray(Charsets.US_ASCII))
                httpOut.flush()

                // Parse HTTP status and headers byte-by-byte so buffered reads don't eat body bytes
                val statusLine = readHttpLine(httpIn)
                    ?: return Result.failure(EOFException("Empty HTTP response from speed probe"))
                val statusCode = statusLine.split(" ", limit = 3).getOrNull(1)?.toIntOrNull() ?: 0
                if (statusCode !in 200..299) {
                    return Result.failure(IllegalStateException("HTTP $statusCode: $statusLine"))
                }
                var headerContentLength: Long? = null
                while (true) {
                    val hdr = readHttpLine(httpIn) ?: break
                    if (hdr.isEmpty()) break
                    if (hdr.startsWith("content-length:", ignoreCase = true)) {
                        headerContentLength = hdr.substringAfter(":").trim().toLongOrNull()?.takeIf { it > 0L }
                    }
                }
                if (headerContentLength != null) {
                    expectedTotalBytes = headerContentLength
                }

                startNs = System.nanoTime()
                var lastEmitNs = 0L
                val buf = ByteArray(16 * 1024)
                val targetBytes = headerContentLength?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: bytesToDownload
                while (!exactBytesRequired || bodyBytesRead < targetBytes) {
                    val n = httpIn.read(buf)
                    if (n < 0) break
                    bodyBytesRead += n
                    val nowNs = System.nanoTime()
                    if (onProgress != null && (nowNs - lastEmitNs >= 60_000_000L || bodyBytesRead >= expectedTotalBytes)) {
                        lastEmitNs = nowNs
                        val elapsedMsSoFar = ((nowNs - startNs) / 1_000_000L).coerceAtLeast(1L)
                        val kbPerSecSoFar = (bodyBytesRead.toLong() * 1000L) / (elapsedMsSoFar * 1024L)
                        val pct = ((bodyBytesRead.toLong() * 100L) / expectedTotalBytes.coerceAtLeast(1L))
                            .toInt()
                            .coerceIn(0, 100)
                        onProgress(
                            SpeedProbeProgress(
                                bytesRead = bodyBytesRead.toLong(),
                                totalBytes = expectedTotalBytes,
                                percent = pct,
                                kbPerSec = kbPerSecSoFar
                            )
                        )
                    }
                }
                val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                if ((exactBytesRequired && bodyBytesRead < targetBytes) || (!exactBytesRequired && bodyBytesRead == 0)) {
                    val partialKbPerSec = (bodyBytesRead.toLong() * 1000L) / (elapsedMs * 1024L)
                    val pct = ((bodyBytesRead.toLong() * 100L) / expectedTotalBytes.coerceAtLeast(1L))
                        .toInt()
                        .coerceIn(0, 99)
                    return Result.failure(
                        SpeedProbeStalledException(
                            message = "Incomplete download: ${bodyBytesRead}B of ${expectedTotalBytes}B after ${elapsedMs}ms",
                            bytesDownloaded = bodyBytesRead,
                            totalBytes = expectedTotalBytes,
                            percentReached = pct,
                            partialKbPerSec = partialKbPerSec
                        )
                    )
                }
                val kbPerSec = (bodyBytesRead.toLong() * 1000L) / (elapsedMs * 1024L)
                onProgress?.invoke(
                    SpeedProbeProgress(
                        bytesRead = bodyBytesRead.toLong(),
                        totalBytes = expectedTotalBytes.coerceAtLeast(bodyBytesRead.toLong()),
                        percent = 100,
                        kbPerSec = kbPerSec
                    )
                )
                Result.success(
                    SpeedProbeResult(
                        bytesDownloaded = bodyBytesRead,
                        elapsedMs = elapsedMs,
                        kbPerSec = kbPerSec
                    )
                )
            } catch (e: Exception) {
                if (bodyBytesRead > 0) {
                    val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                    val partialKbPerSec = (bodyBytesRead.toLong() * 1000L) / (elapsedMs * 1024L)
                    val pct = ((bodyBytesRead.toLong() * 100L) / expectedTotalBytes.coerceAtLeast(1L))
                        .toInt()
                        .coerceIn(0, 99)
                    Result.failure(
                        SpeedProbeStalledException(
                            message = "${e.message ?: e.javaClass.simpleName} (stalled at ${bodyBytesRead / 1024}KB / ${pct}%)",
                            bytesDownloaded = bodyBytesRead,
                            totalBytes = expectedTotalBytes,
                            percentReached = pct,
                            partialKbPerSec = partialKbPerSec,
                            cause = e
                        )
                    )
                } else {
                    Result.failure(IllegalStateException(e.message ?: e.javaClass.simpleName, e))
                }
            } finally {
                runCatching { sslSocket?.close() }
                runCatching { rawSocket?.close() }
            }
        }

        private fun readExactBytes(inStream: InputStream, buffer: ByteArray) {
            var offset = 0
            while (offset < buffer.size) {
                val read = inStream.read(buffer, offset, buffer.size - offset)
                if (read < 0) {
                    throw EOFException("Premature EOF (expected ${buffer.size}B, got ${offset}B)")
                }
                offset += read
            }
        }

        private fun skipExactBytes(inStream: InputStream, count: Int) {
            var remaining = count
            while (remaining > 0) {
                val skipped = inStream.skip(remaining.toLong())
                if (skipped <= 0) {
                    if (inStream.read() < 0) throw EOFException("Premature EOF skipping SOCKS5 bytes")
                    remaining--
                } else {
                    remaining -= skipped.toInt()
                }
            }
        }

        private fun readHttpLine(inStream: InputStream): String? {
            val sb = StringBuilder()
            var prev = -1
            while (true) {
                val b = inStream.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) {
                    if (prev == '\r'.code && sb.isNotEmpty()) {
                        sb.setLength(sb.length - 1)
                    }
                    return sb.toString()
                }
                sb.append(b.toChar())
                prev = b
            }
        }
    }
}
