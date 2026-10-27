package me.uzaaft.bombadil.agent

import android.os.Looper
import android.os.Message
import android.os.MessageQueue
import android.os.SystemClock
import android.util.Log

/**
 * What's due in the main looper's queue, read the way Espresso's
 * `Interrogator` does (the queue's head and each message's `next`, which
 * are hidden). The virtual frame clock needs more than
 * `MessageQueue.isIdle`: a traversal's sync barrier holds back plain
 * messages until the next vsync frame, while asynchronous ones (frames,
 * input, Compose's coroutines) still run. Main thread only.
 */
object MainQueue {
    /** Due now: [async] messages, [sync] ones not held back by a barrier, and whether a [barrier] is up. */
    class Due(val async: Boolean, val sync: Boolean, val barrier: Boolean)

    private val head = runCatching { MessageQueue::class.java.getDeclaredField("mMessages").apply { isAccessible = true } }
        .onFailure { Log.w("BombadilAgent", "can't read the main queue", it) }.getOrNull()
    private val next = runCatching { Message::class.java.getDeclaredField("next").apply { isAccessible = true } }.getOrNull()

    /** What's due, or null if the queue can't be read. */
    fun due(): Due? {
        val head = head ?: return null
        val next = next ?: return null
        val queue = Looper.getMainLooper().queue
        val now = SystemClock.uptimeMillis()
        var async = false
        var sync = false
        var barrier = false
        synchronized(queue) {
            var m = head.get(queue) as Message?
            // Sorted by time: nothing after a message that isn't due is.
            while (m != null && m.`when` <= now) {
                when {
                    m.target == null -> barrier = true
                    m.isAsynchronous -> async = true
                    !barrier -> sync = true
                }
                m = next.get(m) as Message?
            }
        }
        return Due(async, sync, barrier)
    }
}
