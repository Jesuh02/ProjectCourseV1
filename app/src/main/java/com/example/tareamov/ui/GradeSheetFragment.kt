package com.example.tareamov.ui

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.tareamov.R
import com.example.tareamov.service.ApiResult
import com.example.tareamov.service.BackendApiService
import com.example.tareamov.util.SessionManager
import com.google.gson.JsonObject
import kotlinx.coroutines.launch

class GradeSheetFragment : Fragment() {

    companion object {
        private const val TAG = "GradeSheetFragment"
    }

    private var courseId: Long = -1
    private var subjectId: Long = -1
    private var subjectName: String? = null

    // Views
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var emptyMessage: TextView
    private lateinit var horizontalScroll: HorizontalScrollView
    private lateinit var headerRow: LinearLayout
    private lateinit var dataRowsContainer: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var bulkBar: LinearLayout
    private lateinit var bulkTypeSpinner: Spinner
    private lateinit var bulkGradeInput: EditText
    private lateinit var btnApplyBulk: Button
    private lateinit var saveFooter: LinearLayout
    private lateinit var dirtyCount: TextView
    private lateinit var btnSave: Button

    // Data
    private data class StudentEntry(
        val userId: Long,
        val username: String?,
        val avatar: String?,
        val fullName: String?,
        val cedula: String?
    )

    private data class TaskEntry(
        val id: Long,
        val title: String,
        val topicId: Long
    )

    private data class TaskGradeEntry(
        val taskId: Long,
        val studentId: Long,
        val grade: Float?,
        val submissionId: Long
    )

    private data class ManualGradeEntry(
        val studentId: Long,
        val gradeType: String,
        val grade: Float
    )

    private var students = mutableListOf<StudentEntry>()
    private var tasks = mutableListOf<TaskEntry>()
    private var taskGradesList = mutableListOf<TaskGradeEntry>()
    private var manualGradesList = mutableListOf<ManualGradeEntry>()

    // Editable grades: key = "$userId-$gradeType"
    private val editedGrades = mutableMapOf<String, Float?>()
    private val originalGrades = mutableMapOf<String, Float?>()

    // Editable task grades: key = "$studentId-$taskId"
    private val editedTaskGrades = mutableMapOf<String, Float?>()
    private val originalTaskGrades = mutableMapOf<String, Float?>()

    private val gradeTypes = mutableListOf("comportamiento", "participacion", "examenes")
    private val gradeTypeLabels = mutableListOf("🤝 Com", "🙋 Par", "📝 Exa")
    private val baseGradeTypes = listOf("comportamiento", "participacion", "examenes")

    // Column widths in dp
    private val studentColWidth = 160
    private val gradeColWidth = 80
    private val addSlotColWidth = 36
    private val removeSlotColWidth = 36
    private val avgColWidth = 80

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        courseId = arguments?.getLong("courseId", -1) ?: -1
        subjectId = arguments?.getLong("subjectId", -1) ?: -1
        subjectName = arguments?.getString("subjectName")
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_grade_sheet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        loadingSpinner = view.findViewById(R.id.loadingSpinner)
        emptyMessage = view.findViewById(R.id.emptyMessage)
        horizontalScroll = view.findViewById(R.id.horizontalScroll)
        headerRow = view.findViewById(R.id.headerRow)
        dataRowsContainer = view.findViewById(R.id.dataRowsContainer)
        searchInput = view.findViewById(R.id.searchInput)
        bulkBar = view.findViewById(R.id.bulkBar)
        bulkTypeSpinner = view.findViewById(R.id.bulkTypeSpinner)
        bulkGradeInput = view.findViewById(R.id.bulkGradeInput)
        btnApplyBulk = view.findViewById(R.id.btnApplyBulk)
        saveFooter = view.findViewById(R.id.saveFooter)
        dirtyCount = view.findViewById(R.id.dirtyCount)
        btnSave = view.findViewById(R.id.btnSave)

