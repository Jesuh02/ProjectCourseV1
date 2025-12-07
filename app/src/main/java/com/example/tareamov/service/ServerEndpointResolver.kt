package com.example.tareamov.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves MCP and Ollama endpoints dynamically based on the current network.
 * Eliminates hard coded IP lists and keeps the most recent reachable host per port.
 */
object ServerEndpointResolver {
    private const val TAG = "ServerEndpointResolver"
    private const val MCP_PORT = 3000
    private const val OLLAMA_PORT = 11435
    private const val DEFAULT_TIMEOUT_MS = 500 // Reduced from 1500ms to speed up scanning
    private const val PREFS_NAME = "server_endpoint_resolver"
    private const val PREF_KEY_PREFIX = "last_host_"
    private const val MAX_SCAN_HOSTS = 32 // Reduced from 128 to scan fewer hosts
    
    // Railway Cloud URLs (Production)
    const val RAILWAY_MCP_URL = "https://mcp-backenddeploy-production.up.railway.app"
    const val RAILWAY_API_URL = "https://mcp-backenddeploy-production.up.railway.app"  // Same service, different port internally

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val discoveryMutex = Mutex()
    private val cachedHostsByPort = ConcurrentHashMap<Int, String>()

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var lastNetworkId: String? = null

    private val connectivityManager: ConnectivityManager?
        get() = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val wifiManager: WifiManager?
        get() = appContext?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    /** Ensures the resolver is initialised. Idempotent. */
    fun initialize(context: Context) {
        if (appContext != null) {
            return
        }

        appContext = context.applicationContext
        loadPersistedHosts()
        registerNetworkCallback()

        scope.launch {
            refreshForActiveNetwork()
        }
    }

    suspend fun getMcpBaseUrl(forceDiscovery: Boolean = false): String? {
        return getBaseUrlForPort(MCP_PORT, "/health", forceDiscovery)
    }

    suspend fun getOllamaBaseUrl(forceDiscovery: Boolean = false): String? {
        return getBaseUrlForPort(OLLAMA_PORT, "/api/tags", forceDiscovery)
    }

    fun peekMcpBaseUrl(): String? {
        return cachedHostsByPort[MCP_PORT]?.let { "http://$it:$MCP_PORT" }
    }

    fun peekOllamaBaseUrl(): String? {
        return cachedHostsByPort[OLLAMA_PORT]?.let { "http://$it:$OLLAMA_PORT" }
    }

