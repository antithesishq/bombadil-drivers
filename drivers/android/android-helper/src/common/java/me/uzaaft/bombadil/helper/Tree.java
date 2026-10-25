package me.uzaaft.bombadil.helper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns accessibility trees into the JSON the driver reports: prunes
 * layout-only nodes, labels nodes, and gives each node an id that stays
 * the same across sessions as long as the node looks the same. Kept free
 * of Android classes so it runs in the host's unit tests.
 */
public final class Tree {
    private Tree() {}

    /** A node, filled in from an AccessibilityNodeInfo. */
    public static final class Node {
        public String resourceId = "";
        public String className = "";
        public String text = "";
        public String desc = "";
        public boolean clickable, longClickable, scrollable, enabled, checkable, checked, selected, focused, editable;
        public int left, top, right, bottom;
        public List<Node> children = new ArrayList<>();
        /** The AccessibilityNodeInfo this node came from, to act on it later. */
        public Object handle;

        String label = "";
        String id = "";

        boolean isEmpty() {
            return right <= left || bottom <= top;
        }

        boolean informative() {
            return !resourceId.isEmpty() || !text.isEmpty() || !desc.isEmpty() || clickable || longClickable
                || scrollable || checkable || selected || focused || editable;
        }
    }

    /** A window and its (unpruned) root. */
    public static final class Window {
        public String type = "";
        public String packageName = "";
        public String title = "";
        public boolean active, focused;
        public int layer;
        public int left, top, right, bottom;
        public Node root;
        List<Node> nodes = new ArrayList<>();
    }

    /**
     * Drops nodes that carry nothing but layout, moving their children
     * up, and nodes with no area.
     */
    static List<Node> prune(Node node) {
        List<Node> kept = new ArrayList<>();
        if (node.isEmpty()) return kept;
        List<Node> children = new ArrayList<>();
        for (Node child : node.children) children.addAll(prune(child));
        if (node.informative()) {
            node.children = children;
            kept.add(node);
        } else {
            kept.addAll(children);
        }
        return kept;
    }

    /**
     * What a user would read on a node: its text, else its content
     * description, else its descendants' labels joined by spaces (Compose
     * puts a clickable row's text on its children), cut to 200 characters.
     */
    static String label(Node node) {
        List<String> parts = new ArrayList<>();
        for (Node child : node.children) {
            String l = label(child);
            if (!l.isEmpty()) parts.add(l);
        }
        String l = !node.text.isEmpty() ? node.text : !node.desc.isEmpty() ? node.desc : String.join(" ", parts);
        node.label = l.length() > 200 ? l.substring(0, 200) : l;
        return node.label;
    }

    /**
     * Prunes and labels every window and gives every node an id, returning
     * the nodes by id. A node's id hashes its window's type and package,
     * the nearest ancestor with a resource id, its own resource id, class
     * and label, and the label of its nearest clickable ancestor (which
     * tells apart, say, the identical "Follow" buttons of different rows).
     * Not its position, so ids survive scrolling. Nodes that still look
     * alike get `-1`, `-2`, ... in depth-first order.
     */
    public static Map<String, Node> build(List<Window> windows) {
        Map<String, Node> byId = new HashMap<>();
        Map<String, Integer> seen = new HashMap<>();
        for (Window window : windows) {
            window.nodes = window.root == null ? new ArrayList<>() : prune(window.root);
            for (Node node : window.nodes) {
                label(node);
                assignIds(node, window.type + "\u0000" + window.packageName, "", "", seen, byId);
            }
        }
        return byId;
    }

    private static void assignIds(Node node, String windowKey, String taggedAncestor, String clickableAncestor,
            Map<String, Integer> seen, Map<String, Node> byId) {
        String key = windowKey + "\u0000" + taggedAncestor + "\u0000" + node.resourceId + "\u0000" + node.className
            + "\u0000" + node.label + "\u0000" + clickableAncestor;
        String hash = String.format("%08x", fnv1a(key));
        int n = seen.merge(hash, 1, Integer::sum) - 1;
        node.id = n == 0 ? hash : hash + "-" + n;
        byId.put(node.id, node);
        String tagged = node.resourceId.isEmpty() ? taggedAncestor : node.resourceId;
        String clickable = node.clickable ? node.label : clickableAncestor;
        for (Node child : node.children) assignIds(child, windowKey, tagged, clickable, seen, byId);
    }

    private static int fnv1a(String s) {
        int h = 0x811c9dc5;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x01000193;
        }
        return h;
    }

    /** The state JSON for [windows], after [build]. */
    public static String json(String packageName, String activity, String focus, List<Window> windows) {
        StringBuilder out = new StringBuilder(16_384);
        out.append("{\"package\":");
        string(out, packageName);
        out.append(",\"activity\":");
        string(out, activity);
        out.append(",\"focus\":");
        string(out, focus);
        out.append(",\"windows\":[");
        for (int i = 0; i < windows.size(); i++) {
            Window w = windows.get(i);
            if (i > 0) out.append(',');
            out.append("{\"type\":");
            string(out, w.type);
            out.append(",\"package\":");
            string(out, w.packageName);
            out.append(",\"title\":");
            string(out, w.title);
            out.append(",\"active\":").append(w.active);
            out.append(",\"focused\":").append(w.focused);
            out.append(",\"layer\":").append(w.layer);
            bounds(out, w.left, w.top, w.right, w.bottom);
            out.append(",\"nodes\":");
            nodes(out, w.nodes);
            out.append('}');
        }
        return out.append("]}").toString();
    }

    private static void nodes(StringBuilder out, List<Node> nodes) {
        out.append('[');
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            if (i > 0) out.append(',');
            out.append("{\"id\":");
            string(out, n.id);
            out.append(",\"resourceId\":");
            string(out, n.resourceId);
            out.append(",\"class\":");
            string(out, n.className.substring(n.className.lastIndexOf('.') + 1));
            out.append(",\"text\":");
            string(out, n.text);
            out.append(",\"desc\":");
            string(out, n.desc);
            out.append(",\"label\":");
            string(out, n.label);
            out.append(",\"clickable\":").append(n.clickable);
            out.append(",\"longClickable\":").append(n.longClickable);
            out.append(",\"scrollable\":").append(n.scrollable);
            out.append(",\"enabled\":").append(n.enabled);
            out.append(",\"checkable\":").append(n.checkable);
            out.append(",\"checked\":").append(n.checked);
            out.append(",\"selected\":").append(n.selected);
            out.append(",\"focused\":").append(n.focused);
            out.append(",\"editable\":").append(n.editable);
            bounds(out, n.left, n.top, n.right, n.bottom);
            out.append(",\"children\":");
            nodes(out, n.children);
            out.append('}');
        }
        out.append(']');
    }

    private static void bounds(StringBuilder out, int left, int top, int right, int bottom) {
        out.append(",\"bounds\":[").append(left).append(',').append(top).append(',').append(right).append(',')
            .append(bottom).append(']');
    }

    static void string(StringBuilder out, String s) {
        if (s == null) {
            out.append("null");
            return;
        }
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        out.append('"');
    }
}
