package me.uzaaft.bombadil.android

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.uzaaft.bombadil.BombadilDriver
import me.uzaaft.bombadil.runDriver
import java.io.File

/**
 * Drives an installed Android app over adb, through the on-device helper
 * ([AndroidHelper]): the state is the focused window and the
 * accessibility tree of every interactive window, and actions are
 * accessibility actions on nodes (falling back to injected touches), text
 * input, swipes and global actions.
 *
 * Each session clears the app's data and launches it fresh. The first state
 * is reported once two trees in a row agree; after each action, the state
 * is reported best effort, once the app has responded (see the helper's
 * `next`), even if it is still animating.
 *
 * With an agent ([AppAgent]), the agent reads and drives the app from
 * inside its process while the app's windows are in front, and reports
 * each state once the app's main thread and background workers are idle;
 * the helper takes over when the app starts or finishes an activity, or
 * something else is in front.
 *
 * Actions, externally tagged:
 * - `{"Tap": selector}` clicks the node [selector] picks; see [select].
 * - `{"Tap": {"x": x, "y": y}}` taps a point.
 * - `{"LongPress": selector}` long-clicks a node.
 * - `{"TypeText": {"text": "...", "into": selector}}` appends to the
 *   focused text field, or the `into` node if given.
 * - `{"Scroll": {"direction": "up"|"down"|"left"|"right", ...selector}}`
 *   scrolls the node one step (the largest scrollable node of the app's
 *   windows if there's no selector); with `"percent": 1..100` it drags
 *   that share of the node instead.
 * - `{"Swipe": {"from": [x, y], "to": [x, y]}}`.
 * - `"Back"`, `"Home"`, `"Enter"`.
 *
 * An action on a node that isn't there leaves the app alone, since
 * replayed or shrunk traces may refer to nodes that are gone.
 */
