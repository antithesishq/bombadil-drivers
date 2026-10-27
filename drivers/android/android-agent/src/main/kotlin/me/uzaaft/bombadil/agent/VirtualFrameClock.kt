package me.uzaaft.bombadil.agent

import android.view.Choreographer
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.WindowRecomposerFactory
import androidx.compose.ui.platform.WindowRecomposerPolicy
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer

/**
 * The frame clock of the app's window Recomposers when the agent runs
 * with a virtual frame clock: the Android counterpart of compose-ui-test's
 * `MainTestClock` with `autoAdvance = false`, and of the Flutter driver's
 * virtual clock. While [virtual] is off, frames come from vsync as usual
 * (the Choreographer, through `AndroidUiDispatcher`'s clock). While it's
 * on (the agent settles after an action), the Recomposer's frame requests
 * wait here until the agent runs a frame with [frame], which it does as
 * soon as nothing but frame requests is left, instead of waiting for the
 * next vsync. Frame times advance by one frame interval per frame, so
 * animations play their frames back to back. Main thread only.
 *
 * Installed through [WindowRecomposerPolicy] before the app creates any
 * window, so every window Recomposer (and with it every composition,
 * dialog and popup, and every animation, which run on the Recomposer's
 * clock) uses it.
 */
object VirtualFrameClock : MonotonicFrameClock {
    /** Whether frames are virtual (run by [frame]) rather than vsync's. */
    var virtual = false
        set(value) {
            field = value
            if (!value && pending.hasAwaiters) handOver()
        }

    /** Frame requests waiting for a virtual frame. */
    private val pending = BroadcastFrameClock()

    private val vsync: MonotonicFrameClock by lazy { AndroidUiDispatcher.Main[MonotonicFrameClock]!! }

    /** Time between frames, as the display's refresh rate gives it. */
    var frameNanos = 16_666_667L

    /**
     * The last frame time handed out, and how far frame time is ahead of
     * vsync's: frame times stay monotonic when frames switch between
     * virtual and real, and real frames keep advancing at real speed.
     */
    private var last = 0L
    private var ahead = 0L

    /** Virtual frames run so far. */
    var frames = 0L
        private set

    fun install() {
        WindowRecomposerPolicy.setFactory(
            WindowRecomposerFactory { view ->
                // Both arguments: R8 drops the default-arguments bridge.
                view.createLifecycleAwareWindowRecomposer(AndroidUiDispatcher.Main + VirtualFrameClock, null)
            },
        )
    }

    /** Whether a Recomposer waits for a virtual frame. */
    val hasAwaiters: Boolean get() = pending.hasAwaiters

    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R =
        if (virtual) pending.withFrameNanos(onFrame) else vsync.withFrameNanos { onFrame(real(it)) }

    /**
     * Runs one virtual frame: what a Choreographer frame does for Compose.
     * The Recomposers' frame (animations, then recomposition and applying
     * the changes) and then [traversal], which measures and lays out the
     * Compose roots, as compose-ui-test's `TestMonotonicFrameClock` does
     * with `onPerformTraversals`. Coroutines the frame resumes run after
     * it, in the main queue, before the agent's next check.
     */
    fun frame(traversal: () -> Unit) {
        val time = maxOf(last + frameNanos, System.nanoTime() + ahead)
        last = time
        ahead = time - System.nanoTime()
        frames++
        pending.sendFrame(time)
        traversal()
    }

    private fun real(vsyncNanos: Long): Long {
        val time = maxOf(vsyncNanos + ahead, last + 1)
        last = time
        return time
    }

    /**
     * Frame requests left when virtual frames stop (the agent's cap, or an
     * action that leaves the activity): the next vsync serves them, unless
     * virtual frames have started again by then.
     */
    private fun handOver() {
        Choreographer.getInstance().postFrameCallback { vsyncNanos ->
            if (!virtual && pending.hasAwaiters) pending.sendFrame(real(vsyncNanos))
        }
    }
}
