package com.example.tareamov.service.network

import android.util.Log
import okhttp3.Dns
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * FallbackDnsResolver — Resolvedor DNS con fallback a servidores públicos.
 *
 * Problema: En algunos dispositivos/emuladores, el DNS del sistema no logra
 * resolver dominios de Railway (*.up.railway.app), generando:
 *   "Unable to resolve host: No address associated with hostname"
 *
 * Solución multinivel (Adapter Pattern — implementa okhttp3.Dns):
 *   1. Cache en memoria (5 min TTL)
 *   2. DNS del sistema (Dns.SYSTEM)
 *   3. DNS-over-HTTPS vía IP directa (Google 8.8.8.8, Cloudflare 1.1.1.1)
 *      → Usa IP directa para evitar dependencia circular de DNS
 *   4. Resolución UDP directa contra servidores DNS públicos
 *   5. InetAddress.getAllByName como último recurso
 *
 * Principios:
 *   - SRP: Solo resuelve DNS con fallback
 *   - OCP: Nuevos resolvers se agregan sin modificar lógica existente
 *   - DIP: Implementa okhttp3.Dns (puerto que OkHttp expone)
 */
object FallbackDnsResolver : Dns {

    private const val TAG = "FallbackDnsResolver"

    /** Cache de resoluciones exitosas con TTL configurable. */
    private val dnsCache = ConcurrentHashMap<String, CachedEntry>()
    private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutos

    /**
     * DoH (DNS-over-HTTPS) endpoints usando IP directa para evitar
     * dependencia circular: si el DNS del sistema no funciona,
     * no podemos resolver "dns.google" para hacer DoH.
     */
    private val DOH_RESOLVERS = listOf(
        DoHResolver("8.8.8.8", "/resolve"),     // Google DNS IP directa
        DoHResolver("1.1.1.1", "/dns-query"),   // Cloudflare DNS IP directa
        DoHResolver("8.8.4.4", "/resolve"),     // Google DNS secundario
        DoHResolver("1.0.0.1", "/dns-query")    // Cloudflare DNS secundario
    )

    /** Servidores DNS públicos para resolución UDP directa. */
    private val UDP_DNS_SERVERS = listOf("8.8.8.8", "1.1.1.1", "8.8.4.4", "1.0.0.1")

    private data class DoHResolver(val ip: String, val path: String) {
        fun buildUrl(hostname: String): String =
            "https://$ip$path?name=$hostname&type=A"
    }

    private data class CachedEntry(
        val addresses: List<InetAddress>,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isExpired(): Boolean =
            System.currentTimeMillis() - timestamp > CACHE_TTL_MS
    }

    override fun lookup(hostname: String): List<InetAddress> {
        // 1. Consultar cache en memoria
        dnsCache[hostname]?.let { entry ->
            if (!entry.isExpired()) {
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

        // 3. Fallback: DNS-over-HTTPS usando IP directa (sin resolver hostname del DNS)
        val dohAddresses = resolveViaDoH(hostname)
        if (dohAddresses.isNotEmpty()) {
            cacheResult(hostname, dohAddresses)
            Log.i(TAG, "DoH resolved $hostname → ${dohAddresses.size} addresses")
            return dohAddresses
        }

        // 4. Fallback: Resolución UDP directa contra DNS públicos
        val udpAddresses = resolveViaUdpDns(hostname)
        if (udpAddresses.isNotEmpty()) {
            cacheResult(hostname, udpAddresses)
            Log.i(TAG, "UDP DNS resolved $hostname → ${udpAddresses.size} addresses")
            return udpAddresses
        }

        // 5. Último recurso: InetAddress.getAllByName
        return try {
            val directResult = InetAddress.getAllByName(hostname).toList()
            if (directResult.isNotEmpty()) {
                cacheResult(hostname, directResult)
                directResult
            } else {
                throw UnknownHostException("No se pudo resolver: $hostname")
            }
        } catch (e: UnknownHostException) {
            Log.e(TAG, "All DNS resolution methods failed for $hostname")
            throw e
        }
    }

    /**
     * Resuelve hostname mediante DNS-over-HTTPS usando IP directa del servidor DNS.
     * Evita la dependencia circular de necesitar DNS para resolver el servidor DNS.
     */
    private fun resolveViaDoH(hostname: String): List<InetAddress> {
        for (resolver in DOH_RESOLVERS) {
            try {
                val url = java.net.URL(resolver.buildUrl(hostname))
                val connection = url.openConnection() as javax.net.ssl.HttpsURLConnection

                // Configurar hostname verifier para aceptar el IP como valid hostname
                connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, session ->
                    val peerHost = session.peerHost
                    peerHost == resolver.ip || peerHost == "dns.google" || peerHost == "cloudflare-dns.com"
                }

                connection.setRequestProperty("Accept", "application/dns-json")
                connection.connectTimeout = 3_000
                connection.readTimeout = 3_000
                connection.instanceFollowRedirects = true

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().readText()
                    val addresses = parseDnsJsonResponse(response)
                    connection.disconnect()
                    if (addresses.isNotEmpty()) return addresses
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "DoH via ${resolver.ip} failed: ${e.message}")
            }
        }
        return emptyList()
    }

