package me.uzaaft.bombadil.helper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves the accessibility tree and performs actions for Bombadil's adb
 * driver. Started with `am instrument -w`, it keeps one UiAutomation
 * connection open and answers line-delimited JSON requests on the
 * abstract socket `bombadil-helper`, which the host reaches through
 * `adb forward`. See the driver's AndroidHelper for the commands.
 */
public class Helper extends Instrumentation {
    private static final String TAG = "BombadilHelper";
    private static final String SOCKET = "bombadil-helper";

    private UiAutomation ui;
    private String apkSha256 = "";
    /** Nodes of the last `tree`, by id. */
    private Map<String, Tree.Node> nodes = new HashMap<>();
    private volatile String activity;
    private final Map<String, Boolean> isActivity = new HashMap<>();

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        try {
            apkSha256 = sha256(getContext().getApplicationInfo().sourceDir);
            ui = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            AccessibilityServiceInfo info = ui.getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            ui.setServiceInfo(info);
            ui.setOnAccessibilityEventListener(this::onEvent);
            try (LocalServerSocket server = new LocalServerSocket(SOCKET)) {
                Log.i(TAG, "listening on @" + SOCKET);
                while (true) {
                    try (LocalSocket socket = server.accept()) {
                        serve(socket);
                    } catch (Exception e) {
                        Log.w(TAG, "connection ended", e);
                    }
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "failed", e);
            Bundle result = new Bundle();
            result.putString("error", String.valueOf(e));
            finish(1, result);
        }
    }

    /**
     * Waits until no accessibility event has arrived for [quietMs], counting
     * from [since] at the earliest, or until [timeoutMs] passes. Unlike
     * UiAutomation.waitForIdle, this doesn't return at once when the last
     * event came before the action whose effects it's waiting for: an app
     * reports its changes a frame or two (Compose: up to 100ms) late.
     */
    private boolean waitForIdle(long since, long quietMs, long timeoutMs) throws InterruptedException {
        long deadline = since + timeoutMs;
        synchronized (idleLock) {
            while (true) {
                long now = SystemClock.uptimeMillis();
                long quietUntil = Math.max(lastEventTime, since) + quietMs;
                if (now >= quietUntil) return true;
                if (now >= deadline) return false;
                idleLock.wait(Math.min(quietUntil, deadline) - now);
            }
        }
    }

    private final Object idleLock = new Object();
    private long lastEventTime;
    private volatile long eventCount;

    private void onEvent(AccessibilityEvent event) {
        synchronized (idleLock) {
            lastEventTime = SystemClock.uptimeMillis();
            eventCount++;
        }
        trackActivity(event);
    }

    /** Remembers the last activity shown, from window state changes. */
    private void trackActivity(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (event.getPackageName() == null || event.getClassName() == null) return;
        String pkg = event.getPackageName().toString();
        String cls = event.getClassName().toString();
        String key = pkg + "/" + cls;
        Boolean known;
        synchronized (isActivity) {
            known = isActivity.get(key);
        }
        if (known == null) {
            try {
                getContext().getPackageManager().getActivityInfo(new ComponentName(pkg, cls), 0);
                known = true;
            } catch (PackageManager.NameNotFoundException e) {
                known = false;
            }
            synchronized (isActivity) {
                isActivity.put(key, known);
            }
        }
        if (known) activity = cls;
    }

