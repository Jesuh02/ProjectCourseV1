package com.example.tareamov.ui

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.tareamov.R
import com.example.tareamov.adapter.CollaboratorSearchAdapter
import com.example.tareamov.data.entity.VideoData
import com.example.tareamov.data.entity.Topic
import com.example.tareamov.data.entity.Task
import com.example.tareamov.data.entity.ContentItem
import com.example.tareamov.data.entity.Usuario
import com.example.tareamov.util.VideoManager
import com.example.tareamov.data.entity.Course
import com.example.tareamov.service.BackendApiService
import com.example.tareamov.service.ApiResult
import com.example.tareamov.service.StorageHelper
import com.example.tareamov.util.SessionManager
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CourseCreationFragment : Fragment() {

    private var topicCount = 0
    private lateinit var videoManager: VideoManager
    private var currentCourseId: Long = -1L
    private var courseSaved = false
    private lateinit var sessionManager: SessionManager
    private var selectedThumbnailUri: Uri? = null
    private var isEditing = false
    private var isPaidCourse = false
    private lateinit var thumbnailExtractor: com.example.tareamov.util.VideoThumbnailExtractor
    private val selectedCollaborators = mutableListOf<Usuario>()
    private val originalCollaboratorIds = mutableSetOf<Long>()
    private lateinit var collaboratorSearchAdapter: CollaboratorSearchAdapter
    private var searchJob: Job? = null
    private var deadlineMillis: Long? = null

    // Pre-loaded user lists
    private var allUsersCache = listOf<Usuario>()
    private var role2UsersCache = listOf<Usuario>()

    // Guest (invitados) state
    private val selectedGuests = mutableListOf<Usuario>()
    private lateinit var guestSearchAdapter: CollaboratorSearchAdapter
    private var guestSearchJob: Job? = null

    companion object {
        private const val REQUEST_THUMBNAIL_PICK = 1001
        private const val KEY_THUMBNAIL_URI = "key_thumbnail_uri"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_course_creation, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Check arguments for editing
        arguments?.let {
            val id = it.getLong("courseId", -1L)
            isEditing = it.getBoolean("isEditing", false)
            if (id != -1L && isEditing) {
                currentCourseId = id
                courseSaved = true
                loadCourseData(id)
                // Update UI for editing
                view.findViewById<TextView>(R.id.saveButton)?.text = "Actualizar"
            }
        }

        // Restore thumbnail URI if available
        if (savedInstanceState != null) {
            val uriString = savedInstanceState.getString(KEY_THUMBNAIL_URI)
            if (!uriString.isNullOrEmpty()) {
                selectedThumbnailUri = Uri.parse(uriString)
                view.findViewById<ImageView>(R.id.courseThumbnailImageView).setImageURI(selectedThumbnailUri)
            }
        }

        videoManager = VideoManager(requireContext())
        sessionManager = SessionManager.getInstance(requireContext())
        thumbnailExtractor = com.example.tareamov.util.VideoThumbnailExtractor(requireContext())

        // Set up back button
        val backButton = view.findViewById<ImageButton>(R.id.backButton)
        backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        // Set up save button (Siguiente)
        val saveButton = view.findViewById<TextView>(R.id.saveButton)
        saveButton.setOnClickListener {
            saveCourse()
        }

        // Set up add topic button
        val addTopicButton = view.findViewById<Button>(R.id.addTopicButton)
        addTopicButton.setOnClickListener {
            addNewTopic()
        }

        // Set up select thumbnail button
        val selectThumbnailButton = view.findViewById<Button>(R.id.selectThumbnailButton)
        selectThumbnailButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            startActivityForResult(intent, REQUEST_THUMBNAIL_PICK)
        }

        setupToggleLogic(view)
        setupCharacterCounter(view)
        setupCollaboratorSearch(view)
        setupGuestSearch(view)
        setupDeadlinePicker(view)
        setupCategorySpinner(view)
        preloadAllUsers()
    }

    private fun preloadAllUsers() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    BackendApiService.listAllUsers(500)
                }
                if (result is ApiResult.Success) {
                    val currentUsername = sessionManager.getUsername()
                    allUsersCache = result.data.filter { it.usuario != currentUsername }
                    role2UsersCache = allUsersCache.filter { user ->
                        user.hasNetworkRole(2) || user.hasNetworkRole(3)
                    }
                    // Show default lists
                    val collabRecycler = view?.findViewById<RecyclerView>(R.id.collaboratorSearchResultsRecyclerView)
                    val guestRecycler = view?.findViewById<RecyclerView>(R.id.guestSearchResultsRecyclerView)
                    val collabQuery = view?.findViewById<EditText>(R.id.collaboratorSearchEditText)?.text?.toString()?.trim() ?: ""
                    val guestQuery = view?.findViewById<EditText>(R.id.guestSearchEditText)?.text?.toString()?.trim() ?: ""
                    if (collabQuery.isEmpty() && role2UsersCache.isNotEmpty()) {
                        collaboratorSearchAdapter.submitList(role2UsersCache)
                        collaboratorSearchAdapter.setSelectedIds(selectedCollaborators.map { it.id }.toSet())
                        collabRecycler?.visibility = View.VISIBLE
                    }
                    if (guestQuery.isEmpty() && allUsersCache.isNotEmpty()) {
                        guestSearchAdapter.submitList(allUsersCache)
                        guestSearchAdapter.setSelectedIds(selectedGuests.map { it.id }.toSet())
                        guestRecycler?.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                Log.e("CourseCreationFragment", "Error preloading users", e)
            }
        }
    }

    private fun setupCategorySpinner(view: View) {
        val spinner = view.findViewById<Spinner>(R.id.courseCategorySpinner)
        val categories = resources.getStringArray(R.array.course_categories)
        val adapter = object : ArrayAdapter<String>(requireContext(), android.R.layout.simple_spinner_item, categories) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                (v as? TextView)?.apply {
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 15f
                    setPadding(
                        (16 * resources.displayMetrics.density).toInt(),
                        0, 0, 0
                    )
                }
                return v
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent)
                (v as? TextView)?.apply {
                    setTextColor(android.graphics.Color.WHITE)
                    setBackgroundColor(android.graphics.Color.parseColor("#1C1C1E"))
                    textSize = 15f
                    setPadding(
                        (16 * resources.displayMetrics.density).toInt(),
                        (14 * resources.displayMetrics.density).toInt(),
                        (16 * resources.displayMetrics.density).toInt(),
                        (14 * resources.displayMetrics.density).toInt()
                    )
                }
                return v
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
    }

    private fun setupDeadlinePicker(view: View) {
        val deadlineSwitch = view.findViewById<Switch>(R.id.deadlineSwitch)
        val pickerContainer = view.findViewById<LinearLayout>(R.id.deadlinePickerContainer)
        val deadlineText = view.findViewById<TextView>(R.id.deadlineTextView)
        updateActiveHint(view)

        deadlineSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                pickerContainer.visibility = View.VISIBLE
            } else {
                pickerContainer.visibility = View.GONE
                deadlineMillis = null
                deadlineText.text = "Seleccionar fecha y hora"
                deadlineText.setTextColor(Color.parseColor("#8E8E93"))
            }
            updateActiveHint(view)
        }

        pickerContainer.setOnClickListener {
            val cal = Calendar.getInstance().apply {
                deadlineMillis?.let { timeInMillis = it }
            }
            DatePickerDialog(
                requireContext(),
                { _, year, month, day ->
                    TimePickerDialog(
                        requireContext(),
                        { _, hour, minute ->
                            cal.set(year, month, day, hour, minute, 0)
                            cal.set(Calendar.MILLISECOND, 0)
                            deadlineMillis = cal.timeInMillis
                            val display = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(cal.time)
                            deadlineText.text = display
                            deadlineText.setTextColor(Color.WHITE)
                            updateActiveHint(view)
                        },
                        cal.get(Calendar.HOUR_OF_DAY),
                        cal.get(Calendar.MINUTE),
                        true
                    ).show()
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }
    }

    private fun resolveEffectiveIsActive(view: View? = this.view): Boolean {
        val manualActive = view?.findViewById<Switch>(R.id.courseActiveSwitch)?.isChecked ?: true
        val deadlineActive = deadlineMillis?.let { it > System.currentTimeMillis() } ?: true
        return manualActive && deadlineActive
    }

    private fun updateActiveHint(view: View) {
        val hint = view.findViewById<TextView>(R.id.courseActiveHintTextView) ?: return
        val deadlineExpired = deadlineMillis?.let { it <= System.currentTimeMillis() } == true
        hint.text = if (deadlineExpired) {
            "La fecha limite ya vencio; el curso se guardara como inactivo."
        } else {
            "Los roles 1 y 2 solo pueden matricularse cuando el curso esta activo."
        }
    }

    private fun normalizeSearchText(value: String?): String {
        return value
            ?.trim()
            ?.lowercase(Locale.getDefault())
            ?.let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFD) }
            ?.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            ?: ""
    }

    private fun normalizeSearchDigits(value: String?): String {
        return value?.replace("\\D".toRegex(), "") ?: ""
    }

    private fun collaboratorDocument(user: Usuario): String {
        // Check nested persona first (populated when backend join succeeds)
        val cedula = user.personas?.cedula?.toString()?.takeIf { it.isNotBlank() && it != "0" }
        val identificacion = user.personas?.identificacion?.toString()?.takeIf { it.isNotBlank() && it != "0" }
        // Also check the root-level 'identificacion' field that _normalizeUserShape injects
        // This is a String? field added by the backend even when the personas join fails
        val rootIdentificacion = user.identificacionDoc?.takeIf { it.isNotBlank() && it != "0" }
        // identificacionOriginal is a raw string (may be non-numeric like "jesus_duplicate_5"), use as last resort
        val identificacionOriginal = user.personas?.identificacionOriginal?.takeIf { it.isNotBlank() && it != "0" }
        return cedula ?: identificacion ?: rootIdentificacion ?: identificacionOriginal ?: ""
    }

    private fun matchesCollaboratorQuery(user: Usuario, query: String): Boolean {
        if (query.isBlank()) return false
        val normalizedQuery = normalizeSearchText(query)
        val digitsQuery = normalizeSearchDigits(query)
        val fullName = listOfNotNull(user.personas?.nombres, user.personas?.apellidos)
            .joinToString(" ")
        val documentId = collaboratorDocument(user)

        // Búsqueda por texto (username, email, nombres, apellidos)
        val textMatches = listOf(user.usuario, user.email, fullName)
            .any { value ->
                val normalized = normalizeSearchText(value)
                normalized.isNotEmpty() && normalized.contains(normalizedQuery)
            }

        // Búsqueda por cédula/identificación (solo si el query contiene dígitos)
        val documentMatches = digitsQuery.isNotEmpty() && documentId.isNotEmpty() &&
            normalizeSearchDigits(documentId).let { it.isNotEmpty() && it.contains(digitsQuery) }

        // When personas is null (FK join failed in backend) but query is purely digits,
        // trust that the backend returned this user because it matched by identificacion.
        val personasNull = user.personas == null
        val queryIsPurelyDigits = digitsQuery.isNotEmpty() && normalizedQuery.all { it.isDigit() }
        val trustBackendMatch = personasNull && queryIsPurelyDigits

        Log.d("CollaboratorFilter", "Query=$query User=${user.usuario} Doc=$documentId " +
            "text=$textMatches doc=$documentMatches personasNull=$personasNull")
        return textMatches || documentMatches || trustBackendMatch
    }

    private fun filterCollaboratorCandidates(users: List<Usuario>): List<Usuario> {
        val currentUsername = sessionManager.getUsername()
        return users.filter { user -> user.usuario != currentUsername }
    }

    private fun setupCollaboratorSearch(view: View) {
        val searchEditText = view.findViewById<EditText>(R.id.collaboratorSearchEditText)
        val resultsRecyclerView = view.findViewById<RecyclerView>(R.id.collaboratorSearchResultsRecyclerView)
        val chipsContainer = view.findViewById<ChipGroup>(R.id.collaboratorChipsContainer)

        collaboratorSearchAdapter = CollaboratorSearchAdapter { user ->
            if (selectedCollaborators.none { it.id == user.id }) {
                handleCollaboratorSelection(user, chipsContainer)
            } else {
                selectedCollaborators.removeAll { it.id == user.id }
                removeCollaboratorChip(chipsContainer, user.id)
                collaboratorSearchAdapter.setSelectedIds(selectedCollaborators.map { it.id }.toSet())
            }
        }

        resultsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        resultsRecyclerView.adapter = collaboratorSearchAdapter

        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                searchJob?.cancel()
                if (query.isEmpty()) {
                    // Show default role 2 users when search is empty
                    if (role2UsersCache.isNotEmpty()) {
                        collaboratorSearchAdapter.submitList(role2UsersCache)
                        collaboratorSearchAdapter.setSelectedIds(selectedCollaborators.map { it.id }.toSet())
                        resultsRecyclerView.visibility = View.VISIBLE
                    } else {
                        collaboratorSearchAdapter.submitList(emptyList())
                        resultsRecyclerView.visibility = View.GONE
                    }
                    return
                }
                searchJob = CoroutineScope(Dispatchers.Main).launch {
                    delay(300)
                    // First filter from pre-loaded list
                    val localMatches = allUsersCache
                        .filter { user -> matchesCollaboratorQuery(user, query) }
                        .let { filterCollaboratorCandidates(it) }
                    if (localMatches.isNotEmpty()) {
                        collaboratorSearchAdapter.submitList(localMatches)
                        collaboratorSearchAdapter.setSelectedIds(selectedCollaborators.map { it.id }.toSet())
                        resultsRecyclerView.visibility = View.VISIBLE
                    } else {
                        // Fallback to API search
                        val result = withContext(Dispatchers.IO) {
                            BackendApiService.searchUsers(query)
                        }
                        if (result is ApiResult.Success) {
                            val filtered = filterCollaboratorCandidates(result.data)
                                .filter { user -> matchesCollaboratorQuery(user, query) }
                            if (filtered.isNotEmpty()) {
                                collaboratorSearchAdapter.submitList(filtered)
                                collaboratorSearchAdapter.setSelectedIds(selectedCollaborators.map { it.id }.toSet())
                                resultsRecyclerView.visibility = View.VISIBLE
                            } else {
                                collaboratorSearchAdapter.submitList(emptyList())
                                resultsRecyclerView.visibility = View.GONE
                            }
                        } else {
                            collaboratorSearchAdapter.submitList(emptyList())
                            resultsRecyclerView.visibility = View.GONE
                        }
                    }
                }
            }
        })
    }

    private fun addCollaboratorChip(chipGroup: ChipGroup, user: Usuario) {
        val chip = Chip(requireContext()).apply {
            text = user.usuario
            isCloseIconVisible = true
            tag = user.id
            setTextColor(Color.WHITE)
            chipBackgroundColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#1C1C1E"))
            closeIconTint = android.content.res.ColorStateList.valueOf(Color.parseColor("#8E8E93"))
            setOnCloseIconClickListener {
                selectedCollaborators.removeAll { it.id == user.id }
                chipGroup.removeView(this)
                if (chipGroup.childCount == 0) chipGroup.visibility = View.GONE
                collaboratorSearchAdapter.setSelectedIds(selectedCollaborators.map { it.id }.toSet())
            }
        }
        chipGroup.addView(chip)
        chipGroup.visibility = View.VISIBLE
    }

    private fun removeCollaboratorChip(chipGroup: ChipGroup, userId: Long) {
        for (i in 0 until chipGroup.childCount) {
            val chip = chipGroup.getChildAt(i) as? Chip
            if (chip?.tag == userId) {
                chipGroup.removeViewAt(i)
                break
            }
        }
        if (chipGroup.childCount == 0) chipGroup.visibility = View.GONE
    }

    private fun handleCollaboratorSelection(user: Usuario, chipsContainer: ChipGroup) {
        viewLifecycleOwner.lifecycleScope.launch {
            val rolesResult = withContext(Dispatchers.IO) {
                BackendApiService.getUserRoles(user.id)
            }
            val userRoles = (rolesResult as? ApiResult.Success)?.data ?: emptyList()
            // Accept users with role 2 (docente) OR role 3 (creador)
            val canBeCollaborator = userRoles.any { it == 2L || it == 3L }

            if (canBeCollaborator) {
                selectedCollaborators.add(user)
                addCollaboratorChip(chipsContainer, user)
                collaboratorSearchAdapter.setSelectedIds(selectedCollaborators.map { it.id }.toSet())
            } else {
                showPromoteToDocenteDialog(user, chipsContainer)
            }
        }
    }

    private fun showPromoteToDocenteDialog(user: Usuario, chipsContainer: ChipGroup) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_confirm_liquid_glass, null)
        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Asignar rol de docente"
        dialogView.findViewById<TextView>(R.id.dialogMessage).text =
            "El usuario \"${user.usuario}\" no tiene el rol de docente. ¿Deseas asignarle el rol de docente para agregarlo como colaborador?"

        dialogView.findViewById<TextView>(R.id.positiveButton).apply {
            text = "Aceptar"
            setOnClickListener {
                dialog.dismiss()
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        BackendApiService.promoteToDocente(user.id)
                    }
                    if (result is ApiResult.Success) {
                        selectedCollaborators.add(user)
                        addCollaboratorChip(chipsContainer, user)
                        collaboratorSearchAdapter.setSelectedIds(selectedCollaborators.map { it.id }.toSet())
                        Toast.makeText(context, "Rol de docente asignado a ${user.usuario}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Error al asignar el rol de docente", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        dialogView.findViewById<TextView>(R.id.negativeButton).apply {
            text = "Cancelar"
            setOnClickListener {
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    // ─── Guest search ────────────────────────────────────────────────────────

    private fun setupGuestSearch(view: View) {
        val searchEditText = view.findViewById<EditText>(R.id.guestSearchEditText) ?: return
        val resultsRecyclerView = view.findViewById<RecyclerView>(R.id.guestSearchResultsRecyclerView) ?: return
        val chipsContainer = view.findViewById<ChipGroup>(R.id.guestChipsContainer) ?: return

        guestSearchAdapter = CollaboratorSearchAdapter { user ->
            // Don't allow adding someone who is already a collaborator
            if (selectedCollaborators.any { it.id == user.id }) return@CollaboratorSearchAdapter
            if (selectedGuests.none { it.id == user.id }) {
                selectedGuests.add(user)
                addGuestChip(chipsContainer, user)
                guestSearchAdapter.setSelectedIds(selectedGuests.map { it.id }.toSet())
            } else {
                selectedGuests.removeAll { it.id == user.id }
                removeGuestChip(chipsContainer, user.id)
                guestSearchAdapter.setSelectedIds(selectedGuests.map { it.id }.toSet())
            }
        }

        resultsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        resultsRecyclerView.adapter = guestSearchAdapter

        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                guestSearchJob?.cancel()
                if (query.isEmpty()) {
                    // Show all users when search is empty
                    if (allUsersCache.isNotEmpty()) {
                        guestSearchAdapter.submitList(allUsersCache)
                        guestSearchAdapter.setSelectedIds(selectedGuests.map { it.id }.toSet())
                        resultsRecyclerView.visibility = View.VISIBLE
                    } else {
                        guestSearchAdapter.submitList(emptyList())
                        resultsRecyclerView.visibility = View.GONE
                    }
                    return
                }
                guestSearchJob = CoroutineScope(Dispatchers.Main).launch {
                    delay(300)
                    // First filter from pre-loaded list
                    val currentUser = sessionManager.getUsername()
                    val localMatches = allUsersCache.filter { user ->
                        user.usuario != currentUser && matchesCollaboratorQuery(user, query)
                    }
                    if (localMatches.isNotEmpty()) {
                        guestSearchAdapter.submitList(localMatches)
                        guestSearchAdapter.setSelectedIds(selectedGuests.map { it.id }.toSet())
                        resultsRecyclerView.visibility = View.VISIBLE
                    } else {
                        // Fallback to API search
                        val result = withContext(Dispatchers.IO) { BackendApiService.searchUsers(query) }
                        if (result is ApiResult.Success) {
                            val filtered = result.data.filter { it.usuario != currentUser }
                            if (filtered.isNotEmpty()) {
                                guestSearchAdapter.submitList(filtered)
                                guestSearchAdapter.setSelectedIds(selectedGuests.map { it.id }.toSet())
                                resultsRecyclerView.visibility = View.VISIBLE
                            } else {
                                guestSearchAdapter.submitList(emptyList())
                                resultsRecyclerView.visibility = View.GONE
                            }
                        } else {
                            guestSearchAdapter.submitList(emptyList())
                            resultsRecyclerView.visibility = View.GONE
                        }
                    }
                }
            }
        })
    }

    private fun addGuestChip(chipGroup: ChipGroup, user: Usuario) {
        val chip = Chip(requireContext()).apply {
            text = user.usuario
            isCloseIconVisible = true
            tag = user.id
            setTextColor(Color.WHITE)
            chipBackgroundColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#1A2A2A"))
            closeIconTint = android.content.res.ColorStateList.valueOf(Color.parseColor("#00B4A0"))
            setOnCloseIconClickListener {
                selectedGuests.removeAll { it.id == user.id }
                chipGroup.removeView(this)
                if (chipGroup.childCount == 0) chipGroup.visibility = View.GONE
                guestSearchAdapter.setSelectedIds(selectedGuests.map { it.id }.toSet())
            }
        }
        chipGroup.addView(chip)
        chipGroup.visibility = View.VISIBLE
    }

    private fun removeGuestChip(chipGroup: ChipGroup, userId: Long) {
        for (i in 0 until chipGroup.childCount) {
            val chip = chipGroup.getChildAt(i) as? Chip
            if (chip?.tag == userId) {
                chipGroup.removeViewAt(i)
                break
            }
        }
        if (chipGroup.childCount == 0) chipGroup.visibility = View.GONE
    }

    private fun setupToggleLogic(view: View) {
        val btnFree = view.findViewById<LinearLayout>(R.id.btnFree)
        val btnPaid = view.findViewById<LinearLayout>(R.id.btnPaid)
        
        btnFree.setOnClickListener { updateToggleState(false) }
        btnPaid.setOnClickListener { updateToggleState(true) }

        // Initial state
        updateToggleState(false)
    }

    private fun updateToggleState(paid: Boolean) {
        isPaidCourse = paid
        val view = view ?: return
        
        val btnFree = view.findViewById<LinearLayout>(R.id.btnFree)
        val btnPaid = view.findViewById<LinearLayout>(R.id.btnPaid)
        val priceContainer = view.findViewById<LinearLayout>(R.id.priceContainer)
        val iconFree = view.findViewById<ImageView>(R.id.iconFree)
        val textFree = view.findViewById<TextView>(R.id.textFree)
        val iconPaid = view.findViewById<ImageView>(R.id.iconPaid)
        val textPaid = view.findViewById<TextView>(R.id.textPaid)

        if (paid) {
            btnFree.setBackgroundResource(R.drawable.bg_toggle_card_unselected)
            iconFree.setColorFilter(Color.parseColor("#888888"))
            textFree.setTextColor(Color.parseColor("#888888"))

            btnPaid.setBackgroundResource(R.drawable.bg_toggle_card_selected)
            iconPaid.setColorFilter(Color.parseColor("#3EA6FF"))
            textPaid.setTextColor(Color.parseColor("#FFFFFF"))

            priceContainer.visibility = View.VISIBLE
        } else {
            btnFree.setBackgroundResource(R.drawable.bg_toggle_card_selected)
            iconFree.setColorFilter(Color.parseColor("#3EA6FF"))
            textFree.setTextColor(Color.parseColor("#FFFFFF"))

            btnPaid.setBackgroundResource(R.drawable.bg_toggle_card_unselected)
            iconPaid.setColorFilter(Color.parseColor("#888888"))
            textPaid.setTextColor(Color.parseColor("#888888"))

            priceContainer.visibility = View.GONE
        }
    }

    private fun setupCharacterCounter(view: View) {
        val titleEditText = view.findViewById<EditText>(R.id.courseNameEditText)
        val charCounter = view.findViewById<TextView>(R.id.titleCharCounter)
        
        titleEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val length = s?.length ?: 0
                charCounter.text = "$length/100"
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_THUMBNAIL_PICK && resultCode == Activity.RESULT_OK && data != null) {
            selectedThumbnailUri = data.data
            selectedThumbnailUri?.let { uri ->
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            view?.findViewById<ImageView>(R.id.courseThumbnailImageView)?.setImageURI(selectedThumbnailUri)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_THUMBNAIL_URI, selectedThumbnailUri?.toString())
    }

    private fun setLoading(loading: Boolean) {
        val progress = view?.findViewById<android.widget.ProgressBar>(R.id.savingProgressBar)
        val saveBtn = view?.findViewById<TextView>(R.id.saveButton)
        if (loading) {
            progress?.visibility = View.VISIBLE
            saveBtn?.isEnabled = false
            saveBtn?.alpha = 0.5f
        } else {
            progress?.visibility = View.GONE
            saveBtn?.isEnabled = true
            saveBtn?.alpha = 1f
        }
    }

    private fun loadCourseData(courseId: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    BackendApiService.getCourseById(courseId)
                }
                if (result is ApiResult.Success) {
                    val course = result.data
                    
                    view?.findViewById<EditText>(R.id.courseNameEditText)?.setText(course.title)

                    // Setear categoria en el Spinner
                    val categorySpinner = view?.findViewById<android.widget.Spinner>(R.id.courseCategorySpinner)
                    val categories = resources.getStringArray(R.array.course_categories)
                    val categoryIndex = categories.indexOfFirst { it.equals(course.category, ignoreCase = true) }
                    if (categoryIndex >= 0) categorySpinner?.setSelection(categoryIndex)

                    view?.findViewById<EditText>(R.id.courseDescriptionEditText)?.setText(course.description)
                    
                    updateToggleState(course.isPremium)
                    if (course.isPremium) {
                        view?.findViewById<EditText>(R.id.coursePriceEditText)?.setText(course.price.toString())
                    }
                    view?.findViewById<Switch>(R.id.courseActiveSwitch)?.isChecked = course.isActive

                    // Load deadline if present
                    val deadlineStr = course.deadline
                    if (!deadlineStr.isNullOrEmpty()) {
                        try {
                            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).also { it.timeZone = TimeZone.getTimeZone("UTC") }
                            val date = sdf.parse(deadlineStr.take(19))
                            if (date != null) {
                                deadlineMillis = date.time
                                view?.findViewById<Switch>(R.id.deadlineSwitch)?.isChecked = true
                                view?.findViewById<LinearLayout>(R.id.deadlinePickerContainer)?.visibility = View.VISIBLE
                                val display = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(date)
                                view?.findViewById<TextView>(R.id.deadlineTextView)?.apply {
                                    text = display
                                    setTextColor(Color.WHITE)
                                }
                                view?.let { updateActiveHint(it) }
                            }
                        } catch (e: Exception) {
                            Log.w("CourseCreationFragment", "Could not parse deadline: $deadlineStr", e)
                        }
                    }
                    
                    if (!course.thumbnailUri.isNullOrEmpty()) {
                        selectedThumbnailUri = Uri.parse(course.thumbnailUri)
                        val imageView = view?.findViewById<ImageView>(R.id.courseThumbnailImageView)
                        if (imageView != null) {
                            Glide.with(this@CourseCreationFragment)
                                .load(course.thumbnailUri)
                                .placeholder(R.drawable.ic_image_placeholder)
                                .into(imageView)
                        }
                    }
                } else if (result is ApiResult.Error) {
                    Log.e("CourseCreationFragment", "Error loading course: ${result.message}")
                    Toast.makeText(context, "Error al cargar datos del curso", Toast.LENGTH_SHORT).show()
                }

                val collabResult = withContext(Dispatchers.IO) {
                    BackendApiService.getCollaboratorsByCourse(courseId)
                }
                if (collabResult is ApiResult.Success) {
                    val chipsContainer = view?.findViewById<ChipGroup>(R.id.collaboratorChipsContainer)
                    val arr = collabResult.data
                    for (i in 0 until arr.size()) {
                        val obj = arr[i].asJsonObject
                        val userObj = obj.getAsJsonObject("user") ?: continue
                        val userId = userObj.get("id")?.asLong ?: continue
                        val username = userObj.get("username")?.asString ?: ""
                        val email = userObj.get("email")?.asString ?: ""
                        val avatar = userObj.get("avatar")?.let { if (it.isJsonNull) null else it.asString }
                        val user = Usuario(id = userId, usuario = username, email = email, avatar = avatar)
                        if (selectedCollaborators.none { it.id == user.id }) {
                            selectedCollaborators.add(user)
                            if (chipsContainer != null) addCollaboratorChip(chipsContainer, user)
                        }
                    }
                    collaboratorSearchAdapter.setSelectedIds(selectedCollaborators.map { it.id }.toSet())
                    originalCollaboratorIds.clear()
                    originalCollaboratorIds.addAll(selectedCollaborators.map { it.id })
                }

                // Load existing guests
                val guestResult = withContext(Dispatchers.IO) {
                    BackendApiService.getCourseGuests(courseId)
                }
                if (guestResult is ApiResult.Success) {
                    val chipsContainer = view?.findViewById<ChipGroup>(R.id.guestChipsContainer)
                    val arr = guestResult.data
                    for (i in 0 until arr.size()) {
                        val obj = arr[i].asJsonObject
                        val userId = obj.get("userId")?.asLong ?: obj.get("user_id")?.asLong ?: continue
                        // Skip users already loaded as collaborators
                        if (selectedCollaborators.any { it.id == userId }) continue
                        val username = obj.get("username")?.let { if (it.isJsonNull) null else it.asString } ?: "user_$userId"
                        val avatar = obj.get("avatar")?.let { if (it.isJsonNull) null else it.asString }
                        val user = Usuario(id = userId, usuario = username, email = "", avatar = avatar)
                        if (selectedGuests.none { it.id == user.id }) {
                            selectedGuests.add(user)
                            if (chipsContainer != null) addGuestChip(chipsContainer, user)
                        }
                    }
                    guestSearchAdapter.setSelectedIds(selectedGuests.map { it.id }.toSet())
                }
            } catch (e: Exception) {
                Log.e("CourseCreationFragment", "Error loading course data", e)
                Toast.makeText(context, "Error al cargar datos del curso", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveCourse() {
        val courseName = view?.findViewById<EditText>(R.id.courseNameEditText)?.text.toString()
        val courseCategory = view?.findViewById<android.widget.Spinner>(R.id.courseCategorySpinner)?.selectedItem?.toString() ?: ""
        val courseDescription = view?.findViewById<EditText>(R.id.courseDescriptionEditText)?.text.toString()
        
        val coursePrice = if (isPaidCourse) {
            view?.findViewById<EditText>(R.id.coursePriceEditText)?.text.toString().toDoubleOrNull() ?: 0.0
        } else {
            0.0
        }

        if (courseName.length < 3) {
            Toast.makeText(context, "El título debe tener al menos 3 caracteres", Toast.LENGTH_SHORT).show()
            return
        }
        
        val currentUsername = sessionManager.getUsername()
        if (currentUsername == null) {
            Toast.makeText(context, "Error: Usuario no autenticado. No se puede crear el curso.", Toast.LENGTH_LONG).show()
            return
        }

        setLoading(true)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val thumbnailDeferred = async(Dispatchers.IO) {
                    val uri = selectedThumbnailUri
                    val isRemote = uri?.scheme?.startsWith("http") == true
                    if (uri != null && !isRemote && StorageHelper.isConfigured()) {
                        val result = StorageHelper.uploadFile(
                            context = requireContext(),
                            fileUri = uri,
                            folder = "thumbnails/courses",
                            customFileName = "course_${System.currentTimeMillis()}"
                        )
                        (result as? StorageHelper.UploadResult.Success)?.url
                    } else null
                }

                if (isEditing) {
                    val thumbnailUrl = thumbnailDeferred.await()
                    val thumbnailUriString = thumbnailUrl ?: selectedThumbnailUri?.toString()

                    val updates = mapOf<String, Any?>(
                        "title" to courseName,
                        "description" to courseDescription,
                        "category" to courseCategory,
                        "price" to coursePrice,
                        "isFree" to !isPaidCourse,
                        "isActive" to resolveEffectiveIsActive(view),
                        "thumbnailUri" to thumbnailUriString,
                        "deadline" to deadlineMillis?.let {
                            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                                .also { sdf -> sdf.timeZone = TimeZone.getTimeZone("UTC") }
                                .format(Date(it))
                        }
                    )

                    val updateResult = withContext(Dispatchers.IO) {
                        BackendApiService.updateCourse(currentCourseId, updates)
                    }

                    val currentCollabIds = selectedCollaborators.map { it.id }.toSet()
                    if (currentCollabIds != originalCollaboratorIds) {
                        val syncResult = withContext(Dispatchers.IO) {
                            BackendApiService.syncCollaborators(currentCourseId, currentCollabIds.toList())
                        }
                        if (syncResult is ApiResult.Error) {
                            Log.w("CourseCreationFragment", "Sync collaborators failed: ${syncResult.message}")
                        }
                    }

                    if (selectedGuests.isNotEmpty()) {
                        val inviteResult = withContext(Dispatchers.IO) {
                            BackendApiService.inviteCourseGuests(currentCourseId, selectedGuests.map { it.id })
                        }
                        if (inviteResult is ApiResult.Error) {
                            Log.w("CourseCreationFragment", "Invite guests failed: ${inviteResult.message}")
                        }
                    }

                    com.example.tareamov.util.AppCache.invalidateCourses()
                    setLoading(false)
                    when (updateResult) {
                        is ApiResult.Success -> {
                            Toast.makeText(context, "Curso actualizado exitosamente", Toast.LENGTH_SHORT).show()
                            findNavController().navigateUp()
                        }
                        is ApiResult.Error -> {
                            Toast.makeText(context, "Error al actualizar el curso: ${updateResult.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    val searchDeferred = async(Dispatchers.IO) {
                        BackendApiService.searchCourses(courseName)
                    }

                    val searchResult = searchDeferred.await()
                    val titleExists = (searchResult as? ApiResult.Success)?.data?.any {
                        it.title.equals(courseName, ignoreCase = true)
                    } == true

                    if (titleExists) {
                        setLoading(false)
                        Toast.makeText(context, "Ya existe un curso con este título. Elige otro título.", Toast.LENGTH_LONG).show()
                        return@launch
                    }

                    val thumbnailUrl = thumbnailDeferred.await()
                    val thumbnailUriString = thumbnailUrl ?: selectedThumbnailUri?.toString()

                    val deadlineIso = deadlineMillis?.let {
                        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                            .also { sdf -> sdf.timeZone = TimeZone.getTimeZone("UTC") }
                            .format(Date(it))
                    }

                    val payload = mapOf(
                        "title" to courseName,
                        "description" to courseDescription,
                        "category" to courseCategory,
                        "price" to coursePrice,
                        "creatorUsername" to currentUsername,
                        "isFree" to !isPaidCourse,
                        "isActive" to resolveEffectiveIsActive(view),
                        "thumbnailUri" to thumbnailUriString,
                        "deadline" to deadlineIso
                    )

                    val createResult = withContext(Dispatchers.IO) {
                        BackendApiService.createCourse(payload)
                    }

                    com.example.tareamov.util.AppCache.invalidateCourses()
                    setLoading(false)
                    when (createResult) {
                        is ApiResult.Success -> {
                            val createdCourse = createResult.data
                            currentCourseId = createdCourse.id
                            courseSaved = true

                            if (selectedCollaborators.isNotEmpty()) {
                                val syncResult = withContext(Dispatchers.IO) {
                                    BackendApiService.syncCollaborators(createdCourse.id, selectedCollaborators.map { it.id })
                                }
                                if (syncResult is ApiResult.Error) {
                                    Log.w("CourseCreationFragment", "Sync collaborators failed: ${syncResult.message}")
                                    Toast.makeText(context, "Colaboradores no sincronizados: ${syncResult.message}", Toast.LENGTH_SHORT).show()
                                }
                            }

                            if (selectedGuests.isNotEmpty()) {
                                val inviteResult = withContext(Dispatchers.IO) {
                                    BackendApiService.inviteCourseGuests(createdCourse.id, selectedGuests.map { it.id })
                                }
                                if (inviteResult is ApiResult.Error) {
                                    Log.w("CourseCreationFragment", "Invite guests failed: ${inviteResult.message}")
                                }
                            }

                            val bundle = Bundle().apply {
                                putLong("courseId", createdCourse.id)
                                putString("courseName", courseName)
                            }
                            findNavController().navigate(R.id.action_courseCreationFragment_to_subjectCreationFragment, bundle)
                        }
                        is ApiResult.Error -> {
                            Toast.makeText(context, "Error al guardar el curso: ${createResult.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                setLoading(false)
                Log.e("CourseCreationFragment", "Error saving course", e)
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addNewTopic() {
        val courseName = view?.findViewById<EditText>(R.id.courseNameEditText)?.text.toString()
        if (courseName.length < 3) {
            Toast.makeText(context, "El título debe tener al menos 3 caracteres", Toast.LENGTH_SHORT).show()
            return
        }

        val courseCategory = view?.findViewById<android.widget.Spinner>(R.id.courseCategorySpinner)?.selectedItem?.toString() ?: ""
        val courseDescription = view?.findViewById<EditText>(R.id.courseDescriptionEditText)?.text.toString()
        
        val coursePrice = if (isPaidCourse) {
            view?.findViewById<EditText>(R.id.coursePriceEditText)?.text.toString().toDoubleOrNull() ?: 0.0
        } else {
            0.0
        }

        val currentUserUsername = sessionManager.getUsername()
        if (currentUserUsername == null) {
            Toast.makeText(context, "Error: Usuario no autenticado. No se puede agregar tema.", Toast.LENGTH_LONG).show()
            return
        }

        setLoading(true)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val userDeferred = async(Dispatchers.IO) {
                    BackendApiService.getUserByUsername(currentUserUsername)
                }
                val searchDeferred = async(Dispatchers.IO) {
                    BackendApiService.searchCourses(courseName)
                }
                val thumbnailDeferred = async(Dispatchers.IO) {
                    val uri = selectedThumbnailUri
                    val isRemote = uri?.scheme?.startsWith("http") == true
                    if (uri != null && !isRemote && StorageHelper.isConfigured()) {
                        val result = StorageHelper.uploadFile(
                            context = requireContext(),
                            fileUri = uri,
                            folder = "thumbnails/courses",
                            customFileName = "course_${System.currentTimeMillis()}"
                        )
                        (result as? StorageHelper.UploadResult.Success)?.url
                    } else null
                }

                val userResult = userDeferred.await()
                val userId = (userResult as? ApiResult.Success)?.data?.id ?: 0L
                if (userId <= 0) {
                    setLoading(false)
                    Toast.makeText(context, "Error: No se pudo obtener el ID del usuario", Toast.LENGTH_LONG).show()
                    return@launch
                }

                val searchResult = searchDeferred.await()
                val titleExists = (searchResult as? ApiResult.Success)?.data?.any {
                    it.title.equals(courseName, ignoreCase = true)
                } == true

                if (titleExists) {
                    setLoading(false)
                    Toast.makeText(context, "Ya existe un curso con este título. Elige otro título.", Toast.LENGTH_LONG).show()
                    return@launch
                }

                val thumbnailUrl = thumbnailDeferred.await()
                val thumbnailUriString = thumbnailUrl ?: selectedThumbnailUri?.toString()

                val deadlineIsoTopic = deadlineMillis?.let {
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                        .also { sdf -> sdf.timeZone = TimeZone.getTimeZone("UTC") }
                        .format(Date(it))
                }

                val payload = mapOf(
                    "title" to courseName,
                    "description" to courseDescription,
                    "category" to courseCategory,
                    "price" to coursePrice,
                    "creatorUsername" to currentUserUsername,
                    "isFree" to !isPaidCourse,
                    "isActive" to resolveEffectiveIsActive(view),
                    "thumbnailUri" to thumbnailUriString,
                    "deadline" to deadlineIsoTopic
                )

                val createResult = withContext(Dispatchers.IO) {
                    BackendApiService.createCourse(payload)
                }

                com.example.tareamov.util.AppCache.invalidateCourses()
                when (createResult) {
                    is ApiResult.Success -> {
                        val createdCourse = createResult.data
                        currentCourseId = createdCourse.id
                        courseSaved = true
                        topicCount++

                        async(Dispatchers.IO) {
                            BackendApiService.promoteToDocente(userId)
                        }

                        if (selectedCollaborators.isNotEmpty()) {
                            val syncResult = withContext(Dispatchers.IO) {
                                BackendApiService.syncCollaborators(createdCourse.id, selectedCollaborators.map { it.id })
                            }
                            if (syncResult is ApiResult.Error) {
                                Log.w("CourseCreationFragment", "Sync collaborators failed: ${syncResult.message}")
                                Toast.makeText(context, "Colaboradores no sincronizados: ${syncResult.message}", Toast.LENGTH_SHORT).show()
                            }
                        }

                        setLoading(false)
                        val bundle = Bundle().apply {
                            putInt("topicNumber", topicCount)
                            putLong("courseId", createdCourse.id)
                            putString("courseName", courseName)
                        }
                        findNavController().navigate(R.id.action_courseCreationFragment_to_courseTopicFragment, bundle)
                    }
                    is ApiResult.Error -> {
                        setLoading(false)
                        Toast.makeText(context, "Error al guardar el curso: ${createResult.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                setLoading(false)
                Log.e("CourseCreationFragment", "Error saving course", e)
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}