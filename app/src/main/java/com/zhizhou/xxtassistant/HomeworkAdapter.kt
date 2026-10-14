package com.zhizhou.xxtassistant

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.zhizhou.xxtassistant.data.Homework
import com.zhizhou.xxtassistant.data.PinnedStore
import com.zhizhou.xxtassistant.data.ViewSettings

/** 作业列表：状态分区优先，课程分组只发生在每个状态区内部。 */
@SuppressLint("NotifyDataSetChanged")
class HomeworkAdapter(
    private val onClick: (Homework) -> Unit,
    private val onPin: (Homework) -> Unit,
    private val onToggleCompleted: (Homework) -> Unit,
    private val onDelete: (Homework) -> Unit,
    private val onEditDeadline: (Homework) -> Unit,
    private val onHideCourse: (String, List<Homework>) -> Unit,
    private val onSelectionChanged: (Int) -> Unit,
    private val settings: ViewSettings,
    private val pinned: PinnedStore,
    private val accountId: () -> String?
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private enum class Section(val title: String, val color: Int, val background: Int) {
        MISSED("欠账区", R.color.status_missed, 0xFFF5CFCFu.toInt()),
        UPCOMING("未截止", R.color.status_pending, 0xFFFFF2CCu.toInt()),
        DONE("已完成", R.color.status_done, 0xFFE8F5E9u.toInt()),
        UNKNOWN("待补截止时间", R.color.text_secondary, 0xFFEFF2F5u.toInt())
    }

    private data class SectionHeaderToken(val section: Section)
    private data class GroupId(val section: Section, val identity: String)
    private data class GroupHeaderToken(val id: GroupId)

    companion object {
        private const val TYPE_ITEM = 0
        private const val TYPE_GROUP_HEADER = 1
        private const val TYPE_SECTION_HEADER = 2
        private const val TYPE_MISSED_TAUNT = 3
    }

    private object MissedTaunt
    private val plan = mutableListOf<Any>()
    private val items = mutableListOf<Homework>()
    private val selectedKeys = linkedSetOf<String>()
    private val groups = LinkedHashMap<GroupId, MutableList<Homework>>()
    private var mode = settings.mode
    private var layout = if (settings.mode == ViewSettings.MODE_GROUP) settings.layout else ViewSettings.LAYOUT_SINGLE
    private var selectionMode = false
    private val collapsedGroups = mutableSetOf<String>()
    private val collapsedSections = mutableSetOf<String>()
    private val knownGroupKeys = mutableSetOf<String>()
    private var missedTaunt = ""

    private val missedTaunts = arrayOf(
        "你的胆子真是肥嘟嘟的～竟然敢欠作业！",
        "唔…这几单还没交，主人是打算欠到明年吗？",
        "哼哼，作业都堆成小山啦，主人快醒醒！",
        "这些作业都睡过头了，主人还不快补上～"
    )

    fun setViewSettings(newMode: Int, newLayout: Int) {
        val modeChanged = mode != newMode
        mode = newMode
        layout = if (newMode == ViewSettings.MODE_GROUP) newLayout else ViewSettings.LAYOUT_SINGLE
        if (modeChanged) {
            collapsedGroups.clear()
            knownGroupKeys.clear()
            collapsedSections.clear()
        }
        rebuildPlan()
        notifyDataSetChanged()
    }

    fun resetAccountState() {
        collapsedGroups.clear()
        collapsedSections.clear()
        knownGroupKeys.clear()
        selectedKeys.clear()
        selectionMode = false
        onSelectionChanged(0)
    }

    fun selectedItems(): List<Homework> = items.filter { homeworkKey(it) in selectedKeys }

    fun isSelectionMode(): Boolean = selectionMode

    fun clearSelection() {
        if (selectedKeys.isEmpty() && !selectionMode) return
        selectedKeys.clear()
        selectionMode = false
        onSelectionChanged(0)
        notifyDataSetChanged()
    }

    fun setAllGroupsExpanded(expanded: Boolean) {
        if (expanded) {
            collapsedGroups.clear()
            collapsedSections.clear()
        } else {
            collapsedGroups.addAll(groups.keys.map(::groupKey))
            collapsedSections.addAll(Section.values().map(::sectionKey))
        }
        rebuildPlan()
        notifyDataSetChanged()
    }

    fun spanSizeForPosition(position: Int): Int =
        if (position in 0 until itemCount && getItemViewType(position) == TYPE_ITEM) 1 else 2

    fun submit(newItems: List<Homework>) {
        items.clear()
        items.addAll(
            newItems
                .filterNot(::isCompletedWithUnknownDeadline)
                .sortedWith(
                    compareBy<Homework> { !pinned.isPinned(it.accountId, it.id, it.courseKey) }
                        .thenBy { sectionOf(it).ordinal }
                        .thenBy { if (it.endTime <= 0L) Long.MAX_VALUE else it.endTime }
                        .thenBy { it.courseName }
                        .thenBy { it.title }
                )
        )
        selectedKeys.retainAll(items.map(::homeworkKey).toSet())
        if (selectedKeys.isEmpty()) selectionMode = false
        onSelectionChanged(selectedKeys.size)
        groups.clear()
        for (hw in items) {
            val section = sectionOf(hw)
            groups.getOrPut(GroupId(section, groupIdentity(hw))) { mutableListOf() }.add(hw)
        }
        val currentKeys = groups.keys.map(::groupKey).toSet()
        if (mode == ViewSettings.MODE_GROUP) {
            collapsedGroups.addAll(currentKeys - knownGroupKeys)
            knownGroupKeys.addAll(currentKeys)
        }
        collapsedGroups.removeAll { it.startsWith("${accountId() ?: ""}\u0000") && it !in currentKeys }
        collapsedSections.retainAll(Section.values().map(::sectionKey).toSet())
        rebuildPlan()
        notifyDataSetChanged()
    }

    private fun sectionOf(hw: Homework): Section = when {
        hw.endTime <= 0L -> Section.UNKNOWN
        hw.isMissed() -> Section.MISSED
        hw.status == Homework.DONE -> Section.DONE
        else -> Section.UPCOMING
    }

    private fun groupIdentity(homework: Homework): String = homework.courseKey.ifBlank { homework.courseName }
    private fun homeworkKey(homework: Homework): String = "${homework.accountId}:${homework.courseKey}:${homework.id}"
    private fun isCompletedWithUnknownDeadline(homework: Homework): Boolean = homework.status == Homework.DONE && homework.endTime <= 0L
    private fun sectionKey(section: Section): String = "${accountId() ?: ""}\u0000section:${section.name}"
    private fun groupKey(id: GroupId): String = "${sectionKey(id.section)}\u0000${id.identity}"

    private fun rebuildPlan() {
        plan.clear()
        Section.values().forEach { section ->
            val sectionItems = items.filter { sectionOf(it) == section }
            if (sectionItems.isEmpty()) return@forEach
            plan += SectionHeaderToken(section)
            if (sectionKey(section) in collapsedSections) return@forEach
            if (section == Section.MISSED) {
                missedTaunt = missedTaunts.random()
                plan += MissedTaunt
            }
            if (mode == ViewSettings.MODE_GROUP) {
                groups.filterKeys { it.section == section }.forEach { (id, list) ->
                    plan += GroupHeaderToken(id)
                    if (groupKey(id) !in collapsedGroups) plan.addAll(list)
                }
            } else {
                plan.addAll(sectionItems)
            }
        }
    }

    override fun getItemViewType(position: Int): Int = when (plan[position]) {
        is SectionHeaderToken -> TYPE_SECTION_HEADER
        is GroupHeaderToken -> TYPE_GROUP_HEADER
        MissedTaunt -> TYPE_MISSED_TAUNT
        else -> TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SECTION_HEADER -> SectionHeaderHolder(inflater.inflate(R.layout.item_section_header, parent, false))
            TYPE_GROUP_HEADER -> GroupHeaderHolder(inflater.inflate(R.layout.item_group_header, parent, false))
            TYPE_MISSED_TAUNT -> MissedTauntHolder(inflater.inflate(R.layout.item_missed_taunt, parent, false))
            else -> ItemHolder(inflater.inflate(R.layout.item_homework, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val unit = plan[position]) {
            is SectionHeaderToken -> bindSectionHeader(holder as SectionHeaderHolder, unit.section)
            is GroupHeaderToken -> bindGroupHeader(holder as GroupHeaderHolder, unit.id)
            MissedTaunt -> (holder as MissedTauntHolder).tvTaunt.text = missedTaunt
            else -> bindItem(holder as ItemHolder, position)
        }
    }

    private fun bindSectionHeader(holder: SectionHeaderHolder, section: Section) {
        val count = items.count { sectionOf(it) == section }
        val collapsed = sectionKey(section) in collapsedSections
        holder.tvTitle.text = section.title
        holder.tvSummary.text = holder.itemView.context.getString(
            if (collapsed) R.string.section_summary_expand else R.string.section_summary_collapse,
            count
        )
        holder.ivArrow.rotation = if (collapsed) 0f else 180f
        val color = ContextCompat.getColor(holder.itemView.context, section.color)
        holder.tvTitle.setTextColor(color)
        holder.tvSummary.setTextColor(color)
        holder.ivArrow.setColorFilter(color)
        holder.itemView.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(holder.itemView.context, 10).toFloat()
            setColor(section.background)
        }
        holder.itemView.setOnClickListener {
            val key = sectionKey(section)
            if (!collapsedSections.add(key)) collapsedSections.remove(key)
            rebuildPlan()
            notifyDataSetChanged()
        }
    }

    private fun bindGroupHeader(holder: GroupHeaderHolder, id: GroupId) {
        val list = groups[id] ?: return
        val unfinished = list.count { it.isUnfinished() && !it.isMissed() }
        val done = list.count { it.status == Homework.DONE }
        val expanded = groupKey(id) !in collapsedGroups
        holder.tvCourse.text = list.firstOrNull()?.courseName ?: id.identity
        holder.tvSummary.text = holder.itemView.context.getString(
            R.string.course_summary,
            unfinished,
            done
        )
        holder.ivArrow.rotation = if (expanded) 180f else 0f
        holder.itemView.setOnClickListener {
            val key = groupKey(id)
            if (!collapsedGroups.add(key)) collapsedGroups.remove(key)
            rebuildPlan()
            notifyDataSetChanged()
        }
        holder.btnMore.setOnClickListener { onHideCourse(id.identity, list.toList()) }
        holder.itemView.setOnLongClickListener {
            onHideCourse(id.identity, list.toList())
            true
        }
    }

    private fun bindItem(holder: ItemHolder, position: Int) {
        val hw = plan[position] as Homework
        val context = holder.itemView.context
        val selected = homeworkKey(hw) in selectedKeys
        holder.tvTitle.text = hw.title
        holder.tvCourse.text = if (hw.isManual()) "${hw.courseName} · 手动添加" else hw.courseName
        holder.tvTime.text = if (hw.endTime > 0L) {
            "截止 ${MainActivity.formatTime(hw.endTime)}" + if (hw.deadlineOverridden) " · 手动" else ""
        } else "截止时间未知 · 可手动设置"
        holder.tvRemaining.text = if (layout == ViewSettings.LAYOUT_DOUBLE) hw.shortRemainingText() else hw.remainingText()
        holder.tvPinTag.visibility = if (pinned.isPinned(hw.accountId, hw.id, hw.courseKey)) View.VISIBLE else View.GONE

        val (colorRes, badgeBg) = when {
            hw.isMissed() -> R.color.status_missed to R.drawable.bg_badge_red
            hw.status == Homework.PENDING -> R.color.status_pending to R.drawable.bg_badge_red
            hw.status == Homework.SUBMITTED -> R.color.status_submitted to R.drawable.bg_badge_orange
            else -> R.color.status_done to R.drawable.bg_badge_green
        }
        val color = ContextCompat.getColor(context, colorRes)
        holder.tvStatus.text = if (hw.isMissed()) "已错过" else hw.statusText()
        holder.tvStatus.setTextColor(color)
        holder.tvStatus.setBackgroundResource(badgeBg)
        holder.viewStatusBar.setBackgroundColor(color)
        holder.tvRemaining.setTextColor(color)
        holder.content.isSelected = selected

        val completionCheck = !selectionMode && (hw.isManual() || hw.isMissed()) && hw.status != Homework.SUBMITTED
        holder.cbAction.visibility = if (selectionMode || completionCheck) View.VISIBLE else View.GONE
        holder.cbAction.isChecked = if (selectionMode) selected else hw.status == Homework.DONE
        holder.cbAction.setOnClickListener {
            if (selectionMode) toggleSelection(hw) else onToggleCompleted(hw)
        }
        holder.btnMore.visibility = View.VISIBLE
        holder.btnMore.setOnClickListener { showMoreMenu(holder.btnMore, hw) }
        holder.content.setOnClickListener {
            if (selectionMode) toggleSelection(hw) else onClick(hw)
        }
        holder.content.setOnLongClickListener {
            if (!selectionMode) {
                selectionMode = true
                selectedKeys.clear()
            }
            toggleSelection(hw)
            true
        }
    }

    private fun toggleSelection(hw: Homework) {
        val key = homeworkKey(hw)
        if (!selectedKeys.add(key)) selectedKeys.remove(key)
        if (selectedKeys.isEmpty()) selectionMode = false
        onSelectionChanged(selectedKeys.size)
        notifyDataSetChanged()
    }

    private fun showMoreMenu(anchor: View, hw: Homework) {
        PopupMenu(anchor.context, anchor).apply {
            menu.add(if (hw.isUnfinished()) "标记本地完成" else "取消本地完成")
                .setOnMenuItemClickListener { onToggleCompleted(hw); true }
            menu.add(if (pinned.isPinned(hw.accountId, hw.id, hw.courseKey)) "取消置顶" else "置顶")
                .setOnMenuItemClickListener { onPin(hw); true }
            menu.add(if (hw.deadlineOverridden) "恢复官方时间" else "修改截止时间")
                .setOnMenuItemClickListener { onEditDeadline(hw); true }
            menu.add("隐藏作业").setOnMenuItemClickListener { onDelete(hw); true }
            menu.add("隐藏所属课程").setOnMenuItemClickListener {
                val identity = groupIdentity(hw)
                onHideCourse(identity, items.filter { groupIdentity(it) == identity })
                true
            }
            show()
        }
    }

    override fun getItemCount(): Int = plan.size

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    class ItemHolder(view: View) : RecyclerView.ViewHolder(view) {
        val content: View = view.findViewById(R.id.swipe_content)
        val cbAction: CheckBox = view.findViewById(R.id.cb_action)
        val btnMore: ImageView = view.findViewById(R.id.btn_more)
        val viewStatusBar: View = view.findViewById(R.id.view_status_bar)
        val tvTitle: TextView = view.findViewById(R.id.tv_hw_title)
        val tvPinTag: TextView = view.findViewById(R.id.tv_pin_tag)
        val tvCourse: TextView = view.findViewById(R.id.tv_hw_course)
        val tvTime: TextView = view.findViewById(R.id.tv_hw_time)
        val tvRemaining: TextView = view.findViewById(R.id.tv_hw_remaining)
        val tvStatus: TextView = view.findViewById(R.id.tv_hw_status)
    }

    class SectionHeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivArrow: ImageView = view.findViewById(R.id.iv_section_arrow)
        val tvTitle: TextView = view.findViewById(R.id.tv_section_title)
        val tvSummary: TextView = view.findViewById(R.id.tv_section_summary)
    }

    class GroupHeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivArrow: ImageView = view.findViewById(R.id.iv_group_arrow)
        val tvCourse: TextView = view.findViewById(R.id.tv_group_course)
        val tvSummary: TextView = view.findViewById(R.id.tv_group_summary)
        val btnMore: ImageView = view.findViewById(R.id.btn_group_more)
    }

    class MissedTauntHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTaunt: TextView = view.findViewById(R.id.tv_missed_taunt)
    }
}
