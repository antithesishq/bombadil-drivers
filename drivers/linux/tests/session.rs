use std::{
    io::Cursor,
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    time::Duration,
};

use anyhow::Result;
use bombadil_linux::{
    backend::Backend,
    journal::LogBatch,
    model::{
        Action, Lifecycle, LifecycleAction, MachineState, Node, Operation,
        PowerState, Segment, resolve,
    },
    session::{self, Session},
};
use serde_json::{Value, json};

struct Fixture {
    nodes: Vec<Node>,
    pending: Option<String>,
    delay: Duration,
    broken: bool,
    uncertain: bool,
    machine: Option<MachineState>,
    terminated: Arc<AtomicBool>,
}

impl Fixture {
    fn new() -> Self {
        Self {
            nodes: vec![Node {
                selector: vec![Segment {
                    role: "Entry".into(),
                    name: "Test input".into(),
                }],
                enabled: true,
                showing: true,
                focused: true,
                selected: false,
                editable: true,
                actions: vec![],
                window_bounds: Some([40, 70, 180, 30]),
                text: Some(String::new()),
            }],
            pending: None,
            delay: Duration::ZERO,
            broken: false,
            uncertain: false,
            machine: None,
            terminated: Arc::new(AtomicBool::new(false)),
        }
    }
}

impl Backend for Fixture {
    fn observe(&mut self) -> Result<(bool, Vec<Node>)> {
        if let Some(text) = self.pending.take() {
            std::thread::sleep(self.delay);
            if !self.broken {
                self.nodes[0].text = Some(text);
            }
        }
        Ok((true, self.nodes.clone()))
    }
    fn logs(&mut self) -> Result<Vec<LogBatch>> {
        Ok(vec![])
    }
    fn machine(&mut self) -> Result<Option<MachineState>> {
        Ok(self.machine.clone())
    }
    fn apply(&mut self, action: &Action) -> Result<()> {
        action.resolve(&self.nodes)?;
        if self.uncertain {
            anyhow::bail!("input may have been delivered");
        }
        if let bombadil_linux::model::Input::ReplaceText { text } =
            &action.input
        {
            self.pending = Some(text.clone());
        }
        Ok(())
    }
    fn lifecycle(&mut self, action: &LifecycleAction) -> Result<()> {
        let machine = self.machine.as_mut().unwrap();
        match action.lifecycle {
            Lifecycle::Suspend => {
                machine.power = PowerState::Suspended;
                machine.agent_connected = false;
            }
            Lifecycle::Wake => {
                machine.power = PowerState::Running;
                machine.agent_connected = true;
            }
            Lifecycle::Reboot => {
                machine.boot_id = Some("second".into());
                machine.application_generation += 1;
            }
            _ => unreachable!(),
        }
        Ok(())
    }
    fn terminate(&mut self) -> Result<()> {
        self.terminated.store(true, Ordering::SeqCst);
        Ok(())
    }
}

fn text(value: &str) -> Value {
    let target = json!([{"role":"Entry", "name":"Test input"}]);
    json!({"target":target, "input":{"kind":"replaceText", "text":value}, "expect":{"kind":"text", "target":target, "value":value}})
}

fn exchange(
    fixture: Fixture,
    requests: &[Value],
    timeout: Duration,
) -> (Result<()>, Vec<Value>) {
    let terminated = fixture.terminated.clone();
    let input = requests
        .iter()
        .map(|r| format!("{r}\n"))
        .collect::<String>();
    let mut output = Vec::new();
    let result = session::serve(
        Cursor::new(input),
        &mut output,
        || Ok(fixture),
        timeout,
    );
    assert!(terminated.load(Ordering::SeqCst));
    let rows = std::str::from_utf8(&output)
        .unwrap()
        .lines()
        .map(|line| serde_json::from_str(line).unwrap())
        .collect();
    (result, rows)
}

