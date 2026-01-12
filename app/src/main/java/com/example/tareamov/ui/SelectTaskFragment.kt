package com.example.tareamov.ui

import android.animation.ObjectAnimator
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.tareamov.R
import com.example.tareamov.adapter.TaskSelectionAdapter
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.data.entity.Task
import com.example.tareamov.data.sync.SyncRepository
import com.example.tareamov.service.SupabaseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SelectTaskFragment : Fragment() {

    private var courseId: Long = -1L
    private var topicId: Long = -1L
    private var courseName: String? = null
    private lateinit var tasksRecyclerView: RecyclerView
    private lateinit var taskSelectionAdapter: TaskSelectionAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            courseId = it.getLong("courseId", -1L)
            topicId = it.getLong("topicId", -1L)
            courseName = it.getString("courseName")
            Log.d("SelectTaskFragment", "Received courseId=$courseId topicId=$topicId")
        }
        if (courseId == -1L) {
            Log.e("SelectTaskFragment", "Invalid courseId received.")
            Toast.makeText(context, "Error: ID de curso inválido", Toast.LENGTH_SHORT).show()
            findNavController().navigateUp()
            return
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate view and wrap in FrameLayout with FAB for TTS
        val originalView = inflater.inflate(R.layout.fragment_select_task, container, false)
        val context = requireContext()
        val ttsService = com.example.tareamov.service.TTSService.getInstance(context)

        val wrapper = android.widget.FrameLayout(context)
        wrapper.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        wrapper.addView(originalView, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val fab = com.google.android.material.floatingactionbutton.FloatingActionButton(context)
        fab.setImageDrawable(androidx.core.content.ContextCompat.getDrawable(context, android.R.drawable.ic_lock_silent_mode_off))
        fab.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#40C4FF"))
        fab.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
        fab.compatElevation = 10f
        
        val params = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
        val margin = (16 * resources.displayMetrics.density).toInt()
        params.setMargins(margin, margin, margin, margin)
        
        fab.setOnClickListener {
             lifecycleScope.launch {
                 val tasks = if (this@SelectTaskFragment::taskSelectionAdapter.isInitialized) taskSelectionAdapter.currentList else emptyList()
                 val text = "Lista de tareas para elegir: " + if(tasks.isEmpty()) "No hay tareas disponibles." else tasks.joinToString(", ") { it.name }
                 ttsService.speak(text)
             }
        }
        
        wrapper.addView(fab, params)

        return wrapper
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val backButton = view.findViewById<ImageButton>(R.id.backButton)
        val titleText = view.findViewById<TextView>(R.id.selectTaskCourseTitleDetail)
        val addTaskButton = view.findViewById<LinearLayout>(R.id.addTaskButton)
        val topBarCard = view.findViewById<CardView>(R.id.topBarCard)
        val courseTitleCard = view.findViewById<CardView>(R.id.courseTitleCard)
        val emptyStateLayout = view.findViewById<LinearLayout>(R.id.emptyStateLayout)
        val noTasksSelectionTextView = view.findViewById<TextView>(R.id.noTasksSelectionTextView)

        // Set course title
        titleText?.text = courseName ?: "Curso"

        // Back button with haptic feedback and animation
        backButton?.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            animateButtonPress(it)
            findNavController().navigateUp()
        }

        // Apply entrance animations similar to SelectTopicFragment
        applyEntranceAnimations(topBarCard, courseTitleCard, titleText, /* placeholder for spacing */ null)

        // Setup RecyclerView (layout includes it)
        val maybeRecycler = view.findViewById<RecyclerView>(R.id.tasksRecyclerView)
        tasksRecyclerView = maybeRecycler
        setupRecyclerView()


        // Header/title
        titleText?.text = "Selecciona una tarea"

        // Hide addTask button (selection-only screen)
        addTaskButton?.let { it.visibility = View.GONE }

        // Ensure empty state hidden initially
        emptyStateLayout?.visibility = View.GONE

        // Load tasks from DB and render as polished card items
        lifecycleScope.launch {
            try {
                val db = AppDatabase.getDatabase(requireContext())
                val syncRepo = SyncRepository(
                    db.usuarioDao(), db.personaDao(), db.topicDao(), db.contentItemDao(), db.taskDao(),
                    db.subscriptionDao(), db.taskSubmissionDao(), db.videoDao(), db.courseDao(), db.rolDao(),
                    db.recursoDao(), db.rolRecursoDao(), db.chatMessageDao(), db.fileContextDao(), db.progresoEstudianteDao()
                )

                var tasks: List<Task> = emptyList()

                // Try fetching from Supabase first if configured
                if (SupabaseClient.isConfigured()) {
                    try {
                        tasks = withContext(Dispatchers.IO) {
                            syncRepo.fetchTasksByTopicIdsFromSupabase(listOf(topicId))
                        }
                        Log.d("SelectTaskFragment", "Fetched ${tasks.size} tasks from Supabase")
                    } catch (e: Exception) {
                        Log.w("SelectTaskFragment", "Failed to fetch tasks from Supabase", e)
                    }
                }

                // Fallback to local DB if Supabase failed or returned empty
                if (tasks.isEmpty()) {
                    tasks = withContext(Dispatchers.IO) {
                        db.taskDao().getTasksByTopic(topicId)
                    }
                    Log.d("SelectTaskFragment", "Fetched ${tasks.size} tasks from local DB")
                }

                if (tasks.isNullOrEmpty()) {
                    // show empty state
                    emptyStateLayout?.visibility = View.VISIBLE
                    noTasksSelectionTextView?.visibility = View.VISIBLE
                    animateFadeIn(emptyStateLayout)
                    tasksRecyclerView.visibility = View.GONE
                } else {
                    emptyStateLayout?.visibility = View.GONE
                    // submit to adapter
                    if (!this@SelectTaskFragment::taskSelectionAdapter.isInitialized) {
                        setupRecyclerView()
                    }
                    taskSelectionAdapter.submitList(tasks)
                    tasksRecyclerView.visibility = View.VISIBLE
                    animateFadeIn(tasksRecyclerView)
                }

            } catch (e: Exception) {
                Log.e("SelectTaskFragment", "Error cargando tareas", e)
                Toast.makeText(requireContext(), "Error al cargar tareas", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupRecyclerView() {
        taskSelectionAdapter = TaskSelectionAdapter { selectedTask ->
            Log.d("SelectTaskFragment", "Task selected: ID=${selectedTask.id}, Name=${selectedTask.name}")
            val bundle = Bundle().apply {
                putLong("courseId", courseId)
                putString("courseName", courseName)
                putLong("topicId", topicId)
                putLong("taskId", selectedTask.id)
            }
            try {
                findNavController().navigate(R.id.action_selectTaskFragment_to_reinforcementLearningFragment, bundle)
            } catch (ex: Exception) {
                Log.w("SelectTaskFragment", "Navigation failed", ex)
                Toast.makeText(requireContext(), "Tarea seleccionada: ${selectedTask.name}", Toast.LENGTH_SHORT).show()
            }
        }

        tasksRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = taskSelectionAdapter
            isNestedScrollingEnabled = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
    }

    // ============ ANIMATION FUNCTIONS (copied from SelectTopicFragment for identical aesthetics) ============
    private fun applyEntranceAnimations(vararg views: View?) {
        views.forEachIndexed { index, view ->
            view?.let {
                it.alpha = 0f
                it.translationY = 40f

                it.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(600)
                    .setStartDelay((index * 80).toLong())
                    .setInterpolator(DecelerateInterpolator(1.5f))
                    .start()
            }
        }
    }

    private fun animateFadeIn(view: View?) {
        view?.let {
            if (it.visibility == View.VISIBLE) return

            it.visibility = View.VISIBLE
            it.alpha = 0f
            it.scaleX = 0.95f
            it.scaleY = 0.95f

            it.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(400)
                .setInterpolator(OvershootInterpolator(0.5f))
                .start()
        }
    }

    private fun animateFadeOut(view: View?) {
        view?.let {
            if (it.visibility == View.GONE) return

            it.animate()
                .alpha(0f)
                .scaleX(0.95f)
                .scaleY(0.95f)
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    it.visibility = View.GONE
                }
                .start()
        }
    }

    private fun animateButtonPress(view: View) {
        view.animate()
            .scaleX(0.92f)
            .scaleY(0.92f)
            .setDuration(100)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(100)
                    .start()
            }
            .start()
    }
}
