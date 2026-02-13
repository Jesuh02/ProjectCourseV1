package com.example.tareamov.service.network

import android.util.Log
import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * FallbackDnsResolver — Resolvedor DNS con fallback a servidores públicos.
 *
 * Problema: En algunos dispositivos/emuladores, el DNS del sistema no logra
 * resolver dominios de Railway (*.up.railway.app), generando:
 *   "Unable to resolve host: No address associated with hostname"
 *
 * Solución: Si el DNS del sistema falla, se re-intenta con Google DNS (8.8.8.8)
 * y Cloudflare DNS (1.1.1.1) usando resolución directa por socket.
 *
 * Patrón: Adapter (implementa okhttp3.Dns, el puerto que OkHttp expone).
 *
 * Uso:
 *   OkHttpClient.Builder()
 *       .dns(FallbackDnsResolver)
 *       .build()
 */
object FallbackDnsResolver : Dns {

    private const val TAG = "FallbackDnsResolver"

    /** Cache de resoluciones exitosas (TTL manejado por invalidación periódica). */
    private val dnsCache = ConcurrentHashMap<String, CachedEntry>()
    private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutos

    private data class CachedEntry(
        val addresses: List<InetAddress>,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isExpired(): Boolean =
            System.currentTimeMillis() - timestamp > CACHE_TTL_MS
    }

    override fun lookup(hostname: String): List<InetAddress> {
        // 1. Consultar cache
        dnsCache[hostname]?.let { entry ->
            if (!entry.isExpired()) {
                Log.d(TAG, "DNS cache hit: $hostname → ${entry.addresses.size} addresses")
                return entry.addresses
            }
            dnsCache.remove(hostname)
        }

        // 2. Intentar DNS del sistema
        try {
            val systemResult = Dns.SYSTEM.lookup(hostname)
            if (systemResult.isNotEmpty()) {
                cacheResult(hostname, systemResult)
                return systemResult
            }
        } catch (e: UnknownHostException) {
            Log.w(TAG, "System DNS failed for $hostname: ${e.message}")
        }

        // 3. Fallback: resolución directa con DNS públicos
        val fallbackAddresses = resolveFallback(hostname)
        if (fallbackAddresses.isNotEmpty()) {
            cacheResult(hostname, fallbackAddresses)
            Log.i(TAG, "Fallback DNS resolved $hostname → ${fallbackAddresses.size} addresses")
            return fallbackAddresses
        }

        // 4. Último recurso: intentar InetAddress.getAllByName directamente
        return try {
            val directResult = InetAddress.getAllByName(hostname).toList()
            if (directResult.isNotEmpty()) {
                cacheResult(hostname, directResult)
                directResult
            } else {
                throw UnknownHostException("No se pudo resolver: $hostname")
            }
        } catch (e: UnknownHostException) {
            Log.e(TAG, "All DNS resolution failed for $hostname")
            throw e
        }
    }

    /**
     * Resuelve un hostname consultando DNS públicos (Google y Cloudflare)
     * mediante DNS-over-HTTPS (DoH), que no depende de la configuración
     * DNS del sistema operativo.
     */
    private fun resolveFallback(hostname: String): List<InetAddress> {
        val resolvers = listOf(
            "https://dns.google/resolve?name=$hostname&type=A",
            "https://cloudflare-dns.com/dns-query?name=$hostname&type=A"
        )

        for (resolverUrl in resolvers) {
            try {
                val connection = java.net.URL(resolverUrl).openConnection() as java.net.HttpURLConnection
                connection.setRequestProperty("Accept", "application/dns-json")
                connection.connectTimeout = 3000
                connection.readTimeout = 3000

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().readText()
                    val addresses = parseDnsJsonResponse(response)
                    if (addresses.isNotEmpty()) {
                        return addresses
                    }
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "DoH resolver failed ($resolverUrl): ${e.message}")
            }
        }
        return emptyList()
    }

    /**
     * Parsea la respuesta JSON de DNS-over-HTTPS.
     * Formato: { "Answer": [{ "data": "1.2.3.4", "type": 1 }, ...] }
     */
    private fun parseDnsJsonResponse(json: String): List<InetAddress> {
        val addresses = mutableListOf<InetAddress>()
        try {
            // Parse simple — evita dependencia de Gson para esta utility
            val answerPattern = """"data"\s*:\s*"([^"]+)"""".toRegex()
            answerPattern.findAll(json).forEach { match ->
                val ip = match.groupValues[1]
                try {
                    val addr = InetAddress.getByName(ip)
                    if (addr is Inet4Address) {
                        addresses.add(addr)
                    }
                } catch (_: Exception) { /* IP inválida, skip */ }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing DNS JSON: ${e.message}")
        }
        return addresses
    }

    private fun cacheResult(hostname: String, addresses: List<InetAddress>) {
        dnsCache[hostname] = CachedEntry(addresses)
    }

    /** Limpia toda la cache (útil al cambiar de red). */
    fun clearCache() {
        dnsCache.clear()
        Log.d(TAG, "DNS cache cleared")
    }
}
