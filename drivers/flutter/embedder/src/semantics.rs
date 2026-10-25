//! The app's semantics tree, as the engine reports it, and its JSON form.

use std::collections::HashMap;

use serde_json::{json, Map, Value};

/// Names of semantics actions, by bit position in `FlutterSemanticsAction`.
pub const ACTIONS: [&str; 26] = [
    "tap",
    "longPress",
    "scrollLeft",
    "scrollRight",
    "scrollUp",
    "scrollDown",
    "increase",
    "decrease",
    "showOnScreen",
    "moveCursorForwardByCharacter",
    "moveCursorBackwardByCharacter",
    "setSelection",
    "copy",
    "cut",
    "paste",
    "didGainAccessibilityFocus",
    "didLoseAccessibilityFocus",
    "customAction",
    "dismiss",
    "moveCursorForwardByWord",
    "moveCursorBackwardByWord",
    "setText",
    "focus",
    "scrollToOffset",
    "expand",
    "collapse",
];

/// The `FlutterSemanticsAction` bit for an action name.
pub fn action_bit(name: &str) -> Option<u64> {
    ACTIONS.iter().position(|a| *a == name).map(|i| 1 << i)
}

/// A row-major 3x3 matrix, as in `FlutterTransformation`.
pub type Transform = [f64; 9];

pub const IDENTITY: Transform = [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0];

pub fn multiply(a: &Transform, b: &Transform) -> Transform {
    let mut out = [0.0; 9];
    for row in 0..3 {
        for col in 0..3 {
            out[row * 3 + col] = (0..3).map(|k| a[row * 3 + k] * b[k * 3 + col]).sum();
        }
    }
    out
}

/// The bounding box, as `[left, top, right, bottom]`, of `rect`
/// transformed by `t`.
pub fn transform_rect(t: &Transform, rect: [f64; 4]) -> [f64; 4] {
    let [l, top, r, b] = rect;
    let points = [(l, top), (r, top), (l, b), (r, b)].map(|(x, y)| {
        let w = t[6] * x + t[7] * y + t[8];
        let w = if w == 0.0 { 1.0 } else { w };
        (
            (t[0] * x + t[1] * y + t[2]) / w,
            (t[3] * x + t[4] * y + t[5]) / w,
        )
    });
    let xs = points.map(|p| p.0);
    let ys = points.map(|p| p.1);
    [
        xs.iter().copied().fold(f64::INFINITY, f64::min),
        ys.iter().copied().fold(f64::INFINITY, f64::min),
        xs.iter().copied().fold(f64::NEG_INFINITY, f64::max),
        ys.iter().copied().fold(f64::NEG_INFINITY, f64::max),
    ]
}

/// One semantics node, with its rect in its parent's coordinates.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Node {
    pub id: i32,
    pub label: String,
    pub value: String,
    pub hint: String,
    pub tooltip: String,
    pub identifier: String,
    /// Set flags, by name, such as `button` or `enabled`.
    pub flags: Vec<&'static str>,
    /// `FlutterSemanticsAction` bits.
    pub actions: u64,
    /// `[left, top, right, bottom]`.
    pub rect: [f64; 4],
    pub transform: Transform,
    pub children: Vec<i32>,
    /// Position, min and max extent, for nodes that scroll.
    pub scroll: Option<[f64; 3]>,
    pub custom_actions: Vec<i32>,
}

impl Node {
    pub fn supports(&self, action: &str) -> bool {
        action_bit(action).is_some_and(|bit| self.actions & bit != 0)
    }

    pub fn has_flag(&self, flag: &str) -> bool {
        self.flags.contains(&flag)
    }
}

/// The semantics tree, updated incrementally from the engine.
#[derive(Default)]
pub struct Tree {
    nodes: HashMap<i32, Node>,
    custom_actions: HashMap<i32, String>,
}

pub const ROOT: i32 = 0;

impl Tree {
    pub fn update(&mut self, nodes: Vec<Node>, custom_actions: Vec<(i32, String)>) {
        for node in nodes {
            self.nodes.insert(node.id, node);
        }
        self.custom_actions.extend(custom_actions);
        // Forget nodes the update detached from the tree.
        let live = self.screen_rects();
        self.nodes.retain(|id, _| live.contains_key(id));
    }

    pub fn has_root(&self) -> bool {
        self.nodes.contains_key(&ROOT)
    }

    pub fn get(&self, id: i32) -> Option<&Node> {
        self.nodes.get(&id)
    }

    /// Every node reachable from the root, with its screen rect.
    pub fn screen_rects(&self) -> HashMap<i32, [f64; 4]> {
        let mut rects = HashMap::new();
        let mut stack = vec![(ROOT, IDENTITY)];
        while let Some((id, parent)) = stack.pop() {
            let Some(node) = self.nodes.get(&id) else {
                continue;
            };
            if rects.contains_key(&id) {
                continue;
            }
            let t = multiply(&parent, &node.transform);
            rects.insert(id, transform_rect(&t, node.rect));
            stack.extend(node.children.iter().map(|&child| (child, t)));
        }
        rects
    }

