//! A Bombadil external driver for Flutter apps.
//!
//! It hosts the app's debug bundle with the Flutter engine's embedder API,
//! with no window and no changes to the app, and speaks Bombadil's external
//! driver protocol on stdin and stdout. States are the app's semantics
//! (accessibility) tree; actions are semantics actions on its nodes. See
//! the README for both.

mod channels;
mod engine;
mod logs;
mod semantics;

#[allow(
    non_upper_case_globals,
    non_camel_case_types,
    non_snake_case,
    dead_code,
    clippy::all
)]
mod ffi {
    include!(concat!(env!("OUT_DIR"), "/bindings.rs"));
}

use std::fs::File;
use std::io::{BufRead, Write};
use std::process;

use serde_json::{json, Value};

use engine::{millis, now, Host, Options};

const USAGE: &str = "\
usage: bombadil-flutter-driver --assets <flutter_assets> --icu-data <icudtl.dat>
         [--size <width>x<height>] [--name <name>] [--stall-timeout-ms <ms>]
         [-- <engine switches>...]";

/// How long the app must stay quiet to count as settled.
const QUIET_MS: u64 = 300;
/// How long to wait for the app to settle after an action.
const SETTLE_CAP_MS: u64 = 5_000;
/// How long to wait for the app's first semantics tree.
const STARTUP_CAP_MS: u64 = 45_000;

struct Args {
    options: Options,
    name: String,
    stall_timeout_ms: u64,
}

fn parse_args(mut args: impl Iterator<Item = String>) -> Result<Args, String> {
    let mut assets = None;
    let mut icu_data = None;
    let (mut width, mut height) = (1024, 768);
    let mut name = "bombadil-flutter-driver".to_string();
    let mut stall_timeout_ms = 60_000;
    let mut engine_args = Vec::new();
    while let Some(arg) = args.next() {
        let mut value = || args.next().ok_or(format!("{arg} needs a value"));
        match arg.as_str() {
            "--assets" => assets = Some(value()?),
            "--icu-data" => icu_data = Some(value()?),
            "--name" => name = value()?,
            "--size" => {
                let size = value()?;
                let parsed = size
                    .split_once('x')
                    .and_then(|(w, h)| Some((w.parse().ok()?, h.parse().ok()?)));
                (width, height) = parsed.ok_or(format!("bad --size {size}"))?;
            }
            "--stall-timeout-ms" => {
                stall_timeout_ms = value()?.parse().map_err(|e| format!("{e}"))?;
            }
            "--" => engine_args.extend(args.by_ref()),
            _ => return Err(format!("unknown argument {arg}")),
        }
    }
    Ok(Args {
        options: Options {
            assets: assets.ok_or("--assets is required")?,
            icu_data: icu_data.ok_or("--icu-data is required")?,
            width,
            height,
            engine_args,
        },
        name,
        stall_timeout_ms,
    })
}

