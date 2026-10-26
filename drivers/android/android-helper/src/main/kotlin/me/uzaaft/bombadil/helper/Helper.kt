package me.uzaaft.bombadil.helper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONObject
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Serves the accessibility tree and performs actions for Bombadil's adb
 * driver. Started with `am instrument -w`, it keeps one UiAutomation
 * connection open and answers line-delimited JSON requests on the
 * abstract socket `bombadil-helper`, which the host reaches through
 * `adb forward`. See the driver's AndroidHelper for the commands.
 */
class Helper : Instrumentation() {
    private lateinit var ui: UiAutomation
    private var apkSha256 = ""

    /** Nodes of the last `tree`, by id. */
    private var nodes: Map<String, Tree.Node> = HashMap()

    @Volatile
    private var activity: String? = null
    private val isActivity = HashMap<String, Boolean>()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        try {
            apkSha256 = sha256(context.applicationInfo.sourceDir)
            ui = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            val info = ui.serviceInfo
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            ui.serviceInfo = info
            ui.setOnAccessibilityEventListener { onEvent(it) }
            // For the task stack, see [topActivity].
            if (Build.VERSION.SDK_INT >= 29) ui.adoptShellPermissionIdentity()
            LocalServerSocket(SOCKET).use { server ->
                Log.i(TAG, "listening on @$SOCKET")
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
            val result = Bundle()
            result.putString("error", e.toString())
            finish(1, result)
        }
    }

    /**
     * Waits until no accessibility event has arrived for [quietMs], counting
     * from [since] at the earliest, or until [timeoutMs] passes. Unlike
     * UiAutomation.waitForIdle, this doesn't return at once when the last
     * event came before the action whose effects it's waiting for: an app
     * reports its changes a frame or two (Compose: up to 100ms) late.
     */
    private fun waitForIdle(since: Long, quietMs: Long, timeoutMs: Long): Boolean {
        val deadline = since + timeoutMs
        idleLock.withLock {
            while (true) {
                val now = SystemClock.uptimeMillis()
                val quietUntil = maxOf(lastEventTime, since) + quietMs
                if (now >= quietUntil) return true
                if (now >= deadline) return false
                event.await(minOf(quietUntil, deadline) - now, TimeUnit.MILLISECONDS)
            }
        }
    }

    /** Guards the event fields below; [event] is signalled on every accessibility event. */
    private val idleLock = ReentrantLock()
    private val event = idleLock.newCondition()
    private var lastEventTime = 0L
    private var lastEventNanos = 0L

    /** Window changes (windows added, removed or focused; activities and dialogs shown) seen so far. */
    @Volatile
    private var windowChanges = 0L

