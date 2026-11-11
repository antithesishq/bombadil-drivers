use std::{process::Stdio, time::Duration};

use anyhow::{Context, Result, bail, ensure};
use serde::Deserialize;
use tokio::{
    process::{Child, Command},
    runtime::Runtime,
    time::{sleep, timeout},
};

use crate::{
    accessibility::Accessibility,
    journal::LogBatch,
    model::{Action, Input, LifecycleAction, MachineState, Node, resolve},
    processes::{ProcessInfo, SignalAction},
};

const IO_TIMEOUT: Duration = Duration::from_secs(3);

pub trait Backend {
    fn observe(&mut self) -> Result<(bool, Vec<Node>)>;
    fn logs(&mut self) -> Result<Vec<LogBatch>>;
    fn apply(&mut self, action: &Action) -> Result<()>;
    fn terminate(&mut self) -> Result<()>;
    fn machine(&mut self) -> Result<Option<MachineState>> {
        Ok(None)
    }
    fn lifecycle(&mut self, _: &LifecycleAction) -> Result<()> {
        bail!("lifecycle actions require a VM controller")
    }
    fn processes(&mut self) -> Result<Vec<ProcessInfo>> {
        Ok(Vec::new())
    }
    fn signal(&mut self, _: &SignalAction) -> Result<()> {
        bail!("signal actions require a VM controller")
    }
}

impl<B: Backend + ?Sized> Backend for Box<B> {
    fn observe(&mut self) -> Result<(bool, Vec<Node>)> {
        (**self).observe()
    }
    fn logs(&mut self) -> Result<Vec<LogBatch>> {
        (**self).logs()
    }
    fn apply(&mut self, action: &Action) -> Result<()> {
        (**self).apply(action)
    }
    fn terminate(&mut self) -> Result<()> {
        (**self).terminate()
    }
    fn machine(&mut self) -> Result<Option<MachineState>> {
        (**self).machine()
    }
    fn lifecycle(&mut self, action: &LifecycleAction) -> Result<()> {
        (**self).lifecycle(action)
    }
    fn processes(&mut self) -> Result<Vec<ProcessInfo>> {
        (**self).processes()
    }
    fn signal(&mut self, action: &SignalAction) -> Result<()> {
        (**self).signal(action)
    }
}

