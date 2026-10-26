package me.uzaaft.bombadil.android

import java.io.File
import java.security.MessageDigest

/**
 * Talks to the in-process agent (`android-agent/`): an instrumentation
 * of the app under test, signed with the app's key, which runs inside the
 * app's process and answers line-delimited JSON requests on the abstract
 * socket `bombadil-agent`, reached through `adb forward`. Build its APK
 * for an app with `android-agent/package.sh`.
 *
 * Requests (see the helper's for the shared shapes):
 * - `ping`.
 * - `state` `capMs` → waits until the app's main thread is idle and
 *   returns its windows (see below).
 * - `click`/`longClick` `node`, `setText` `node`? `text` `replace`?,
 *   `scroll` `node` `direction`, `tap` `x` `y`, `swipe` `x1` `y1` `x2`
 *   `y2` `durationMs`, `key` `code` (`capMs` on each) → `done`, `via` or
 *   `reason`, `actionMs`; then either `leaving: true` and `from` (the
 *   activity the app is leaving: it started another or is finishing this
 *   one, and the system takes over), or the same as `state`.
 *
 * `state`'s responses: `settled` (false if the cap was reached; `busy`
 * says with what: `recomposer`, `layout`, `queue`, `focus`, `blocked`), `waitMs`,
 * `rounds`, `buildMs`, `focused` (whether one of the app's windows has
 * input focus; if not, something else is in front), `resumed`, and
 * `state`, the app's windows in the helper's shape, without the system's
 * (no `state` if the main thread is blocked).
 *
 * Starting the agent (re)starts the app's process: it runs from the
 * process's start, so that it sees every window the app creates.
 */
class AppAgent(adb: Adb, private val apk: File, appPackage: String) : DeviceSocket(adb, SOCKET) {
    private val agentPackage = "$appPackage.bombadil"

    /** Installs the agent if the device doesn't have this build of it. */
    fun install() {
        val local = MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).joinToString("") { "%02x".format(it) }
        val path = adb.shell("pm path $agentPackage").lines().firstOrNull { it.startsWith("package:") }?.removePrefix("package:")?.trim()
        if (path != null && adb.shell("sha256sum $path").startsWith(local)) return
        adb.run("install", "-r", apk.path, timeoutSeconds = 120)
    }

    /** Starts the app's process with the agent in it, and connects. The app must not be running. */
    fun start() {
        close()
        adb.run("shell", "nohup am instrument -w $agentPackage/$CLASS >/dev/null 2>&1 &")
        await(30_000, "the agent") { request("ping")["ok"] != null }
    }

    companion object {
        const val SOCKET = "bombadil-agent"
        const val CLASS = "me.uzaaft.bombadil.agent.Agent"
    }
}