/// An action from the specification.
#[derive(Debug, PartialEq)]
enum Action {
    /// A semantics action without arguments, by name, on a node.
    Semantics { node: i32, action: &'static str },
    SetText { node: i32, text: String },
    Custom { node: i32, id: i32 },
    PointerTap { x: f64, y: f64 },
    Back,
}

const SEMANTICS_ACTIONS: [(&str, &str); 15] = [
    ("Tap", "tap"),
    ("LongPress", "longPress"),
    ("ScrollUp", "scrollUp"),
    ("ScrollDown", "scrollDown"),
    ("ScrollLeft", "scrollLeft"),
    ("ScrollRight", "scrollRight"),
    ("Increase", "increase"),
    ("Decrease", "decrease"),
    ("Dismiss", "dismiss"),
    ("Focus", "focus"),
    ("Expand", "expand"),
    ("Collapse", "collapse"),
    ("ShowOnScreen", "showOnScreen"),
    ("Copy", "copy"),
    ("Paste", "paste"),
];

fn parse_action(action: &Value) -> Result<Action, String> {
    let bad = || format!("unknown action: {action}");
    if action == "Back" {
        return Ok(Action::Back);
    }
    let Some((tag, body)) = action.as_object().filter(|o| o.len() == 1).and_then(|o| o.iter().next())
    else {
        return Err(bad());
    };
    let node = || {
        body["node"]
            .as_i64()
            .map(|n| n as i32)
            .ok_or_else(|| format!("{tag} needs a node: {action}"))
    };
    if let Some((_, name)) = SEMANTICS_ACTIONS.iter().find(|(t, _)| t == tag) {
        return Ok(Action::Semantics { node: node()?, action: name });
    }
    match tag.as_str() {
        "SetText" => Ok(Action::SetText {
            node: node()?,
            text: body["text"].as_str().ok_or_else(bad)?.to_string(),
        }),
        "CustomAction" => Ok(Action::Custom {
            node: node()?,
            id: body["action"].as_i64().ok_or_else(bad)? as i32,
        }),
        "PointerTap" => Ok(Action::PointerTap {
            x: body["x"].as_f64().ok_or_else(bad)?,
            y: body["y"].as_f64().ok_or_else(bad)?,
        }),
        _ => Err(bad()),
    }
}

fn settle(host: &Host) -> bool {
    host.settle(millis(QUIET_MS), millis(SETTLE_CAP_MS))
}

fn tap_at(host: &Host, x: f64, y: f64) -> Result<(), String> {
    host.pointer(ffi::kDown, x, y)?;
    host.pump_through(now() + millis(50));
    host.pointer(ffi::kUp, x, y)
}

/// The centre of a node that is at least partly on screen.
fn visible_centre(host: &Host, node: i32) -> Option<(f64, f64)> {
    let rect = *host.app().tree.screen_rects().get(&node)?;
    let [l, t, r, b] = [
        rect[0].max(0.0),
        rect[1].max(0.0),
        rect[2].min(host.width as f64),
        rect[3].min(host.height as f64),
    ];
    (r > l && b > t).then_some(((l + r) / 2.0, (t + b) / 2.0))
}

/// Whether `node` exists and supports `action`.
fn supports(host: &Host, node: i32, action: &str) -> Result<bool, String> {
    let app = host.app();
    let node = app.tree.get(node).ok_or(format!("no node {node}"))?;
    Ok(node.supports(action))
}

/// Performs `action`. Errors mean that the action didn't apply to the
/// current state, which the state reports; they don't end the run.
fn perform(host: &Host, action: &Action) -> Result<(), String> {
    match action {
        Action::Semantics { node, action } => {
            if supports(host, *node, action)? {
                host.dispatch_semantics_action(*node, semantics::action_bit(action).unwrap(), &[])
            } else if *action == "tap" {
                let (x, y) = visible_centre(host, *node)
                    .ok_or(format!("node {node} can't be tapped and isn't on screen"))?;
                tap_at(host, x, y)
            } else {
                Err(format!("node {node} doesn't support {action}"))
            }
        }
        Action::SetText { node, text } => {
            // Text fields take text once focused.
            if !supports(host, *node, "setText")? {
                if supports(host, *node, "tap")? {
                    host.dispatch_semantics_action(*node, semantics::action_bit("tap").unwrap(), &[])?;
                } else if let Some((x, y)) = visible_centre(host, *node) {
                    tap_at(host, x, y)?;
                }
                settle(host);
            }
            if supports(host, *node, "setText")? {
                host.dispatch_semantics_action(
                    *node,
                    semantics::action_bit("setText").unwrap(),
                    &channels::standard_string(text),
                )
            } else if let Some(client) = host.app().platform.text_client {
                host.send("flutter/textinput", &channels::update_editing_state(client, text))
            } else {
                Err(format!("node {node} doesn't take text"))
            }
        }
        Action::Custom { node, id } => {
            host.dispatch_semantics_action(
                *node,
                semantics::action_bit("customAction").unwrap(),
                &channels::standard_int(*id),
            )
        }
        Action::PointerTap { x, y } => tap_at(host, *x, *y),
        Action::Back => host.send(
            "flutter/navigation",
            &channels::json_method_call("popRoute", Value::Null),
        ),
    }
}

fn state(host: &Host, settled: bool, last_action: Option<Value>) -> Value {
    let mut app = host.app();
    let mut state = json!({
        "route": app.platform.route,
        "size": [host.width, host.height],
        "tree": app.tree.to_json((host.width as f64, host.height as f64)),
        "errors": logs::take_errors(),
        "exitRequested": std::mem::take(&mut app.platform.exit_requested),
        "settled": settled,
    });
    if let Some(last_action) = last_action {
        state["lastAction"] = last_action;
    }
    state
}

struct Protocol(File);

impl Protocol {
    fn send(&mut self, message: Value) {
        let line = format!("{message}\n");
        if self.0.write_all(line.as_bytes()).and_then(|_| self.0.flush()).is_err() {
            // Bombadil has gone.
            process::exit(1);
        }
    }

