//! Test Rune apps with [Bombadil](https://github.com/antithesishq/bombadil).
//!
//! Give [`Driver`] your app's [`Runtime`] and a callback that polls application
//! work. It sends input through Rune, reads the element tree, and exchanges
//! states and actions with Bombadil over stdin and stdout.
//!
//! This is an in-process, grey-box driver. Enable it in a testing build, using
//! the same app specification as the native runner. The app still owns its
//! backends, external I/O, and cleanup.
//!
//! # Where to start
//!
//! - [`Driver::new`] - Give the driver a runtime and a readiness callback.
//! - [`Driver::run`] - Run a session with Bombadil's JSONL protocol.
//! - [`Action`] - Send taps, pointer input, text, keys, and scrolling through Rune.
//! - [`Driver::observe`] - Advance frames and read the current element tree.
//! - [`Driver::with_screenshots`] - Include PNGs from Rune's offscreen renderer.
//!
//! # Example
//!
//! This app has no background work, so its readiness callback returns `true`.
//! An app with pending work needs to poll it before reporting ready.
//!
//! ```no_run
//! use bombadil_rune::Driver;
//! use rune::{App, AppSpec, Cx, El, Env};
//!
//! fn view(_: &(), _: &mut Cx<()>) -> El {
//!     rune::text("Hello, Bombadil")
//! }
//!
//! let spec = AppSpec::new("Example", (320.0, 240.0), || (), view);
//! let runtime = App::new(spec).headless_runtime((320.0, 240.0), Env::new(true, 1.0));
//! let mut driver = Driver::new(runtime, |_, _| true);
//! driver.run("example", std::io::stdin().lock(), std::io::stdout().lock())?;
//! # Ok::<(), anyhow::Error>(())
//! ```
//!
//! # Settling
//!
//! Frames advance at a simulated 120 Hz. An observation settles when motion
//! stops, no Rune wake is pending, and the callback reports ready. If the frame
//! budget runs out, the state carries `settled: false`.
//!
//! Simulated frame time does not advance network or service clocks. Headless
//! runs do not exercise OS input delivery, the compositor, or a physical display.
mod screenshot;

use std::io::{BufRead, Write};
use std::time::{SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result};
use base64::Engine;
use rune::{Key, Runtime};
use serde::Deserialize;
use serde_json::{Value, json};

use screenshot::Screenshot;

/// Input to send through Rune's handlers.
///
/// Coordinates use the logical viewport, not desktop pixels. JSON uses the
/// variant name as its tag, for example `{"Tap":{"key":"increment"}}`.
#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub enum Action {
    /// Click the center of the element with this key.
    Tap { key: String },
    /// Move the pointer, press, and release at this position.
    Click { x: f32, y: f32 },
    /// Send each character as a key press. Newlines and tabs become Enter and Tab.
    TypeText { text: String },
    /// Press a named key or a single Unicode character.
    ///
    /// Named keys are Enter, Tab, Escape, Backspace, Delete, Left, Right, Up,
    /// Down, Home, End, PageUp, and PageDown. Modifier chords are not supported.
    Key { name: String },
    /// Send a vertical wheel delta at this position.
    Scroll { x: f32, y: f32, dy: f32 },
}

#[derive(Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
enum Request {
    ApplyAction { action: Value },
    Terminate,
}

/// Drive one Rune runtime through a Bombadil session.
///
/// Start with [`Driver::new`], then call [`run`](Self::run). Use a fresh runtime
/// for each session; the driver does not reset application state between runs.
pub struct Driver<S: 'static, Ready> {
    runtime: Runtime<S>,
    ready: Ready,
    frame_limit: usize,
    screenshot: Option<Screenshot>,
}