    suspend fun isServiceReachable(url: String, fallbackHealthPath: String? = null): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val parsed = URL(url)
            val host = parsed.host
            val port = if (parsed.port != -1) parsed.port else parsed.defaultPort
            val healthPath = when {
                fallbackHealthPath != null -> fallbackHealthPath
                parsed.path.isNotBlank() && parsed.path != "/" -> parsed.path
                port == MCP_PORT -> "/health"
                port == OLLAMA_PORT -> "/api/tags"
                else -> null
            }
            isServiceReachableBlocking(host, port, healthPath)
        } catch (e: Exception) {
            Log.d(TAG, "Invalid URL provided for reachability check: $url", e)
            false
        }
    }

    suspend fun collectOllamaDiagnostics(limit: Int = 32): Map<String, Boolean> {
        return collectDiagnosticsForPort(OLLAMA_PORT, "/api/tags", limit)
    }

    suspend fun collectMcpDiagnostics(limit: Int = 32): Map<String, Boolean> {
        return collectDiagnosticsForPort(MCP_PORT, "/health", limit)
    }

    private suspend fun getBaseUrlForPort(port: Int, healthPath: String, forceDiscovery: Boolean): String? {
        ensureInitialised()
        val host = ensureHostForPort(port, healthPath, forceDiscovery)
        return host?.let { "http://$it:$port" }
    }

    private suspend fun ensureHostForPort(port: Int, healthPath: String, forceDiscovery: Boolean): String? = withContext(Dispatchers.IO) {
        if (!forceDiscovery) {
            cachedHostsByPort[port]?.let { cached ->
                if (isServiceReachableBlocking(cached, port, healthPath)) {
                    return@withContext cached
                }
                cachedHostsByPort.remove(port)
            }

            loadPersistedHost(port)?.let { persisted ->
                if (isServiceReachableBlocking(persisted, port, healthPath)) {
                    cachedHostsByPort[port] = persisted
                    return@withContext persisted
                }
            }
        }

        return@withContext discoveryMutex.withLock {
            cachedHostsByPort[port]?.let { cached ->
                if (!forceDiscovery && isServiceReachableBlocking(cached, port, healthPath)) {
                    return@withLock cached
                }
                cachedHostsByPort.remove(port)
            }

            val discovered = discoverHostForPortInternal(port, healthPath)
            if (discovered != null) {
                cacheHost(port, discovered)
            }
            discovered
        }
    }

    private suspend fun collectDiagnosticsForPort(port: Int, healthPath: String, limit: Int): Map<String, Boolean> = withContext(Dispatchers.IO) {
        val results = LinkedHashMap<String, Boolean>()
        val candidates = buildCandidateHosts()
        if (candidates.isEmpty()) {
            return@withContext mapOf("<sin candidatos>" to false)
        }

        for (host in candidates) {
            val reachable = isServiceReachableBlocking(host, port, healthPath)
            results["http://$host:$port"] = reachable
            if (results.size >= limit) {
                break
            }
        }

        if (results.isEmpty()) {
            results["http://127.0.0.1:$port"] = false
        }
        return@withContext results
    }

    private suspend fun refreshForActiveNetwork() = withContext(Dispatchers.IO) {
        ensureInitialised()

        val currentNetworkId = getActiveNetworkId()
        val networkChanged = currentNetworkId != null && currentNetworkId != lastNetworkId
        if (networkChanged) {
            Log.d(TAG, "Network changed from $lastNetworkId to $currentNetworkId")
            cachedHostsByPort.clear()
            lastNetworkId = currentNetworkId
        }

        discoveryMutex.withLock {
            discoverHostForPortInternal(MCP_PORT, "/health")?.let { cacheHost(MCP_PORT, it) }
            discoverHostForPortInternal(OLLAMA_PORT, "/api/tags")?.let { cacheHost(OLLAMA_PORT, it) }
        }
    }

    private fun registerNetworkCallback() {
        val cm = connectivityManager ?: return

        if (networkCallback != null) {
            return
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available: $network")
                scope.launch {
                    refreshForActiveNetwork()
                }
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost: $network")
                // Keep cached hosts; they will be validated when accessed.
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            cm.registerDefaultNetworkCallback(callback)
        } else {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
        }

        networkCallback = callback
    }

    private fun ensureInitialised() {
        checkNotNull(appContext) { "ServerEndpointResolver not initialised. Call initialize(context) first." }
    }

    private fun cacheHost(port: Int, host: String) {
        cachedHostsByPort[port] = host
        val prefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        prefs.edit().putString("$PREF_KEY_PREFIX$port", host).apply()
    }

    private fun loadPersistedHosts() {
        val ctx = appContext ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        listOf(MCP_PORT, OLLAMA_PORT).forEach { port ->
            prefs.getString("$PREF_KEY_PREFIX$port", null)?.let { cachedHostsByPort[port] = it }
        }
    }

    private fun loadPersistedHost(port: Int): String? {
        val ctx = appContext ?: return null
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString("$PREF_KEY_PREFIX$port", null)
    }

    private fun discoverHostForPortInternal(port: Int, healthPath: String): String? {
        val candidates = buildCandidateHosts()
        if (candidates.isEmpty()) {
            Log.w(TAG, "No candidates available to discover host for port $port")
            return null
        }

        for (candidate in candidates) {
            if (isServiceReachableBlocking(candidate, port, healthPath)) {
                Log.d(TAG, "Discovered host $candidate for port $port")
                return candidate
            }
        }

        Log.w(TAG, "Unable to find reachable host for port $port")
        return null
    }

    private fun buildCandidateHosts(): List<String> {
        val candidates = LinkedHashSet<String>()

        // Cached hosts first
        cachedHostsByPort.values.filterNotNull().forEach { candidates.add(it) }

        // Persisted hosts
        loadPersistedHost(MCP_PORT)?.let { candidates.add(it) }
        loadPersistedHost(OLLAMA_PORT)?.let { candidates.add(it) }

        // Explicitly add the user's PC IP
        candidates.add("192.168.1.90")

        if (isEmulator()) {
            candidates.add("10.0.2.2")
        }

        candidates.add("host.docker.internal")
        candidates.add("127.0.0.1")
        candidates.add("localhost")

        getGatewayAddress()?.let { candidates.add(it) }

        collectSubnetCandidates().forEach { candidates.add(it) }

        return candidates.toList()
    }

    private fun collectSubnetCandidates(): List<String> {
        val cm = connectivityManager ?: return emptyList()
        val network = cm.activeNetwork ?: return emptyList()
        val linkProperties = cm.getLinkProperties(network) ?: return emptyList()

        val result = LinkedHashSet<String>()
        for (linkAddress in linkProperties.linkAddresses) {
            addSubnetCandidates(linkAddress, result)
            if (result.size >= MAX_SCAN_HOSTS) {
                break
            }
        }

        return result.toList()
    }

    private fun addSubnetCandidates(linkAddress: LinkAddress, bucket: MutableSet<String>) {
        val address = linkAddress.address
        if (address !is Inet4Address || address.isLoopbackAddress) {
            return
        }

        val hostLong = ipv4ToLong(address)
        val mask = prefixToMask(linkAddress.prefixLength)
        val network = hostLong and mask
    val broadcast = network or (mask.inv() and 0xFFFFFFFFL)
        val startHost = network + 1
        val endHost = broadcast - 1

        var lower = hostLong - 1
        var upper = hostLong + 1

        while ((lower >= startHost || upper <= endHost) && bucket.size < MAX_SCAN_HOSTS) {
            if (lower >= startHost) {
                bucket.add(longToIpv4(lower))
                lower--
            }
            if (upper <= endHost && bucket.size < MAX_SCAN_HOSTS) {
                bucket.add(longToIpv4(upper))
                upper++
            }
        }
    }

    private fun ipv4ToLong(address: Inet4Address): Long {
        val bytes = address.address
        var value = 0L
        for (byte in bytes) {
            value = (value shl 8) or (byte.toInt() and 0xFF).toLong()
        }
        return value
    }

    private fun longToIpv4(value: Long): String {
        return listOf(24, 16, 8, 0)
            .joinToString(".") { shift -> ((value shr shift) and 0xFF).toString() }
    }

    private fun prefixToMask(prefixLength: Int): Long {
        if (prefixLength <= 0) return 0L
        if (prefixLength >= 32) return 0xFFFFFFFFL
        return (0xFFFFFFFFL shl (32 - prefixLength)) and 0xFFFFFFFFL
    }

    private fun getGatewayAddress(): String? {
        return try {
            val dhcpInfo = wifiManager?.dhcpInfo ?: return null
            if (dhcpInfo.gateway == 0) {
                null
            } else {
                longToIpv4(dhcpInfo.gateway.toLong() and 0xFFFFFFFFL)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Unable to resolve gateway address", e)
            null
        }
    }

    private fun isServiceReachableBlocking(host: String, port: Int, healthPath: String?): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), DEFAULT_TIMEOUT_MS)
            }

            if (healthPath != null) {
                val url = URL("http://$host:$port$healthPath")
                val connection = url.openConnection() as HttpURLConnection
                return try {
                    connection.connectTimeout = DEFAULT_TIMEOUT_MS
                    connection.readTimeout = DEFAULT_TIMEOUT_MS
                    connection.requestMethod = "GET"
                    val code = connection.responseCode
                    code in 200..399
                } finally {
                    connection.disconnect()
                }
            }

            true
        } catch (e: Exception) {
            Log.d(TAG, "Host $host:$port not reachable (${e.message})")
            false
        }
    }

    private fun isEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT
        val model = Build.MODEL
        val brand = Build.BRAND
        val product = Build.PRODUCT
        val hardware = Build.HARDWARE

        return fingerprint.startsWith("generic") ||
            fingerprint.startsWith("unknown") ||
            model.contains("google_sdk") ||
            model.contains("Emulator") ||
            model.contains("Android SDK built for x86") ||
            brand.startsWith("generic") && product.startsWith("generic") ||
            hardware.contains("goldfish") ||
            hardware.contains("ranchu")
    }

    private fun getActiveNetworkId(): String? {
        val cm = connectivityManager ?: return null
        val network = cm.activeNetwork ?: return null
        val linkProperties: LinkProperties = cm.getLinkProperties(network) ?: return null
        return linkProperties.interfaceName
    }
}