#[test]
fn rpc_reports_observed_outcomes_and_cleans_up_on_eof() {
    let (result, rows) = exchange(
        Fixture::new(),
        &[
            json!({"type":"apply_action", "action":text("Different 42")}),
            json!({"type":"apply_action", "action":text("")}),
        ],
        Duration::from_secs(1),
    );
    result.unwrap();
    assert_eq!(rows.len(), 4);
    assert_eq!(rows[0]["type"], "hello");
    assert_eq!(rows[0]["protocol"], 0);
    assert_eq!(rows[1]["state"]["outcome"], Value::Null);
    assert_eq!(rows[2]["state"]["nodes"][0]["text"], "Different 42");
    assert_eq!(rows[3]["state"]["nodes"][0]["text"], "");
    for row in &rows[2..] {
        assert_eq!(row["state"]["outcome"], true);
        assert!(row.get("not_applied").is_none());
    }
    for row in &rows[1..] {
        assert_eq!(row["settled"], false);
        assert!(row["timestamp_us"].as_u64().unwrap() > 0);
    }
    assert!(rows[1..].windows(2).all(
        |p| p[0]["timestamp_us"].as_u64() <= p[1]["timestamp_us"].as_u64()
    ));
}

#[test]
fn rejection_does_not_mutate_and_is_not_reused_for_the_next_action() {
    let (result, rows) = exchange(
        Fixture::new(),
        &[
            json!({"type":"apply_action", "action":text("not\nprintable")}),
            json!({"type":"apply_action", "action":text("valid")}),
            json!({"type":"terminate"}),
        ],
        Duration::from_secs(1),
    );
    result.unwrap();
    assert!(rows[1].get("not_applied").is_none());
    assert!(rows[2]["not_applied"].as_str().unwrap().contains("ASCII"));
    assert_eq!(rows[2]["state"]["nodes"][0]["text"], "");
    assert_eq!(rows[3]["state"]["nodes"][0]["text"], "valid");
    assert!(rows[3].get("not_applied").is_none());
}

#[test]
fn uncertain_input_is_fatal_not_rejected_or_retried() {
    let mut fixture = Fixture::new();
    fixture.uncertain = true;
    let (result, rows) = exchange(
        fixture,
        &[
            json!({"type":"apply_action", "action":text("first")}),
            json!({"type":"apply_action", "action":text("second")}),
        ],
        Duration::from_secs(1),
    );
    assert!(result.is_err());
    assert_eq!(rows.len(), 3);
    assert_eq!(rows[2]["type"], "error");
    assert!(
        rows[2]["message"]
            .as_str()
            .unwrap()
            .contains("may have been delivered")
    );
    assert!(rows[2].get("not_applied").is_none());
}

#[test]
fn wrong_or_late_observations_are_not_success() {
    for late in [false, true] {
        let mut fixture = Fixture::new();
        fixture.broken = !late;
        fixture.delay = if late {
            Duration::from_millis(30)
        } else {
            Duration::ZERO
        };
        let (result, rows) = exchange(
            fixture,
            &[json!({"type":"apply_action", "action":text("42")})],
            Duration::from_millis(10),
        );
        result.unwrap();
        assert_eq!(rows[2]["state"]["outcome"], false);
        assert_eq!(rows[2]["state"]["lastAction"], text("42"));
        if late {
            assert_eq!(rows[2]["state"]["nodes"][0]["text"], "42");
        }
    }
}