class AdbAppDriver(
    private val adb: Adb,
    private val packageName: String,
    /** Installed at the start of each session if given. */
    private val apk: String? = null,
    /** Permissions granted before launching, so their prompts don't block the app. */
    private val permissions: List<String> = emptyList(),
    /**
     * For the first state of a session: how long the app must go without an
     * accessibility event before the driver fetches the tree, which it does
     * until two trees in a row agree, and for how long at most.
     */
    private val quietMs: Long = 200,
    private val settleTimeoutMs: Long = 10_000,
    /**
     * After an action: the app has finished responding once its windows
     * have presented no frame for this long (two refresh periods at 60Hz),
     * or, after an activity or window change, sent no accessibility event
     * for this long. One refresh period left 16% of states mid-transition,
     * against 10% with two or three.
     */
    private val gapMs: Long = 33,
    /**
     * Fallback: after Back or Home in another app (say, the browser the app
     * opened), which gives no signal that it has finished handling them,
     * the gap is this long instead.
     */
    private val foreignGapMs: Long = 250,
    /**
     * Fallback: no frame or window change within this long means the action
     * changed nothing visible. An accessibility click that starts another
     * app's activity draws nothing in the app, and the new window can take
     * a few hundred milliseconds to appear.
     */
    private val frameTimeoutMs: Long = 500,
    /** Fallback: a window change that never goes quiet is reported after this long. */
    private val windowTimeoutMs: Long = 1_000,
    /**
     * Fallback: after the app starts or finishes an activity, the new top
     * activity's window is awaited this long. Another app's cold start (the
     * browser, for a link) took over a second on a busy emulator.
     */
    private val activityTimeoutMs: Long = 3_000,
    /** Fallback: an app that never stops presenting frames is reported mid-animation after this long. */
    private val drainMaxMs: Long = 2_000,
    /**
     * The in-process agent's APK for this app (see `android-agent/package.sh`).
     * Without it, the helper reads and drives the app from outside.
     */
    agentApk: File? = null,
    /**
     * Fallback: how long the agent waits for an app that never goes idle
     * (an endless animation) before reporting it as it is.
     */
    private val agentCapMs: Long = 2_000,
    /** A file to append each step's timings to, as JSON lines. */
    private val timingLog: File? = null,
    override val stallTimeoutMs: Long? = 120_000,
) : BombadilDriver {
    override val name = "android-adb ($packageName on ${adb.serial})"

    private val helper = AndroidHelper(adb)
    private val agent = agentApk?.let { AppAgent(adb, it, packageName) }

    /** Whether the last state came from the agent, so that its node ids are the agent's. */
    private var inAgent = false

    /** The app's resumed activity in the last state from the agent. */
    private var lastTop: String? = null

    /** The current step's timings. */
    private val step = LinkedHashMap<String, JsonElement>()
    private var state: JsonObject? = null

    override fun start(): JsonElement {
        adb.run("wait-for-device", timeoutSeconds = 60)
        waitUntil(60_000, "boot") { adb.shell("getprop sys.boot_completed").trim() == "1" }
        for (setting in listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")) {
            adb.shell("settings put global $setting 0")
        }
        if (apk != null) adb.run("install", "-r", "-g", apk, timeoutSeconds = 120)
        adb.shell("am force-stop $packageName")
        adb.shell("pm clear $packageName").let { check("Success" in it) { "pm clear failed: $it" } }
        for (permission in permissions) adb.shell("pm grant $packageName $permission")
        val activity = adb.shell("cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $packageName")
            .lines().map { it.trim() }.lastOrNull { it.startsWith("$packageName/") }
            ?: error("$packageName has no launcher activity; is it installed?")
        helper.connect()
        agent?.let {
            it.install()
            // Starts the app's process, with the agent in it.
            it.start()
        }
        val started = System.nanoTime()
        adb.shell("am start -W -n $activity")
        return settle(JsonNull, started, initial = true)
    }

    override fun apply(action: JsonElement): JsonElement {
        val started = System.nanoTime()
        step.clear()
        val (tag, args) = when (action) {
            is JsonPrimitive -> action.content to JsonObject(emptyMap())
            is JsonObject -> action.entries.single().let { it.key to it.value.jsonObject }
            else -> error("not an action: $action")
        }
        // The app's own windows are in front and the state came from the
        // agent: act through it, inside the app. Otherwise through the helper.
        val agent = agent?.takeIf { inAgent && it.connected }
        val target: DeviceSocket = agent ?: helper
        val via = if (agent != null) "agent" else "helper"
        val capMs = "capMs" to agentCapMs
        val result: JsonObject? = attempt(via) {
            when (tag) {
                "Tap" -> point(args)?.let { (x, y) -> target.request("tap", "x" to x, "y" to y, capMs) }
                    ?: target(args)?.let { target.request("click", "node" to it, capMs) }
                "LongPress" -> target(args)?.let { target.request("longClick", "node" to it, capMs) }
                "TypeText" -> {
                    val into = args["into"]?.jsonObject?.let { target(it) ?: return@attempt null }
                    target.request("setText", "node" to into, "text" to args.string("text"), capMs)
                }
                "Scroll" -> scroll(args, target)
                "Swipe" -> {
                    val (x1, y1) = args.getValue("from").jsonArray.map { it.jsonPrimitive.int }
                    val (x2, y2) = args.getValue("to").jsonArray.map { it.jsonPrimitive.int }
                    target.request("swipe", "x1" to x1, "y1" to y1, "x2" to x2, "y2" to y2, "durationMs" to 300, capMs)
                }
                // Inside the app, Back is a key event the app handles before
                // the agent replies; Home always leaves it, so the helper does it.
                "Back" -> if (agent != null) agent.request("key", "code" to 4, capMs) else helper.request("global", "action" to "back")
                "Home" -> helper.request("global", "action" to "home")
                "Enter" -> target.request("key", "code" to 66, capMs)
                else -> error("unknown action `$tag`")
            }
        }
        if (result != null && result["done"]?.jsonPrimitive?.boolean == false) {
            log("$action not done: ${result["reason"]?.jsonPrimitive?.content}")
        }
        if (agent == null || tag == "Home") return settle(action, started, fromTop = if (agent != null) lastTop else null)
        return settleAfterAgent(action, started, result)
    }

    override fun cancel() {
        helper.cancel()
        agent?.cancel()
        adb.cancel()
    }

    override fun stop() {
        helper.close()
        agent?.close()
        adb.resume()
        runCatching { adb.shell("am force-stop $packageName", timeoutSeconds = 3) }
    }

    private fun scroll(args: JsonObject, target: DeviceSocket): JsonObject? {
        val direction = args.string("direction") ?: "down"
        val id = args["node"]?.jsonPrimitive?.content
        val node = when {
            id != null -> nodes().firstOrNull { it.id == id } ?: return null.also { log("no node $id; skipping") }
            args.keys.any { it in selectorKeys } -> select(args) ?: return null
            else -> largestScrollable()
        }
        val percent = args["percent"]?.jsonPrimitive?.intOrNull
        if (node != null && percent == null) return target.request("scroll", "node" to node.id, "direction" to direction, "capMs" to agentCapMs)
        val area = node?.bounds ?: screenBounds()
        val share = (percent ?: 50).coerceIn(1, 100) / 100.0
        // Keep clear of the area's edges, where system gestures and bars live.
        val left = area[0] + (area[2] - area[0]) / 10
        val right = area[2] - (area[2] - area[0]) / 10
        val top = area[1] + (area[3] - area[1]) / 10
        val bottom = area[3] - (area[3] - area[1]) / 10
        val dx = ((right - left) * share).toInt()
        val dy = ((bottom - top) * share).toInt()
        val (cx, cy) = (area[0] + area[2]) / 2 to (area[1] + area[3]) / 2
        // Content moves with the finger, so scrolling down drags upwards.
        val (from, to) = when (direction) {
            "down" -> (cx to bottom) to (cx to bottom - dy)
            "up" -> (cx to top) to (cx to top + dy)
            "right" -> (right to cy) to (right - dx to cy)
            "left" -> (left to cy) to (left + dx to cy)
            else -> error("unknown scroll direction in $args")
        }
        return target.request(
            "swipe", "x1" to from.first, "y1" to from.second, "x2" to to.first, "y2" to to.second, "durationMs" to 400,
            "capMs" to agentCapMs,
        )
    }

    /** A node in the last state, flattened. */
    data class Node(
        val id: String,
        val window: JsonObject,
        val fields: JsonObject,
    ) {
        val bounds get() = fields.getValue("bounds").jsonArray.map { it.jsonPrimitive.int }
        fun field(key: String) = fields[key]?.jsonPrimitive?.content
    }

    /** Every node of the last state, depth first, windows from top to bottom. */
    fun nodes(): Sequence<Node> = sequence {
        for (window in state?.get("windows")?.jsonArray.orEmpty().map { it.jsonObject }) {
            suspend fun SequenceScope<Node>.walk(nodes: JsonArray) {
                for (node in nodes.map { it.jsonObject }) {
                    yield(Node(node.getValue("id").jsonPrimitive.content, window, node))
                    walk(node.getValue("children").jsonArray)
                }
            }
            walk(window.getValue("nodes").jsonArray)
        }
    }

    private fun largestScrollable() = nodes()
        .filter { it.window["type"]?.jsonPrimitive?.content == "application" && it.field("scrollable") == "true" }
        .maxByOrNull { val (l, t, r, b) = it.bounds; (r - l).toLong() * (b - t) }

    private fun screenBounds(): List<Int> =
        state?.get("windows")?.jsonArray?.map { w -> w.jsonObject.getValue("bounds").jsonArray.map { it.jsonPrimitive.int } }
            ?.reduceOrNull { a, b -> listOf(minOf(a[0], b[0]), minOf(a[1], b[1]), maxOf(a[2], b[2]), maxOf(a[3], b[3])) }
            ?: listOf(0, 0, 1080, 2400)

    private fun point(args: JsonObject): Pair<Int, Int>? {
        val x = args["x"]?.jsonPrimitive?.intOrNull
        val y = args["y"]?.jsonPrimitive?.intOrNull
        return if (x != null && y != null) x to y else null
    }

    /** The id of the node [selector] picks: its `node` id, or what [select] finds. */
    private fun target(selector: JsonObject): String? =
        selector["node"]?.jsonPrimitive?.content ?: select(selector)?.id

    /**
     * The node in the last state whose `resourceId`, `text`, `desc`,
     * `label` and `class` equal those given (all optional), taking the
     * `index`th match (default 0) in depth-first order. Prefer the `node`
     * ids that states report: they don't depend on what else is shown.
     */
    fun select(selector: JsonObject): Node? {
        val wanted = selector.filterKeys { it in selectorKeys }.mapValues { it.value.jsonPrimitive.content }
        val matches = nodes().filter { node -> wanted.all { (key, value) -> node.field(key) == value } }
        val node = matches.elementAtOrNull(selector["index"]?.jsonPrimitive?.intOrNull ?: 0)
        if (node == null) log("no node matches $selector; skipping")
        return node
    }

    /**
     * Returns the state after an action the helper performed ([initial]:
     * after launching the app; [fromTop]: after the agent saw the app leave
     * that activity, or Home), recording how long each part took. The
     * helper waits for the response from outside the app (see its `next`
     * and `await`); the first state waits until two trees agree instead,
     * so that sessions start from the same state. If the app's own windows
     * are then in front, the agent's view of them replaces the helper's.
     */
    private fun settle(lastAction: JsonElement, started: Long, initial: Boolean = false, fromTop: String? = null): JsonElement {
        val acted = System.nanoTime()
        val response = when {
            initial -> helper.request("settle", "quietMs" to quietMs, "timeoutMs" to settleTimeoutMs)
            fromTop != null -> helper.request(
                "await", "fromTop" to fromTop, "gapMs" to gapMs, "windowTimeoutMs" to activityTimeoutMs,
                "sinceActionMs" to (acted - started) / 1_000_000,
            )
            else -> helper.request(
                "next", "gapMs" to gapMs, "foreignGapMs" to foreignGapMs, "appPackage" to packageName,
                "frameTimeoutMs" to frameTimeoutMs, "windowTimeoutMs" to windowTimeoutMs, "drainMaxMs" to drainMaxMs,
            )
        }
        record(if (initial) "settle" else if (fromTop != null) "await" else "next", response, helper, acted)
        inAgent = false
        val fresh = response.getValue("state").jsonObject
        val inApp = fresh["package"]?.jsonPrimitive?.contentOrNull == packageName
        return finish(lastAction, started, initial, (if (inApp) agentState() else null) ?: fresh)
    }

    /**
     * Returns the state after an action the agent performed: its own view
     * of the app once the app's main thread is idle, or, if the app is
     * leaving its activity (another one starting, or this one finishing),
     * the helper's view once the system has switched activities.
     */
    private fun settleAfterAgent(lastAction: JsonElement, started: Long, result: JsonObject?): JsonElement {
        if (result != null) record("agent", result, agent!!, started)
        val leaving = result?.get("leaving")?.jsonPrimitive?.boolean == true
        if (leaving) return settle(lastAction, started, fromTop = result!!["from"]?.jsonPrimitive?.contentOrNull ?: lastTop)
        val state = result?.let { agentResult(it) }
        if (state != null) return finish(lastAction, started, false, state)
        // The agent is gone (the app crashed), its tree couldn't be read, or
        // something outside the app is in front: back to the helper, waiting
        // for quiet as at the start of a session.
        val acted = System.nanoTime()
        val response = helper.request("settle", "quietMs" to quietMs, "timeoutMs" to settleTimeoutMs)
        record("settle", response, helper, acted)
        inAgent = false
        return finish(lastAction, started, false, response.getValue("state").jsonObject)
    }

    /** The agent's state of the app, if it has one: its windows are in front and its main thread answers. */
    private fun agentState(): JsonObject? {
        val agent = agent?.takeIf { it.connected } ?: return null
        val acted = System.nanoTime()
        val response = attempt("agent") { agent.request("state", "capMs" to agentCapMs) } ?: return null
        record("agentState", response, agent, acted)
        return agentResult(response)
    }

    private fun agentResult(response: JsonObject): JsonObject? {
        val state = response["state"]?.jsonObject
        if (state == null || response["focused"]?.jsonPrimitive?.boolean != true) {
            inAgent = false
            return null
        }
        inAgent = true
        lastTop = response["top"]?.jsonPrimitive?.contentOrNull
        return state
    }

    /** Runs a request to the agent, or, if it fails (the app died), forgets the agent for this session. */
    private fun <T> attempt(via: String, block: () -> T): T? {
        if (via != "agent") return block()
        return try {
            block()
        } catch (e: IllegalStateException) {
            log("the agent failed, using the helper: ${e.message}")
            agent?.close()
            inAgent = false
            null
        }
    }

    /** Adds a response's timings to the step's, as `<what>.<key>`. */
    private fun record(what: String, response: JsonObject, socket: DeviceSocket, sent: Long) {
        if (timingLog == null) return
        for ((key, value) in response) {
            if (key == "state" || key == "ok" || value !is JsonPrimitive) continue
            step["$what.$key"] = value
        }
        step["$what.requestMs"] = JsonPrimitive((System.nanoTime() - sent) / 1_000_000.0)
        step["$what.bytes"] = JsonPrimitive(socket.lastResponseBytes)
        step["$what.parseMs"] = JsonPrimitive(socket.lastParseMs)
        step["path"] = JsonPrimitive(listOfNotNull(step["path"]?.jsonPrimitive?.content, what).joinToString("+"))
    }

    private fun finish(lastAction: JsonElement, started: Long, initial: Boolean, fresh: JsonObject): JsonElement {
        state = fresh
        timingLog?.appendText(
            JsonObject(
                step + mapOf(
                    "initial" to JsonPrimitive(initial),
                    "inAgent" to JsonPrimitive(inAgent),
                    "totalMs" to JsonPrimitive((System.nanoTime() - started) / 1_000_000.0),
                ),
            ).toString() + "\n",
        )
        step.clear()
        return JsonObject(fresh + ("lastAction" to lastAction))
    }

    private fun waitUntil(timeoutMs: Long, what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!runCatching(condition).getOrDefault(false)) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(500)
        }
    }

    private fun log(message: String) = System.err.println("[$name] $message")

    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.content

    private companion object {
        val selectorKeys = setOf("resourceId", "text", "desc", "label", "class")
    }
}

