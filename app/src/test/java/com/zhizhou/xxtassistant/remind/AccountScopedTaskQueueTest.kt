package com.zhizhou.xxtassistant.remind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor

class AccountScopedTaskQueueTest {
    @Test
    fun latestQueuedRebuildSupersedesOlderRebuildForSameScheduleFamily() {
        val worker = QueuedExecutor()
        val queue = AccountScopedTaskQueue(worker, Executor { it.run() })
        val applied = mutableListOf<Int>()

        queue.submit("courses", { true }, { applied += 1 })
        queue.submit("courses", { true }, { applied += 2 })
        worker.runAll()

        assertEquals(listOf(2), applied)
    }

    @Test
    fun queuedRebuildIsSkippedWhenItsAccountIsNoLongerActive() {
        val worker = QueuedExecutor()
        val queue = AccountScopedTaskQueue(worker, Executor { it.run() })
        var accountActive = true
        var applied = false

        queue.submit("homework", { accountActive }, { applied = true })
        accountActive = false
        worker.runAll()

        assertTrue(!applied)
    }

    @Test
    fun logoutInvalidationSkipsPendingRebuilds() {
        val worker = QueuedExecutor()
        val queue = AccountScopedTaskQueue(worker, Executor { it.run() })
        var applied = false

        queue.submit("courses", { true }, { applied = true })
        queue.invalidateAll()
        worker.runAll()

        assertTrue(!applied)
    }

    private class QueuedExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }
}