#[test]
fn lifecycle_preserves_unknown_guest_state_and_checks_boot_identity() {
    let mut fixture = Fixture::new();
    fixture.machine = Some(serde_json::from_value(json!({
        "power":"running", "agentConnected":true, "bootId":"first",
        "applicationGeneration":1, "transition":null, "events":[], "fault":null,
    })).unwrap());
    let mut session = Session::new(fixture, Duration::from_secs(1)).unwrap();
    let initial = session.observe().unwrap();
    fn lifecycle(name: &str, power: &str, connected: bool) -> Operation {
        serde_json::from_value(json!({"lifecycle":name, "timeoutSeconds":1,
            "expect":{"power":power,"agentConnected":connected,"boot":"same"}}))
        .unwrap()
    }
    session
        .apply(lifecycle("suspend", "suspended", false), &initial)
        .unwrap();
    let suspended = session.observe().unwrap();
    assert_eq!(suspended.outcome, Some(true));
    assert!(
        suspended.app_alive.is_none()
            && suspended.journals.is_none()
            && suspended.processes.is_none()
    );
    session
        .apply(lifecycle("wake", "running", true), &suspended)
        .unwrap();
    let awake = session.observe().unwrap();
    assert_eq!(awake.outcome, Some(true));
    assert_eq!(awake.app_alive, Some(true));
    session
        .apply(lifecycle("reboot", "running", true), &awake)
        .unwrap();
    let wrong_boot = session.observe().unwrap();
    assert_eq!(wrong_boot.outcome, Some(false));
    assert_eq!(
        wrong_boot.machine.unwrap().boot_id.as_deref(),
        Some("second")
    );
}

#[test]
fn target_and_expectation_ambiguity_fail_closed() {
    let fixture = Fixture::new();
    let nodes = fixture.nodes;
    let target = nodes[0].selector.clone();
    let duplicate = [nodes[0].clone(), nodes[0].clone()];
    assert!(resolve(&duplicate, &target).is_err());
    let action: Action = serde_json::from_value(text("")).unwrap();
    assert!(action.expect.satisfied(&duplicate).is_err());
    let mut hidden = nodes;
    hidden[0].showing = false;
    assert!(resolve(&hidden, &target).is_err());
    let mut ambiguous = json!({"lifecycle":"powerOff", "timeoutSeconds":1,
        "expect":{"power":"off", "agentConnected":false}});
    ambiguous["process"] = json!({"pid":1,"startTimeTicks":"1"});
    ambiguous["signal"] = "SIGKILL".into();
    assert!(serde_json::from_value::<Operation>(ambiguous).is_err());
}

#[test]
fn invalid_envelopes_and_failed_launches_emit_errors() {
    for input in [
        "not-json\n",
        "{\"type\":\"unknown\"}\n",
        "{\"type\":\"terminate\",\"extra\":true}\n",
        "{\"type\":\"terminate\"}",
    ] {
        let fixture = Fixture::new();
        let terminated = fixture.terminated.clone();
        let mut output = Vec::new();
        assert!(
            session::serve(
                Cursor::new(input),
                &mut output,
                || Ok(fixture),
                Duration::from_secs(1)
            )
            .is_err()
        );
        let last: Value = serde_json::from_str(
            std::str::from_utf8(&output)
                .unwrap()
                .lines()
                .last()
                .unwrap(),
        )
        .unwrap();
        assert_eq!(last["type"], "error");
        assert!(terminated.load(Ordering::SeqCst));
    }
    let mut output = Vec::new();
    assert!(
        session::serve::<Fixture>(
            Cursor::new(""),
            &mut output,
            || anyhow::bail!("launch failed"),
            Duration::from_secs(1)
        )
        .is_err()
    );
    let rows: Vec<Value> = std::str::from_utf8(&output)
        .unwrap()
        .lines()
        .map(|line| serde_json::from_str(line).unwrap())
        .collect();
    assert_eq!(rows.len(), 2);
    assert_eq!(rows[1]["message"], "launch failed");
}

#[test]
fn cli_requires_explicit_input_before_touching_transport() {
    let refused =
        std::process::Command::new(env!("CARGO_BIN_EXE_bombadil-linux"))
            .args([
                "run",
                "--agent-command",
                "does-not-exist.json",
                "--",
                "fixture",
            ])
            .output()
            .unwrap();
    assert!(!refused.status.success());
    assert!(refused.stdout.is_empty());
    assert!(
        String::from_utf8_lossy(&refused.stderr).contains("input is disabled")
    );
}