impl<S, Ready> Driver<S, Ready>
where
    S: 'static,
    Ready: FnMut(&mut Runtime<S>, f64) -> bool,
{
    /// Take a runtime and a callback that polls work after each observation frame.
    ///
    /// The callback receives the runtime and the frame's elapsed seconds. Use
    /// [`Runtime::update_if`] to deliver model changes, then return `false`
    /// until a later frame draws them. Return `true` when no work is pending.
    /// The callback must not block beyond Bombadil's host stall timeout.
    pub fn new(runtime: Runtime<S>, ready: Ready) -> Self {
        Self {
            runtime,
            ready,
            frame_limit: 600,
            screenshot: None,
        }
    }

    /// Include a base64-encoded PNG in each observation.
    ///
    /// Creates an offscreen GPU renderer. Returns an error if the runtime is
    /// transparent, its dimensions are invalid, or GPU setup fails.
    pub fn with_screenshots(mut self) -> Result<Self> {
        self.screenshot = Some(Screenshot::new(&self.runtime)?);
        Ok(self)
    }

    /// Set the frame budget for each observation. The default is 600 frames.
    ///
    /// Frames advance at 120 Hz. Running out of frames reports `settled: false`
    /// without jumping animations to their final values. A zero budget still
    /// commits queued input and polls the readiness callback once.
    pub fn set_frame_limit(&mut self, frames: usize) {
        self.frame_limit = frames;
    }

    /// Read the runtime without advancing a frame.
    pub fn runtime(&self) -> &Runtime<S> {
        &self.runtime
    }

    /// Advance frames and return a JSONL state message as a JSON value.
    ///
    /// The message contains the element tree, viewport, settling status, and a
    /// host timestamp in epoch microseconds. With screenshots enabled, it also
    /// contains `screenshot_png`. The `accessibility` field is null because the
    /// elements come from Rune, not OS accessibility.
    ///
    /// This method does not write to the session's output. Use [`run`](Self::run)
    /// to exchange messages and keep timestamps nondecreasing within a session.
    pub fn observe(&mut self) -> Result<Value> {
        let mut settled = false;
        // Commit queued input even when the caller allows no settling frames.
        let mut moving = self.runtime.frame(0.0);
        for frame in 0..=self.frame_limit {
            let ready = (self.ready)(
                &mut self.runtime,
                if frame == 0 { 0.0 } else { 1.0 / 120.0 },
            );
            if !moving && self.runtime.wake_deadline().is_none() && ready {
                settled = true;
                break;
            }
            if frame == self.frame_limit {
                break;
            }
            moving = self.runtime.frame(1.0 / 120.0);
        }
        let elements: Vec<_> = self
            .runtime
            .elements()
            .into_iter()
            .map(|element| {
                json!({
                    "key": element.key, "path": element.path, "parent": element.parent,
                    "role": element.role, "label": element.label, "text": element.text,
                    "rect": element.rect, "visible": element.visible, "opacity": element.opacity,
                    "focused": element.focused, "focusable": element.focusable,
                    "inert": element.inert, "exiting": element.exiting, "moving": element.moving,
                })
            })
            .collect();
        let mut message = json!({
            "type": "state", "settled": settled,
            "state": {
                "elements": elements, "accessibility": null,
                "viewport": [self.runtime.viewport.0, self.runtime.viewport.1],
            },
            "timestamp_us": u64::try_from(SystemTime::now().duration_since(UNIX_EPOCH)?.as_micros())?,
        });
        if let Some(screenshot) = &mut self.screenshot {
            message["screenshot_png"] = base64::prelude::BASE64_STANDARD
                .encode(screenshot.capture(&mut self.runtime)?)
                .into();
        }
        Ok(message)
    }

    /// Send input, or return a reason for rejecting it before dispatch.
    ///
    /// `None` means the action was accepted, not that a handler received input.
    /// Call [`observe`](Self::observe) to advance frames and read the result.
    pub fn apply_action(&mut self, action: Action) -> Option<String> {
        match action {
            Action::Tap { key } => {
                let Some((x, y)) = self.runtime.find(&key) else {
                    return Some(format!("no element with key {key}"));
                };
                self.click(x, y)
            }
            Action::Click { x, y } => self.click(x, y),
            Action::TypeText { text } => {
                for character in text.chars() {
                    let key = match character {
                        '\n' => Key::Enter,
                        '\t' => Key::Tab,
                        _ => Key::Text(character.to_string()),
                    };
                    self.press(key);
                }
                None
            }
            Action::Key { name } => {
                let key = match name.as_str() {
                    "Enter" => Key::Enter,
                    "Tab" => Key::Tab,
                    "Escape" => Key::Escape,
                    "Backspace" => Key::Backspace,
                    "Delete" => Key::Delete,
                    "Left" => Key::Left,
                    "Right" => Key::Right,
                    "Up" => Key::Up,
                    "Down" => Key::Down,
                    "Home" => Key::Home,
                    "End" => Key::End,
                    "PageUp" => Key::PageUp,
                    "PageDown" => Key::PageDown,
                    _ if name.chars().count() == 1 => Key::Text(name),
                    _ => return Some(format!("unsupported key {name}")),
                };
                self.press(key);
                None
            }
            Action::Scroll { x, y, dy } => {
                if !self.inside(x, y) || !dy.is_finite() {
                    return Some("invalid scroll coordinates or delta".into());
                }
                self.runtime.wheel(x, y, dy);
                None
            }
        }
    }

    fn inside(&self, x: f32, y: f32) -> bool {
        x >= 0.0 && y >= 0.0 && x < self.runtime.viewport.0 && y < self.runtime.viewport.1
    }

    fn click(&mut self, x: f32, y: f32) -> Option<String> {
        if !self.inside(x, y) {
            return Some("click is outside the viewport".into());
        }
        self.runtime.pointer_move(x, y);
        self.runtime.frame(0.0);
        self.runtime.pointer_down(x, y);
        self.runtime.frame(0.0);
        self.runtime.pointer_up(x, y);
        None
    }

    fn press(&mut self, key: Key) {
        let event = rune::KeyEvent {
            key,
            mods: rune::Mods::default(),
        };
        self.runtime.key(event.clone());
        self.runtime.frame(0.0);
        self.runtime.key_up(event);
        self.runtime.frame(0.0);
    }

    /// Run Bombadil's version-0 JSONL protocol until EOF or `terminate`.
    ///
    /// Sends a handshake with `name`, an initial state, then one state per
    /// `apply_action`. Rejected actions add `not_applied` to the next state;
    /// background work can still advance during that observation.
    ///
    /// Malformed requests and observation failures produce an error message
    /// and end the session. Reserve `output` for protocol messages; send app
    /// logs to stderr. The caller owns application cleanup after this returns.
    pub fn run(&mut self, name: &str, input: impl BufRead, mut output: impl Write) -> Result<()> {
        let result = self.run_session(name, input, &mut output);
        if let Err(error) = &result {
            send(
                &mut output,
                &json!({"type":"error", "message": error.to_string()}),
            )?;
        }
        result
    }

    fn run_session(
        &mut self,
        name: &str,
        input: impl BufRead,
        output: &mut impl Write,
    ) -> Result<()> {
        send(output, &json!({"type":"hello", "protocol":0, "name":name}))?;
        let mut previous = 0;
        self.send_state(output, None, &mut previous)?;
        for line in input.lines() {
            let line = line?;
            if line.trim().is_empty() {
                continue;
            }
            match serde_json::from_str(&line).context("invalid RPC request")? {
                Request::Terminate => return Ok(()),
                Request::ApplyAction { action } => {
                    let rejected = match serde_json::from_value(action) {
                        Ok(action) => self.apply_action(action),
                        Err(error) => Some(format!("unsupported action: {error}")),
                    };
                    self.send_state(output, rejected, &mut previous)?;
                }
            }
        }
        Ok(())
    }

    fn send_state(
        &mut self,
        output: &mut impl Write,
        rejected: Option<String>,
        previous: &mut u64,
    ) -> Result<()> {
        let mut message = self.observe()?;
        *previous = message["timestamp_us"]
            .as_u64()
            .context("missing capture timestamp")?
            .max(*previous);
        message["timestamp_us"] = (*previous).into();
        if let Some(reason) = rejected {
            message["not_applied"] = reason.into();
        }
        send(output, &message)
    }
}

fn send(output: &mut impl Write, message: &Value) -> Result<()> {
    serde_json::to_writer(&mut *output, message)?;
    output.write_all(b"\n")?;
    output.flush()?;
    Ok(())
}
