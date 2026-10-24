package com.zhizhou.xxtassistant

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhizhou.xxtassistant.data.AccountManager
import com.zhizhou.xxtassistant.data.CourseSchedule
import com.zhizhou.xxtassistant.data.CourseScheduleStore
import com.zhizhou.xxtassistant.data.CoursePeriod
import com.zhizhou.xxtassistant.data.CoursePeriodStore
import com.zhizhou.xxtassistant.data.CourseTableParser
import com.zhizhou.xxtassistant.data.CourseTableSettingsStore
import com.zhizhou.xxtassistant.data.TeachingWeekPicker
import com.zhizhou.xxtassistant.remind.ReminderDispatch
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 课程表首版页面。课程表数据独立于作业缓存，支持导入、手动维护和日/周查看。
 * 页面只在用户确认导入后写入本地，并在成功后重新安排课程提醒。
 */
class CourseTableActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val accountId = AccountManager(this).getActiveUid()
        if (accountId.isNullOrBlank()) {
            finish()
            return
        }
        val store = CourseScheduleStore(this)
        setContent {
            CourseTableScreen(
                accountId = accountId,
                store = store,
                onSchedulesChanged = {
                    ReminderDispatch.enqueueCourses(
                        context = this,
                        accountId = accountId,
                        schedules = { store.list(accountId) }
                    )
                }
            )
        }
    }
}

private data class CourseImportPreview(
    val schedules: List<CourseSchedule>,
    val errors: List<String>,
    val sourceLabel: String,
    val targetTerm: String
)

private data class CoursePalette(
    val container: Color,
    val content: Color,
    val accent: Color
)

// Distinct, low-saturation colors make a busy week scannable without tying the
// timetable to one brand color or reducing text contrast.
private val coursePalettes = listOf(
    CoursePalette(Color(0xFFE0F2F1), Color(0xFF123D3A), Color(0xFF23877F)),
    CoursePalette(Color(0xFFE3F2FD), Color(0xFF14364D), Color(0xFF347DAF)),
    CoursePalette(Color(0xFFFFF0DE), Color(0xFF4B2A12), Color(0xFFC8782E)),
    CoursePalette(Color(0xFFEAF4E2), Color(0xFF29431E), Color(0xFF65934E)),
    CoursePalette(Color(0xFFF2E8F7), Color(0xFF3E2451), Color(0xFF8B5AA8)),
    CoursePalette(Color(0xFFFFE8EC), Color(0xFF54212A), Color(0xFFC55E70)),
    CoursePalette(Color(0xFFFFF5CC), Color(0xFF4D3B0A), Color(0xFFB08B24))
)