    private void serve(LocalSocket socket) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        Writer out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
        String line;
        while ((line = in.readLine()) != null) {
            String response;
            try {
                response = handle(new JSONObject(line));
            } catch (Exception e) {
                Log.w(TAG, "request failed: " + line, e);
                response = new JSONObject().put("ok", false).put("error", String.valueOf(e)).toString();
            }
            out.write(response);
            out.write('\n');
            out.flush();
        }
    }

    private String handle(JSONObject request) throws Exception {
        String command = request.getString("cmd");
        JSONObject response = new JSONObject().put("ok", true);
        switch (command) {
            case "ping":
                return response.put("apkSha256", apkSha256).toString();
            case "tree": {
                long start = SystemClock.uptimeMillis();
                String tree = tree();
                // Spliced in rather than parsed, to save the time.
                return "{\"ok\":true,\"buildMs\":" + (SystemClock.uptimeMillis() - start) + ",\"state\":" + tree + "}";
            }
            case "waitForIdle": {
                long start = SystemClock.uptimeMillis();
                long events = eventCount;
                boolean idle = waitForIdle(start, request.getLong("quietMs"), request.getLong("timeoutMs"));
                return response.put("idle", idle).put("events", eventCount - events).put("ms", SystemClock.uptimeMillis() - start).toString();
            }
            case "settle": {
                // Waits for quiet and fetches the tree until two trees in a
                // row agree: apps (Compose among them) don't send events
                // for every change, so quiet alone doesn't mean settled.
                long start = SystemClock.uptimeMillis();
                long quietMs = request.getLong("quietMs");
                long deadline = start + request.getLong("timeoutMs");
                long idleMs = 0, buildMs = 0;
                int trees = 0;
                String previous = null, tree;
                while (true) {
                    long t0 = SystemClock.uptimeMillis();
                    waitForIdle(t0, quietMs, Math.max(0, deadline - t0));
                    long t1 = SystemClock.uptimeMillis();
                    tree = tree();
                    long t2 = SystemClock.uptimeMillis();
                    idleMs += t1 - t0;
                    buildMs += t2 - t1;
                    trees++;
                    // No active window: between windows, e.g. while another app starts.
                    if ((tree.equals(previous) && tree.startsWith("{\"package\":\"")) || t2 >= deadline) break;
                    previous = tree;
                }
                return "{\"ok\":true,\"stable\":" + tree.equals(previous) + ",\"trees\":" + trees + ",\"idleMs\":"
                    + idleMs + ",\"buildMs\":" + buildMs + ",\"ms\":" + (SystemClock.uptimeMillis() - start)
                    + ",\"state\":" + tree + "}";
            }
            case "click":
                return done(response, act(request, AccessibilityNodeInfo.ACTION_CLICK, false));
            case "longClick":
                return done(response, act(request, AccessibilityNodeInfo.ACTION_LONG_CLICK, true));
            case "setText":
                return done(response, setText(request));
            case "scroll":
                return done(response, scroll(request));
            case "tap":
                tap(request.getInt("x"), request.getInt("y"), false);
                return done(response, "input");
            case "swipe":
                swipe(request.getInt("x1"), request.getInt("y1"), request.getInt("x2"), request.getInt("y2"),
                    request.optLong("durationMs", 300));
                return done(response, "input");
            case "global": {
                int action;
                switch (request.getString("action")) {
                    case "back": action = AccessibilityService.GLOBAL_ACTION_BACK; break;
                    case "home": action = AccessibilityService.GLOBAL_ACTION_HOME; break;
                    default: throw new IllegalArgumentException("unknown global action " + request);
                }
                return done(response, ui.performGlobalAction(action) ? "action" : null);
            }
            case "key": {
                int code = request.getInt("code");
                long now = SystemClock.uptimeMillis();
                inject(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                    InputDevice.SOURCE_KEYBOARD));
                inject(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                    InputDevice.SOURCE_KEYBOARD));
                return done(response, "input");
            }
            default:
                throw new IllegalArgumentException("unknown command " + command);
        }
    }

    /** `via` says how the action was done, or is null if it wasn't (with `reason`). */
    private static String done(JSONObject response, String via) throws Exception {
        if (via == null || via.startsWith("!")) {
            return response.put("done", false).put("reason", via == null ? "refused" : via.substring(1)).toString();
        }
        return response.put("done", true).put("via", via).toString();
    }

    // Tree

    private String tree() {
        // The node cache misses changes that apps don't send events for.
        if (android.os.Build.VERSION.SDK_INT >= 34) ui.clearCache();
        List<AccessibilityWindowInfo> infos = new ArrayList<>(ui.getWindows());
        Collections.sort(infos, (a, b) -> Integer.compare(b.getLayer(), a.getLayer()));
        List<Tree.Window> windows = new ArrayList<>();
        String activePackage = null;
        String activeTitle = null;
        for (AccessibilityWindowInfo info : infos) {
            AccessibilityNodeInfo root = info.getRoot();
            if (root == null) continue;
            Tree.Window window = new Tree.Window();
            window.type = windowType(info.getType());
            window.packageName = root.getPackageName() == null ? "" : root.getPackageName().toString();
            window.title = info.getTitle() == null ? "" : info.getTitle().toString();
            window.active = info.isActive();
            window.focused = info.isFocused();
            window.layer = info.getLayer();
            Rect r = new Rect();
            info.getBoundsInScreen(r);
            window.left = r.left;
            window.top = r.top;
            window.right = r.right;
            window.bottom = r.bottom;
            window.root = node(root);
            windows.add(window);
            if (info.isActive()) {
                activePackage = window.packageName;
                activeTitle = window.title;
            }
        }
        nodes = Tree.build(windows);
        return Tree.json(activePackage, activity, activeTitle, windows);
    }

    private static Tree.Node node(AccessibilityNodeInfo info) {
        Tree.Node node = new Tree.Node();
        node.handle = info;
        node.resourceId = str(info.getViewIdResourceName());
        node.className = str(info.getClassName());
        node.text = str(info.getText());
        node.desc = str(info.getContentDescription());
        node.clickable = info.isClickable();
        node.longClickable = info.isLongClickable();
        node.scrollable = info.isScrollable();
        node.enabled = info.isEnabled();
        node.checkable = info.isCheckable();
        node.checked = info.isChecked();
        node.selected = info.isSelected();
        node.focused = info.isFocused();
        node.editable = info.isEditable();
        if (node.editable && android.os.Build.VERSION.SDK_INT >= 26 && info.isShowingHintText()) node.text = "";
        Rect r = new Rect();
        info.getBoundsInScreen(r);
        node.left = r.left;
        node.top = r.top;
        node.right = r.right;
        node.bottom = r.bottom;
        for (int i = 0; i < info.getChildCount(); i++) {
            AccessibilityNodeInfo child = info.getChild(i);
            if (child != null && child.isVisibleToUser()) node.children.add(node(child));
        }
        return node;
    }

    private static String windowType(int type) {
        switch (type) {
            case AccessibilityWindowInfo.TYPE_APPLICATION: return "application";
            case AccessibilityWindowInfo.TYPE_INPUT_METHOD: return "inputMethod";
            case AccessibilityWindowInfo.TYPE_SYSTEM: return "system";
            case AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY: return "accessibilityOverlay";
            case AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER: return "splitScreenDivider";
            default: return "type" + type;
        }
    }

    private static String str(CharSequence s) {
        return s == null ? "" : s.toString();
    }

    // Actions

    /** The node of the last tree with the request's `node` id, refreshed, or null if it's gone. */
    private AccessibilityNodeInfo find(JSONObject request) {
        Tree.Node node = nodes.get(request.optString("node"));
        if (node == null) return null;
        AccessibilityNodeInfo info = (AccessibilityNodeInfo) node.handle;
        return info.refresh() ? info : null;
    }

    /** Performs [action] on the node, or taps (or presses) its centre if it doesn't take the action. */
    private String act(JSONObject request, int action, boolean longPress) throws Exception {
        AccessibilityNodeInfo node = find(request);
        if (node == null) return "!no such node";
        if (node.getActionList().contains(new AccessibilityAction(action, null)) && node.performAction(action)) {
            return "action";
        }
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.isEmpty()) return "!node has no area";
        tap(r.centerX(), r.centerY(), longPress);
        return "input";
    }

    /** Types `text` into `node`, or the focused input; appended to what's there unless `replace`. */
    private String setText(JSONObject request) throws Exception {
        AccessibilityNodeInfo node = request.has("node") ? find(request) : ui.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (node == null || !node.isEditable()) return "!no editable node";
        String current = node.getText() == null ? "" : node.getText().toString();
        if (android.os.Build.VERSION.SDK_INT >= 26 && node.isShowingHintText()) current = "";
        String text = request.optBoolean("replace") ? request.getString("text") : current + request.getString("text");
        Bundle arguments = new Bundle();
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) ? "action" : null;
    }

    /**
     * Scrolls the node one step in `direction`, with its directional or
     * forward/backward accessibility action, or by dragging across it if
     * it has neither.
     */
    private String scroll(JSONObject request) throws Exception {
        AccessibilityNodeInfo node = find(request);
        if (node == null) return "!no such node";
        String direction = request.getString("direction");
        AccessibilityAction directional;
        AccessibilityAction relative;
        switch (direction) {
            case "down": directional = AccessibilityAction.ACTION_SCROLL_DOWN; relative = AccessibilityAction.ACTION_SCROLL_FORWARD; break;
            case "up": directional = AccessibilityAction.ACTION_SCROLL_UP; relative = AccessibilityAction.ACTION_SCROLL_BACKWARD; break;
            case "right": directional = AccessibilityAction.ACTION_SCROLL_RIGHT; relative = AccessibilityAction.ACTION_SCROLL_FORWARD; break;
            case "left": directional = AccessibilityAction.ACTION_SCROLL_LEFT; relative = AccessibilityAction.ACTION_SCROLL_BACKWARD; break;
            default: throw new IllegalArgumentException("unknown direction " + direction);
        }
        List<AccessibilityAction> actions = node.getActionList();
        if (actions.contains(directional)) return node.performAction(directional.getId()) ? "action" : "!at the end";
        boolean vertical = direction.equals("down") || direction.equals("up");
        // Forward/backward is ambiguous for nodes that scroll both ways; trust it for the other cases.
        if (actions.contains(relative)) return node.performAction(relative.getId()) ? "action" : "!at the end";
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        int dx = vertical ? 0 : r.width() * 3 / 10;
        int dy = vertical ? r.height() * 3 / 10 : 0;
        int sign = direction.equals("down") || direction.equals("right") ? 1 : -1;
        swipe(r.centerX() + sign * dx, r.centerY() + sign * dy, r.centerX() - sign * dx, r.centerY() - sign * dy, 300);
        return "input";
    }

    private void tap(int x, int y, boolean longPress) throws Exception {
        long down = SystemClock.uptimeMillis();
        inject(motion(down, down, MotionEvent.ACTION_DOWN, x, y));
        long up = down + (longPress ? 1000 : 60);
        sleepUntil(up);
        inject(motion(down, up, MotionEvent.ACTION_UP, x, y));
    }

    /**
     * Drags from (x1, y1) to (x2, y2), then holds still before lifting so
     * that nothing flings: every event's time is planned rather than
     * measured, so the same swipe always scrolls the same distance.
     */
    private void swipe(int x1, int y1, int x2, int y2, long durationMs) throws Exception {
        long down = SystemClock.uptimeMillis();
        inject(motion(down, down, MotionEvent.ACTION_DOWN, x1, y1));
        int steps = (int) Math.max(1, durationMs / 10);
        for (int i = 1; i <= steps; i++) {
            long t = down + durationMs * i / steps;
            sleepUntil(t);
            inject(motion(down, t, MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * i / steps, y1 + (y2 - y1) * i / steps));
        }
        long end = down + durationMs;
        for (int i = 1; i <= 10; i++) {
            sleepUntil(end + 20L * i);
            inject(motion(down, end + 20L * i, MotionEvent.ACTION_MOVE, x2, y2));
        }
        inject(motion(down, end + 200, MotionEvent.ACTION_UP, x2, y2));
    }

    private static MotionEvent motion(long down, long time, int action, int x, int y) {
        MotionEvent event = MotionEvent.obtain(down, time, action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        return event;
    }

    private void inject(android.view.InputEvent event) {
        if (!ui.injectInputEvent(event, true)) throw new IllegalStateException("could not inject " + event);
    }

    private static void sleepUntil(long uptime) throws InterruptedException {
        long wait = uptime - SystemClock.uptimeMillis();
        if (wait > 0) Thread.sleep(wait);
    }

    private static String sha256(String path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(path)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) > 0) digest.update(buffer, 0, n);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format("%02x", b));
        return hex.toString();
    }
}
