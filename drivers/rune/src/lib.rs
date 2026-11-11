//! Opt-in, in-process Rune adapter for Bombadil's v0 JSONL protocol.
//! See the counter example for application-side integration.
mod screenshot;

use std::io::{BufRead, Write};
use std::time::{SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result};
use base64::Engine;
use rune::{Key, Runtime};
use serde::Deserialize;
use serde_json::{Value, json};

use screenshot::Screenshot;

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub enum Action {
    Tap { key: String },
    Click { x: f32, y: f32 },
    TypeText { text: String },
    Key { name: String },
    Scroll { x: f32, y: f32, dy: f32 },
}

#[derive(Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
enum Request {
    ApplyAction { action: Value },
    Terminate,
}

/// One Rune runtime per RPC session. `ready` polls application work after each
/// frame, with that frame's elapsed seconds. Use Runtime::update_if when it
/// delivers model changes; return false until those changes have been drawn.
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
    pub fn new(runtime: Runtime<S>, ready: Ready) -> Self {
        Self {
            runtime,
            ready,
            frame_limit: 600,
            screenshot: None,
        }
    }

    pub fn with_screenshots(mut self) -> Result<Self> {
        self.screenshot = Some(Screenshot::new(&self.runtime)?);
        Ok(self)
    }

    /// Maximum natural 120 Hz frames per observation. Exhaustion reports
    /// `settled: false`; it never snaps animations to their targets.
    pub fn set_frame_limit(&mut self, frames: usize) {
        self.frame_limit = frames;
    }

    pub fn runtime(&self) -> &Runtime<S> {
        &self.runtime
    }

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
