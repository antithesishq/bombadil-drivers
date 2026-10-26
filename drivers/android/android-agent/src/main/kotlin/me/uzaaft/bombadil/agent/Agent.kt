package me.uzaaft.bombadil.agent

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Rect
import android.graphics.Region
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.MessageQueue
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Checkable
import android.widget.TextView
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertiesAndroid
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import me.uzaaft.bombadil.helper.Tree
import org.json.JSONObject
import java.util.WeakHashMap
import kotlin.math.roundToInt
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Bombadil's agent inside the app under test: an instrumentation whose
 * target is the app (and so signed with the app's key), started with
 * `am instrument`, so it runs in the app's process, as Espresso and
 * Compose's UI tests do. It reads the app's windows directly (Compose
 * semantics trees, and plain Views), acts on them, and after each action
 * waits for the app's main thread to have nothing left to do, from
 * Compose's and the main looper's own signals. It answers line-delimited
 * JSON requests on the abstract socket `bombadil-agent`; see the driver's
 * AppAgent for the commands.
 *
 * Only the agent's own classes are in its APK: it shares the app's class
 * loader, and the app provides Compose and the Kotlin standard library.
 */
class Agent : Instrumentation() {
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Compose roots ever created, attached or not. Main thread only. */
    private val composeRoots = WeakHashMap<ViewRootForTest, Unit>()

    /** Live activities in creation order, and which are resumed. Main thread only. */
    private val activities = ArrayList<Activity>()
    private val resumed = HashSet<Activity>()

    /** Counts every startActivity from the app (its filter matches everything). */
    private val starts = ActivityMonitor(null as android.content.IntentFilter?, null, false)

    /** Nodes of the last tree, by id. Main thread only. */
    private var nodes: Map<String, Tree.Node> = HashMap()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        // Before the app creates any view: Compose calls this for each root
        // (activity content, dialogs, popups).
        ViewRootForTest.onViewCreatedCallback = { root -> composeRoots[root] = Unit }
        addMonitor(starts)
        start()
    }

    override fun callActivityOnCreate(activity: Activity, icicle: Bundle?) {
        activities.add(activity)
        super.callActivityOnCreate(activity, icicle)
    }

    override fun callActivityOnResume(activity: Activity) {
        super.callActivityOnResume(activity)
        resumed.add(activity)
    }

    override fun callActivityOnPause(activity: Activity) {
        resumed.remove(activity)
        super.callActivityOnPause(activity)
    }

    override fun callActivityOnDestroy(activity: Activity) {
        activities.remove(activity)
        resumed.remove(activity)
        super.callActivityOnDestroy(activity)
    }

    override fun onStart() {
        try {
            LocalServerSocket(SOCKET).use { server ->
                Log.i(TAG, "listening on @$SOCKET in ${context.packageName}")
                while (true) {
                    try {
                        server.accept().use { serve(it) }
                    } catch (e: Exception) {
                        Log.w(TAG, "connection ended", e)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "failed", e)
            finish(1, Bundle().apply { putString("error", e.toString()) })
        }
    }

    private fun serve(socket: LocalSocket) {
        val input = socket.inputStream.bufferedReader(Charsets.UTF_8)
        val out = socket.outputStream.writer(Charsets.UTF_8)
        while (true) {
            val line = input.readLine() ?: break
            val response = try {
                handle(JSONObject(line))
            } catch (e: Exception) {
                Log.w(TAG, "request failed: $line", e)
                JSONObject().put("ok", false).put("error", e.toString()).toString()
            }
            out.write(response)
            out.write('\n'.code)
            out.flush()
        }
    }

    // Requests

    private fun handle(request: JSONObject): String {
        val command = request.getString("cmd")
        if (command == "ping") return JSONObject().put("ok", true).toString()
        val capMs = request.optLong("capMs", 2_000)
        if (command == "state") return state(capMs, JSONObject().put("ok", true))
        val started = SystemClock.uptimeMillis()
        val hitsBefore = starts.hits
        val top = onMain(capMs) { topActivity() }
        val response = JSONObject().put("ok", true)
        val via = act(command, request)
        response.put("actionMs", SystemClock.uptimeMillis() - started)
        if (via == null || via.startsWith("!")) {
            response.put("done", false).put("reason", via?.substring(1) ?: "refused")
        } else {
            response.put("done", true).put("via", via)
        }
        // Leaving the app's current activity (another one started, or this
        // one finishing): the system takes over, and the driver follows it
        // from outside the process.
        val finishing = onMain(capMs) { activities.any { it.isFinishing } } ?: false
        if (starts.hits != hitsBefore || finishing) {
            return response.put("leaving", true).put("from", top ?: JSONObject.NULL).toString()
        }
        return state(capMs, response)
    }

    /** Waits for the app to settle and adds the state, or `settled: false` and no state if it can't be read. */
    private fun state(capMs: Long, response: JSONObject): String {
        val settle = settle(capMs)
        response.put("settled", settle.reason == null).put("busy", settle.reason ?: JSONObject.NULL)
            .put("waitMs", settle.waitMs).put("rounds", settle.rounds).put("buildMs", settle.buildMs)
            .put("focused", settle.focused).put("resumed", settle.resumed).put("top", settle.top ?: JSONObject.NULL)
        for ((reason, ms) in settle.busyMs) response.put("busyMs.$reason", ms)
        val tree = settle.tree ?: return response.put("settled", false).toString()
        // Spliced in rather than parsed, to save the time.
        val head = response.toString()
        return head.substring(0, head.length - 1) + ",\"state\":" + tree + "}"
    }

    private fun act(command: String, request: JSONObject): String? = when (command) {
        "click" -> onMain(request) { node -> click(node, long = false) }.orTap(request, long = false)
        "longClick" -> onMain(request) { node -> click(node, long = true) }.orTap(request, long = true)
        "setText" -> onMain(1_000) { setText(request) ?: "!refused" } ?: "!main thread busy"
        "scroll" -> {
            val result = onMain(request) { node -> scroll(node, request.getString("direction")) }
            if (result is Swipe) {
                injected { swipe(result.x1, result.y1, result.x2, result.y2, 300) }
            } else {
                result as String?
            }
        }
        "tap" -> injected { tap(request.getInt("x"), request.getInt("y"), long = false) }
        "swipe" -> injected {
            swipe(request.getInt("x1"), request.getInt("y1"), request.getInt("x2"), request.getInt("y2"),
                request.optLong("durationMs", 300))
        }
        "key" -> injected { sendKeyDownUpSync(request.getInt("code")) }
        else -> throw IllegalArgumentException("unknown command $command")
    }

    /** A point to tap, for a node that takes no action. */
    private class Tap(val x: Int, val y: Int)
    private class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int)

    /** Runs [block] on the main thread with the request's node, or says it's gone. */
    private fun onMain(request: JSONObject, block: (Tree.Node) -> Any?): Any? = onMain(1_000) {
        val node = nodes[request.optString("node")] ?: return@onMain "!no such node"
        block(node)
    } ?: "!main thread busy"

    private fun Any?.orTap(request: JSONObject, long: Boolean): String? {
        if (this !is Tap) return this as String?
        return injected { tap(x, y, long) }
    }

    /**
     * Runs an input injection. The system refuses one aimed at a window the
     * app doesn't own (a node under the navigation bar, or a window that
     * has just gone): the action is then not done, and the agent carries on.
     */
    private fun injected(block: () -> Unit): String = try {
        block()
        "input"
    } catch (e: IllegalArgumentException) {
        "!input refused: ${e.message}"
    } catch (e: SecurityException) {
        "!input refused: ${e.message}"
    }

    private fun click(node: Tree.Node, long: Boolean): Any? {
        if (covered(node)) return bounds(node)?.let { Tap(it.centerX(), it.centerY()) } ?: "!node has no area"
        when (val handle = node.handle) {
            is SemanticsHandle -> {
                val semantics = handle.resolve() ?: return "!no such node"
                val action = semantics.config.opt(if (long) SemanticsActions.OnLongClick else SemanticsActions.OnClick)
                if (action?.action?.invoke() == true) return "action"
            }
            is View -> {
                if ((if (long) handle.isLongClickable else handle.isClickable) &&
                    (if (long) handle.performLongClick() else handle.performClick())
                ) {
                    return "action"
                }
            }
        }
        val r = bounds(node) ?: return "!node has no area"
        return Tap(r.centerX(), r.centerY())
    }

    private fun setText(request: JSONObject): String? {
        val node = if (request.has("node")) nodes[request.getString("node")] else focusedEditable()
        if (node != null && covered(node)) return "!covered by another window"
        val append = !request.optBoolean("replace")
        val text = request.getString("text")
        when (val handle = node?.handle) {
            is SemanticsHandle -> {
                val semantics = handle.resolve() ?: return "!no editable node"
                val current = semantics.config.opt(SemanticsProperties.EditableText)?.text
                    ?: return "!no editable node"
                val action = semantics.config.opt(SemanticsActions.SetText) ?: return "!no editable node"
                return if (action.action?.invoke(AnnotatedString(if (append) current + text else text)) == true) {
                    "action"
                } else {
                    null
                }
            }
            is TextView -> {
                if (!handle.onCheckIsTextEditor()) return "!no editable node"
                if (append) handle.append(text) else handle.text = text
                return "action"
            }
            else -> return "!no editable node"
        }
    }

    /**
     * Whether another of the app's windows that takes input (a dialog, say)
     * is in front of the node's. Semantics actions and View methods would reach the
     * node anyway, which no user can: act on it with real input instead,
     * which goes to the window in front.
     */
    private fun covered(node: Tree.Node): Boolean {
        val window = when (val handle = node.handle) {
            is SemanticsHandle -> handle.root.view.rootView
            is View -> handle.rootView
            else -> return false
        }
        val windows = roots().map { it.first }
        val top = topFocusable(windows) ?: return false
        return window !== top && windows.indexOf(window) > windows.indexOf(top)
    }

    private fun focusedEditable(): Tree.Node? = nodes.values.firstOrNull { it.focused && it.editable }

    /**
     * Scrolls the node a viewport in `direction` with Compose's ScrollBy,
     * or a View's accessibility scroll action, or by dragging across it.
     */
    private fun scroll(node: Tree.Node, direction: String): Any? {
        val vertical = direction == "down" || direction == "up"
        val sign = if (direction == "down" || direction == "right") 1f else -1f
        when (val handle = node.handle.takeUnless { covered(node) }) {
            is SemanticsHandle -> {
                val semantics = handle.resolve() ?: return "!no such node"
                val config = semantics.config
                val scrollBy = config.opt(SemanticsActions.ScrollBy)?.action
                if (scrollBy != null) {
                    val range = config.opt(if (vertical) SemanticsProperties.VerticalScrollAxisRange else SemanticsProperties.HorizontalScrollAxisRange)
                    if (range != null) {
                        val value = range.value()
                        val reverse = if (range.reverseScrolling) -1f else 1f
                        val forward = sign * reverse > 0
                        if (forward && value >= range.maxValue() || !forward && value <= 0f) return "!at the end"
                    }
                    val b = semantics.boundsInWindow
                    val amount = sign * (if (vertical) b.bottom - b.top else b.right - b.left)
                    val done = if (vertical) scrollBy(0f, amount) else scrollBy(amount, 0f)
                    return if (done) "action" else "!at the end"
                }
            }
            is View -> {
                val action = if (sign > 0) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                val can = if (vertical) handle.canScrollVertically(sign.toInt()) else handle.canScrollHorizontally(sign.toInt())
                if (!can) return "!at the end"
                if (handle.performAccessibilityAction(action, null)) return "action"
            }
        }
        val r = bounds(node) ?: return "!node has no area"
        val dx = if (vertical) 0 else r.width() * 3 / 10
        val dy = if (vertical) r.height() * 3 / 10 else 0
        val s = sign.toInt()
        return Swipe(r.centerX() + s * dx, r.centerY() + s * dy, r.centerX() - s * dx, r.centerY() - s * dy)
    }

    private fun bounds(node: Tree.Node): Rect? =
        Rect(node.left, node.top, node.right, node.bottom).takeUnless { it.isEmpty }

    // Input, injected into the app's own windows. Not on the main thread.

    private fun tap(x: Int, y: Int, long: Boolean) {
        val down = SystemClock.uptimeMillis()
        sendPointerSync(motion(down, down, MotionEvent.ACTION_DOWN, x, y))
        val up = down + if (long) 1000 else 60
        sleepUntil(up)
        sendPointerSync(motion(down, up, MotionEvent.ACTION_UP, x, y))
    }

    /** As the helper's: planned event times, and a pause before lifting so that nothing flings. */
    private fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) {
        val down = SystemClock.uptimeMillis()
        sendPointerSync(motion(down, down, MotionEvent.ACTION_DOWN, x1, y1))
        val steps = maxOf(1, durationMs / 10).toInt()
        for (i in 1..steps) {
            val t = down + durationMs * i / steps
            sleepUntil(t)
            sendPointerSync(motion(down, t, MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * i / steps, y1 + (y2 - y1) * i / steps))
        }
        val end = down + durationMs
        for (i in 1..10) {
            sleepUntil(end + 20L * i)
            sendPointerSync(motion(down, end + 20L * i, MotionEvent.ACTION_MOVE, x2, y2))
        }
        sendPointerSync(motion(down, end + 200, MotionEvent.ACTION_UP, x2, y2))
    }

    // Settling

    private class Settled(
        val tree: String?,
        /** What kept the app busy when the cap was reached, or null if it settled. */
        val reason: String?,
        val waitMs: Long,
        val rounds: Int,
        val buildMs: Long,
        val focused: Boolean,
        val resumed: Boolean,
        /** The resumed activity, as package/class. */
        val top: String? = null,
        /** How long each reason kept the app busy. */
        val busyMs: Map<String, Long> = emptyMap(),
    )

    /**
     * Waits until the app's main thread has nothing left to do for the
     * last action, then builds the tree there. Idle means, as in Compose's
     * UI tests: no snapshot changes waiting to be applied, no Recomposer
     * with pending work (recompositions, or animations waiting for a
     * frame), no Compose root with a pending measure or layout, and no
     * message due in the main looper's queue (which includes pending View
     * traversals). Each check runs on the main thread; while busy, the
     * next check runs after the next frame or when the queue goes idle,
     * whichever comes first: signals, not timers.
     *
     * Best effort, like Bombadil's browser driver: work on other threads
     * (loading data, images) isn't waited for; the next state shows it.
     * The only timeout is [capMs], for apps that never go idle (an endless
     * animation): the tree is then reported as it is, with `settled:
     * false`, and if the main thread is blocked (an ANR), without a tree.
     */
    private fun settle(capMs: Long): Settled {
        val start = SystemClock.uptimeMillis()
        val deadline = start + capMs
        val done = CountDownLatch(1)
        var result: Settled? = null
        var error: Throwable? = null
        val check = object : Runnable, Choreographer.FrameCallback, MessageQueue.IdleHandler {
            var rounds = 0
            var waiting = false

            // How long each reason kept the app busy.
            val busyMs = LinkedHashMap<String, Long>()
            var lastReason: String? = null
            var lastCheck = start

            override fun run() {
                waiting = false
                rounds++
                try {
                    check()
                } catch (e: Throwable) {
                    // Never take the app down with the agent.
                    Log.e(TAG, "settling failed", e)
                    error = e
                    done.countDown()
                }
            }

            fun check() {
                // Resumed, but the app's front window doesn't have input focus
                // yet: a dialog just opened, or closed, and focus moves a frame
                // or two later. If it doesn't within FOCUS_WAIT_MS (a fallback),
                // something outside the app is in front, and the driver asks the
                // helper.
                val reason = busy() ?: if (resumed.isNotEmpty() && !frontFocused() &&
                    SystemClock.uptimeMillis() < start + FOCUS_WAIT_MS
                ) {
                    "focus"
                } else {
                    null
                }
                val now = SystemClock.uptimeMillis()
                lastReason?.let { busyMs[it] = (busyMs[it] ?: 0) + now - lastCheck }
                lastReason = reason
                lastCheck = now
                if (reason == null || SystemClock.uptimeMillis() >= deadline) {
                    val built = SystemClock.uptimeMillis()
                    val tree = tree()
                    result = Settled(
                        tree, reason, built - start, rounds, SystemClock.uptimeMillis() - built,
                        frontFocused(), resumed.isNotEmpty(), topActivity(), busyMs,
                    )
                    done.countDown()
                    return
                }
                waiting = true
                // Messages due: check again once the queue has run them. Anything
                // else waits for a frame: recomposition, layout and animations
                // happen in frames, and focus comes back with one.
                if (reason == "queue") Looper.myQueue().addIdleHandler(this) else Choreographer.getInstance().postFrameCallback(this)
            }

            override fun doFrame(frameTimeNanos: Long) {
                if (!waiting) return
                // After this frame's other callbacks (Compose's recomposition)
                // and traversal: post rather than check now.
                main.post(this)
                waiting = false
            }

            override fun queueIdle(): Boolean {
                if (waiting) {
                    waiting = false
                    // Not run() here: it may add this idle handler again, which
                    // returning false would then remove.
                    main.post(this)
                }
                return false
            }
        }
        main.post(check)
        val inTime = done.await(capMs + 100, TimeUnit.MILLISECONDS)
        error?.let { throw IllegalStateException("settling failed: $it", it) }
        if (!inTime) {
            // Busy without frames or messages to wake up for (say, a
            // Recomposer whose frames are paused), or blocked: take the tree
            // as it is if the main thread is free at all.
            main.post {
                try {
                    if (result == null) {
                    val built = SystemClock.uptimeMillis()
                    result = Settled(tree(), busy() ?: "nothing", built - start, check.rounds,
                        SystemClock.uptimeMillis() - built, frontFocused(), resumed.isNotEmpty())
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "reading the tree failed", e)
                }
                done.countDown()
            }
            if (!done.await(capMs, TimeUnit.MILLISECONDS)) {
                return Settled(null, "blocked", SystemClock.uptimeMillis() - start, check.rounds, 0, false, false)
            }
        }
        return result!!
    }

    /** What the app is still busy with, or null if it's idle. Main thread only. */
    private fun busy(): String? {
        if (Snapshot.isApplyObserverNotificationPending) Snapshot.sendApplyNotifications()
        if (Recomposer.runningRecomposers.value.any { it.hasPendingWork }) return "recomposer"
        if (composeRoots.keys.any { visible(it.view) && it.hasPendingMeasureOrLayout }) return "layout"
        if (!Looper.myQueue().isIdle) return "queue"
        if (coroutinesBusy() || backgroundBusy()) return "background"
        return null
    }

    // Background work

    private val threadGroup: ThreadGroup by lazy {
        var group = Thread.currentThread().threadGroup!!
        while (group.parent != null) group = group.parent
        group
    }

    /** The scheduler behind coroutines' Default and IO dispatchers, if the app's kotlinx.coroutines has one. */
    private val coroutineScheduler: Any? by lazy {
        runCatching {
            val default = kotlinx.coroutines.Dispatchers.Default
            var type: Class<*>? = default.javaClass
            while (type != null && type.name != "kotlinx.coroutines.scheduling.SchedulerCoroutineDispatcher") type = type.superclass
            type?.getDeclaredField("coroutineScheduler")?.apply { isAccessible = true }?.get(default)
        }.onFailure { Log.w(TAG, "no coroutine scheduler; watching its threads only", it) }.getOrNull()
    }

    /**
     * Whether coroutines' Default or IO dispatcher has work: a task queued,
     * or a worker running one. Unlike the workers' thread states, this also
     * sees a task that has been handed to a parked worker that hasn't woken
     * up yet, as happens right after a click launches a coroutine on IO. The
     * scheduler only tells through its description, so this reads that.
     */
    private fun coroutinesBusy(): Boolean {
        val description = coroutineScheduler?.toString() ?: return false
        val workers = SCHEDULER_WORKERS.find(description) ?: return false
        val queues = SCHEDULER_QUEUES.find(description) ?: return false
        val local = SCHEDULER_LOCAL.find(description) ?: return false
        return workers.groupValues.drop(1).any { it != "0" } || queues.groupValues.drop(1).any { it != "0" } ||
            local.groupValues[1].isNotBlank()
    }

    /**
     * Whether one of the app's background workers is running: coroutines'
     * IO and Default dispatchers, and Room's. Most of an app's responses
     * to input go through them (ViewModel, repository, database, back to
     * the main thread through a flow), and while they run, their result
     * hasn't reached the main thread yet. Idle workers are parked.
     */
    private fun backgroundBusy(): Boolean {
        val threads = arrayOfNulls<Thread>(threadGroup.activeCount() + 16)
        val n = threadGroup.enumerate(threads, true)
        for (i in 0 until n) {
            val thread = threads[i] ?: continue
            if (thread.state == Thread.State.RUNNABLE && BACKGROUND_POOLS.any { thread.name.startsWith(it) }) return true
        }
        return false
    }

    // Tree

    /** The app's visible windows, topmost first: dialogs and popups, then the resumed activity. */
    private fun roots(): List<Pair<View, String>> {
        val windows = ArrayList<Pair<View, String>>()
        val activity = topActivityInstance()
        val decor = activity?.window?.peekDecorView()
        for (root in composeRoots.keys) {
            val view = root.view.rootView
            if (view !== decor && visible(root.view) && windows.none { it.first === view }) {
                windows.add(view to "")
            }
        }
        if (decor != null && decor.isAttachedToWindow) windows.add(decor to (activity!!.title?.toString() ?: ""))
        return windows
    }

    /** Whether [view] is in a window that's shown: a stopped activity's stays attached, but hidden. */
    private fun visible(view: View): Boolean =
        view.isAttachedToWindow && view.windowVisibility == View.VISIBLE && view.isShown

    private fun topActivityInstance(): Activity? = activities.lastOrNull { it in resumed }

    private fun topActivity(): String? = topActivityInstance()?.componentName?.flattenToString()

    /** The app's front window that takes input: popups don't. */
    private fun topFocusable(windows: List<View> = roots().map { it.first }): View? = windows.firstOrNull {
        val params = it.layoutParams as? WindowManager.LayoutParams
        params == null || params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0
    }

    private fun modal(view: View): Boolean {
        val flags = (view.layoutParams as? WindowManager.LayoutParams)?.flags ?: return false
        return flags and (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL) == 0
    }

    /** Whether the app's front window has input focus, so that nothing else is in front of it. */
    private fun frontFocused(): Boolean = topFocusable()?.hasWindowFocus() == true

    private fun tree(): String {
        val windows = ArrayList<Tree.Window>()
        var focus: String? = null
        val roots = roots()
        for ((i, pair) in roots.withIndex()) {
            val (view, title) = pair
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val window = Tree.Window()
            window.type = "application"
            window.packageName = targetContext.packageName
            window.title = title
            window.focused = view.hasWindowFocus()
            window.active = window.focused
            window.layer = roots.size - i
            window.left = location[0]
            window.top = location[1]
            window.right = location[0] + view.width
            window.bottom = location[1] + view.height
            val clip = Rect(window.left, window.top, window.right, window.bottom)
            window.root = viewNode(view, location, clip)
            windows.add(window)
            if (window.focused) focus = title
            // A modal window (a dialog) takes every touch, so accessibility
            // leaves out the windows behind it; so does the state.
            if (modal(view)) break
        }
        nodes = Tree.build(windows)
        val packageName = if (windows.any { it.focused }) targetContext.packageName else null
        return Tree.json(packageName, topActivityInstance()?.javaClass?.name, focus, windows)
    }

    private fun viewNode(view: View, origin: IntArray, clip: Rect): Tree.Node {
        val node = Tree.Node()
        node.handle = view
        node.className = view.accessibilityClassName?.toString() ?: view.javaClass.name
        node.resourceId = resourceName(view)
        if (view is TextView) node.text = view.text?.toString() ?: ""
        node.desc = view.contentDescription?.toString() ?: ""
        node.clickable = view.isClickable
        node.longClickable = view.isLongClickable
        node.scrollable = view.canScrollVertically(1) || view.canScrollVertically(-1) ||
            view.canScrollHorizontally(1) || view.canScrollHorizontally(-1)
        node.enabled = view.isEnabled
        node.checkable = view is Checkable
        node.checked = (view as? Checkable)?.isChecked == true
        node.selected = view.isSelected
        node.focused = view.isFocused
        node.editable = view is TextView && view.onCheckIsTextEditor()
        val r = Rect()
        if (view.getGlobalVisibleRect(r)) {
            r.offset(origin[0], origin[1])
            if (!r.intersect(clip)) r.setEmpty()
        } else {
            r.setEmpty()
        }
        node.left = r.left; node.top = r.top; node.right = r.right; node.bottom = r.bottom
        if (view is ViewRootForTest) {
            // A Compose root: its semantics instead of its (one) View.
            val semantics = view.semanticsOwner.unmergedRootSemanticsNode
            val location = IntArray(2)
            view.view.rootView.getLocationOnScreen(location)
            semanticsNode(view, semantics, location, r, Region(r), isRoot = true)?.let { node.children.add(it) }
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (child.visibility == View.VISIBLE && child.id !in BAR_BACKGROUNDS) node.children.add(viewNode(child, origin, r))
            }
        }
        return node
    }

    private fun resourceName(view: View): String {
        if (view.id == View.NO_ID || view.id ushr 24 == 0) return ""
        return try {
            view.resources.getResourceName(view.id)
        } catch (e: Exception) {
            ""
        }
    }

    /** Points at a semantics node by its id, which lasts as long as the node, to act on it later. */
    private class SemanticsHandle(val root: ViewRootForTest, val id: Int) {
        fun resolve(): SemanticsNode? {
            fun find(node: SemanticsNode): SemanticsNode? {
                if (node.id == id) return node
                for (child in node.children) find(child)?.let { return it }
                return null
            }
            return find(root.semanticsOwner.unmergedRootSemanticsNode)
        }
    }

    /**
     * The node and its descendants as accessibility would show them, or
     * null if it wouldn't, following Compose's accessibility layer
     * (`getAllUncoveredSemanticsNodesToIntObjectMap`): nodes that aren't
     * placed, are transparent or hidden from accessibility are left out,
     * and so are nodes whose whole area is covered by important nodes in
     * front of them (later siblings, and their descendants), as when a
     * screen stays composed behind the one on top. [uncovered] is the
     * root's area not yet covered, in screen coordinates; bounds are clipped
     * to it and the window ([clip]), not to the parent, as there; children are
     * visited front to back, as Compose does, and each important node
     * takes its area out of it.
     */
    private fun semanticsNode(
        root: ViewRootForTest, semantics: SemanticsNode, origin: IntArray, clip: Rect, uncovered: Region, isRoot: Boolean = false,
        tagsAsIds: Boolean = false,
    ): Tree.Node? {
        val config = semantics.config
        // Test tags show up as resource ids where the app asks for it, for a
        // node and its descendants (`testTagsAsResourceId`).
        val tags = config.opt(SemanticsPropertiesAndroid.TestTagsAsResourceId) ?: tagsAsIds
        if (!isRoot && (!semantics.layoutInfo.isPlaced || !semantics.layoutInfo.isAttached || hidden(semantics))) return null
        // Touch bounds, as accessibility reports: at least 48dp for clickable
        // nodes. They're relative to the Compose root; move them into the window.
        val inWindow = semantics.boundsInWindow
        val inRoot = semantics.boundsInRoot
        val b = semantics.touchBoundsInRoot
        val dx = inWindow.left - inRoot.left + origin[0]
        val dy = inWindow.top - inRoot.top + origin[1]
        val touch = Rect(round(b.left + dx), round(b.top + dy), round(b.right + dx), round(b.bottom + dy))
        val visible = Region(touch)
        if (!visible.op(uncovered, Region.Op.INTERSECT) && !isRoot) return null
        val r = Rect(visible.bounds)
        if (!r.intersect(clip)) r.setEmpty()
        val node = Tree.Node()
        node.handle = SemanticsHandle(root, semantics.id)
        val editable = config.opt(SemanticsProperties.EditableText)
        val text = config.opt(SemanticsProperties.Text)
        node.text = editable?.text ?: text?.joinToString(", ") { it.text } ?: ""
        node.desc = config.opt(SemanticsProperties.ContentDescription)?.joinToString(", ") ?: ""
        node.resourceId = if (tags) config.opt(SemanticsProperties.TestTag) ?: "" else ""
        node.className = className(config, editable != null, text != null)
        // As accessibility reports them: a selected tab or radio button takes no click.
        val role = config.opt(SemanticsProperties.Role)
        val selected = config.opt(SemanticsProperties.Selected) == true
        node.clickable = config.has(SemanticsActions.OnClick) && !(selected && (role == Role.Tab || role == Role.RadioButton))
        node.longClickable = config.has(SemanticsActions.OnLongClick)
        node.scrollable = config.has(SemanticsActions.ScrollBy) && (canScroll(config.opt(SemanticsProperties.VerticalScrollAxisRange)) ||
            canScroll(config.opt(SemanticsProperties.HorizontalScrollAxisRange)))
        node.enabled = !config.has(SemanticsProperties.Disabled)
        val toggle = config.opt(SemanticsProperties.ToggleableState)
        node.checkable = toggle != null
        node.checked = toggle == ToggleableState.On
        node.selected = selected
        node.focused = config.opt(SemanticsProperties.Focused) == true
        node.editable = editable != null
        node.left = r.left; node.top = r.top; node.right = r.right; node.bottom = r.bottom
        val children = semantics.children
        val kept = arrayOfNulls<Tree.Node>(children.size)
        for (i in children.indices.reversed()) kept[i] = semanticsNode(root, children[i], origin, clip, uncovered, tagsAsIds = tags)
        kept.filterNotNullTo(node.children)
        if (!isRoot && important(semantics)) uncovered.op(touch, Region.Op.DIFFERENCE)
        return node
    }

    private fun round(x: Float): Int = x.roundToInt()

    private fun hidden(semantics: SemanticsNode): Boolean =
        semantics.config.has(SemanticsProperties.HideFromAccessibility) ||
            @Suppress("DEPRECATION") semantics.config.has(SemanticsProperties.InvisibleToUser) ||
            IS_TRANSPARENT?.invoke(semantics) == true

    /** Whether accessibility would announce the node: then it hides what's behind it. */
    private fun important(semantics: SemanticsNode): Boolean {
        val config = semantics.config
        if (config.isMergingSemanticsOfDescendants) return true
        CONTAINS_IMPORTANT?.let { return it.invoke(config) == true }
        return config.has(SemanticsProperties.Text) || config.has(SemanticsProperties.ContentDescription) ||
            config.has(SemanticsProperties.EditableText) || config.has(SemanticsActions.OnClick) ||
            config.has(SemanticsProperties.ToggleableState) || config.has(SemanticsProperties.StateDescription)
    }

    private fun canScroll(range: androidx.compose.ui.semantics.ScrollAxisRange?): Boolean =
        range != null && (range.value() > 0f || range.value() < range.maxValue())

    /** The class an accessibility service would see, from the node's role. */
    private fun className(config: SemanticsConfiguration, editable: Boolean, text: Boolean): String {
        val role = config.opt(SemanticsProperties.Role)
        return when {
            role == Role.Button -> "android.widget.Button"
            role == Role.Checkbox -> "android.widget.CheckBox"
            role == Role.Switch -> "android.widget.Switch"
            role == Role.RadioButton -> "android.widget.RadioButton"
            role == Role.Image -> "android.widget.ImageView"
            role == Role.DropdownList -> "android.widget.Spinner"
            editable -> "android.widget.EditText"
            text -> "android.widget.TextView"
            else -> "android.view.View"
        }
    }

    private fun <T> SemanticsConfiguration.opt(key: SemanticsPropertyKey<T>): T? = getOrElseNullable(key) { null }

    private fun SemanticsConfiguration.has(key: SemanticsPropertyKey<*>): Boolean = contains(key)

    // Threads

    /** Runs [block] on the main thread and returns its result, or null if it didn't run within [timeoutMs]. */
    private fun <T> onMain(timeoutMs: Long, block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val done = CountDownLatch(1)
        var result: T? = null
        var error: Throwable? = null
        main.post {
            try {
                result = block()
            } catch (e: Throwable) {
                error = e
            }
            done.countDown()
        }
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        error?.let { throw it }
        return result
    }

    private companion object {
        const val TAG = "BombadilAgent"
        const val SOCKET = "bombadil-agent"
        const val FOCUS_WAIT_MS = 300L
        val BACKGROUND_POOLS = listOf("DefaultDispatcher-worker-", "arch_disk_io_")
        // Compose internals its accessibility layer uses; public in the bytecode.
        val IS_TRANSPARENT: java.lang.reflect.Method? =
            runCatching { SemanticsNode::class.java.getMethod("isTransparent\$ui") }.getOrNull()
        val CONTAINS_IMPORTANT: java.lang.reflect.Method? =
            runCatching { SemanticsConfiguration::class.java.getMethod("containsImportantForAccessibility\$ui") }.getOrNull()

        val SCHEDULER_WORKERS = Regex("CPU = (\\d+), blocking = (\\d+)")
        val SCHEDULER_QUEUES = Regex("global CPU queue size = (\\d+), global blocking queue size = (\\d+)")
        val SCHEDULER_LOCAL = Regex("running workers queues = \\[([^\\]]*)\\]")

        // The decor's backgrounds behind the system bars, which accessibility doesn't show.
        val BAR_BACKGROUNDS = setOf(android.R.id.statusBarBackground, android.R.id.navigationBarBackground)

        fun motion(down: Long, time: Long, action: Int, x: Int, y: Int): MotionEvent =
            MotionEvent.obtain(down, time, action, x.toFloat(), y.toFloat(), 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }

        fun sleepUntil(uptime: Long) {
            val wait = uptime - SystemClock.uptimeMillis()
            if (wait > 0) Thread.sleep(wait)
        }
    }
}
