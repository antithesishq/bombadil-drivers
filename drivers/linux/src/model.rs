use std::time::SystemTime;

use anyhow::{Result, bail, ensure};
use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Segment {
    pub role: String,
    pub name: String,
}

pub type Selector = Vec<Segment>;

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Node {
    pub selector: Selector,
    pub enabled: bool,
    pub showing: bool,
    pub focused: bool,
    pub selected: bool,
    pub editable: bool,
    pub actions: Vec<String>,
    pub window_bounds: Option<[i32; 4]>,
    pub text: Option<String>,
}

impl Node {
    pub fn interactable(&self) -> bool {
        self.enabled
            && self.showing
            && self
                .window_bounds
                .is_some_and(|[_, _, w, h]| w > 0 && h > 0)
            && (self.editable || !self.actions.is_empty())
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "camelCase", deny_unknown_fields)]
pub enum Input {
    Click,
    DoubleClick,
    ReplaceText { text: String },
    Key { key: Key },
    Chord { keys: Vec<Key> },
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum Key {
    Tab,
    Escape,
    Enter,
    Space,
    Ctrl,
    Shift,
    N,
    L,
    Z,
    F2,
    Delete,
}

impl Key {
    pub fn code(self) -> u16 {
        match self {
            Self::Tab => 15,
            Self::Escape => 1,
            Self::Enter => 28,
            Self::Space => 57,
            Self::Ctrl => 29,
            Self::Shift => 42,
            Self::N => 49,
            Self::L => 38,
            Self::Z => 44,
            Self::F2 => 60,
            Self::Delete => 111,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "camelCase", deny_unknown_fields)]
pub enum Expectation {
    Present { target: Selector },
    Absent { target: Selector },
    Text { target: Selector, value: String },
    ContainsText { target: Selector, value: String },
    Focused { target: Selector },
    Selected { target: Selector },
    Enabled { target: Selector, value: bool },
}

impl Expectation {
    pub fn satisfied(&self, nodes: &[Node]) -> Result<bool> {
        let (Self::Present { target }
        | Self::Absent { target }
        | Self::Text { target, .. }
        | Self::ContainsText { target, .. }
        | Self::Focused { target }
        | Self::Selected { target }
        | Self::Enabled { target, .. }) = self;
        let matches: Vec<_> =
            nodes.iter().filter(|n| n.selector == *target).collect();
        ensure!(
            matches.len() <= 1,
            "ambiguous expectation target: {target:?}"
        );
        Ok(match (self, matches.first()) {
            (Self::Absent { .. }, node) => node.is_none(),
            (Self::Present { .. }, Some(node)) => node.showing,
            (Self::Focused { .. }, Some(node)) => node.focused,
            (Self::Selected { .. }, Some(node)) => node.selected,
            (Self::Enabled { value, .. }, Some(node)) => node.enabled == *value,
            (Self::ContainsText { target, value }, Some(node)) => {
                node.showing
                    && nodes.iter().any(|child| {
                        child.showing
                            && child.selector.starts_with(target)
                            && child.text.as_ref() == Some(value)
                    })
            }
            (Self::Text { value, .. }, Some(node)) => {
                node.text.as_ref() == Some(value)
            }
            _ => false,
        })
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Action {
    pub target: Selector,
    pub input: Input,
    pub expect: Expectation,
}

impl Action {
    pub fn validate(&self) -> Result<()> {
        ensure!(!self.target.is_empty(), "empty input target");
        if let Input::Chord { keys } = &self.input {
            ensure!(
                !keys.is_empty() && keys.len() <= 4,
                "chord requires 1 to 4 keys"
            );
            ensure!(
                keys.iter()
                    .enumerate()
                    .all(|(i, key)| !keys[..i].contains(key)),
                "duplicate chord key"
            );
            ensure!(
                matches!(
                    self.target.last().unwrap().role.as_str(),
                    "Frame" | "Dialog"
                ),
                "chords require a window target"
            );
        }
        if let Input::ReplaceText { text } = &self.input {
            ensure!(text.len() <= 256, "text exceeds 256 bytes");
            ensure!(
                text.bytes().all(|b| b.is_ascii_graphic() || b == b' '),
                "PoC text must contain only printable ASCII"
            );
        }
        Ok(())
    }

    pub fn resolve<'a>(&self, nodes: &'a [Node]) -> Result<&'a Node> {
        if !matches!(self.input, Input::Chord { .. }) {
            return resolve(nodes, &self.target);
        }
        self.validate()?;
        let matches: Vec<_> = nodes
            .iter()
            .filter(|node| node.selector == self.target)
            .collect();
        ensure!(matches.len() == 1, "chord window is missing or ambiguous");
        let node = matches[0];
        ensure!(
            node.enabled && node.showing,
            "chord window is not available"
        );
        Ok(node)
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum PowerState {
    Off,
    Running,
    Suspended,
    Paused,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Lifecycle {
    Suspend,
    Wake,
    Shutdown,
    PowerOff,
    PowerOn,
    Reboot,
    Reset,
    Wait,
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum BootExpectation {
    #[default]
    Any,
    Same,
    Changed,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct MachineExpectation {
    pub power: PowerState,
    #[serde(default)]
    pub boot: BootExpectation,
    pub agent_connected: bool,
}

impl MachineExpectation {
    pub fn satisfied(
        &self,
        state: &MachineState,
        before: Option<&str>,
    ) -> bool {
        self.power == state.power
            && self.agent_connected == state.agent_connected
            && match self.boot {
                BootExpectation::Any => true,
                BootExpectation::Same => {
                    before.is_some() && before == state.boot_id.as_deref()
                }
                BootExpectation::Changed => {
                    state.boot_id.is_some()
                        && before != state.boot_id.as_deref()
                }
            }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LifecycleAction {
    pub lifecycle: Lifecycle,
    pub timeout_seconds: u64,
    #[serde(default)]
    pub settle_seconds: u64,
    pub expect: MachineExpectation,
}

impl LifecycleAction {
    pub fn validate(&self) -> Result<()> {
        ensure!(
            (1..=300).contains(&self.timeout_seconds),
            "lifecycle timeout must be between 1 and 300 seconds"
        );
        ensure!(
            self.settle_seconds < self.timeout_seconds,
            "lifecycle settlement must be shorter than its timeout"
        );
        ensure!(
            !self.expect.agent_connected
                || self.expect.power == PowerState::Running,
            "agent connectivity requires a running VM"
        );
        Ok(())
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MachineState {
    pub power: PowerState,
    pub agent_connected: bool,
    pub boot_id: Option<String>,
    pub application_generation: u64,
    pub transition: Option<Lifecycle>,
    pub events: Vec<serde_json::Value>,
    pub fault: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(untagged)]
pub enum Operation {
    Desktop(Action),
    Lifecycle(LifecycleAction),
    Signal(crate::processes::SignalAction),
}

impl From<Action> for Operation {
    fn from(action: Action) -> Self {
        Self::Desktop(action)
    }
}

impl Operation {
    pub fn validate(&self) -> Result<()> {
        match self {
            Self::Desktop(action) => action.validate(),
            Self::Lifecycle(action) => action.validate(),
            Self::Signal(action) => action.validate(),
        }
    }
}

pub fn resolve<'a>(nodes: &'a [Node], target: &Selector) -> Result<&'a Node> {
    let mut matches = nodes.iter().filter(|n| n.selector == *target);
    let Some(node) = matches.next() else {
        bail!("target disappeared: {target:?}");
    };
    ensure!(
        matches.next().is_none(),
        "ambiguous input target: {target:?}"
    );
    ensure!(
        node.interactable(),
        "target is not interactable: {target:?}"
    );
    Ok(node)
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DesktopState {
    pub timestamp: SystemTime,
    pub app_alive: Option<bool>,
    pub nodes: Vec<Node>,
    pub last_action: Option<Operation>,
    pub outcome: Option<bool>,
    pub journals: Option<Vec<crate::journal::LogBatch>>,
    pub machine: Option<MachineState>,
    pub processes: Option<Vec<crate::processes::ProcessInfo>>,
}