    fn fail(&mut self, message: String) -> ! {
        eprintln!("bombadil-flutter-driver: {message}");
        self.send(json!({"type": "error", "message": message}));
        process::exit(1);
    }
}

fn main() {
    let args = match parse_args(std::env::args().skip(1)) {
        Ok(args) => args,
        Err(error) => {
            eprintln!("{error}\n{USAGE}");
            process::exit(2);
        }
    };
    let mut protocol = Protocol(logs::redirect().expect("redirecting stdout and stderr"));
    protocol.send(json!({
        "type": "hello",
        "protocol": 0,
        "name": args.name,
        "stall_timeout_ms": args.stall_timeout_ms,
    }));

    let host = Host::start(args.options).unwrap_or_else(|e| protocol.fail(e));
    std::thread::spawn(move || {
        for line in std::io::stdin().lock().lines() {
            match line {
                Ok(line) if line.trim().is_empty() => {}
                Ok(line) => host.push_line(Some(line)),
                Err(_) => break,
            }
        }
        host.push_line(None);
    });

    let settled = host.settle(millis(QUIET_MS), millis(STARTUP_CAP_MS));
    if !host.app().tree.has_root() {
        protocol.fail(format!(
            "the app reported no semantics tree within {STARTUP_CAP_MS}ms of starting"
        ));
    }
    protocol.send(json!({"type": "state", "state": state(host, settled, None)}));

    loop {
        let line = loop {
            if let Some(line) = host.next_line() {
                break line;
            }
            host.pump(now() + millis(1000), true);
        };
        let Some(line) = line else { break };
        let message: Value = serde_json::from_str(&line)
            .unwrap_or_else(|e| protocol.fail(format!("invalid message {line}: {e}")));
        match message["type"].as_str() {
            Some("apply") => {
                let raw = &message["action"];
                let action = parse_action(raw).unwrap_or_else(|e| protocol.fail(e));
                let result = perform(host, &action);
                let settled = settle(host);
                let mut last_action = json!({"action": raw, "ok": result.is_ok()});
                if let Err(error) = result {
                    last_action["error"] = json!(error);
                }
                protocol.send(json!({
                    "type": "state",
                    "state": state(host, settled, Some(last_action)),
                }));
            }
            Some("terminate") => break,
            _ => protocol.fail(format!("unknown message {line}")),
        }
    }
    host.shutdown();
    process::exit(0);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_actions() {
        assert_eq!(parse_action(&json!("Back")), Ok(Action::Back));
        assert_eq!(
            parse_action(&json!({"Tap": {"node": 7}})),
            Ok(Action::Semantics { node: 7, action: "tap" })
        );
        assert_eq!(
            parse_action(&json!({"ScrollDown": {"node": 2}})),
            Ok(Action::Semantics { node: 2, action: "scrollDown" })
        );
        assert_eq!(
            parse_action(&json!({"SetText": {"node": 3, "text": "hi"}})),
            Ok(Action::SetText { node: 3, text: "hi".into() })
        );
        assert_eq!(
            parse_action(&json!({"PointerTap": {"x": 1, "y": 2.5}})),
            Ok(Action::PointerTap { x: 1.0, y: 2.5 })
        );
        assert!(parse_action(&json!({"Tap": {}})).is_err());
        assert!(parse_action(&json!({"Fly": {"node": 1}})).is_err());
        assert!(parse_action(&json!("Forward")).is_err());
    }

    #[test]
    fn parses_arguments() {
        let args = parse_args(
            [
                "--assets", "a", "--icu-data", "i", "--size", "800x600", "--",
                "--verbose-logging",
            ]
            .map(String::from)
            .into_iter(),
        )
        .unwrap();
        assert_eq!((args.options.width, args.options.height), (800, 600));
        assert_eq!(args.options.engine_args, ["--verbose-logging"]);
        assert_eq!(args.stall_timeout_ms, 60_000);
        assert!(parse_args(["--assets", "a"].map(String::from).into_iter()).is_err());
    }
}
