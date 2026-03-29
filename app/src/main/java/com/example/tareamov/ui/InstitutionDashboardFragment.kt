package com.example.tareamov.ui

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.tareamov.R
import com.example.tareamov.config.TenantManager
import com.example.tareamov.service.BackendApiService
import com.example.tareamov.util.SessionManager

class InstitutionDashboardFragment : Fragment() {

    companion object {
        private const val TAG = "InstitutionDashboard"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_institution_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val sessionManager = SessionManager.getInstance(requireContext())
        val tenant = TenantManager.getSelectedTenant(requireContext())

        // Bind views
        val institutionName = view.findViewById<TextView>(R.id.institutionNameText)
        val connectionStatus = view.findViewById<TextView>(R.id.connectionStatusText)
        val serverUrl = view.findViewById<TextView>(R.id.serverUrlText)
        val projectId = view.findViewById<TextView>(R.id.projectIdText)
        val usernameText = view.findViewById<TextView>(R.id.usernameText)
        val btnGoToApp = view.findViewById<Button>(R.id.btnGoToApp)
        val btnLogout = view.findViewById<Button>(R.id.btnLogout)

        // Populate data
        institutionName.text = tenant?.name ?: "Sin institución"
        connectionStatus.text = "Conectado"
        serverUrl.text = tenant?.serverUrl?.let {
            try {
                android.net.Uri.parse(it).host ?: it
            } catch (_: Exception) { it }
        } ?: "N/A"
        projectId.text = tenant?.supabaseProjectId ?: "N/A"
        usernameText.text = sessionManager.getUsername() ?: "Usuario"

        // Navigate to app
        btnGoToApp.setOnClickListener {
            try {
                findNavController().navigate(R.id.action_institutionDashboardFragment_to_videoHomeFragment)
            } catch (e: Exception) {
                Log.e(TAG, "Navigation error", e)
            }
        }

        // Logout
        btnLogout.setOnClickListener {
            sessionManager.logout()
            BackendApiService.logout()
            TenantManager.clearTenant(requireContext())
            try {
                findNavController().navigate(R.id.action_global_loginFragment)
            } catch (e: Exception) {
                Log.e(TAG, "Navigation error", e)
            }
        }
    }
}