pub async fn command(program: &str, args: &[&str]) -> Result<Vec<u8>> {
    let output = timeout(
        IO_TIMEOUT,
        Command::new(program)
            .args(args)
            .stdin(Stdio::null())
            .kill_on_drop(true)
            .output(),
    )
    .await
    .with_context(|| format!("{program} timed out"))?
    .with_context(|| format!("execute {program}"))?;
    ensure!(
        output.status.success(),
        "{program} failed ({}): {}{}",
        output.status,
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    Ok(output.stdout)
}

#[derive(Debug, Deserialize)]
struct Monitor {
    x: i32,
    y: i32,
    scale: f64,
    transform: i32,
    width: i32,
    height: i32,
}

#[derive(Debug, Deserialize)]
struct Cursor {
    x: f64,
    y: f64,
}

#[derive(Debug, Deserialize)]
struct Client {
    address: String,
    pid: u32,
    title: String,
    mapped: bool,
    hidden: bool,
    at: [i32; 2],
    size: [i32; 2],
}

pub struct DesktopBackend {
    runtime: Runtime,
    accessibility: Accessibility,
    pid: u32,
    child: Child,
}

impl DesktopBackend {
    pub fn launch(args: &[String]) -> Result<Self> {
        ensure!(!args.is_empty(), "missing application command");
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()?;
        let accessibility = runtime.block_on(async {
            timeout(IO_TIMEOUT, Accessibility::connect()).await?
        })?;
        let child = {
            let _guard = runtime.enter();
            Command::new(&args[0])
                .args(&args[1..])
                .stdin(Stdio::null())
                .stdout(
                    std::fs::OpenOptions::new()
                        .write(true)
                        .open("/dev/stderr")?,
                )
                .kill_on_drop(true)
                .spawn()
                .context("launch test application")?
        };
        let pid = child.id().context("test application has no PID")?;
        let mut backend = Self {
            runtime,
            accessibility,
            pid,
            child,
        };
        backend.runtime.block_on(async {
            let start = std::time::Instant::now();
            loop {
                if let Some(status) = backend.child.try_wait()? {
                    bail!("test application exited during startup: {status}");
                }
                match timeout(IO_TIMEOUT, backend.accessibility.nodes(pid)).await {
                    Ok(Ok(nodes)) if nodes.iter().any(Node::interactable) => break,
                    result if start.elapsed() >= Duration::from_secs(10) => {
                        bail!("application did not expose an interactable AT-SPI tree: {result:?}");
                    }
                    _ => sleep(Duration::from_millis(100)).await,
                }
            }
            Ok::<_, anyhow::Error>(())
        })?;
        Ok(backend)
    }

    async fn clients() -> Result<Vec<Client>> {
        Ok(serde_json::from_slice(
            &command("hyprctl", &["-j", "clients"]).await?,
        )?)
    }

    async fn active() -> Result<serde_json::Value> {
        Ok(serde_json::from_slice(
            &command("hyprctl", &["-j", "activewindow"]).await?,
        )?)
    }

    async fn prepare_input(&self, action: &Action) -> Result<()> {
        let monitors: Vec<Monitor> = serde_json::from_slice(
            &command("hyprctl", &["-j", "monitors"]).await?,
        )?;
        ensure!(
            monitors.len() == 1,
            "PoC input requires exactly one monitor"
        );
        let monitor = &monitors[0];
        ensure!(
            monitor.x == 0
                && monitor.y == 0
                && monitor.scale == 1.0
                && monitor.transform == 0,
            "PoC input requires an unrotated scale-1 monitor at (0, 0)"
        );
        let nodes = timeout(IO_TIMEOUT, self.accessibility.nodes(self.pid))
            .await
            .context("AT-SPI snapshot timed out before input preparation")??;
        action.resolve(&nodes)?;
        let window_name = action
            .target
            .iter()
            .find(|s| s.role == "Frame" || s.role == "Dialog")
            .context("target has no accessible window ancestor")?
            .name
            .as_str();
        let clients = Self::clients().await?;
        let matches: Vec<_> = clients
            .iter()
            .filter(|c| {
                c.pid == self.pid
                    && c.title == window_name
                    && c.mapped
                    && !c.hidden
            })
            .collect();
        ensure!(
            matches.len() == 1,
            "cannot uniquely match AT-SPI window {window_name:?} to a mapped Hyprland window"
        );
        let client = matches[0];
        ensure!(
            client.address.starts_with("0x")
                && client.address[2..].chars().all(|c| c.is_ascii_hexdigit()),
            "invalid Hyprland address"
        );
        command(
            "hyprctl",
            &[
                "dispatch",
                &format!(
                    "hl.dsp.focus({{window = \"address:{}\"}})",
                    client.address
                ),
            ],
        )
        .await?;
        timeout(IO_TIMEOUT, async {
            loop {
                if Self::active().await?["address"] == client.address {
                    break;
                }
                sleep(Duration::from_millis(50)).await;
            }
            Ok::<_, anyhow::Error>(())
        })
        .await
        .context("window did not acquire focus")??;
        let nodes = timeout(IO_TIMEOUT, self.accessibility.nodes(self.pid))
            .await
            .context("AT-SPI snapshot timed out after window focus")??;
        let node = action.resolve(&nodes)?;
        if matches!(
            action.input,
            Input::Chord { .. } | Input::Key { .. } | Input::ReplaceText { .. }
        ) {
            let devices: serde_json::Value = serde_json::from_slice(
                &command("hyprctl", &["-j", "devices"]).await?,
            )?;
            ensure!(
                devices["keyboards"].as_array().is_some_and(|keyboards| {
                    keyboards.iter().any(|keyboard| {
                        keyboard["name"] == "ydotoold-virtual-device"
                            && keyboard["active_keymap"] == "English (US)"
                    })
                }),
                "PoC keyboard input requires ydotoold-virtual-device with the English (US) keymap"
            );
        }
        if matches!(action.input, Input::Chord { .. }) {
            ensure!(
                Self::active().await?["address"] == client.address,
                "focus changed before shortcut input"
            );
            return Ok(());
        }
        if matches!(action.input, Input::ReplaceText { .. }) {
            ensure!(
                node.editable && node.text.is_some(),
                "target does not expose editable text"
            );
        }
        let [x, y, w, h] = node
            .window_bounds
            .context("target has no window-relative bounds")?;
        let clients = Self::clients().await?;
        let fresh = clients
            .iter()
            .find(|c| {
                c.address == client.address
                    && c.pid == self.pid
                    && c.mapped
                    && !c.hidden
            })
            .context("window disappeared before input")?;
        let local_x = i64::from(x) + i64::from(w) / 2;
        let local_y = i64::from(y) + i64::from(h) / 2;
        ensure!(
            local_x >= 0
                && local_y >= 0
                && local_x < i64::from(fresh.size[0])
                && local_y < i64::from(fresh.size[1]),
            "AT-SPI coordinates do not fall inside the target window"
        );
        let x = i64::from(fresh.at[0]) + local_x;
        let y = i64::from(fresh.at[1]) + local_y;
        ensure!(
            x >= 0
                && y >= 0
                && x < i64::from(monitor.width)
                && y < i64::from(monitor.height),
            "target is outside the monitor"
        );
        ensure!(
            Self::active().await?["address"] == client.address,
            "focus changed before input"
        );
        if !matches!(
            action.input,
            Input::Key { .. } | Input::ReplaceText { .. }
        ) || !node.focused
        {
            let raw_input: serde_json::Value = serde_json::from_slice(
                &command(
                    "hyprctl",
                    &["-j", "getoption", "input:force_no_accel"],
                )
                .await?,
            )?;
            ensure!(
                raw_input["bool"] == true,
                "PoC pointer input requires input:force_no_accel=true"
            );
            let cursor: Cursor = serde_json::from_slice(
                &command("hyprctl", &["-j", "cursorpos"]).await?,
            )?;
            command(
                "ydotool",
                &[
                    "mousemove",
                    "-x",
                    &(x as f64 - cursor.x).round().to_string(),
                    "-y",
                    &(y as f64 - cursor.y).round().to_string(),
                ],
            )
            .await?;
            timeout(IO_TIMEOUT, async {
                loop {
                    let cursor: Cursor = serde_json::from_slice(
                        &command("hyprctl", &["-j", "cursorpos"]).await?,
                    )?;
                    if (cursor.x - x as f64).abs() <= 1.0
                        && (cursor.y - y as f64).abs() <= 1.0
                    {
                        break;
                    }
                    sleep(Duration::from_millis(10)).await;
                }
                Ok::<_, anyhow::Error>(())
            })
            .await
            .with_context(|| {
                format!("pointer did not reach input target ({x}, {y})")
            })??;
            ensure!(
                Self::active().await?["address"] == client.address,
                "focus changed before click"
            );
            if matches!(action.input, Input::DoubleClick) {
                command(
                    "ydotool",
                    &["click", "--repeat", "2", "--next-delay", "50", "0xC0"],
                )
                .await?;
            } else {
                command("ydotool", &["click", "0xC0"]).await?;
            }
        }
        if matches!(action.input, Input::Key { .. } | Input::ReplaceText { .. })
        {
            timeout(IO_TIMEOUT, async {
                loop {
                    ensure!(
                        Self::active().await?["address"] == client.address,
                        "focus changed before keyboard input"
                    );
                    let nodes = self.accessibility.nodes(self.pid).await?;
                    if resolve(&nodes, &action.target)?.focused {
                        break;
                    }
                    sleep(Duration::from_millis(50)).await;
                }
                Ok::<_, anyhow::Error>(())
            })
            .await
            .context("target did not acquire keyboard focus")??;
        }
        Ok(())
    }
}

impl Backend for DesktopBackend {
    fn logs(&mut self) -> Result<Vec<LogBatch>> {
        Ok(Vec::new())
    }

    fn observe(&mut self) -> Result<(bool, Vec<Node>)> {
        self.runtime.block_on(async {
            command("hyprctl", &["-j", "monitors"]).await?;
            if self.child.try_wait()?.is_some() {
                return Ok((false, Vec::new()));
            }
            Ok((
                true,
                timeout(IO_TIMEOUT, self.accessibility.nodes(self.pid))
                    .await
                    .context(
                        "AT-SPI snapshot timed out during observation",
                    )??,
            ))
        })
    }

    fn apply(&mut self, action: &Action) -> Result<()> {
        action.validate()?;
        self.runtime.block_on(async {
            self.prepare_input(action).await?;
            match &action.input {
                Input::Click | Input::DoubleClick => {}
                Input::Chord { keys } => {
                    let mut args = vec!["key".to_string()];
                    args.extend(
                        keys.iter().map(|key| format!("{}:1", key.code())),
                    );
                    args.extend(
                        keys.iter()
                            .rev()
                            .map(|key| format!("{}:0", key.code())),
                    );
                    command(
                        "ydotool",
                        &args.iter().map(String::as_str).collect::<Vec<_>>(),
                    )
                    .await?;
                }
                Input::ReplaceText { text } => {
                    command(
                        "ydotool",
                        &["key", "29:1", "30:1", "30:0", "29:0"],
                    )
                    .await?;
                    if text.is_empty() {
                        command("ydotool", &["key", "14:1", "14:0"]).await?;
                    } else {
                        command(
                            "ydotool",
                            &[
                                "type",
                                "--key-delay",
                                "1",
                                "--key-hold",
                                "1",
                                "--escape",
                                "0",
                                "--",
                                text,
                            ],
                        )
                        .await?;
                    }
                }
                Input::Key { key } => {
                    let code = key.code();
                    command(
                        "ydotool",
                        &["key", &format!("{code}:1"), &format!("{code}:0")],
                    )
                    .await?;
                }
            }
            Ok(())
        })
    }

    fn terminate(&mut self) -> Result<()> {
        self.runtime.block_on(async {
            if self.child.try_wait()?.is_none() {
                self.child.kill().await?;
            }
            self.child.wait().await?;
            Ok(())
        })
    }
}
