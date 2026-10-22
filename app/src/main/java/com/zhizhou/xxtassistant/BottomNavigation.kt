package com.zhizhou.xxtassistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource

/** Flags used by every root-tab transition; kept explicit for regression tests. */
internal const val TAB_NAVIGATION_FLAGS: Int =
    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP

internal fun shouldNavigateToTab(current: Class<*>, target: Class<*>): Boolean =
    current != target

/**
 * Switches between the four root pages without growing an Activity stack.
 *
 * Every root page is a singleTop destination. REORDER_TO_FRONT keeps an
 * already-created page alive and moves it to the front; SINGLE_TOP prevents a
 * second instance when the destination is already visible. Removing the
 * default transition also keeps rapid bottom-bar taps visually continuous.
 */
@Suppress("DEPRECATION")
fun Context.navigateToTab(target: Class<out Activity>) {
    val activity = this as? Activity ?: return
    if (!shouldNavigateToTab(activity.javaClass, target)) return
    activity.startActivity(
        Intent(activity, target).addFlags(TAB_NAVIGATION_FLAGS)
    )
    activity.overridePendingTransition(0, 0)
}

/** Shared four-tab navigation used by the Compose pages. */
@Composable
fun XxtBottomNavigation(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    val tabs = listOf(
        R.drawable.ic_nav_today to "今天",
        R.drawable.ic_nav_schedule to "课程表",
        R.drawable.ic_nav_homework to "作业",
        R.drawable.ic_nav_mine to "我的"
    )
    NavigationBar {
        tabs.forEachIndexed { index, (icon, label) ->
            NavigationBarItem(
                selected = selectedTab == index,
                onClick = { onTabSelected(index) },
                icon = { Icon(painterResource(icon), contentDescription = label) },
                label = { Text(label) }
            )
        }
    }
}