    /**
     * Resolución DNS UDP directa contra servidores públicos.
     * Construye un paquete DNS estándar (RFC 1035) y envía directamente
     * al puerto 53 del servidor DNS, sin pasar por el resolver del sistema.
     * 
     * Útil en emuladores donde el DNS del sistema está roto pero
     * la conectividad UDP funciona.
     */
    private fun resolveViaUdpDns(hostname: String): List<InetAddress> {
        for (dnsServer in UDP_DNS_SERVERS) {
            try {
                val query = buildDnsQuery(hostname)
                val socket = DatagramSocket()
                socket.soTimeout = 3_000

                val serverAddr = InetAddress.getByName(dnsServer)
                val sendPacket = DatagramPacket(query, query.size, serverAddr, 53)
                socket.send(sendPacket)

                val buffer = ByteArray(512)
                val receivePacket = DatagramPacket(buffer, buffer.size)
                socket.receive(receivePacket)
                socket.close()

                val addresses = parseDnsResponse(buffer, receivePacket.length)
                if (addresses.isNotEmpty()) return addresses
            } catch (e: Exception) {
                Log.w(TAG, "UDP DNS via $dnsServer failed: ${e.message}")
            }
        }
        return emptyList()
    }

    /**
     * Construye un paquete DNS query (RFC 1035) para resolver un hostname A record.
     */
    private fun buildDnsQuery(hostname: String): ByteArray {
        val parts = hostname.split(".")
        val buffer = mutableListOf<Byte>()

        // Header: ID=0x1234, QR=0, Opcode=0, RD=1, QDCOUNT=1
        buffer.addAll(listOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00).map { it.toByte() })

        // Question section: encode hostname labels
        for (part in parts) {
            buffer.add(part.length.toByte())
            buffer.addAll(part.toByteArray().toList())
        }
        buffer.add(0x00) // end of name

        // Type A (0x0001), Class IN (0x0001)
        buffer.addAll(listOf(0x00, 0x01, 0x00, 0x01).map { it.toByte() })

        return buffer.toByteArray()
    }

    /**
     * Parsea respuesta DNS UDP y extrae direcciones IPv4.
     */
    private fun parseDnsResponse(data: ByteArray, length: Int): List<InetAddress> {
        if (length < 12) return emptyList()
        val addresses = mutableListOf<InetAddress>()

        // Answer count is at bytes 6-7
        val answerCount = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        if (answerCount == 0) return emptyList()

        // Skip header (12 bytes) + question section
        var offset = 12
        // Skip question name
        while (offset < length && data[offset].toInt() != 0) {
            val labelLen = data[offset].toInt() and 0xFF
            if (labelLen >= 0xC0) { offset += 2; break } // compressed pointer
            offset += labelLen + 1
        }
        if (offset < length && data[offset].toInt() == 0) offset++
        offset += 4 // skip QTYPE + QCLASS

        // Parse answer records
        for (i in 0 until answerCount) {
            if (offset + 12 > length) break
            // Skip name (may be compressed pointer)
            if ((data[offset].toInt() and 0xC0) == 0xC0) {
                offset += 2
            } else {
                while (offset < length && data[offset].toInt() != 0) {
                    offset += (data[offset].toInt() and 0xFF) + 1
                }
                offset++
            }
            if (offset + 10 > length) break
            val rType = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            val rdLength = ((data[offset + 8].toInt() and 0xFF) shl 8) or (data[offset + 9].toInt() and 0xFF)
            offset += 10

            if (rType == 1 && rdLength == 4 && offset + 4 <= length) {
                // A record — 4 bytes IPv4
                val ip = byteArrayOf(data[offset], data[offset + 1], data[offset + 2], data[offset + 3])
                try { addresses.add(InetAddress.getByAddress(ip)) } catch (_: Exception) {}
            }
            offset += rdLength
        }
        return addresses
    }

    /**
     * Parsea la respuesta JSON de DNS-over-HTTPS.
     * Formato: { "Answer": [{ "data": "1.2.3.4", "type": 1 }, ...] }
     */
    private fun parseDnsJsonResponse(json: String): List<InetAddress> {
        val addresses = mutableListOf<InetAddress>()
        try {
            val answerPattern = """"data"\s*:\s*"([^"]+)"""".toRegex()
            answerPattern.findAll(json).forEach { match ->
                val ip = match.groupValues[1]
                // Solo aceptar IPs válidas (no CNAMEs u otros records)
                if (ip.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
                    try {
                        val addr = InetAddress.getByName(ip)
                        if (addr is Inet4Address) {
                            addresses.add(addr)
                        }
                    } catch (_: Exception) { /* IP inválida, skip */ }
                }
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
