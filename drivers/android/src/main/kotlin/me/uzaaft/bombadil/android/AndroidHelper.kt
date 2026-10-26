package me.uzaaft.bombadil.android

import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest

/**
 * Talks to the on-device helper (`android-helper/`): an instrumentation
 * that keeps a UiAutomation connection open and answers line-delimited
 * JSON requests on the abstract socket `bombadil-helper`, reached through
 * `adb forward`.
 *
 * Requests are objects with a `cmd`; every response has `ok`, and `error`
 * when it's false:
 * - `ping` → `apkSha256` of the running helper.
 * - `tree` → `state` (focused package, activity and window title, and the
 *   pruned node tree of every interactive window), `buildMs`.
 * - `settle` `quietMs` `timeoutMs` → `state`, `stable`, `trees`: waits for
 *   `quietMs` without accessibility events and fetches the tree, until two
 *   trees agree. For a session's first state.
 * - `next` `gapMs` `foreignGapMs` `appPackage` `frameTimeoutMs` `windowTimeoutMs` `drainMaxMs` →
 *   `state`, `signal` (`frame`, `activity`, `window`, `timeout`, `windowCap`,
 *   `drainCap`), `waitMs`, `firstFrameMs`, `frames`, `buildMs`: after an
 *   action, waits for the app's response (frames presented, or an activity or window
 *   change) and returns the tree, best effort.
 * - `await` `fromTop` `gapMs` `windowTimeoutMs` → `state`, `signal`
 *   (`activity`, `windowCap`): after the in-process agent saw the app
 *   start or finish an activity, waits until the top activity is no
 *   longer `fromTop` and its window is active and quiet.
 * - `click`/`longClick` `node`, `setText` `node`? `text` `replace`?,
 *   `scroll` `node` `direction` → `done`, then `via` (`action` or
 *   `input`) or `reason`: an accessibility action on the node from the
 *   last `tree`, falling back to injected touches at its centre.
 * - `tap` `x` `y`, `swipe` `x1` `y1` `x2` `y2` `durationMs`, `key`
 *   `code`, `global` `action` (`back`, `home`).
 *
 * The helper outlives sessions: [connect] reuses one that's running the
 * same APK, and otherwise installs and starts it.
 */
class AndroidHelper(adb: Adb) : DeviceSocket(adb, SOCKET) {
    /** Connects to the helper, installing and starting it first if needed. */
    fun connect() {
        if (open(forward()) && runCatching { ping() }.getOrNull() == apkSha256) return
        close()
        install()
        // `am instrument -w` holds the UiAutomation connection, so it must
        // outlive this command. It does under `adb shell`, not `exec-out`.
        adb.run("shell", "nohup am instrument -w $PACKAGE/.Helper >/dev/null 2>&1 &")
        await(30_000, "the helper") { ping() == apkSha256 }
    }

    private fun ping() = request("ping")["apkSha256"]?.jsonPrimitive?.content

    private fun install() {
        val file = File.createTempFile("bombadil-helper", ".apk")
        try {
            file.writeBytes(apk)
            try {
                adb.run("install", "-r", file.path, timeoutSeconds = 120)
            } catch (e: IllegalStateException) {
                // Signed with another build's key: start over.
                if ("INSTALL_FAILED_UPDATE_INCOMPATIBLE" !in e.message.orEmpty()) throw e
                adb.run("uninstall", PACKAGE)
                adb.run("install", file.path, timeoutSeconds = 120)
            }
        } finally {
            file.delete()
        }
    }

    companion object {
        const val PACKAGE = "me.uzaaft.bombadil.helper"
        const val SOCKET = "bombadil-helper"

        val apk: ByteArray by lazy {
            AndroidHelper::class.java.getResourceAsStream("bombadil-helper.apk")?.use { it.readBytes() }
                ?: error("bombadil-helper.apk is missing from the classpath; build with `gradle installDist`")
        }

        val apkSha256: String by lazy {
            MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
        }
    }
}