        view.findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            findNavController().popBackStack()
        }

        // Setup bulk type spinner
        updateBulkSpinner()

        btnApplyBulk.setOnClickListener { applyBulkGrade() }
        btnSave.setOnClickListener { saveGrades() }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { renderDataRows() }
        })

        loadGradeSheet()
    }

    private fun loadGradeSheet() {
        loadingSpinner.visibility = View.VISIBLE
        emptyMessage.visibility = View.GONE
        horizontalScroll.visibility = View.GONE
        bulkBar.visibility = View.GONE
        saveFooter.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                BackendApiService.initialize(requireContext())
                val result = BackendApiService.getGradeSheet(subjectId)

                if (result is ApiResult.Success<*>) {
                    val data = (result.data as? com.google.gson.JsonObject)
                    if (data != null) {
                        parseSheetData(data)
                        discoverGradeSlots()
                        renderSheet()
                    } else {
                        showEmpty()
                    }
                } else {
                    showEmpty()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading grade sheet", e)
                showEmpty()
            }
        }
    }

    private fun parseSheetData(data: JsonObject) {
        students.clear()
        tasks.clear()
        taskGradesList.clear()
        manualGradesList.clear()
        editedGrades.clear()
        originalGrades.clear()

        // Parse students
        val studentsArr = data.getAsJsonArray("students")
        studentsArr?.forEach { elem ->
            val obj = elem.asJsonObject
            students.add(StudentEntry(
                userId = obj.get("userId")?.asLong ?: 0,
                username = obj.get("username")?.takeIf { !it.isJsonNull }?.asString,
                avatar = obj.get("avatar")?.takeIf { !it.isJsonNull }?.asString,
                fullName = obj.get("fullName")?.takeIf { !it.isJsonNull }?.asString,
                cedula = obj.get("cedula")?.takeIf { !it.isJsonNull }?.asString,
            ))
        }

        // Parse tasks
        val tasksArr = data.getAsJsonArray("tasks")
        tasksArr?.forEach { elem ->
            val obj = elem.asJsonObject
            tasks.add(TaskEntry(
                id = obj.get("id")?.asLong ?: 0,
                title = obj.get("title")?.asString ?: "(Sin título)",
                topicId = obj.get("topicId")?.asLong ?: 0
            ))
        }

        // Parse task grades
        val taskGradesArr = data.getAsJsonArray("taskGrades")
        taskGradesArr?.forEach { elem ->
            val obj = elem.asJsonObject
            val entry = TaskGradeEntry(
                taskId = obj.get("taskId")?.asLong ?: 0,
                studentId = obj.get("studentId")?.asLong ?: 0,
                grade = if (obj.get("grade")?.isJsonNull == true) null else obj.get("grade")?.asFloat,
                submissionId = obj.get("submissionId")?.asLong ?: 0
            )
            taskGradesList.add(entry)
            if (entry.grade != null) {
                val key = "${entry.studentId}-${entry.taskId}"
                editedTaskGrades[key] = entry.grade
                originalTaskGrades[key] = entry.grade
            }
        }

        // Parse manual grades
        val manualArr = data.getAsJsonArray("manualGrades")
        manualArr?.forEach { elem ->
            val obj = elem.asJsonObject
            val studentId = obj.get("studentId")?.asLong ?: 0
            val gradeType = obj.get("gradeType")?.asString ?: ""
            val grade = obj.get("grade")?.asFloat ?: 0f

            manualGradesList.add(ManualGradeEntry(studentId, gradeType, grade))
            val key = "${studentId}-${gradeType}"
            editedGrades[key] = grade
            originalGrades[key] = grade
        }
    }

    private fun showEmpty() {
        loadingSpinner.visibility = View.GONE
        emptyMessage.visibility = View.VISIBLE
        horizontalScroll.visibility = View.GONE
    }

    private fun renderSheet() {
        loadingSpinner.visibility = View.GONE

        if (students.isEmpty()) {
            showEmpty()
            return
        }

        horizontalScroll.visibility = View.VISIBLE
        bulkBar.visibility = View.VISIBLE
        emptyMessage.visibility = View.GONE

        updateBulkSpinner()
        renderHeader()
        renderDataRows()
    }

    private fun renderHeader() {
        headerRow.removeAllViews()

        // Student column header
        headerRow.addView(createHeaderCell("Estudiante", studentColWidth))

        // Manual grade type columns grouped by base type, with "+" and "−" buttons after each group
        for (base in baseGradeTypes) {
            val slotCount = gradeTypes.count { it == base || it.startsWith("${base}_") }
            for (i in gradeTypes.indices) {
                if (gradeTypes[i] == base || gradeTypes[i].startsWith("${base}_")) {
                    headerRow.addView(createHeaderCell(gradeTypeLabels[i], gradeColWidth))
                }
            }
            headerRow.addView(createAddSlotButton(base))
            if (slotCount > 1) {
                headerRow.addView(createRemoveSlotButton(base))
            }
        }

        // Task columns
        for (task in tasks) {
            val truncated = if (task.title.length > 10) task.title.take(10) + "…" else task.title
            headerRow.addView(createHeaderCell("📋 $truncated", gradeColWidth + 10))
        }

        // Average column
        headerRow.addView(createHeaderCell("Promedio", avgColWidth))
    }

    private fun createHeaderCell(text: String, widthDp: Int): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            setTextColor(Color.parseColor("#BF5AF2"))
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dpToPx(6), dpToPx(8), dpToPx(6), dpToPx(8))
            layoutParams = LinearLayout.LayoutParams(dpToPx(widthDp), LinearLayout.LayoutParams.MATCH_PARENT)
        }
    }

    private fun getFilteredStudents(): List<StudentEntry> {
        val query = searchInput.text?.toString()?.trim()?.lowercase() ?: ""
        if (query.isEmpty()) return students
        return students.filter { s ->
            val name = s.fullName?.lowercase() ?: ""
            val uname = s.username?.lowercase() ?: ""
            val ced = s.cedula?.lowercase() ?: ""
            name.contains(query) || uname.contains(query) || ced.contains(query)
        }
    }

    private fun renderDataRows() {
        dataRowsContainer.removeAllViews()
        val filtered = getFilteredStudents()

        for (student in filtered) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dpToPx(48)
                )
            }

            // Student name cell
            val studentCell = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
                layoutParams = LinearLayout.LayoutParams(dpToPx(studentColWidth), LinearLayout.LayoutParams.MATCH_PARENT)
            }

            val nameText = TextView(requireContext()).apply {
                text = student.fullName ?: student.username ?: "—"
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            studentCell.addView(nameText)

            val usernameText = TextView(requireContext()).apply {
                text = "@${student.username ?: ""}"
                setTextColor(Color.parseColor("#8E8E93"))
                textSize = 10f
                maxLines = 1
            }
            studentCell.addView(usernameText)
            row.addView(studentCell)

            // Manual grade columns (editable), grouped by base type with spacers for "+" buttons
            for (base in baseGradeTypes) {
                for (i in gradeTypes.indices) {
                    if (gradeTypes[i] == base || gradeTypes[i].startsWith("${base}_")) {
                        val gradeType = gradeTypes[i]
                        val key = "${student.userId}-${gradeType}"
                        val input = EditText(requireContext()).apply {
                            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                            gravity = Gravity.CENTER
                            setTextColor(Color.WHITE)
                            textSize = 13f
                            typeface = Typeface.DEFAULT_BOLD
                            setBackgroundColor(Color.parseColor("#0AFFFFFF"))
                            setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
                            layoutParams = LinearLayout.LayoutParams(dpToPx(gradeColWidth), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                                setMargins(dpToPx(1), dpToPx(2), dpToPx(1), dpToPx(2))
                            }
                            hint = "—"
                            setHintTextColor(Color.parseColor("#555555"))
                            val current = editedGrades[key]
                            setText(if (current != null) current.toString() else "")

                            addTextChangedListener(object : TextWatcher {
                                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                                override fun afterTextChanged(s: Editable?) {
                                    val value = s?.toString()?.toFloatOrNull()
                                    if (value != null) {
                                        editedGrades[key] = value.coerceIn(0f, 10f)
                                    } else {
                                        editedGrades[key] = null
                                    }
                                    updateDirtyCount()
                                }
                            })
                        }
                        row.addView(input)
                    }
                }
                // Spacer for "+" and "−" button columns
                val slotCount = gradeTypes.count { it == base || it.startsWith("${base}_") }
                val spacerWidth = addSlotColWidth + (if (slotCount > 1) removeSlotColWidth else 0)
                row.addView(View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(dpToPx(spacerWidth), LinearLayout.LayoutParams.MATCH_PARENT)
                })
            }

            // Task grade columns (editable — allow grading even without an existing submission)
            for (task in tasks) {
                val taskKey = "${student.userId}-${task.id}"
                val input = EditText(requireContext()).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setBackgroundColor(Color.parseColor("#0A00D4FF"))
                    setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
                    layoutParams = LinearLayout.LayoutParams(dpToPx(gradeColWidth + 10), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                        setMargins(dpToPx(1), dpToPx(2), dpToPx(1), dpToPx(2))
                    }
                    hint = "—"
                    setHintTextColor(Color.parseColor("#555555"))
                    val current = editedTaskGrades[taskKey]
                    setText(if (current != null) current.toString() else "")

                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                        override fun afterTextChanged(s: Editable?) {
                            val value = s?.toString()?.toFloatOrNull()
                            editedTaskGrades[taskKey] = value?.coerceIn(0f, 10f)
                            updateDirtyCount()
                        }
                    })
                }
                row.addView(input)
            }

            // Average column
            val avg = computeAverage(student.userId)
            val avgCell = TextView(requireContext()).apply {
                gravity = Gravity.CENTER
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
                layoutParams = LinearLayout.LayoutParams(dpToPx(avgColWidth), LinearLayout.LayoutParams.MATCH_PARENT)

                if (avg != null) {
                    text = String.format("%.1f", avg)
                    if (avg >= 6f) {
                        setTextColor(Color.parseColor("#30D158"))
                        setBackgroundColor(Color.parseColor("#1A30D158"))
                    } else {
                        setTextColor(Color.parseColor("#FF375F"))
                        setBackgroundColor(Color.parseColor("#1AFF375F"))
                    }
                } else {
                    text = "—"
                    setTextColor(Color.parseColor("#555555"))
                }
            }
            row.addView(avgCell)

            // Alternating row background
            val idx = dataRowsContainer.childCount
            if (idx % 2 == 1) {
                row.setBackgroundColor(Color.parseColor("#0DFFFFFF"))
            }

            dataRowsContainer.addView(row)
        }
    }

    private fun computeAverage(userId: Long): Float? {
        val values = mutableListOf<Float>()

        for (type in gradeTypes) {
            val key = "${userId}-${type}"
            val val_ = editedGrades[key]
            if (val_ != null) values.add(val_)
        }

        for (task in tasks) {
            val key = "${userId}-${task.id}"
            val grade = editedTaskGrades[key]
            if (grade != null) values.add(grade)
        }

        return if (values.isEmpty()) null else values.sum() / values.size
    }

    private fun updateDirtyCount() {
        val dirtyManual = editedGrades.count { (key, value) ->
            value != originalGrades[key]
        }
        val dirtyTask = editedTaskGrades.count { (key, value) ->
            value != originalTaskGrades[key]
        }
        val dirty = dirtyManual + dirtyTask
        if (dirty > 0) {
            saveFooter.visibility = View.VISIBLE
            dirtyCount.text = "$dirty cambio(s) sin guardar"
        } else {
            saveFooter.visibility = View.GONE
        }
    }

    private fun discoverGradeSlots() {
        val baseEmojis = mapOf("comportamiento" to "🤝", "participacion" to "🙋", "examenes" to "📝")
        val baseShorts = mapOf("comportamiento" to "Com", "participacion" to "Par", "examenes" to "Exa")

        val maxSlots = mutableMapOf<String, Int>()
        for (base in baseGradeTypes) maxSlots[base] = 1

        for (mg in manualGradesList) {
            val match = Regex("^(comportamiento|participacion|examenes)(?:_(\\d+))?$").find(mg.gradeType)
            if (match != null) {
                val base = match.groupValues[1]
                val slot = if (match.groupValues[2].isNotEmpty()) match.groupValues[2].toInt() else 1
                if (slot > (maxSlots[base] ?: 1)) maxSlots[base] = slot
            }
        }

        gradeTypes.clear()
        gradeTypeLabels.clear()
        for (base in baseGradeTypes) {
            val count = maxSlots[base] ?: 1
            for (slot in 1..count) {
                val key = if (slot == 1) base else "${base}_${slot}"
                gradeTypes.add(key)
                val emoji = baseEmojis[base] ?: ""
                val short = baseShorts[base] ?: ""
                val label = if (count > 1) "$emoji $short$slot" else "$emoji $short"
                gradeTypeLabels.add(label)
            }
        }
    }

    private fun addSlot(baseType: String) {
        val baseEmojis = mapOf("comportamiento" to "🤝", "participacion" to "🙋", "examenes" to "📝")
        val baseShorts = mapOf("comportamiento" to "Com", "participacion" to "Par", "examenes" to "Exa")
        val existing = gradeTypes.count { it == baseType || it.startsWith("${baseType}_") }
        val newSlot = existing + 1
        val newKey = "${baseType}_${newSlot}"

        val emoji = baseEmojis[baseType] ?: ""
        val short = baseShorts[baseType] ?: ""

        // Find insertion index: after last column of this base type
        val lastIdx = gradeTypes.indexOfLast { it == baseType || it.startsWith("${baseType}_") }
        gradeTypes.add(lastIdx + 1, newKey)
        gradeTypeLabels.add(lastIdx + 1, "$emoji $short$newSlot")

        // Update existing slot labels to show numbers if they were single-slot before
        if (existing == 1) {
            val firstIdx = gradeTypes.indexOf(baseType)
            if (firstIdx >= 0) {
                gradeTypeLabels[firstIdx] = "$emoji ${short}1"
            }
        }

        updateBulkSpinner()
        renderHeader()
        renderDataRows()
    }

    private fun updateBulkSpinner() {
        val bulkLabels = gradeTypeLabels.toList()
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            bulkLabels
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        bulkTypeSpinner.adapter = adapter
    }

    private fun createAddSlotButton(baseType: String): Button {
        return Button(requireContext()).apply {
            text = "+"
            setTextColor(Color.parseColor("#BF5AF2"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#14BF5AF2"))
            setPadding(dpToPx(2), dpToPx(4), dpToPx(2), dpToPx(4))
            layoutParams = LinearLayout.LayoutParams(dpToPx(addSlotColWidth), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                setMargins(dpToPx(1), 0, dpToPx(1), 0)
            }
            setOnClickListener { addSlot(baseType) }
        }
    }

    private fun createRemoveSlotButton(baseType: String): Button {
        return Button(requireContext()).apply {
            text = "−"
            setTextColor(Color.parseColor("#FF375F"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#14FF375F"))
            setPadding(dpToPx(2), dpToPx(4), dpToPx(2), dpToPx(4))
            layoutParams = LinearLayout.LayoutParams(dpToPx(removeSlotColWidth), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                setMargins(dpToPx(1), 0, dpToPx(1), 0)
            }
            setOnClickListener { showRemoveSlotConfirmation(baseType) }
        }
    }

    private fun showRemoveSlotConfirmation(baseType: String) {
        val baseLabels = mapOf("comportamiento" to "Comportamiento", "participacion" to "Participación", "examenes" to "Exámenes")
        val label = baseLabels[baseType] ?: baseType

        val dialogView = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dpToPx(28), dpToPx(28), dpToPx(28), dpToPx(20))

            // Icon
            addView(TextView(requireContext()).apply {
                text = "🗑️"
                textSize = 32f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dpToPx(12))
            })

            // Title
            addView(TextView(requireContext()).apply {
                text = "¿Estás seguro de eliminar la columna de notas?"
                setTextColor(Color.WHITE)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dpToPx(10))
            })

            // Description
            addView(TextView(requireContext()).apply {
                text = "Se eliminará la última columna de $label. Las notas ingresadas en esa columna se perderán si no fueron guardadas."
                setTextColor(Color.parseColor("#A0A0B0"))
                textSize = 13f
                gravity = Gravity.CENTER
                lineHeight = dpToPx(20)
                setPadding(0, 0, 0, dpToPx(20))
            })
        }

        val dialog = AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setView(dialogView)
            .create()

        // Buttons
        val btnRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dpToPx(8), 0, dpToPx(8), dpToPx(8))
        }

        val cancelBtn = Button(requireContext()).apply {
            text = "Cancelar"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#2AFFFFFF"))
                cornerRadius = dpToPx(14).toFloat()
            }
            background = bg
            setPadding(dpToPx(16), dpToPx(11), dpToPx(16), dpToPx(11))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, dpToPx(5), 0)
            }
            setOnClickListener { dialog.dismiss() }
        }
        btnRow.addView(cancelBtn)

        val confirmBtn = Button(requireContext()).apply {
            text = "Eliminar"
            setTextColor(Color.parseColor("#FF375F"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#40FF375F"))
                cornerRadius = dpToPx(14).toFloat()
            }
            background = bg
            setPadding(dpToPx(16), dpToPx(11), dpToPx(16), dpToPx(11))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dpToPx(5), 0, 0, 0)
            }
            setOnClickListener {
                dialog.dismiss()
                removeSlot(baseType)
            }
        }
        btnRow.addView(confirmBtn)

        dialogView.addView(btnRow)

        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(Color.parseColor("#8C1E1E32"))
                cornerRadius = dpToPx(24).toFloat()
            })
            setDimAmount(0.55f)
        }

        dialog.show()
    }

    private fun removeSlot(baseType: String) {
        val count = gradeTypes.count { it == baseType || it.startsWith("${baseType}_") }
        if (count <= 1) return

        val removedKey = if (count == 1) baseType else "${baseType}_${count}"

        // Clear edited data for removed column
        for (student in students) {
            val key = "${student.userId}-${removedKey}"
            editedGrades.remove(key)
            originalGrades.remove(key)
        }

        // Remove from lists
        val idx = gradeTypes.indexOf(removedKey)
        if (idx >= 0) {
            gradeTypes.removeAt(idx)
            gradeTypeLabels.removeAt(idx)
        }

        // If only 1 slot remains, revert label to non-numbered
        val baseEmojis = mapOf("comportamiento" to "🤝", "participacion" to "🙋", "examenes" to "📝")
        val baseShorts = mapOf("comportamiento" to "Com", "participacion" to "Par", "examenes" to "Exa")
        val remaining = gradeTypes.count { it == baseType || it.startsWith("${baseType}_") }
        if (remaining == 1) {
            val firstIdx = gradeTypes.indexOf(baseType)
            if (firstIdx >= 0) {
                val emoji = baseEmojis[baseType] ?: ""
                val short = baseShorts[baseType] ?: ""
                gradeTypeLabels[firstIdx] = "$emoji $short"
            }
        }

        updateBulkSpinner()
        renderHeader()
        renderDataRows()
    }

    private fun applyBulkGrade() {
        val gradeText = bulkGradeInput.text?.toString()?.toFloatOrNull() ?: return
        val clamped = gradeText.coerceIn(0f, 10f)
        val typeIndex = bulkTypeSpinner.selectedItemPosition
        val gradeType = gradeTypes.getOrNull(typeIndex) ?: return

        for (student in students) {
            editedGrades["${student.userId}-${gradeType}"] = clamped
        }

        renderDataRows()
        updateDirtyCount()
        Toast.makeText(context, "Nota $clamped aplicada a todos", Toast.LENGTH_SHORT).show()
    }

    private fun saveGrades() {
        val dirty = editedGrades.filter { (key, value) -> value != originalGrades[key] }
        val dirtyTask = editedTaskGrades.filter { (key, value) -> value != null && value != originalTaskGrades[key] }
        if (dirty.isEmpty() && dirtyTask.isEmpty()) return

        btnSave.isEnabled = false
        btnSave.text = "Guardando..."

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                BackendApiService.initialize(requireContext())

                // Group by type
                val byType = mutableMapOf<String, MutableList<Pair<Long, Float>>>()
                for ((key, value) in dirty) {
                    if (value == null) continue
                    val parts = key.split("-")
                    val userId = parts[0].toLongOrNull() ?: continue
                    val type = parts.drop(1).joinToString("-")
                    byType.getOrPut(type) { mutableListOf() }.add(userId to value)
                }

                for ((type, entries) in byType) {
                    val body = mapOf(
                        "courseId" to courseId,
                        "subjectId" to subjectId,
                        "gradeType" to type,
                        "entries" to entries.map { (studentId, grade) ->
                            mapOf("studentId" to studentId, "grade" to grade)
                        }
                    )
                    BackendApiService.bulkSetManualGrades(body)
                }

                // Update originals
                for ((key, value) in editedGrades) {
                    originalGrades[key] = value
                }

                // Save dirty task grades
                for ((key, newGrade) in editedTaskGrades) {
                    if (newGrade == null) continue
                    if (newGrade == originalTaskGrades[key]) continue
                    val parts = key.split("-")
                    val studentId = parts[0].toLongOrNull() ?: continue
                    val taskId = parts[1].toLongOrNull() ?: continue
                    var tg = taskGradesList.find { it.studentId == studentId && it.taskId == taskId }
                    if ((tg?.submissionId ?: 0L) > 0L) {
                        BackendApiService.gradeSubmission(tg!!.submissionId, newGrade, null)
                        originalTaskGrades[key] = newGrade
                    } else {
                        // Create submission for student then grade it
                        try {
                            val createBody = mapOf("taskId" to taskId, "studentId" to studentId)
                            val createdRes = BackendApiService.submitWorkForStudent(createBody)
                            if (createdRes is ApiResult.Success<*>) {
                                val created = createdRes.data as com.google.gson.JsonObject
                                val createdId = created.get("id")?.asLong ?: 0L
                                if (createdId > 0L) {
                                    taskGradesList.add(TaskGradeEntry(taskId, studentId, null, createdId))
                                    BackendApiService.gradeSubmission(createdId, newGrade, null)
                                    originalTaskGrades[key] = newGrade
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error creating submission for student $studentId task $taskId", e)
                        }
                    }
                }

                updateDirtyCount()
                Toast.makeText(context, "Notas guardadas exitosamente", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Error saving grades", e)
                Toast.makeText(context, "Error al guardar notas", Toast.LENGTH_SHORT).show()
            } finally {
                btnSave.isEnabled = true
                btnSave.text = "Guardar Notas"
            }
        }
    }
}
