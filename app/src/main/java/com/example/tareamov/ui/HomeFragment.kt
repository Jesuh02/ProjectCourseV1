package com.example.tareamov.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.tareamov.MainActivity
import com.example.tareamov.R
import com.example.tareamov.adapter.PersonaAdapter
import com.example.tareamov.data.entity.Persona
import com.example.tareamov.util.SessionManager
import com.example.tareamov.viewmodel.AuthViewModel
import com.example.tareamov.viewmodel.PersonaViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment : Fragment(), PersonaAdapter.OnItemClickListener {
    private lateinit var welcomeTextView: TextView
    private lateinit var instructionsTextView: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var addPersonFab: ExtendedFloatingActionButton
    private lateinit var searchEditText: TextInputEditText
    private lateinit var totalUsersCount: TextView
    private lateinit var activeUsersCount: TextView
    private lateinit var resultsCountText: TextView
    private lateinit var emptyStateLayout: LinearLayout

    // Add animation properties
    private lateinit var fabOpen: Animation
    private lateinit var fabClose: Animation
    private lateinit var rotateForward: Animation
    private lateinit var rotateBackward: Animation
    private var isFabOpen = false

    private lateinit var adapter: PersonaAdapter
    private lateinit var personaViewModel: PersonaViewModel
    private lateinit var authViewModel: AuthViewModel
    private var allPersonas: List<Persona> = emptyList()
    private lateinit var sessionManager: SessionManager // Declare sessionManager here

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_home, container, false)

        // Initialize views
        welcomeTextView = view.findViewById(R.id.welcomeTextView)
        instructionsTextView = view.findViewById(R.id.instructionsTextView)
        recyclerView = view.findViewById(R.id.recyclerView)
        addPersonFab = view.findViewById(R.id.addPersonFab)
        searchEditText = view.findViewById(R.id.searchEditText)
        totalUsersCount = view.findViewById(R.id.totalUsersCount)
        activeUsersCount = view.findViewById(R.id.activeUsersCount)
        resultsCountText = view.findViewById(R.id.resultsCountText)
        emptyStateLayout = view.findViewById(R.id.emptyStateLayout)

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sessionManager = SessionManager.getInstance(requireContext()) // Initialize sessionManager

        // Initialize animations
        fabOpen = AnimationUtils.loadAnimation(requireContext(), R.anim.fab_open)
        fabClose = AnimationUtils.loadAnimation(requireContext(), R.anim.fab_close)
        rotateForward = AnimationUtils.loadAnimation(requireContext(), R.anim.rotate_forward)
        rotateBackward = AnimationUtils.loadAnimation(requireContext(), R.anim.rotate_backward)

        // Get ViewModels from MainActivity...
        personaViewModel = (requireActivity() as MainActivity).personaViewModel
        authViewModel = (requireActivity() as MainActivity).authViewModel

        // Set up RecyclerView
        adapter = PersonaAdapter()
        adapter.setOnItemClickListener(this)
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        // Add this line to set up the ItemTouchHelper for swipe actions
        setupItemTouchHelper()

        // Set up button animations
        setupButtonAnimations()

        // Set up the FAB to navigate to RegisterFragment (for user registration)
        addPersonFab.setOnClickListener {
            // Navigate to RegisterFragment
            findNavController().navigate(R.id.action_homeFragment_to_registerFragment)
        }

        // Observe personas from ViewModel
        personaViewModel.allPersonas.observe(viewLifecycleOwner) { personas ->
            allPersonas = personas
            adapter.submitList(personas)
            updateUserCounts(personas)
            updateEmptyState(personas)
        }

        // Set default welcome messages
        welcomeTextView.text = "GESTIÓN DE USUARIOS"
        instructionsTextView.text = "Sistema de administración"

        // Update welcome message with user's name if available
        // Observe currentUserId LiveData from AuthViewModel
        authViewModel.currentUserId.observe(viewLifecycleOwner) { userId -> // This is line 144
            if (userId != null && userId > 0) { // Check for null and ensure userId is valid
                // Use a coroutine to fetch the persona by ID
                lifecycleScope.launch { // Use lifecycleScope for Fragment coroutines
                    try {
                        val persona = withContext(Dispatchers.IO) { // Switch to IO dispatcher for DB call
                            personaViewModel.getPersonaByIdSync(userId)
                        }
                        // Switch back to Main dispatcher is implicit with lifecycleScope.launch for UI updates
                        if (persona != null) {
                            welcomeTextView.text = "Bienvenido, ${persona.nombres}"
                            instructionsTextView.text = "Panel de administración de usuarios"
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        // Optionally, show a default message or handle the error in UI
                        welcomeTextView.text = "GESTIÓN DE USUARIOS"
                        instructionsTextView.text = "Sistema de administración"
                    }
                }
            } else {
                // Handle case where userId is null or not valid (e.g., show default messages)
                welcomeTextView.text = "GESTIÓN DE USUARIOS"
                instructionsTextView.text = "Sistema de administración"
            }
        }

        // Set up search functionality
        setupSearch()
    }

    private fun setupSearch() {
        // Add text change listener for real-time filtering
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                // Filter when text changes
                filterPersonas(s.toString())
            }
        })
    }

    private fun filterPersonas(query: String) {
        if (query.isEmpty()) {
            // If query is empty, show all personas
            adapter.submitList(allPersonas)
            return
        }

        // Convert query to lowercase for case-insensitive search
        val lowercaseQuery = query.lowercase()

        lifecycleScope.launch {
            try {
                // First search in personas table
                val filteredPersonas = allPersonas.filter { persona ->
                    persona.nombres.lowercase().contains(lowercaseQuery) ||
                            persona.apellidos.lowercase().contains(lowercaseQuery) ||
                            persona.identificacion.lowercase().contains(lowercaseQuery) ||
                            "${persona.nombres} ${persona.apellidos}".lowercase().contains(lowercaseQuery)
                }

                // Then search in usuarios table by username
                val userResults = withContext(Dispatchers.IO) {
                    // Get the database instance from the ViewModel
                    personaViewModel.searchPersonasByUsername(lowercaseQuery)
                }

                // Combine results (avoiding duplicates)
                val combinedResults = (filteredPersonas + userResults).distinctBy { it.id }

                // Update the adapter with filtered results
                adapter.submitList(combinedResults)
                updateResultsCount(combinedResults.size)
                updateEmptyState(combinedResults)

                // Show message if no results found
                if (combinedResults.isEmpty()) {
                    Toast.makeText(requireContext(), "No se encontraron resultados", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(requireContext(), "Error al buscar: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateUserCounts(personas: List<Persona>) {
        val totalCount = personas.size
        val activeCount = personas.count { it.esUsuario }
        
        totalUsersCount.text = totalCount.toString()
        activeUsersCount.text = activeCount.toString()
        resultsCountText.text = "$totalCount usuarios"
    }

    private fun updateResultsCount(count: Int) {
        resultsCountText.text = "$count usuarios"
    }

    private fun updateEmptyState(personas: List<Persona>) {
        if (personas.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyStateLayout.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyStateLayout.visibility = View.GONE
        }
    }

    private fun setupItemTouchHelper() {
        val itemTouchHelperCallback = object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition // Use bindingAdapterPosition instead of adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val persona = adapter.currentList[position]

                    when (direction) {
                        ItemTouchHelper.LEFT -> {
                            // Delete action
                            showDeleteConfirmationDialog(persona)
                        }
                        ItemTouchHelper.RIGHT -> {
                            // Edit action
                            navigateToEditFragment(persona)
                        }
                    }

                    // Reset the swipe state
                    adapter.notifyItemChanged(position)
                }
            }
        }

        val itemTouchHelper = ItemTouchHelper(itemTouchHelperCallback)
        itemTouchHelper.attachToRecyclerView(recyclerView)
    }

    private fun showDeleteConfirmationDialog(persona: Persona) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Confirmar eliminación")
            .setMessage("¿Está seguro que desea eliminar a ${persona.nombres} ${persona.apellidos}?")
            .setPositiveButton("Eliminar") { _, _ ->
                personaViewModel.delete(persona)
                Toast.makeText(requireContext(), "Persona eliminada", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun navigateToEditFragment(persona: Persona) {
        val bundle = Bundle().apply {
            putLong("personaId", persona.id)
            putBoolean("isEditMode", true)
        }
        findNavController().navigate(R.id.action_homeFragment_to_registerFragment, bundle)
    }

    // PersonaAdapter.OnItemClickListener implementation
    override fun onItemClick(persona: Persona) {
        // Handle item click
        // Navigate to persona detail
        val bundle = Bundle().apply {
            putLong("personaId", persona.id)
        }
        // Add this line to use the bundle
        // findNavController().navigate(R.id.action_homeFragment_to_personaDetailFragment, bundle)
    }

    override fun onEditClick(persona: Persona) {
        // Handle edit click - navigate to edit fragment
        navigateToEditFragment(persona)
    }

    // Add the missing method implementation
    override fun onDeleteClick(persona: Persona) {
        // Handle delete click
        showDeleteConfirmationDialog(persona)
    }

    private fun setupButtonAnimations() {
        val buttonPressAnimation = AnimationUtils.loadAnimation(requireContext(), R.anim.button_press)

        // Apply animation to FAB
        addPersonFab.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.startAnimation(buttonPressAnimation)
                    false // Don't consume the event, let it pass to performClick
                }
                MotionEvent.ACTION_UP -> {
                    v.performClick() // Explicitly call performClick
                    false
                }
                else -> false
            }
        }
    }

    // Simplified animation method for single FAB
    private fun animateFab() {
        if (isFabOpen) {
            addPersonFab.startAnimation(rotateBackward)
            isFabOpen = false
        } else {
            addPersonFab.startAnimation(rotateForward)
            isFabOpen = true
        }
    }
}