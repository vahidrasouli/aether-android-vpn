package io.aethervpn.android.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

enum class SoarPhase(val label: String) {
    STOPPED("Disconnected"),
    STARTING("Starting Soar"),
    CONNECTED("Connected"),
    DISCONNECTING("Disconnecting"),
    ERROR("Error")
}

data class SoarRuntimeState(
    val phase: SoarPhase = SoarPhase.STOPPED,
    val mode: SoarConnectionMode = SoarConnectionMode.PROXY_ONLY,
    val profileName: String = "",
    val listenAddress: String = "${SoarEngineManager.DEFAULT_LISTEN_HOST}:${SoarEngineManager.DEFAULT_LISTEN_PORT}",
    val errorMessage: String? = null
) {
    val isActive: Boolean get() = phase == SoarPhase.CONNECTED || phase == SoarPhase.STARTING
    val isConnected: Boolean get() = phase == SoarPhase.CONNECTED
}

object SoarStateStore {
    private val _state = MutableStateFlow(SoarRuntimeState())
    val state: StateFlow<SoarRuntimeState> = _state.asStateFlow()

    fun update(newState: SoarRuntimeState) {
        _state.value = newState
    }

    fun resetStopped(mode: SoarConnectionMode = _state.value.mode) {
        _state.value = SoarRuntimeState(phase = SoarPhase.STOPPED, mode = mode)
    }

    fun reportError(message: String, mode: SoarConnectionMode = _state.value.mode) {
        DiagnosticsLog.record("Soar", "Error: $message")
        _state.value = _state.value.copy(
            phase = SoarPhase.ERROR,
            mode = mode,
            errorMessage = message
        )
    }
}

/**
 * Manages the isolated `libsoar.so` subprocess (`github.com/getlantern/soar` wrapper).
 * Self-contained so Aether, Sing-box, and Psiphon remain completely untouched.
 */
class SoarEngineManager(private val privateDataDir: File) {

    private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var activeProcess: Process? = null

    private var logReaderJob: Job? = null
    private var activeConfigFile: File? = null