    /// The tree as JSON, from the root. A node is `visible` if it isn't
    /// hidden and some of it is within `screen` (width and height).
    pub fn to_json(&self, screen: (f64, f64)) -> Value {
        let rects = self.screen_rects();
        self.node_json(ROOT, &rects, screen)
    }

    fn node_json(&self, id: i32, rects: &HashMap<i32, [f64; 4]>, screen: (f64, f64)) -> Value {
        let (Some(node), Some(&[l, t, r, b])) = (self.nodes.get(&id), rects.get(&id)) else {
            return Value::Null;
        };
        let mut out = Map::new();
        out.insert("id".into(), json!(id));
        for (key, text) in [
            ("label", &node.label),
            ("value", &node.value),
            ("hint", &node.hint),
            ("tooltip", &node.tooltip),
            ("identifier", &node.identifier),
        ] {
            if !text.is_empty() {
                out.insert(key.into(), json!(text));
            }
        }
        out.insert("flags".into(), json!(node.flags));
        let actions: Vec<_> = ACTIONS
            .iter()
            .enumerate()
            .filter(|(i, _)| node.actions & (1 << i) != 0)
            .map(|(_, name)| *name)
            .collect();
        out.insert("actions".into(), json!(actions));
        if !node.custom_actions.is_empty() {
            let custom: Vec<_> = node
                .custom_actions
                .iter()
                .map(|id| json!({"id": id, "label": self.custom_actions.get(id)}))
                .collect();
            out.insert("customActions".into(), json!(custom));
        }
        out.insert(
            "rect".into(),
            json!([l.round(), t.round(), (r - l).round(), (b - t).round()]),
        );
        let on_screen = r > l && b > t && r > 0.0 && b > 0.0 && l < screen.0 && t < screen.1;
        out.insert(
            "visible".into(),
            json!(on_screen && !node.has_flag("hidden")),
        );
        if let Some([position, min, max]) = node.scroll {
            out.insert(
                "scroll".into(),
                json!({"position": position.round(), "min": min.round(), "max": max.round()}),
            );
        }
        let children: Vec<_> = node
            .children
            .iter()
            .map(|&child| self.node_json(child, rects, screen))
            .filter(|child| !child.is_null())
            .collect();
        out.insert("children".into(), json!(children));
        Value::Object(out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn translate(x: f64, y: f64) -> Transform {
        [1.0, 0.0, x, 0.0, 1.0, y, 0.0, 0.0, 1.0]
    }

    #[test]
    fn composes_transforms_down_the_tree() {
        let mut tree = Tree::default();
        tree.update(
            vec![
                Node {
                    id: 0,
                    rect: [0.0, 0.0, 800.0, 600.0],
                    transform: IDENTITY,
                    children: vec![1],
                    ..Default::default()
                },
                Node {
                    id: 1,
                    rect: [0.0, 0.0, 100.0, 50.0],
                    transform: translate(10.0, 20.0),
                    children: vec![2],
                    ..Default::default()
                },
                Node {
                    id: 2,
                    label: "OK".into(),
                    flags: vec!["button"],
                    actions: action_bit("tap").unwrap(),
                    rect: [5.0, 5.0, 25.0, 15.0],
                    transform: translate(1000.0, 0.0),
                    ..Default::default()
                },
            ],
            vec![],
        );
        let json = tree.to_json((800.0, 600.0));
        let child = &json["children"][0];
        assert_eq!(child["rect"], json!([10.0, 20.0, 100.0, 50.0]));
        assert_eq!(child["visible"], json!(true));
        let button = &child["children"][0];
        assert_eq!(button["label"], json!("OK"));
        assert_eq!(button["actions"], json!(["tap"]));
        assert_eq!(button["rect"], json!([1015.0, 25.0, 20.0, 10.0]));
        assert_eq!(button["visible"], json!(false));
        assert!(button.get("hint").is_none());
    }

    #[test]
    fn forgets_detached_nodes() {
        let mut tree = Tree::default();
        let root = |children| Node {
            id: 0,
            transform: IDENTITY,
            children,
            ..Default::default()
        };
        let leaf = Node {
            id: 1,
            transform: IDENTITY,
            ..Default::default()
        };
        tree.update(vec![root(vec![1]), leaf], vec![]);
        assert!(tree.get(1).is_some());
        tree.update(vec![root(vec![])], vec![]);
        assert!(tree.get(1).is_none());
    }

    #[test]
    fn scales_rects() {
        let scale = [2.0, 0.0, 0.0, 0.0, 2.0, 0.0, 0.0, 0.0, 1.0];
        assert_eq!(
            transform_rect(
                &multiply(&scale, &translate(1.0, 1.0)),
                [0.0, 0.0, 2.0, 3.0]
            ),
            [2.0, 2.0, 6.0, 8.0]
        );
    }

    #[test]
    fn maps_action_names_to_bits() {
        assert_eq!(action_bit("tap"), Some(1));
        assert_eq!(action_bit("setText"), Some(1 << 21));
        assert_eq!(action_bit("nope"), None);
    }
}
