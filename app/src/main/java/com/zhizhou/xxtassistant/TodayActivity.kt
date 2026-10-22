package com.zhizhou.xxtassistant

import android.content.Intent
import android.os.Bundle
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.zhizhou.xxtassistant.data.AcademicYear
import com.zhizhou.xxtassistant.data.AccountManager
import com.zhizhou.xxtassistant.data.CourseSchedule
import com.zhizhou.xxtassistant.data.CourseScheduleStore
import com.zhizhou.xxtassistant.data.CourseTableSettingsStore
import com.zhizhou.xxtassistant.data.DeadlineOverrideStore
import com.zhizhou.xxtassistant.data.Homework
import com.zhizhou.xxtassistant.data.HomeworkCache
import com.zhizhou.xxtassistant.data.ManualHomeworkStore
import com.zhizhou.xxtassistant.data.TodayHomeworkPolicy
import com.zhizhou.xxtassistant.data.TodayTodo
import com.zhizhou.xxtassistant.data.TodayTodoStore
import com.zhizhou.xxtassistant.data.ViewSettings
import com.zhizhou.xxtassistant.remind.TodayTodoReminderWorker
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 今日聚合页：只读本地课表和作业，不触发网络请求。 */
class TodayActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uid = AccountManager(this).getActiveUid()
        if (uid.isNullOrBlank()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        setContent { TodayScreen(uid) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodayScreen(accountId: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val today = remember { System.currentTimeMillis() }
    val dayLabel = remember(today) {
        SimpleDateFormat("M月d日 E", Locale.CHINA).format(today)
    }
    val weekday = remember(today) {
        Calendar.getInstance().apply { timeInMillis = today }
            .get(Calendar.DAY_OF_WEEK).let { if (it == Calendar.SUNDAY) 7 else it - 1 }
    }
    val term = remember { AcademicYear.currentTerm(today) }
    val week = remember {
        CourseTableSettingsStore(context).teachingWeek(accountId, term, today) ?: 1
    }
    val courses = remember(accountId, term, weekday, week) {
        CourseScheduleStore(context).list(accountId, term)
            .filter { it.weekday == weekday && it.occursInWeek(week) }
            .sortedBy { it.startPeriod }
    }
    val viewSettings = remember { ViewSettings(context) }
    val homeworks = remember(accountId, viewSettings.filterAll, viewSettings.filterStartDay, viewSettings.filterEndDay) {
        val overrides = DeadlineOverrideStore(context)
        TodayHomeworkPolicy.filter(
            (HomeworkCache(context).load(accountId) + ManualHomeworkStore(context).list(accountId)).map(overrides::apply),
            viewSettings.filterAll,
            viewSettings.filterStartDay,
            ViewSettings.endOfDay(viewSettings.filterEndDay)
        )
            .take(8)
    }
    val dayKey = remember(today) { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(today) }
    val todoStore = remember { TodayTodoStore(context) }
    var todos by remember(accountId, dayKey) { mutableStateOf(todoStore.list(accountId, dayKey)) }
    var addTodoOpen by remember { mutableStateOf(false) }
    var plannedDate by remember { mutableStateOf(startOfToday()) }
    var reminderTime by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("今天 · $dayLabel") },
                actions = {
                    Box(
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .clickable { addTodoOpen = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("+", fontSize = 34.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            )
        },
        bottomBar = {
            XxtBottomNavigation(selectedTab = 0) { tab ->
                when (tab) {
                    1 -> context.navigateToTab(CourseTableActivity::class.java)
                    2 -> context.navigateToTab(MainActivity::class.java)
                    3 -> context.navigateToTab(MyActivity::class.java)
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item { Text("第 $week 周 · 今天的课程", style = MaterialTheme.typography.titleMedium) }
            if (courses.isEmpty()) {
                item { EmptyTodayCard("今天没有排课，给自己留一点整块时间吧。") }
            } else {
                items(courses, key = { it.id }) { course -> TodayCourseCard(course) }
            }
            item { Text("今日待办", style = MaterialTheme.typography.titleMedium) }
            if (todos.isEmpty()) {
                item { EmptyTodayCard("还没有今日待办，点击右上角 + 添加。") }
            } else {
                items(todos, key = { it.id }) { todo ->
                    TodayTodoCard(
                        todo = todo,
                        onToggle = {
                            todoStore.setDone(accountId, dayKey, todo.id, !todo.done)
                            todos = todoStore.list(accountId, dayKey)
                        },
                        onDelete = {
                            todoStore.delete(accountId, dayKey, todo.id)
                            todos = todoStore.list(accountId, dayKey)
                        }
                    )
                }
            }
            item { Text("需要留意的作业", style = MaterialTheme.typography.titleMedium) }
            if (homeworks.isEmpty()) {
                item { EmptyTodayCard("当前没有已知截止时间的未完成作业。") }
            } else {
                items(homeworks, key = { it.id }) { homework -> TodayHomeworkCard(homework) }
            }
        }
    }
    if (addTodoOpen) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addTodoOpen = false },
            title = { Text("添加今日待办") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("要做什么") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            val current = Calendar.getInstance().apply { timeInMillis = plannedDate }
                            DatePickerDialog(
                                context,
                                { _, year, month, day ->
                                    plannedDate = Calendar.getInstance().apply {
                                        set(year, month, day, 0, 0, 0)
                                        set(Calendar.MILLISECOND, 0)
                                    }.timeInMillis
                                },
                                current.get(Calendar.YEAR),
                                current.get(Calendar.MONTH),
                                current.get(Calendar.DAY_OF_MONTH)
                            ).apply {
                                datePicker.calendarViewShown = false
                                datePicker.spinnersShown = true
                            }.show()
                        }) { Text("日期 ${SimpleDateFormat("M月d日", Locale.CHINA).format(plannedDate)}") }
                        TextButton(onClick = {
                            val now = Calendar.getInstance()
                            val initial = reminderTime ?: (now.get(Calendar.HOUR_OF_DAY) to now.get(Calendar.MINUTE))
                            TimePickerDialog(
                                context,
                                { _, hour, minute -> reminderTime = hour to minute },
                                initial.first,
                                initial.second,
                                true
                            ).show()
                        }) { Text(reminderTime?.let { "提醒 ${"%02d:%02d".format(it.first, it.second)}" } ?: "设置时间") }
                    }
                    Text("待办会在所选日期当天显示；设置时间后会在该时刻提醒。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val scheduledAt = reminderTime?.let { (hour, minute) ->
                        Calendar.getInstance().apply {
                            timeInMillis = plannedDate
                            set(Calendar.HOUR_OF_DAY, hour)
                            set(Calendar.MINUTE, minute)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }.timeInMillis
                    }
                    val targetDayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(plannedDate)
                    val saved = todoStore.add(accountId, targetDayKey, text, scheduledAt)
                    if (saved != null && scheduledAt != null) {
                        TodayTodoReminderWorker.schedule(context, accountId, targetDayKey, saved)
                    }
                    todos = todoStore.list(accountId, dayKey)
                    addTodoOpen = false
                }, enabled = text.trim().isNotBlank()) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { addTodoOpen = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun TodayCourseCard(course: CourseSchedule) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer
    )) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(course.name, style = MaterialTheme.typography.titleMedium)
            Text(
                listOf(course.periodText(), course.room, course.teacher).filter { it.isNotBlank() }.joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TodayHomeworkCard(homework: Homework) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(homework.title, style = MaterialTheme.typography.titleSmall)
            Text(homework.courseName, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${homework.remainingText()} · 截止 ${MainActivity.formatDateTime(homework.endTime)}")
        }
    }
}

@Composable
private fun TodayTodoCard(todo: TodayTodo, onToggle: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(if (todo.done) "✓" else "○", modifier = Modifier.padding(end = 12.dp), color = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(todo.text, color = if (todo.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                todo.scheduledAt?.let {
                    Text(SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = onToggle) { Text(if (todo.done) "恢复" else "完成") }
            TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
        }
    }
}

private fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

@Composable
private fun EmptyTodayCard(message: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
