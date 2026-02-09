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
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    // MCP HTTP server runs on 3000 (mcp-http.js). Use 3000 so physical devices discover the correct service.
    private const val MCP_PORT = 3000
    private const val OLLAMA_PORT = 11435
    // Aggressively low timeout for fast mobile probes (practical lower bound ~50-250ms)
    // Increased to 250ms to be more reliable on slower networks while still failing fast
    private const val DEFAULT_TIMEOUT_MS = 250
    private const val PREFS_NAME = "server_endpoint_resolver"
    private const val PREF_KEY_PREFIX = "last_host_"
    private const val PREF_KEY_FORCED_FULL = "mcp_forced_base_url_full"
    private const val MAX_SCAN_HOSTS = 48 // Slightly increased to allow more candidates
    
    // Railway Cloud URLs — resolved from BuildConfig per flavor (QA / Production)
    val RAILWAY_MCP_URL = com.example.tareamov.BuildConfig.BACKEND_URL
    val RAILWAY_API_URL = com.example.tareamov.BuildConfig.BACKEND_URL

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

    suspend fun getMcpBaseUrl(forceDiscovery: Boolean = false): String {
        if (appContext == null) {
            Log.w(TAG, "ServerEndpointResolver not initialised. Falling back to Railway cloud: $RAILWAY_MCP_URL")
            return RAILWAY_MCP_URL
        }

        // 1. Try local discovery first
        val localUrl = getBaseUrlForPort(MCP_PORT, "/health", forceDiscovery)
        if (localUrl != null) {
            Log.i(TAG, "Using local MCP URL: $localUrl")
            return localUrl
        }
        
        // 2. Fallback to Railway (Production)
        // This ensures that if local discovery fails, we always try the cloud
        Log.i(TAG, "Local MCP discovery failed, falling back to Railway cloud: $RAILWAY_MCP_URL")
        return RAILWAY_MCP_URL
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
            
            // Handle HTTPS (Railway) directly
            if (parsed.protocol == "https") {
                val connection = parsed.openConnection() as HttpURLConnection
                return@withContext try {
                    connection.connectTimeout = DEFAULT_TIMEOUT_MS
                    connection.readTimeout = DEFAULT_TIMEOUT_MS
                    connection.requestMethod = "GET"
                    connection.responseCode in 200..399
                } catch (e: Exception) {
                    false
                } finally {
                    connection.disconnect()
                }
            }

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

    /**
     * Fast resolution used by UI: prefer cached local host if reachable quickly,
     * otherwise return the Railway cloud URL.
     */
    suspend fun fastResolveMcpBaseUrl(): String = withContext(Dispatchers.IO) {
        if (appContext == null) {
            Log.w(TAG, "fastResolve: ServerEndpointResolver not initialised. Falling back to Railway URL.")
            return@withContext RAILWAY_MCP_URL
        }

        // 0. Honor an explicit forced full URL set via preferences (useful for testing with Docker host)
        try {
            val ctx = appContext
            if (ctx != null) {
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.getString(PREF_KEY_FORCED_FULL, null)?.let { forced ->
                    if (!forced.isNullOrBlank()) {
                        // SPECIAL CHECK: If the forced URL is one of the known legacy dev IPs that are now invalid, clear it.
                        if (forced.contains("192.168.1.90") || forced.contains("10.144.200.79")) {
                            Log.w(TAG, "fastResolve: Found obsolete dev URL in forced prefs, clearing: $forced")
                            prefs.edit().remove(PREF_KEY_FORCED_FULL).apply()
                        } else {
                            try {
                                if (isServiceReachable(forced)) {
                                    Log.i(TAG, "fastResolve: returning forced MCP URL from prefs: $forced")
                                    return@withContext forced
                                } else {
                                    Log.w(TAG, "fastResolve: forced MCP URL not reachable: $forced")
                                }
                            } catch (e: Exception) {
                                Log.d(TAG, "fastResolve: error checking forced url: ${e.message}")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "fastResolve: forced-pref check failed: ${e.message}")
        }

        // 1. Quick cached check (non-blocking)
        val cached = peekMcpBaseUrl()
        if (!cached.isNullOrBlank()) {
             // SPECIAL CHECK: Obsolete IPs
             if (cached.contains("192.168.1.90") || cached.contains("10.144.200.79")) {
                 Log.w(TAG, "fastResolve: clearing obsolete cached host: $cached")
                 cachedHostsByPort.remove(MCP_PORT)
                 // Also remove from persistance
                 appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        ?.edit()?.remove("${PREF_KEY_PREFIX}${MCP_PORT}")?.apply()
             } else {
                try {
                    if (isServiceReachable(cached)) {
                        return@withContext cached
                    } else {
                        // IMPORTANT: If cached host is not reachable, remove it immediately to allow fallback
                        Log.w(TAG, "fastResolve: cached host no longer reachable, removing: $cached")
                        cachedHostsByPort.remove(MCP_PORT)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "fastResolve: cached host not reachable: ${e.message}")
                    cachedHostsByPort.remove(MCP_PORT)
                }
            }
        }

        // 2. Build a compact candidate list (gateway + small subset of subnet candidates)
        val candidates = LinkedHashSet<String>()
        try {
            getGatewayAddress()?.let { gw ->
                if (gw.isNotBlank()) {
                    candidates.add(gw)
                    val base = gw.substringBeforeLast('.')
                    candidates.add("$base.1")
                    candidates.add("$base.2")
                    candidates.add("$base.100")
                }
            }
        } catch (_: Exception) {}

        // Include a few helpful defaults and cached/persisted hosts
        candidates.addAll(listOf("127.0.0.1", "localhost", "host.docker.internal"))
        loadPersistedHost(MCP_PORT)?.let { 
             if (it != "192.168.1.90" && it != "10.144.200.79") candidates.add(it)
        }
        cachedHostsByPort[MCP_PORT]?.let { 
             if (it != "192.168.1.90" && it != "10.144.200.79") candidates.add(it)
        }

        // Add a small subset of subnet candidates (up to 8) to keep probes cheap
        try {
            val subnet = collectSubnetCandidates()
            for (c in subnet.take(8)) candidates.add(c)
        } catch (_: Exception) {}
        
        // Remove specific bad IPs from candidates if they snuck in
        candidates.remove("192.168.1.90")
        candidates.remove("10.144.200.79")

        // 3. Probe candidates in parallel and return first success very fast
        try {
            val deferredFound = CompletableDeferred<String?>()
            val probeScope = CoroutineScope(Dispatchers.IO)
            val jobs = mutableListOf<kotlinx.coroutines.Job>()

            for (host in candidates) {
                val job = probeScope.launch {
                    try {
                        if (isServiceReachableBlocking(host, MCP_PORT, "/health")) {
                            if (!deferredFound.isCompleted) deferredFound.complete(host)
                        }
                    } catch (_: Exception) {}
                }
                jobs.add(job)
            }

            // Wait a small bounded time for any probe to succeed (short, bounded)
            val foundHost = withTimeoutOrNull(200) { // ms
                deferredFound.await()
            }

            // Cancel outstanding probes
            jobs.forEach { it.cancel() }

            if (!foundHost.isNullOrBlank()) {
                val url = "http://$foundHost:$MCP_PORT"
                Log.i(TAG, "fastResolve: parallel probe found host: $url")
                return@withContext url
            }
        } catch (e: Exception) {
            Log.d(TAG, "fastResolve parallel probes error: ${e.message}")
        }

        // 4. If nothing found quickly, fall back to cloud immediately
        return@withContext RAILWAY_MCP_URL
    }

    /**
     * Set or clear a forced full MCP base URL for tests (e.g. http://192.168.1.90:3000).
     * When set, `fastResolveMcpBaseUrl` and `getMcpBaseUrl` will prefer this value if reachable.
     */
    fun setForcedMcpBaseUrl(context: Context, fullUrl: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (fullUrl.isNullOrBlank()) {
            prefs.edit().remove(PREF_KEY_FORCED_FULL).apply()
            Log.i(TAG, "Forced MCP URL cleared from prefs")
        } else {
            prefs.edit().putString(PREF_KEY_FORCED_FULL, fullUrl).apply()
            Log.i(TAG, "Forced MCP URL saved to prefs: $fullUrl")
            // Attempt to cache host part for faster discovery
            try {
                val u = URL(fullUrl)
                val host = u.host
                val scheme = u.protocol
                val port = if (u.port == -1) u.defaultPort else u.port
                // Only cache as a local MCP host when it's an http host on MCP_PORT or explicit local IPs.
                val isLikelyLocal = try {
                    host == "127.0.0.1" || host == "localhost" || host == "10.0.2.2" || host.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))
                } catch (e: Exception) { false }

                if (!host.isNullOrBlank() && scheme == "http" && (port == MCP_PORT || isLikelyLocal)) {
                    cachedHostsByPort[MCP_PORT] = host
                } else {
                    // Do not cache cloud HTTPS hosts as local MCP hosts (prevents adding :3000 to cloud domain)
                    Log.d(TAG, "Not caching forced MCP host for discovery (scheme=$scheme, host=$host, port=$port)")
                }
            } catch (e: Exception) {
                Log.d(TAG, "setForcedMcpBaseUrl: parse error: ${e.message}")
            }
        }
    }

    fun getForcedMcpBaseUrl(): String? {
        val ctx = appContext ?: return null
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(PREF_KEY_FORCED_FULL, null)
    }

    private suspend fun getBaseUrlForPort(port: Int, healthPath: String, forceDiscovery: Boolean): String? {
        ensureInitialised()
        val host = ensureHostForPort(port, healthPath, forceDiscovery)
        return host?.let { "http://$it:$port" }
    }

    private suspend fun ensureHostForPort(port: Int, healthPath: String, forceDiscovery: Boolean): String? = withContext(Dispatchers.IO) {
        if (!forceDiscovery) {
            cachedHostsByPort[port]?.let { cached ->
                // More strict validation with timeout
                if (isServiceReachableBlocking(cached, port, healthPath)) {
                    Log.d(TAG, "Using cached host for port $port: $cached")
                    return@withContext cached
                } else {
                    Log.w(TAG, "Cached host $cached:$port no longer reachable, removing from cache")
                    cachedHostsByPort.remove(port)
                    // Clear from persisted storage too
                    appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        ?.edit()?.remove("$PREF_KEY_PREFIX$port")?.apply()
                }
            }

            loadPersistedHost(port)?.let { persisted ->
                if (isServiceReachableBlocking(persisted, port, healthPath)) {
                    Log.d(TAG, "Using persisted host for port $port: $persisted")
                    cachedHostsByPort[port] = persisted
                    return@withContext persisted
                } else {
                    Log.w(TAG, "Persisted host $persisted:$port no longer reachable")
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

    /**
     * Return a short list of likely MCP HTTP candidates on the LAN.
     * Includes gateway IP (if available), emulator host aliases and host.docker.internal.
     * This is intentionally small and ordered by likelihood so probes are fast.
     */
    fun getLikelyMcpCandidates(): List<String> {
        val candidates = LinkedHashSet<String>()
        try {
            // Forced/persisted full URL first
            getForcedMcpBaseUrl()?.let { f ->
                if (f.isNotBlank()) candidates.add(f.trimEnd('/'))
            }

            // Add common aliases
            candidates.add("http://host.docker.internal:3000")
            candidates.add("http://10.0.2.2:3000")

            // Try gateway IP if available (WiFi)
            try {
                val wifi = wifiManager
                if (wifi != null) {
                    @Suppress("DEPRECATION")
                    val dhcp = wifi.dhcpInfo
                    val gw = dhcp?.gateway ?: 0
                    if (gw != 0) {
                        // Convert int gateway to dotted-quad
                        val g = listOf(
                            (gw and 0xFF),
                            (gw shr 8 and 0xFF),
                            (gw shr 16 and 0xFF),
                            (gw shr 24 and 0xFF)
                        ).joinToString(".")
                        candidates.add("http://$g:3000")
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "gateway lookup failed: ${e.message}")
            }

            // Add fallback common host guesses on the subnet (small set)
            val subnetGuesses = listOf(1, 2, 10, 20, 40, 100)
            // Try to derive device local IP to construct sibling addresses
            try {
                val cm = connectivityManager
                val linkProps = cm?.getLinkProperties(cm.activeNetwork)
                val addr = linkProps?.linkAddresses?.firstOrNull { it.address is Inet4Address }?.address?.hostAddress
                if (!addr.isNullOrBlank()) {
                    val parts = addr.split('.')
                    if (parts.size == 4) {
                        val base = parts.subList(0, 3).joinToString(".")
                        for (n in subnetGuesses) {
                            val ip = "$base.$n"
                            if (ip != "192.168.1.90" && ip != "10.144.200.79") {
                                candidates.add("http://$ip:3000")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "subnet derivation failed: ${e.message}")
            }

        // Persisted hosts
        loadPersistedHost(MCP_PORT)?.let { 
             if (it != "192.168.1.90" && it != "10.144.200.79") candidates.add(it)
        }
        loadPersistedHost(OLLAMA_PORT)?.let { 
             if (it != "192.168.1.90" && it != "10.144.200.79") candidates.add(it)
       
            }
        } catch (e: Exception) {
            Log.d(TAG, "getLikelyMcpCandidates failed: ${e.message}")
        }
        return ArrayList(candidates)
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

        // Add a few probable host addresses on the same subnet: .1, .100, .254 — quick wins for many routers
        try {
            getGatewayAddress()?.let { gw ->
                val parts = gw.split('.')
                if (parts.size == 4) {
                    val base = parts.subList(0, 3).joinToString(".")
                    candidates.add("$base.1")
                    candidates.add("$base.100")
                    candidates.add("$base.254")
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "adding probable hosts failed: ${e.message}")
        }

        if (isEmulator()) {
            candidates.add("10.0.2.2")
        }

        // Quick wins: emulator and common docker host alias
        // Android emulator uses 10.0.2.2 -> host's localhost
        candidates.add("10.0.2.2")
        // Some Docker setups expose host.docker.internal for host access
        candidates.add("host.docker.internal")
        candidates.add("127.0.0.1")
        candidates.add("localhost")

        // Add gateway only if it is not an emulator-only subnet (10.0.2.x)
        getGatewayAddress()?.let {
            if (it.startsWith("10.0.2.") && !isEmulator()) {
                Log.d(TAG, "Skipping gateway $it (emulator subnet) on physical device")
            } else {
                candidates.add(it)
            }
        }

        // Collect subnet candidates but skip emulator subnets when on a physical device
        collectSubnetCandidates().forEach { candidate ->
            if (candidate.startsWith("10.0.2.") && !isEmulator()) {
                Log.d(TAG, "Skipping candidate $candidate from subnet scan (emulator subnet) on physical device")
            } else {
                candidates.add(candidate)
            }
        }

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

    fun getGatewayAddress(): String? {
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