    private fun onEvent(e: AccessibilityEvent) {
        idleLock.withLock {
            lastEventTime = SystemClock.uptimeMillis()
            lastEventNanos = System.nanoTime()
            val type = e.eventType
            if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                windowChanges++
            }
            event.signalAll()
        }
        trackActivity(e)
    }

    // Waiting for the response to an action

    /** When the last action finished, in System.nanoTime(), the time base of frame stats. */
    @Volatile
    private var actedAt = 0L

    /** The windows whose frame stats were cleared before the last action. */
    private val armedWindows = HashSet<Int>()
    private var windowChangesAtArm = 0L
    private var refreshNanos = 16_666_667L

    /** Whether the last action was a global one (Back, Home). */
    @Volatile
    private var lastActionGlobal = false

    /** The top activity (package/class) when the last action was armed. */
    private var topAtArm: String? = null

    private val activityManager by lazy { context.getSystemService(ActivityManager::class.java) }

    /**
     * The top activity of the top task, as package/class. The task stack
     * changes as soon as an activity is started, finished or moved to the
     * back (in a few milliseconds), well before the windows follow: those
     * are announced up to ~200ms later, once the window manager reports
     * them to accessibility. Needs the shell's REAL_GET_TASKS, adopted at
     * start; null if it's unavailable.
     */
    @Suppress("DEPRECATION") // Still returns every task to the shell.
    private fun topActivity(): String? =
        runCatching { activityManager.getRunningTasks(1).firstOrNull()?.topActivity?.flattenToString() }.getOrNull()

    /** The windows that can respond to an action: the app's and the keyboard. */
    private fun respondingWindows(): Set<Int> {
        val ids = HashSet<Int>()
        for (w in ui.windows) {
            val type = w.type
            if (type == AccessibilityWindowInfo.TYPE_APPLICATION || type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                ids.add(w.id)
            }
        }
        return ids
    }

    /** Whether a window is active, and belongs to [pkg] if given. */
    private fun hasActiveWindow(pkg: String? = null): Boolean =
        ui.windows.any { it.isActive && it.root?.let { root -> pkg == null || root.packageName?.toString() == pkg } == true }

    /**
     * Whether the window of [top] (package/class) has been shown and its
     * package's window is active. The window state change that shows an
     * activity names it; until then the old window of the same app may
     * still be the active one.
     */
    private fun shown(top: String): Boolean =
        activity == top.substringAfter('/') && hasActiveWindow(top.substringBefore('/'))

    /** Before an action: forgets the frames shown so far, so that the next ones stand out. */
    private fun arm() {
        armedWindows.clear()
        for (id in respondingWindows()) {
            ui.clearWindowContentFrameStats(id)
            armedWindows.add(id)
        }
        windowChangesAtArm = windowChanges
        topAtArm = topActivity()
    }

    /**
     * After an action: waits for the app's response and returns the tree,
     * best effort, in the spirit of Bombadil's browser and terminal drivers:
     * it waits for the response to happen, not for the app to stop changing.
     * If the app is still animating, the next state shows the rest.
     *
     * Before the action, [arm] clears the frame stats of the app's windows
     * and the keyboard, and notes the top activity. Afterwards the signal
     * is one of:
     * - Frames: a watched window has presented a frame after the action, and
     *   neither a further frame nor an accessibility event has followed
     *   within [gapMs] (a few refresh periods), like the terminal driver
     *   reading output until the program stops writing. Frames cover
     *   content changes that send no accessibility events (most of
     *   Compose's).
     * - An activity change: the top activity differs from the one before
     *   the action (another activity or app started, or Back or Home left
     *   one). The response is over once a window of the new top activity's
     *   package is active and accessibility events and frames have paused
     *   for [gapMs].
     * - A window change (TYPE_WINDOWS_CHANGED or TYPE_WINDOW_STATE_CHANGED:
     *   a dialog or keyboard coming or going): the response is over once
     *   accessibility events and frames pause for [gapMs] and a window is
     *   active. New windows aren't polled for frame stats: on API 35 that
     *   delayed a dialog's first frame by most of a second.
     * Accessibility events wake the wait up; between them it polls the
     * frame stats and the top activity once per refresh period.
     *
     * Fallbacks, none of which is the mechanism:
     * - [frameTimeoutMs]: no frame, window or activity change at all, so
     *   the action changed nothing visible; the tree is reported as it is.
     * - [windowTimeoutMs]: a window or activity change that never goes
     *   quiet, or leaves no window active, is reported after this long.
     * - [drainMaxMs]: an app that animates without pause never ends its
     *   burst of frames; the tree is reported mid-animation.
     */
    private fun next(
        frameTimeoutMs: Long, windowTimeoutMs: Long, gapMs: Long, foreignGapMs: Long, drainMaxMs: Long, appPackage: String,
    ): String {
        val start = System.nanoTime()
        val since = if (actedAt == 0L) start else actedAt
        val deadline = start + frameTimeoutMs * 1_000_000
        val windowDeadline = start + windowTimeoutMs * 1_000_000
        val drainDeadline = start + drainMaxMs * 1_000_000
        val gap = gapMs * 1_000_000
        // Back or Home in another app (a browser the app opened): no signal
        // tells when it has finished handling them. WebView, say, presents a
        // frame and finishes its activity ~150ms later. Fallback: a longer gap.
        // The app under test finishes its activities while handling Back,
        // before its next frame, so the activity change is the signal there.
        val frameGap = if (lastActionGlobal && topAtArm != null && topAtArm!!.substringBefore('/') != appPackage) {
            foreignGapMs * 1_000_000
        } else {
            gap
        }
        var windowChanged = false
        // The new top activity (package/class), once it has changed.
        var newTop: String? = null
        // Frames seen per window: a window stops reporting stats once another
        // covers it, so keep the counts from earlier polls.
        val seenByWindow = HashMap<Int, Int>()
        var firstFrame = -1L
        var lastFrame = -1L
        var polls = 0
        var frames: Int
        var retries = 0
        var signal: String?
        var tree: String
        var waitedAt: Long
        while (true) {
            // Only the windows armed before the action: polling the stats of
            // a window that has just appeared delayed its first frame by
            // most of a second on API 35.
            polls++
            for (id in armedWindows) {
                val stats = ui.getWindowContentFrameStats(id) ?: continue
                if (stats.refreshPeriodNano > 0) refreshNanos = stats.refreshPeriodNano
                var n = 0
                for (i in 0 until stats.frameCount) {
                    val presented = stats.getFramePresentedTimeNano(i)
                    if (presented <= since) continue // Before the action, or not presented yet (-1).
                    n++
                    if (firstFrame < 0 || presented < firstFrame) firstFrame = presented
                    if (presented > lastFrame) lastFrame = presented
                }
                seenByWindow[id] = maxOf(seenByWindow[id] ?: n, n)
            }
            frames = seenByWindow.values.sum()
            if (newTop == null && topAtArm != null) {
                val top = topActivity()
                if (top != null && top != topAtArm) newTop = top
            }
            var lastEvent: Long
            idleLock.withLock {
                lastEvent = lastEventNanos
                if (windowChanges != windowChangesAtArm) windowChanged = true
            }
            val now = System.nanoTime()
            var until: Long
            if (windowChanged || newTop != null) {
                // An activity or window came or went: the response is over
                // once frames and accessibility events pause for a gap and
                // the new activity's window is active. New windows report
                // their content with events, so the pause in events stands
                // in for their frames.
                val quietSince = maxOf(lastEvent, lastFrame)
                signal = when {
                    now - quietSince >= gap && (if (newTop != null) shown(newTop) else hasActiveWindow()) ->
                        if (newTop != null) "activity" else "window"
                    now >= windowDeadline -> "windowCap"
                    else -> null
                }
                until = minOf(quietSince + gap, windowDeadline)
            } else if (frames > 0) {
                // Accessibility events count as output too.
                val quietSince = maxOf(lastFrame, if (lastEvent > since) lastEvent else 0)
                signal = if (now - quietSince >= frameGap) "frame" else if (now >= drainDeadline) "drainCap" else null
                until = minOf(quietSince + frameGap, drainDeadline)
            } else {
                signal = if (now >= deadline) "timeout" else null
                until = deadline
            }
            if (signal != null) {
                waitedAt = System.nanoTime()
                tree = tree()
                // Between one app's window going and another's coming, no
                // window is active; that's not worth reporting. Fallback:
                // after the window cap, report it anyway.
                if (tree.startsWith("{\"package\":\"") || System.nanoTime() >= windowDeadline) break
                retries++
                until = now + refreshNanos
            }
            idleLock.withLock {
                event.await(maxOf(1, minOf(refreshNanos, until - now) / 1_000_000), TimeUnit.MILLISECONDS)
            }
        }
        val waited = waitedAt
        val built = System.nanoTime()
        return "{\"ok\":true,\"signal\":\"" + signal + "\",\"waitMs\":" + (waited - start) / 1e6 +
            ",\"sinceActionMs\":" + (waited - since) / 1e6 +
            ",\"firstFrameMs\":" + (if (firstFrame < 0) -1.0 else (firstFrame - since) / 1e6) +
            ",\"lastFrameMs\":" + (if (lastFrame < 0) -1.0 else (lastFrame - since) / 1e6) +
            ",\"frames\":" + frames + ",\"polls\":" + polls + ",\"retries\":" + retries + ",\"buildMs\":" + (built - waited) / 1e6 +
            ",\"state\":" + tree + "}"
    }

    /**
     * For the in-process agent, when the app has started an activity or is
     * finishing one: waits until the top activity is no longer [fromTop],
     * its window has been shown (a window state change names it), a window
     * of its package is active, and frames
     * and accessibility events have paused for [gapMs], then returns the
     * tree. The same signals as [next]'s activity change, without frames
     * to watch (the agent acted, not the helper). Fallback:
     * [windowTimeoutMs], if the activity never changes (a start that
     * failed) or no window becomes active.
     */
    private fun await(fromTop: String?, gapMs: Long, windowTimeoutMs: Long): String {
        val start = System.nanoTime()
        val deadline = start + windowTimeoutMs * 1_000_000
        val gap = gapMs * 1_000_000
        var newTop: String? = null
        var polls = 0
        var signal: String?
        while (true) {
            polls++
            if (newTop == null) {
                val top = topActivity()
                if (top != null && top != fromTop) newTop = top
            }
            val lastEvent = idleLock.withLock { lastEventNanos }
            val now = System.nanoTime()
            signal = when {
                newTop != null && now - lastEvent >= gap && shown(newTop) -> "activity"
                now >= deadline -> "windowCap"
                else -> null
            }
            if (signal != null) break
            val until = if (newTop != null) minOf(lastEvent + gap, deadline) else deadline
            idleLock.withLock {
                event.await(maxOf(1, minOf(refreshNanos, until - now) / 1_000_000), TimeUnit.MILLISECONDS)
            }
        }
        val waited = System.nanoTime()
        val tree = tree()
        return "{\"ok\":true,\"signal\":\"" + signal + "\",\"waitMs\":" + (waited - start) / 1e6 + ",\"polls\":" + polls +
            ",\"buildMs\":" + (System.nanoTime() - waited) / 1e6 + ",\"state\":" + tree + "}"
    }

    /** Remembers the last activity shown, from window state changes. */
    private fun trackActivity(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        val key = "$pkg/$cls"
        var known = synchronized(isActivity) { isActivity[key] }
        if (known == null) {
            known = try {
                context.packageManager.getActivityInfo(ComponentName(pkg, cls), 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
            synchronized(isActivity) { isActivity[key] = known }
        }
        if (known) activity = cls
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

    private fun handle(request: JSONObject): String {
        val command = request.getString("cmd")
        if (command !in ACTIONS) return handleCommand(command, request)
        arm()
        lastActionGlobal = command == "global"
        try {
            return handleCommand(command, request)
        } finally {
            actedAt = System.nanoTime()
        }
    }

    private fun handleCommand(command: String, request: JSONObject): String {
        val response = JSONObject().put("ok", true)
        when (command) {
            "ping" -> return response.put("apkSha256", apkSha256).toString()
            "tree" -> {
                val start = SystemClock.uptimeMillis()
                val tree = tree()
                // Spliced in rather than parsed, to save the time.
                return "{\"ok\":true,\"buildMs\":" + (SystemClock.uptimeMillis() - start) + ",\"state\":" + tree + "}"
            }
            "settle" -> {
                // For the first state of a session only: waits for quiet and
                // fetches the tree until two trees in a row agree, so that
                // every session starts from the same state. After actions,
                // `next` is used instead.
                val start = SystemClock.uptimeMillis()
                val quietMs = request.getLong("quietMs")
                val deadline = start + request.getLong("timeoutMs")
                var idleMs = 0L
                var buildMs = 0L
                var trees = 0
                var previous: String? = null
                var tree: String
                while (true) {
                    val t0 = SystemClock.uptimeMillis()
                    waitForIdle(t0, quietMs, maxOf(0, deadline - t0))
                    val t1 = SystemClock.uptimeMillis()
                    tree = tree()
                    val t2 = SystemClock.uptimeMillis()
                    idleMs += t1 - t0
                    buildMs += t2 - t1
                    trees++
                    // No active window: between windows, e.g. while another app starts.
                    if ((tree == previous && tree.startsWith("{\"package\":\"")) || t2 >= deadline) break
                    previous = tree
                }
                return "{\"ok\":true,\"stable\":" + (tree == previous) + ",\"trees\":" + trees + ",\"idleMs\":" +
                    idleMs + ",\"buildMs\":" + buildMs + ",\"ms\":" + (SystemClock.uptimeMillis() - start) +
                    ",\"state\":" + tree + "}"
            }
            "await" -> return await(
                request.optString("fromTop").ifEmpty { null }, request.getLong("gapMs"), request.getLong("windowTimeoutMs"),
            )
            "next" -> return next(
                request.getLong("frameTimeoutMs"), request.getLong("windowTimeoutMs"),
                request.getLong("gapMs"), request.getLong("foreignGapMs"), request.getLong("drainMaxMs"),
                request.getString("appPackage"),
            )
            "click" -> return done(response, act(request, AccessibilityNodeInfo.ACTION_CLICK, false))
            "longClick" -> return done(response, act(request, AccessibilityNodeInfo.ACTION_LONG_CLICK, true))
            "setText" -> return done(response, setText(request))
            "scroll" -> return done(response, scroll(request))
            "tap" -> {
                tap(request.getInt("x"), request.getInt("y"), false)
                return done(response, "input")
            }
            "swipe" -> {
                swipe(
                    request.getInt("x1"), request.getInt("y1"), request.getInt("x2"), request.getInt("y2"),
                    request.optLong("durationMs", 300),
                )
                return done(response, "input")
            }
            "global" -> {
                val action = when (request.getString("action")) {
                    "back" -> AccessibilityService.GLOBAL_ACTION_BACK
                    "home" -> AccessibilityService.GLOBAL_ACTION_HOME
                    else -> throw IllegalArgumentException("unknown global action $request")
                }
                return done(response, if (ui.performGlobalAction(action)) "action" else null)
            }
            "key" -> {
                val code = request.getInt("code")
                val now = SystemClock.uptimeMillis()
                inject(
                    KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                        InputDevice.SOURCE_KEYBOARD),
                )
                inject(
                    KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                        InputDevice.SOURCE_KEYBOARD),
                )
                return done(response, "input")
            }
            else -> throw IllegalArgumentException("unknown command $command")
        }
    }

    // Tree

    private fun tree(): String {
        // Fetching each window's nodes in batches (API 33+) saves round trips
        // to the app. On API 35 a batch occasionally comes back malformed
        // (an NPE inside AccessibilityInteractionClient); fetch node by node
        // then.
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                return tree(PREFETCH)
            } catch (e: RuntimeException) {
                Log.w(TAG, "prefetching failed; fetching node by node", e)
            }
        }
        return tree(0)
    }

    private fun tree(prefetch: Int): String {
        // The node cache misses changes that apps don't send events for.
        if (Build.VERSION.SDK_INT >= 34) ui.clearCache()
        val infos = ArrayList(ui.windows)
        infos.sortWith { a, b -> b.layer.compareTo(a.layer) }
        val windows = ArrayList<Tree.Window>()
        var activePackage: String? = null
        var activeTitle: String? = null
        for (info in infos) {
            val root = (if (prefetch != 0) info.getRoot(prefetch) else info.root) ?: continue
            val window = Tree.Window()
            window.type = windowType(info.type)
            window.packageName = root.packageName?.toString() ?: ""
            window.title = info.title?.toString() ?: ""
            window.active = info.isActive
            window.focused = info.isFocused
            window.layer = info.layer
            val r = Rect()
            info.getBoundsInScreen(r)
            window.left = r.left
            window.top = r.top
            window.right = r.right
            window.bottom = r.bottom
            window.root = node(root, prefetch)
            windows.add(window)
            if (info.isActive) {
                activePackage = window.packageName
                activeTitle = window.title
            }
        }
        nodes = Tree.build(windows)
        return Tree.json(activePackage, activity, activeTitle, windows)
    }

    // Actions

    /** The node of the last tree with the request's `node` id, refreshed, or null if it's gone. */
    private fun find(request: JSONObject): AccessibilityNodeInfo? {
        val node = nodes[request.optString("node")] ?: return null
        val info = node.handle as AccessibilityNodeInfo
        return if (info.refresh()) info else null
    }

    /** Performs [action] on the node, or taps (or presses) its centre if it doesn't take the action. */
    private fun act(request: JSONObject, action: Int, longPress: Boolean): String {
        val node = find(request) ?: return "!no such node"
        if (node.actionList.contains(AccessibilityAction(action, null)) && node.performAction(action)) {
            return "action"
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return "!node has no area"
        tap(r.centerX(), r.centerY(), longPress)
        return "input"
    }

    /** Types `text` into `node`, or the focused input; appended to what's there unless `replace`. */
    private fun setText(request: JSONObject): String? {
        val node = (if (request.has("node")) find(request) else ui.findFocus(AccessibilityNodeInfo.FOCUS_INPUT))
        if (node == null || !node.isEditable) return "!no editable node"
        var current = node.text?.toString() ?: ""
        if (Build.VERSION.SDK_INT >= 26 && node.isShowingHintText) current = ""
        val text = if (request.optBoolean("replace")) request.getString("text") else current + request.getString("text")
        val arguments = Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) "action" else null
    }

    /**
     * Scrolls the node one step in `direction`, with its directional or
     * forward/backward accessibility action, or by dragging across it if
     * it has neither.
     */
    private fun scroll(request: JSONObject): String {
        val node = find(request) ?: return "!no such node"
        val direction = request.getString("direction")
        val (directional, relative) = when (direction) {
            "down" -> AccessibilityAction.ACTION_SCROLL_DOWN to AccessibilityAction.ACTION_SCROLL_FORWARD
            "up" -> AccessibilityAction.ACTION_SCROLL_UP to AccessibilityAction.ACTION_SCROLL_BACKWARD
            "right" -> AccessibilityAction.ACTION_SCROLL_RIGHT to AccessibilityAction.ACTION_SCROLL_FORWARD
            "left" -> AccessibilityAction.ACTION_SCROLL_LEFT to AccessibilityAction.ACTION_SCROLL_BACKWARD
            else -> throw IllegalArgumentException("unknown direction $direction")
        }
        val actions = node.actionList
        if (actions.contains(directional)) return if (node.performAction(directional.id)) "action" else "!at the end"
        val vertical = direction == "down" || direction == "up"
        // Forward/backward is ambiguous for nodes that scroll both ways; trust it for the other cases.
        if (actions.contains(relative)) return if (node.performAction(relative.id)) "action" else "!at the end"
        val r = Rect()
        node.getBoundsInScreen(r)
        val dx = if (vertical) 0 else r.width() * 3 / 10
        val dy = if (vertical) r.height() * 3 / 10 else 0
        val sign = if (direction == "down" || direction == "right") 1 else -1
        swipe(r.centerX() + sign * dx, r.centerY() + sign * dy, r.centerX() - sign * dx, r.centerY() - sign * dy, 300)
        return "input"
    }

    private fun tap(x: Int, y: Int, longPress: Boolean) {
        val down = SystemClock.uptimeMillis()
        inject(motion(down, down, MotionEvent.ACTION_DOWN, x, y))
        val up = down + if (longPress) 1000 else 60
        sleepUntil(up)
        inject(motion(down, up, MotionEvent.ACTION_UP, x, y))
    }

    /**
     * Drags from (x1, y1) to (x2, y2), then holds still before lifting so
     * that nothing flings: every event's time is planned rather than
     * measured, so the same swipe always scrolls the same distance.
     */
    private fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) {
        val down = SystemClock.uptimeMillis()
        inject(motion(down, down, MotionEvent.ACTION_DOWN, x1, y1))
        val steps = maxOf(1, durationMs / 10).toInt()
        for (i in 1..steps) {
            val t = down + durationMs * i / steps
            sleepUntil(t)
            inject(motion(down, t, MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * i / steps, y1 + (y2 - y1) * i / steps))
        }
        val end = down + durationMs
        for (i in 1..10) {
            sleepUntil(end + 20L * i)
            inject(motion(down, end + 20L * i, MotionEvent.ACTION_MOVE, x2, y2))
        }
        inject(motion(down, end + 200, MotionEvent.ACTION_UP, x2, y2))
    }

    private fun inject(event: InputEvent) {
        check(ui.injectInputEvent(event, true)) { "could not inject $event" }
    }

    private companion object {
        const val TAG = "BombadilHelper"
        const val SOCKET = "bombadil-helper"

        // A node and up to 50 descendants per round trip, without stopping
        // early when the next request comes in. Measured on API 35, Now in
        // Android: 10ms instead of 23ms (onboarding), 29ms instead of 34ms
        // (feed); depth-first was no faster, and without
        // FLAG_PREFETCH_UNINTERRUPTIBLE prefetching gained nothing.
        const val PREFETCH =
            AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID or AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE

        val ACTIONS = setOf("click", "longClick", "setText", "scroll", "tap", "swipe", "global", "key")

        /** `via` says how the action was done, or is null if it wasn't (with `reason`). */
        fun done(response: JSONObject, via: String?): String {
            if (via == null || via.startsWith("!")) {
                return response.put("done", false).put("reason", via?.substring(1) ?: "refused").toString()
            }
            return response.put("done", true).put("via", via).toString()
        }

        fun node(info: AccessibilityNodeInfo, prefetch: Int): Tree.Node {
            val node = Tree.Node()
            node.handle = info
            node.resourceId = str(info.viewIdResourceName)
            node.className = str(info.className)
            node.text = str(info.text)
            node.desc = str(info.contentDescription)
            node.clickable = info.isClickable
            node.longClickable = info.isLongClickable
            node.scrollable = info.isScrollable
            node.enabled = info.isEnabled
            node.checkable = info.isCheckable
            node.checked = info.isChecked
            node.selected = info.isSelected
            node.focused = info.isFocused
            node.editable = info.isEditable
            if (node.editable && Build.VERSION.SDK_INT >= 26 && info.isShowingHintText) node.text = ""
            val r = Rect()
            info.getBoundsInScreen(r)
            node.left = r.left
            node.top = r.top
            node.right = r.right
            node.bottom = r.bottom
            for (i in 0 until info.childCount) {
                val child = if (prefetch != 0) info.getChild(i, prefetch) else info.getChild(i)
                if (child != null && child.isVisibleToUser) node.children.add(node(child, prefetch))
            }
            return node
        }

        fun windowType(type: Int): String = when (type) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "inputMethod"
            AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "accessibilityOverlay"
            AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "splitScreenDivider"
            else -> "type$type"
        }

        fun str(s: CharSequence?): String = s?.toString() ?: ""

        fun motion(down: Long, time: Long, action: Int, x: Int, y: Int): MotionEvent {
            val event = MotionEvent.obtain(down, time, action, x.toFloat(), y.toFloat(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            return event
        }

        fun sleepUntil(uptime: Long) {
            val wait = uptime - SystemClock.uptimeMillis()
            if (wait > 0) Thread.sleep(wait)
        }

        fun sha256(path: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(path).use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