/**
 * Runs an [AdbAppDriver] for one Bombadil session.
 *
 * ```
 * bombadil-android --package <package> [--apk <file>] [--agent <apk>] [--grant <permission>]...
 * ```
 *
 * The device is the first serial in `BOMBADIL_ANDROID_SERIALS`
 * (comma-separated) that no other driver on this host is using, so
 * parallel fuzzing workers each get their own emulator; it defaults to
 * `ANDROID_SERIAL`, then `emulator-5554`. `BOMBADIL_ANDROID_TIMINGS`
 * names a file to append each step's timings to.
 */
fun main(args: Array<String>) {
    var packageName: String? = null
    var apk: String? = null
    var agentApk: File? = null
    val permissions = mutableListOf<String>()
    val rest = args.iterator()
    while (rest.hasNext()) {
        when (val arg = rest.next()) {
            "--package" -> packageName = rest.next()
            "--apk" -> apk = rest.next()
            "--grant" -> permissions += rest.next()
            "--agent" -> agentApk = File(rest.next())
            else -> error("unknown argument `$arg`")
        }
    }
    val serials = (System.getenv("BOMBADIL_ANDROID_SERIALS") ?: System.getenv("ANDROID_SERIAL") ?: "emulator-5554")
        .split(',').map { it.trim() }.filter { it.isNotEmpty() }
    runDriver(
        AdbAppDriver(
            Adb.claim(serials),
            packageName ?: error("--package is required"),
            apk,
            permissions,
            timingLog = System.getenv("BOMBADIL_ANDROID_TIMINGS")?.let(::File),
            agentApk = agentApk,
        ),
    )
}