private fun paletteFor(course: CourseSchedule): CoursePalette {
    val index = (course.name.trim().hashCode() and Int.MAX_VALUE) % coursePalettes.size
    return coursePalettes[index]
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CourseTableScreen(
    accountId: String,
    store: CourseScheduleStore,
    onSchedulesChanged: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    BackHandler { returnToTodayOrExit(context as ComponentActivity) }
    val snackbar = remember { SnackbarHostState() }
    val periodStore = remember { CoursePeriodStore(context) }
    val tableSettings = remember { CourseTableSettingsStore(context) }
    val defaultTerm = remember { currentTerm() }
    var selectedTerm by rememberSaveable { mutableStateOf(defaultTerm) }
    var terms by remember { mutableStateOf(store.terms(accountId).ifEmpty { listOf(defaultTerm) }) }
    var schedules by remember { mutableStateOf(store.list(accountId, selectedTerm)) }
    var viewMode by rememberSaveable { mutableIntStateOf(0) }
    var selectedDay by rememberSaveable { mutableIntStateOf(todayWeekday()) }
    var selectedWeek by rememberSaveable {
        mutableIntStateOf(TeachingWeekPicker.clamp(currentTeachingWeek(accountId, defaultTerm, tableSettings)))
    }
    val currentWeek = TeachingWeekPicker.clamp(currentTeachingWeek(accountId, selectedTerm, tableSettings))
    var courseMenuOpen by remember { mutableStateOf(false) }
    var weekPickerOpen by remember { mutableStateOf(false) }
    var showManualDialog by remember { mutableStateOf(false) }
    var editingCourse by remember { mutableStateOf<CourseSchedule?>(null) }
    var viewingCourse by remember { mutableStateOf<CourseSchedule?>(null) }
    var showPeriodDialog by remember { mutableStateOf(false) }
    var importPreview by remember { mutableStateOf<CourseImportPreview?>(null) }
    var deleteCandidate by remember { mutableStateOf<CourseSchedule?>(null) }
    val importExecutor = remember {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "xxt-course-file-import").apply { isDaemon = true }
        }
    }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(Unit) {
        onDispose {
            importExecutor.shutdownNow()
            mainHandler.removeCallbacksAndMessages(null)
        }
    }

    val academicImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            reloadSchedules(store, accountId, selectedTerm) { nextTerms, nextSchedules ->
                terms = nextTerms
                schedules = nextSchedules
            }
            onSchedulesChanged()
        }
    }

    fun reload() {
        terms = store.terms(accountId).ifEmpty { listOf(defaultTerm) }
        schedules = store.list(accountId, selectedTerm)
    }

    LaunchedEffect(selectedTerm) {
        selectedWeek = TeachingWeekPicker.clamp(currentTeachingWeek(accountId, selectedTerm, tableSettings))
        schedules = store.list(accountId, selectedTerm)
    }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else "schedule.csv" }
            ?: "schedule.csv"
        importPreview = CourseImportPreview(emptyList(), listOf("正在读取文件，请稍候……"), name, selectedTerm)
        importExecutor.execute {
            val parsed = runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    // A timetable is small in practice. Keep a hard limit so a
                    // malformed provider cannot make the UI process allocate
                    // an unbounded byte array.
                    val maxBytes = 8 * 1024 * 1024
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > maxBytes) error("文件超过 8 MB，无法导入")
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: error("无法读取所选文件")
                val lowerName = name.lowercase(Locale.ROOT)
                val result = if (lowerName.endsWith(".html") || lowerName.endsWith(".htm")) {
                    CourseTableParser.parseHtml(bytes.toString(Charsets.UTF_8))
                } else {
                    CourseTableParser.parseFile(name, bytes)
                }
                val detectedTerms = result.rows.map { it.term.trim() }.filter { it.isNotBlank() }.distinct()
                val targetTerm = detectedTerms.singleOrNull() ?: selectedTerm
                val termErrors = if (detectedTerms.size > 1) {
                    listOf("文件同时包含多个学期，已暂停导入。请按学期拆分后再导入。")
                } else emptyList()
                val imported = if (termErrors.isEmpty()) CourseTableParser.toSchedules(
                    accountId,
                    result.rows,
                    CourseSchedule.SOURCE_TABLE,
                    termOverride = targetTerm
                ) else emptyList()
                CourseImportPreview(imported, result.errors + termErrors, name, targetTerm)
            }.getOrElse { error ->
                CourseImportPreview(emptyList(), listOf(error.message ?: "无法解析所选文件"), name, selectedTerm)
            }
            mainHandler.post {
                if (!importExecutor.isShutdown) {
                    if (parsed.targetTerm.isNotBlank() && parsed.targetTerm != selectedTerm) {
                        selectedTerm = parsed.targetTerm
                    }
                    importPreview = parsed
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("课程表", style = MaterialTheme.typography.titleLarge)
                        Text(displayTermName(selectedTerm), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    Box {
                        IconButton(onClick = { courseMenuOpen = true }) {
                            Icon(painterResource(R.drawable.ic_menu_24), contentDescription = "课程表菜单")
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = courseMenuOpen,
                            onDismissRequest = { courseMenuOpen = false }
                        ) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("开学日期与作息") },
                                onClick = { courseMenuOpen = false; showPeriodDialog = true }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("表格导入") },
                                onClick = {
                                    courseMenuOpen = false
                                    fileLauncher.launch(arrayOf("text/*", "text/html", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream"))
                                }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("教务系统导入") },
                                onClick = {
                                    courseMenuOpen = false
                                    academicImportLauncher.launch(Intent(context, CourseWebImportActivity::class.java).putExtra(CourseWebImportActivity.EXTRA_TERM, selectedTerm))
                                }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("分享课表") },
                                onClick = {
                                    courseMenuOpen = false
                                    val content = buildString {
                                        append("小笙助手课程表 · ").append(displayTermName(selectedTerm)).append('\n')
                                        schedules.filter { it.occursInWeek(selectedWeek) }
                                            .sortedWith(compareBy<CourseSchedule> { it.weekday }.thenBy { it.startPeriod })
                                            .forEach { course ->
                                                append(dayNames.getOrNull(course.weekday - 1) ?: "周?")
                                                    .append(" ").append(course.periodText()).append(" ").append(course.name)
                                                if (course.room.isNotBlank()) append(" · ").append(course.room)
                                                append('\n')
                                            }
                                    }
                                    runCatching {
                                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, content.toString().trim())
                                        }, "分享课程表"))
                                    }
                                }
                            )
                            if (terms.size > 1) {
                                androidx.compose.material3.HorizontalDivider()
                                terms.forEach { term ->
                                    androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(if (term == selectedTerm) "✓ ${displayTermName(term)}" else displayTermName(term)) },
                                        onClick = { selectedTerm = term; courseMenuOpen = false }
                                    )
                                }
                            }
                        }
                    }
                },
                actions = { TextButton(onClick = { viewMode = 1 - viewMode }) { Text(if (viewMode == 0) "周视图" else "日视图") } }
            )
        },
        bottomBar = {
            XxtBottomNavigation(selectedTab = 1) { tab ->
                when (tab) {
                    0 -> context.navigateToTab(TodayActivity::class.java)
                    2 -> context.navigateToTab(MainActivity::class.java)
                    3 -> context.navigateToTab(MyActivity::class.java)
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .pointerInput(viewMode, selectedWeek) {
                    if (viewMode == 1) {
                        var horizontalDrag = 0f
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                horizontalDrag += dragAmount
                            },
                            onDragEnd = {
                                selectedWeek = TeachingWeekPicker.afterHorizontalSwipe(selectedWeek, horizontalDrag)
                                horizontalDrag = 0f
                            },
                            onDragCancel = { horizontalDrag = 0f }
                        )
                    }
                },
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    TextButton(onClick = { weekPickerOpen = true }) {
                        Text(if (selectedWeek == currentWeek) "本周  ▾" else "第 ${selectedWeek} 周  ▾", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { selectedWeek = TeachingWeekPicker.clamp(currentTeachingWeek(accountId, selectedTerm, tableSettings)) }) { Text("本周") }
                TextButton(onClick = { showManualDialog = true }) { Text("＋课程") }
            }
            if (viewMode == 0) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(dayNames) { index, name ->
                        FilterChip(
                            selected = selectedDay == index + 1,
                            onClick = { selectedDay = index + 1 },
                            label = { Text(name) }
                        )
                    }
                }
            }

            AnimatedContent(
                targetState = viewMode,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    (slideInHorizontally { width -> if (targetState > initialState) width else -width } + fadeIn()) togetherWith
                        (slideOutHorizontally { width -> if (targetState > initialState) -width else width } + fadeOut())
                },
                label = "course-table-view"
            ) { mode ->
                val visible = schedules.filter { it.occursInWeek(selectedWeek) }
                    .let { entries -> if (mode == 0) entries.filter { it.weekday == selectedDay } else entries }
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (mode == 0) "${dayNames.getOrNull(selectedDay - 1) ?: "今天"} · ${visible.size} 节课"
                        else "第 $selectedWeek 周 · 共 ${visible.size} 节课",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    if (visible.isEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                        ) {
                            Text("这段时间还没有课程", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else if (mode == 1) {
                        AnimatedContent(
                            targetState = selectedWeek,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            transitionSpec = {
                                (slideInHorizontally { width -> if (targetState > initialState) width else -width } + fadeIn()) togetherWith
                                    (slideOutHorizontally { width -> if (targetState > initialState) -width else width } + fadeOut())
                            },
                            label = "course-week-swipe"
                        ) { week ->
                            CourseWeekGrid(
                                schedules = schedules.filter { it.occursInWeek(week) },
                                periods = periodStore.list(accountId),
                                displayedWeek = week,
                                currentWeek = currentWeek,
                                onEdit = { viewingCourse = it },
                                onDelete = { viewingCourse = it },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp)
                        ) {
                            items(visible, key = { it.id }) { course ->
                                CourseCard(course) { viewingCourse = course }
                            }
                        }
                    }
                }
            }
        }
    }

    if (weekPickerOpen) {
        AlertDialog(
            onDismissRequest = { weekPickerOpen = false },
            title = { Text("选择周次") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TeachingWeekPicker.rows.forEach { rowWeeks ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowWeeks.forEach { week ->
                                val selected = week == selectedWeek
                                val isCurrent = week == currentWeek
                                val hasCourses = schedules.any { it.occursInWeek(week) }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            when {
                                                selected -> MaterialTheme.colorScheme.primaryContainer
                                                isCurrent -> MaterialTheme.colorScheme.secondaryContainer
                                                hasCourses -> MaterialTheme.colorScheme.tertiaryContainer
                                                else -> MaterialTheme.colorScheme.surfaceVariant
                                            }
                                        )
                                        .border(
                                            width = if (selected) 1.5.dp else 0.dp,
                                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                            shape = RoundedCornerShape(6.dp)
                                        )
                                        .clickable {
                                            selectedWeek = week
                                            weekPickerOpen = false
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (isCurrent) "本周" else week.toString(),
                                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { weekPickerOpen = false }) { Text("关闭") } }
        )
    }

    importPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { importPreview = null },
            title = { Text("导入预览") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("文件：${preview.sourceLabel}")
                    Text("导入学期：${preview.targetTerm}")
                    Text("可导入 ${preview.schedules.size} 节课程")
                    if (preview.errors.isNotEmpty()) {
                        HorizontalDivider()
                        Text("有 ${preview.errors.size} 行需要检查：", color = MaterialTheme.colorScheme.error)
                        preview.errors.take(5).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (preview.errors.size > 5) Text("其余错误已省略", style = MaterialTheme.typography.bodySmall)
                        Text("已暂停导入，修正文件后再试，避免覆盖现有课表。", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(enabled = preview.schedules.isNotEmpty() && preview.errors.isEmpty(), onClick = {
                    store.replaceImported(accountId, preview.targetTerm, preview.schedules, CourseSchedule.SOURCE_TABLE)
                    reload()
                    onSchedulesChanged()
                    importPreview = null
                }) { Text("确认导入") }
            },
            dismissButton = { TextButton(onClick = { importPreview = null }) { Text("取消") } }
        )
    }

    if (showManualDialog || editingCourse != null) {
        ManualCourseDialog(
            term = selectedTerm,
            initial = editingCourse,
            onDismiss = {
                showManualDialog = false
                editingCourse = null
            },
            onSave = { name, teacher, room, weekday, startPeriod, endPeriod, startWeek, endWeek ->
                val item = editingCourse?.copy(
                    term = selectedTerm,
                    name = name,
                    teacher = teacher,
                    room = room,
                    weekday = weekday,
                    startPeriod = startPeriod,
                    endPeriod = endPeriod,
                    startWeek = startWeek,
                    endWeek = endWeek
                ) ?: CourseSchedule(
                        id = "manual_${System.currentTimeMillis()}",
                        accountId = accountId,
                        term = selectedTerm,
                        name = name,
                        teacher = teacher,
                        room = room,
                        weekday = weekday,
                        startPeriod = startPeriod,
                        endPeriod = endPeriod,
                        startWeek = startWeek,
                        endWeek = endWeek,
                        source = CourseSchedule.SOURCE_MANUAL
                    )
                if (item.isValid()) {
                    store.upsert(item)
                    reload()
                    onSchedulesChanged()
                    showManualDialog = false
                    editingCourse = null
                }
            }
        )
    }

    if (showPeriodDialog) {
        PeriodSettingsDialog(
            accountId = accountId,
            term = selectedTerm,
            requiredPeriods = schedules.flatMap { listOf(it.startPeriod, it.endPeriod) }.toSet(),
            periodStore = periodStore,
            tableSettings = tableSettings,
            onDismiss = { showPeriodDialog = false },
            onSaved = {
                showPeriodDialog = false
                selectedWeek = TeachingWeekPicker.clamp(currentTeachingWeek(accountId, selectedTerm, tableSettings))
                onSchedulesChanged()
            }
        )
    }

    viewingCourse?.let { course ->
        CourseDetailsDialog(
            course = course,
            onDismiss = { viewingCourse = null },
            onEdit = {
                viewingCourse = null
                editingCourse = course
            },
            onDelete = {
                viewingCourse = null
                deleteCandidate = course
            }
        )
    }

    deleteCandidate?.let { course ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("删除这门课程？") },
            text = {
                Text("将从 ${course.term.ifBlank { "当前学期" }} 的本地课表中移除“${course.name}”。学习通或教务系统中的课程不会被删除。")
            },
            confirmButton = {
                Button(onClick = {
                    store.remove(accountId, course.id)
                    deleteCandidate = null
                    reload()
                    onSchedulesChanged()
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("保留") } }
        )
    }
}

