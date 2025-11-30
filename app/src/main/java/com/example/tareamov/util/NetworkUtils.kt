package com.example.tareamov.util

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * Utilidades para obtener información de red del dispositivo
 */
object NetworkUtils {
    private const val TAG = "NetworkUtils"

    /**
     * Obtiene la dirección IP local del dispositivo (WiFi o Ethernet)
     * @return La dirección IP como String, o null si no se puede obtener
     */
    fun getLocalIpAddress(context: Context): String? {
        try {
            // Método 1: Intentar obtener IP de WiFi
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiManager?.let {
                val wifiInfo = it.connectionInfo
                val ipInt = wifiInfo.ipAddress
                if (ipInt != 0) {
                    val ip = String.format(
                        "%d.%d.%d.%d",
                        ipInt and 0xff,
                        ipInt shr 8 and 0xff,
                        ipInt shr 16 and 0xff,
                        ipInt shr 24 and 0xff
                    )
                    Log.d(TAG, "IP obtenida de WiFi: $ip")
                    return ip
                }
            }

            // Método 2: Buscar en todas las interfaces de red
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                // Ignorar interfaces inactivas o loopback
                if (!intf.isUp || intf.isLoopback) continue

                val addresses = Collections.list(intf.inetAddresses)
                for (addr in addresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress
                        Log.d(TAG, "IP obtenida de interfaz ${intf.name}: $ip")
                        return ip
                    }
                }
            }

            Log.w(TAG, "No se pudo obtener ninguna dirección IP")
        } catch (e: Exception) {
            Log.e(TAG, "Error al obtener dirección IP", e)
        }
        return null
    }

    /**
     * Obtiene todas las direcciones IP disponibles del dispositivo
     * @return Lista de direcciones IP
     */
    fun getAllLocalIpAddresses(): List<String> {
        val ipList = mutableListOf<String>()
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue

                val addresses = Collections.list(intf.inetAddresses)
                for (addr in addresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        addr.hostAddress?.let { ipList.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al obtener direcciones IP", e)
        }
        return ipList
    }

    /**
     * Construye URLs de servidor basadas en la IP del dispositivo
     * @param port Puerto del servidor
     * @return Lista de URLs posibles, ordenadas por prioridad
     */
    fun buildServerUrls(context: Context, port: Int): List<String> {
        val urls = mutableListOf<String>()
        
        // Explicitly add the user's PC IP
        urls.add("http://192.168.1.90:$port")
        
        // Agregar emulador primero (si aplica)
        urls.add("http://10.0.2.2:$port")
        
        // Obtener IP local del dispositivo
        getLocalIpAddress(context)?.let { localIp ->
            urls.add("http://$localIp:$port")
            
            // Calcular IP del gateway (cambiar último octeto a .1)
            val parts = localIp.split(".")
            if (parts.size == 4) {
                val gatewayIp = "${parts[0]}.${parts[1]}.${parts[2]}.1"
                urls.add("http://$gatewayIp:$port")
            }
        }
        
        // Agregar localhost como fallback
        urls.add("http://localhost:$port")
        urls.add("http://127.0.0.1:$port")
        
        Log.d(TAG, "URLs construidas para puerto $port: $urls")
        return urls
    }
}