    suspend fun start(
        context: Context,
        profile: SoarProfile,
        mode: SoarConnectionMode,
        listenHost: String = DEFAULT_LISTEN_HOST,
        listenPort: Int = DEFAULT_LISTEN_PORT,
        readinessTimeoutMs: Long = 10_000L
    ): Result<Unit> {
        val binary = findSoarBinary(context)
            ?: return Result.failure(
                IllegalStateException("Soar binary (libsoar.so) not found in nativeLibraryDir")
            )

        if (profile.domain.isBlank() || profile.publicKey.isBlank()) {
            return Result.failure(IllegalArgumentException("Soar profile requires domain and public_key"))
        }

        stop()

        val stateDir = File(privateDataDir, "soar").apply { mkdirs() }
        val configFile = File(stateDir, "soar-config.json")
        configFile.writeText(buildRuntimeConfigJson(profile), Charsets.UTF_8)
        activeConfigFile = configFile

        val listenAddr = "$listenHost:$listenPort"
        val args = buildCommandArgs(binary.absolutePath, configFile.absolutePath, listenAddr)
        DiagnosticsLog.record("Chain", buildChainLog(mode, profile, listenHost, listenPort))
        DiagnosticsLog.record("Soar", "Launching libsoar.so on $listenAddr (domain=${profile.domain})")

        val pb = ProcessBuilder(args)
            .directory(stateDir)
            .redirectErrorStream(true)

        val proc = runCatching { pb.start() }.getOrElse { err ->
            cleanupConfigFile()
            return Result.failure(IllegalStateException("Failed to launch libsoar.so: ${err.message}", err))
        }
        activeProcess = proc
        startLogPump(proc)

        val deadline = System.currentTimeMillis() + readinessTimeoutMs
        var backoffMs = 80L
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive) {
                val exitCode = runCatching { proc.exitValue() }.getOrDefault(-1)
                stop()
                return Result.failure(IllegalStateException("libsoar.so exited prematurely (code $exitCode)"))
            }
            if (isSocks5Ready(listenHost, listenPort)) {
                DiagnosticsLog.record("Soar", "SOCKS5 ready on $listenAddr")
                return Result.success(Unit)
            }
            delay(backoffMs)
            backoffMs = (backoffMs + 40L).coerceAtMost(250L)
        }

        stop()
        return Result.failure(IllegalStateException("Timed out waiting for Soar SOCKS5 on $listenAddr"))
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
                if (!proc.waitFor(1500, TimeUnit.MILLISECONDS)) {
                    proc.destroyForcibly()
                }
            }
            DiagnosticsLog.record("Soar", "Stopped libsoar.so")
        }
        cleanupConfigFile()
    }

    private fun cleanupConfigFile() {
        val f = activeConfigFile
        activeConfigFile = null
        runCatching { f?.delete() }
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
                            DiagnosticsLog.record("Soar", trimmed)
                        }
                        line = reader.readLine()
                    }
                }
            }
        }
    }

    companion object {
        const val DEFAULT_LISTEN_HOST = "127.0.0.1"
        const val DEFAULT_LISTEN_PORT = 1829
        const val TUN_IPV4_ADDRESS = "172.19.0.1"
        const val TUN_IPV4_PREFIX = 30
        const val TUN_MAPDNS_ADDRESS = "172.19.0.2"

        fun findSoarBinary(context: Context): File? {
            val nativeDir = context.applicationInfo.nativeLibraryDir ?: return null
            val candidate = File(nativeDir, "libsoar.so")
            return if (candidate.exists() && candidate.canExecute()) candidate else null
        }

        fun buildRuntimeConfigJson(profile: SoarProfile): String {
            val s = profile.soarSettings.normalized()
            return JSONObject().apply {
                put("domain", profile.domain.trim())
                put("public_key", profile.publicKey.trim())
                put("resolvers", JSONArray().apply {
                    profile.resolvers.forEach { put(it) }
                })
                put("transport", s.transport)
                put("discovery", s.discovery)
                put("country", s.country)
                put("discoveryWant", s.discoveryWant)
                put("discoveryConcurrency", s.discoveryConcurrency)
                put("discoveryPaceMs", s.discoveryPaceMs)
                put("safeQName", s.safeQName)
                put("maxQName", s.maxQName)
                put("maxAnswer", s.maxAnswer)
                put("holdMs", s.holdMs)
                put("maxInflight", s.maxInflight)
                put("maxPathWindow", s.maxPathWindow)
                put("maxStreams", s.maxStreams)
                put("streamWindowKiB", s.streamWindowKiB)
                put("sendBufferKiB", s.sendBufferKiB)
                put("sessionIdleSeconds", s.sessionIdleSeconds)
                put("dialTimeoutSeconds", s.dialTimeoutSeconds)
            }.toString(2)
        }

        fun buildCommandArgs(
            binaryPath: String,
            configPath: String,
            listenAddress: String = "$DEFAULT_LISTEN_HOST:$DEFAULT_LISTEN_PORT"
        ): List<String> {
            return listOf(
                binaryPath,
                "--config",
                configPath,
                "--listen",
                listenAddress
            )
        }

        fun buildChainLog(
            mode: SoarConnectionMode,
            profile: SoarProfile,
            listenHost: String = DEFAULT_LISTEN_HOST,
            listenPort: Int = DEFAULT_LISTEN_PORT
        ): String {
            val s = profile.soarSettings.normalized()
            val countryLabel = if (s.discovery) s.country else "static"
            return when (mode) {
                SoarConnectionMode.PROXY_ONLY ->
                    "Local SOCKS5 ($listenHost:$listenPort) -> Soar -> DNS (${s.transport}, $countryLabel)"
                SoarConnectionMode.VPN_FULL_DEVICE ->
                    "TUN ($TUN_IPV4_ADDRESS) -> HEV -> Soar ($listenHost:$listenPort) -> DNS (${s.transport}, $countryLabel)"
            }
        }

        fun buildTelegramSocksUri(
            host: String = DEFAULT_LISTEN_HOST,
            port: Int = DEFAULT_LISTEN_PORT
        ): String {
            return "tg://socks?server=$host&port=$port"
        }

        private fun isSocks5Ready(host: String, port: Int): Boolean {
            return runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 400)
                    socket.soTimeout = 400
                    val out = socket.getOutputStream()
                    val inp = socket.getInputStream()
                    out.write(byteArrayOf(0x05, 0x01, 0x00))
                    out.flush()
                    val resp = ByteArray(2)
                    val read = inp.read(resp)
                    read == 2 && resp[0] == 0x05.toByte() && resp[1] == 0x00.toByte()
                }
            }.getOrDefault(false)
        }
    }
}