/** 节次网格课表：课程块按起止节次定位，冲突课程自动并列。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CourseWeekGrid(
    schedules: List<CourseSchedule>,
    periods: List<CoursePeriod>,
    displayedWeek: Int,
    currentWeek: Int,
    onEdit: (CourseSchedule) -> Unit,
    onDelete: (CourseSchedule) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier) {
        val labelWidth = minOf(48.dp, maxWidth * 0.14f)
        val dayWidth = (maxWidth - labelWidth) / 7
        val maxPeriod = maxOf(
            periods.maxOfOrNull { it.period } ?: 0,
            schedules.maxOfOrNull { it.endPeriod } ?: 0
        ).coerceIn(1, 24)
        val labels = (1..maxPeriod).map { number -> periods.firstOrNull { it.period == number } }
        val rowHeight = ((maxHeight - 46.dp).coerceAtLeast(28.dp) / maxPeriod).coerceIn(28.dp, 52.dp)
        val gridHeight = rowHeight * maxPeriod
        val placements = remember(schedules, maxPeriod) { buildGridPlacements(schedules) }

        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Surface(
                    Modifier.width(labelWidth).height(42.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("节次", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Row(Modifier.weight(1f)) {
                    dayNames.forEachIndexed { index, dayName ->
                        val isToday = TeachingWeekPicker.isTodayColumn(
                            displayedWeek, currentWeek, index + 1, todayWeekday()
                        )
                        Surface(
                            Modifier.width(dayWidth).height(42.dp),
                            color = if (isToday) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            Column(
                                Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(dayName.removePrefix("周"), style = MaterialTheme.typography.labelMedium)
                                if (isToday) Text("今", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    Modifier.width(labelWidth).height(gridHeight),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    labels.forEachIndexed { index, period ->
                        Surface(
                            Modifier.width(labelWidth).height(rowHeight),
                            color = MaterialTheme.colorScheme.surfaceContainerLowest
                        ) {
                            Column(
                                Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text("${index + 1}", style = MaterialTheme.typography.labelLarge)
                                Text(period?.let { formatMinutes(it.startMinute) } ?: "—", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                Text(period?.let { formatMinutes(it.endMinute) } ?: "—", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                }
                Row(Modifier.weight(1f)) {
                    dayNames.indices.forEach { dayIndex ->
                        val dayCourses = placements.filter { it.course.weekday == dayIndex + 1 }
                        Box(
                            Modifier.width(dayWidth).height(gridHeight)
                                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            repeat(maxPeriod) { periodIndex ->
                                Box(
                                    Modifier.fillMaxWidth().height(rowHeight).offset(y = rowHeight * periodIndex)
                                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                                )
                            }
                            dayCourses.forEach { placement ->
                                androidx.compose.runtime.key(placement.course.id) {
                                    val course = placement.course
                                    val palette = paletteFor(course)
                                    val span = (course.endPeriod - course.startPeriod + 1).coerceAtLeast(1)
                                    val blockWidth = dayWidth / placement.laneCount
                                    Surface(
                                        Modifier.offset(
                                            x = blockWidth * placement.lane + 1.dp,
                                            y = rowHeight * (course.startPeriod - 1) + 2.dp
                                        )
                                            .width((blockWidth - 2.dp).coerceAtLeast(8.dp))
                                            .height(rowHeight * span - 4.dp)
                                            .border(1.dp, palette.accent.copy(alpha = 0.58f), RoundedCornerShape(6.dp))
                                            .clip(RoundedCornerShape(6.dp))
                                            .combinedClickable(
                                                onClick = { onEdit(course) },
                                                onLongClick = { onDelete(course) }
                                            ),
                                        color = palette.container,
                                        contentColor = palette.content
                                    ) {
                                        Column(
                                            Modifier.fillMaxSize().padding(horizontal = 3.dp, vertical = 5.dp),
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Text(course.name, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                                            course.room.takeIf { it.isNotBlank() }?.let { room ->
                                                Text(
                                                    room,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    maxLines = 2,
                                                    color = palette.content.copy(alpha = 0.82f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatMinutes(value: Int): String = "%02d:%02d".format(Locale.ROOT, value / 60, value % 60)

@Composable
private fun CourseCard(course: CourseSchedule, onClick: () -> Unit) {
    val palette = paletteFor(course)
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = palette.container, contentColor = palette.content),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row {
            Box(Modifier.width(4.dp).heightIn(min = 66.dp).background(palette.accent))
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(course.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text("${course.periodText()} · ${course.weekText()}", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                val location = course.locationText()
                if (location.isNotBlank()) Text(location, style = MaterialTheme.typography.labelSmall, color = palette.content.copy(alpha = 0.8f), maxLines = 1)
                if (course.note.isNotBlank()) Text(course.note, style = MaterialTheme.typography.bodySmall, color = palette.content.copy(alpha = 0.8f))
            }
        }
    }
}

@Composable
private fun CourseDetailsDialog(
    course: CourseSchedule,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val palette = paletteFor(course)
    val weekday = listOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")
        .getOrElse(course.weekday) { "星期未知" }
    val source = when (course.source) {
        CourseSchedule.SOURCE_ACADEMIC_WEB -> "教务系统导入"
        CourseSchedule.SOURCE_TABLE -> "表格导入"
        else -> "手动添加"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(5.dp).height(30.dp).background(palette.accent))
                Text(course.name, modifier = Modifier.padding(start = 10.dp))
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$weekday · ${course.periodText()}", style = MaterialTheme.typography.titleSmall)
                Text(course.weekText(), style = MaterialTheme.typography.bodyMedium)
                if (course.room.isNotBlank()) Text("教室：${course.room}", style = MaterialTheme.typography.bodyMedium)
                if (course.teacher.isNotBlank()) Text("教师：${course.teacher}", style = MaterialTheme.typography.bodyMedium)
                if (course.note.isNotBlank()) Text(course.note, style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider()
                Text("学期：${course.term.ifBlank { "未设置" }}", style = MaterialTheme.typography.bodySmall)
                Text("来源：$source", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onEdit) { Text("编辑") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("删除") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    )
}

private fun CourseSchedule.weekText(): String {
    val explicit = weeks.distinct().sorted()
    if (explicit.isNotEmpty()) {
        val ranges = buildList {
            var start = explicit.first()
            var previous = start
            explicit.drop(1).forEach { value ->
                if (value == previous + 1) {
                    previous = value
                } else {
                    add(if (start == previous) "$start" else "$start-$previous")
                    start = value
                    previous = value
                }
            }
            add(if (start == previous) "$start" else "$start-$previous")
        }
        return "第 ${ranges.joinToString("、")} 周"
    }
    val parity = when (weekParity) {
        1 -> "（单周）"
        2 -> "（双周）"
        else -> ""
    }
    return "第 $startWeek-$endWeek 周$parity"
}

@Composable
private fun PeriodSettingsDialog(
    accountId: String,
    term: String,
    requiredPeriods: Set<Int>,
    periodStore: CoursePeriodStore,
    tableSettings: CourseTableSettingsStore,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val initial = remember(accountId) { periodStore.list(accountId) }
    val hasSavedPeriods = remember(accountId) { periodStore.hasCustom(accountId) }
    val initialByPeriod = initial.associateBy { it.period }
    val periodLimit = maxOf(
        CoursePeriodStore.defaultPeriods.maxOf { it.period },
        initial.maxOfOrNull { it.period } ?: 0,
        requiredPeriods.maxOrNull() ?: 0
    )
    var timeValues by remember(accountId) {
        mutableStateOf<Map<Int, Pair<String, String>>>((1..periodLimit).associateWith { period ->
            val value = initialByPeriod[period] ?: if (!hasSavedPeriods) {
                CoursePeriodStore.defaultPeriods.firstOrNull { it.period == period }
            } else null
            if (value == null) "" to "" else formatClock(value.startMinute) to formatClock(value.endMinute)
        })
    }
    var prepHour by remember(accountId) { mutableStateOf(tableSettings.preparationHour(accountId).toString()) }
    var prepMinute by remember(accountId) { mutableStateOf(tableSettings.preparationMinute(accountId).toString().padStart(2, '0')) }
    var courseLeadMinutes by remember(accountId) { mutableStateOf(tableSettings.courseLeadMinutes(accountId).toString()) }
    var termStartDate by remember(accountId, term) { mutableStateOf(tableSettings.termStartDate(accountId, term)) }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("作息与课程提醒") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("按你学校的实际作息修改每节课起止时间。前一天提醒默认在 20:00 发送。", style = MaterialTheme.typography.bodySmall)
                if (!hasSavedPeriods || (requiredPeriods - initialByPeriod.keys).isNotEmpty()) {
                    Text(
                        "请核对每节课的真实起止时间。未配置的节次不会安排课前提醒；新增节次的空白时间必须填写。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text("第 1 周开始日期", style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = {
                    WheelPickerDialogs.date(context, termStartDate ?: System.currentTimeMillis()) {
                        termStartDate = it
                        error = ""
                    }
                }) {
                    Text(termStartDate?.let(::formatDate).orEmpty().ifBlank { "选择日期" })
                }
                if (!Regex("20\\d{2}").containsMatchIn(term)) {
                    TextButton(onClick = { termStartDate = null }) { Text("清除开学日期") }
                }
                OutlinedTextField(
                    value = courseLeadMinutes,
                    onValueChange = { if (it.length <= 3 && it.all(Char::isDigit)) courseLeadMinutes = it },
                    label = { Text("课前提前提醒（分钟）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = prepHour,
                        onValueChange = { if (it.length <= 2 && it.all(Char::isDigit)) prepHour = it },
                        label = { Text("提醒时") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = prepMinute,
                        onValueChange = { if (it.length <= 2 && it.all(Char::isDigit)) prepMinute = it },
                        label = { Text("分") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                timeValues.toSortedMap().forEach { (period, time) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("第 $period 节", modifier = Modifier.width(52.dp), style = MaterialTheme.typography.labelMedium)
                        OutlinedTextField(
                            value = time.first,
                            onValueChange = { next ->
                                if (next.length <= 5) timeValues = timeValues + (period to (next to time.second))
                            },
                            label = { Text("开始") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = time.second,
                            onValueChange = { next ->
                                if (next.length <= 5) timeValues = timeValues + (period to (time.first to next))
                            },
                            label = { Text("结束") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(onClick = {
                val hour = prepHour.toIntOrNull()
                val minute = prepMinute.toIntOrNull()
                val leadMinutes = courseLeadMinutes.toIntOrNull()
                val periods = timeValues.mapNotNull { (period, time) ->
                    val start = parseClock(time.first)
                    val end = parseClock(time.second)
                    if (start == null || end == null) null else CoursePeriod(period, start, end)
                }
                when {
                    Regex("20\\d{2}").containsMatchIn(term) && termStartDate == null ->
                        error = "请填写该学期真实的第 1 周开始日期，避免课程提醒按错误周次触发"
                    hour == null || minute == null || hour !in 0..23 || minute !in 0..59 -> error = "提醒时间应为 00:00 至 23:59"
                    leadMinutes == null || leadMinutes !in 0..180 -> error = "课前提醒时间应在 0 至 180 分钟之间"
                    periods.size != timeValues.size || periods.any { !it.isValid() } -> error = "请输入完整的时间，例如 08:00；结束时间必须晚于开始时间"
                    else -> {
                        periodStore.save(accountId, periods)
                        tableSettings.savePreparationTime(accountId, hour, minute)
                        tableSettings.saveCourseLeadMinutes(accountId, leadMinutes)
                        tableSettings.saveTermStartDate(accountId, term, termStartDate)
                        onSaved()
                    }
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun formatClock(minutes: Int): String =
    "${(minutes / 60).toString().padStart(2, '0')}:${(minutes % 60).toString().padStart(2, '0')}"

private fun parseClock(value: String): Int? {
    val match = Regex("^(\\d{1,2}):(\\d{1,2})$").matchEntire(value.trim()) ?: return null
    val hour = match.groupValues[1].toIntOrNull() ?: return null
    val minute = match.groupValues[2].toIntOrNull() ?: return null
    return (hour * 60 + minute).takeIf { hour in 0..23 && minute in 0..59 }
}

private fun currentTeachingWeek(
    accountId: String,
    term: String,
    tableSettings: CourseTableSettingsStore
): Int = tableSettings.teachingWeek(accountId, term, System.currentTimeMillis())
    ?.let(TeachingWeekPicker::clamp) ?: TeachingWeekPicker.MIN_WEEK

private fun formatDate(day: Long?): String {
    if (day == null) return ""
    val calendar = Calendar.getInstance(Locale.CHINA).apply { timeInMillis = day }
    return "${calendar.get(Calendar.YEAR)}-${(calendar.get(Calendar.MONTH) + 1).toString().padStart(2, '0')}-${calendar.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')}"
}

private fun displayTermName(term: String): String = when {
    term.matches(Regex("\\d{4}-\\d{4}-1")) -> term.removeSuffix("-1") + "第一学期"
    term.matches(Regex("\\d{4}-\\d{4}-2")) -> term.removeSuffix("-2") + "第二学期"
    else -> term
}

private fun reloadSchedules(
    store: CourseScheduleStore,
    accountId: String,
    term: String,
    onLoaded: (List<String>, List<CourseSchedule>) -> Unit
) {
    val terms = store.terms(accountId).ifEmpty { listOf(term) }
    onLoaded(terms, store.list(accountId, term))
}

@Composable
private fun ManualCourseDialog(
    term: String,
    initial: CourseSchedule? = null,
    onDismiss: () -> Unit,
    onSave: (String, String, String, Int, Int, Int, Int, Int) -> Unit
) {
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var teacher by remember(initial) { mutableStateOf(initial?.teacher.orEmpty()) }
    var room by remember(initial) { mutableStateOf(initial?.room.orEmpty()) }
    var weekday by remember(initial) { mutableStateOf((initial?.weekday ?: todayWeekday()).toString()) }
    var start by remember(initial) { mutableStateOf((initial?.startPeriod ?: 1).toString()) }
    var end by remember(initial) { mutableStateOf((initial?.endPeriod ?: 2).toString()) }
    var startWeek by remember(initial) { mutableStateOf((initial?.startWeek ?: 1).toString()) }
    var endWeek by remember(initial) { mutableStateOf((initial?.endWeek ?: 20).toString()) }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "手动添加课程" else "编辑课程") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("学期：$term", style = MaterialTheme.typography.labelMedium)
                CourseTextField("课程名称", name) { name = it }
                CourseTextField("教师（可选）", teacher) { teacher = it }
                CourseTextField("教室（可选）", room) { room = it }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CourseNumberField("星期", weekday, 1..7, Modifier.weight(1f)) { weekday = it }
                    CourseNumberField("开始节", start, 1..24, Modifier.weight(1f)) { start = it }
                    CourseNumberField("结束节", end, 1..24, Modifier.weight(1f)) { end = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CourseNumberField("开始周", startWeek, 1..60, Modifier.weight(1f)) { startWeek = it }
                    CourseNumberField("结束周", endWeek, 1..60, Modifier.weight(1f)) { endWeek = it }
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(onClick = {
                val values = listOf(weekday, start, end, startWeek, endWeek).map { it.toIntOrNull() }
                val valid = name.isNotBlank() && values.all { it != null }
                if (!valid) {
                    error = "请填写课程名称和有效的数字"
                } else {
                    val (day, first, last, firstWeek, lastWeek) = values.map { it!! }
                    if (first > last || firstWeek > lastWeek) error = "节次或周次范围不正确"
                    else onSave(name.trim(), teacher.trim(), room.trim(), day, first, last, firstWeek, lastWeek)
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun CourseTextField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(value, onValueChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun CourseNumberField(
    label: String,
    value: String,
    range: IntRange,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { valueText ->
            val number = valueText.toIntOrNull()
            if (valueText.length <= 2 && (valueText.isEmpty() || (number != null && number in range))) {
                onValueChange(valueText)
            }
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

private val dayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

private fun todayWeekday(): Int {
    val day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
    return ((day + 5) % 7) + 1
}

private fun currentTerm(): String {
    return com.zhizhou.xxtassistant.data.AcademicYear.currentTerm()
}
