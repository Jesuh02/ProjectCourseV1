package com.example.tareamov.service.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log

/**
 * NetworkConnectivityChecker — Valida conectividad de red antes de operaciones HTTP.
 *
 * Responsabilidad única: determinar si el dispositivo tiene conectividad
 * de red válida (Wi-Fi, celular o ethernet) con acceso a internet.
 *
 * Uso:
 *   if (!NetworkConnectivityChecker.isNetworkAvailable(context)) {
 *       // Mostrar mensaje offline
 *   }
 */
object NetworkConnectivityChecker {

    private const val TAG = "NetworkChecker"

    /**
     * Verifica si el dispositivo tiene una conexión de red activa con
     * capacidad de alcanzar internet.
     *
     * @param context Contexto de Android (Application o Activity).
     * @return true si hay conectividad, false si el dispositivo está offline.
     */
    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return false

        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false

        val hasTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        val result = hasTransport && hasInternet && isValidated
        if (!result) {
            Log.w(TAG, "Network unavailable: transport=$hasTransport, internet=$hasInternet, validated=$isValidated")
        }
        return result
    }

    /**
     * Devuelve un mensaje de error legible para el usuario según el estado de red.
     */
    fun getOfflineMessage(): String =
        "Sin conexión a internet. Verifica tu conexión Wi-Fi o datos móviles e intenta de nuevo."
}
