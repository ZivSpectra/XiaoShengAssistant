package com.zhizhou.xxtassistant.data

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Runs independent course fetches without letting one course erase the other
 * courses' results. Course-list failures are handled by the caller; this
 * runner only deals with failures after a course has already been scheduled.
 */
internal object CourseBatchRunner {

    fun <T, R> run(
        items: List<T>,
        parallelism: Int,
        fetch: (T) -> R,
        onSuccess: (T, R) -> Unit,
        onFailure: (T, Exception) -> Unit
    ): List<R> {
        if (items.isEmpty()) return emptyList()
        val pool: ExecutorService = Executors.newFixedThreadPool(parallelism.coerceAtLeast(1))
        try {
            val futures = items.map { item ->
                pool.submit(Callable {
                    try {
                        val value = fetch(item)
                        onSuccess(item, value)
                        value
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw e
                    } catch (e: Exception) {
                        onFailure(item, e)
                        null
                    }
                })
            }
            val result = mutableListOf<R>()
            try {
                futures.forEach { future ->
                    future.get()?.let(result::add)
                }
            } catch (e: InterruptedException) {
                futures.forEach { it.cancel(true) }
                Thread.currentThread().interrupt()
                throw e
            } catch (e: ExecutionException) {
                futures.forEach { it.cancel(true) }
                throw (e.cause ?: e)
            }
            return result
        } finally {
            pool.shutdownNow()
        }
    }
}
