use std::{
    io::{BufRead, Write},
    process::Stdio,
    time::Duration,
};

use anyhow::{Context, Result, bail, ensure};
use serde::{Deserialize, Serialize, de::DeserializeOwned};
use tokio::{
    io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader},
    process::{Child, ChildStdin, ChildStdout, Command},
    runtime::Runtime,
};

use crate::{
    backend::{Backend, DesktopBackend},
    journal::{Journal, LogBatch, LogOptions},
    model::{Action, Lifecycle, Node},
    processes::{self, ProcessInfo, SignalAction},
};

const MAX_MESSAGE: u64 = 32 * 1024 * 1024;
const RPC_TIMEOUT: Duration = Duration::from_secs(20);

#[derive(Serialize, Deserialize)]
#[serde(tag = "method", rename_all = "camelCase", deny_unknown_fields)]
enum Request {
    Identity,
    Power {
        operation: Lifecycle,
    },
    Launch {
        command: Vec<String>,
        logs: Vec<LogOptions>,
    },
    Observe,
    Processes,
    Signal {
        action: SignalAction,
    },
    Logs,
    Apply {
        action: Action,
    },
    Terminate,
}

#[derive(Serialize, Deserialize)]
#[serde(tag = "status", rename_all = "camelCase")]
enum Response {
    Ok { value: serde_json::Value },
    Error { message: String },
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Identity {
    pub boot_id: String,
    pub vm_uuid: String,
}

pub struct GuestBackend<B> {
    backend: B,
    journal: Option<Journal>,
}

impl<B: Backend> GuestBackend<B> {
    pub fn launch(
        logs: Vec<LogOptions>,
        launch: impl FnOnce() -> Result<B>,
    ) -> Result<Self> {
        let journal = Journal::start(logs)?;
        Ok(Self {
            backend: launch()?,
            journal,
        })
    }
}

impl<B: Backend> Backend for GuestBackend<B> {
    fn processes(&mut self) -> Result<Vec<ProcessInfo>> {
        processes::snapshot()
    }
    fn observe(&mut self) -> Result<(bool, Vec<Node>)> {
        self.backend.observe()
    }
    fn logs(&mut self) -> Result<Vec<LogBatch>> {
        self.journal
            .as_mut()
            .map(Journal::poll)
            .transpose()
            .map(Option::unwrap_or_default)
    }
    fn apply(&mut self, action: &Action) -> Result<()> {
        self.backend.apply(action)
    }
    fn terminate(&mut self) -> Result<()> {
        self.backend.terminate()
    }
}

pub fn serve(
    launch: impl FnOnce(&[String]) -> Result<DesktopBackend>,
) -> Result<()> {
    serve_with(std::io::stdin().lock(), std::io::stdout().lock(), launch)
}

pub fn serve_with<B: Backend>(
    mut input: impl BufRead,
    mut output: impl Write,
    launch: impl FnOnce(&[String]) -> Result<B>,
) -> Result<()> {
    let mut launch = Some(launch);
    let mut backend: Option<GuestBackend<B>> = None;
    let result = (|| -> Result<()> {
        loop {
            let mut line = Vec::new();
            let count = std::io::Read::take(&mut input, MAX_MESSAGE + 1)
                .read_until(b'\n', &mut line)?;
            if count == 0 {
                return Ok(());
            }
            ensure!(
                line.len() as u64 <= MAX_MESSAGE && line.last() == Some(&b'\n'),
                "invalid or oversized agent request"
            );
            let request: Request = serde_json::from_slice(&line)?;
            let terminate = matches!(request, Request::Terminate);
            let response = (|| -> Result<serde_json::Value> {
                if matches!(request, Request::Identity | Request::Power { .. })
                {
                    let uuid = std::process::Command::new("sudo")
                        .args(["-n", "cat", "/sys/class/dmi/id/product_uuid"])
                        .output()?;
                    ensure!(
                        uuid.status.success(),
                        "cannot verify disposable guest UUID"
                    );
                    let identity = Identity {
                        boot_id: std::fs::read_to_string(
                            "/proc/sys/kernel/random/boot_id",
                        )?
                        .trim()
                        .into(),
                        vm_uuid: String::from_utf8(uuid.stdout)?.trim().into(),
                    };
                    if let Request::Power { operation } = request {
                        let verb = match operation {
                            Lifecycle::Suspend => "suspend",
                            Lifecycle::Shutdown => "poweroff",
                            Lifecycle::Reboot => "reboot",
                            _ => bail!("operation is not guest-controlled"),
                        };
                        let result = std::process::Command::new("sudo")
                            .args([
                                "-n",
                                "systemd-run",
                                "--quiet",
                                "--collect",
                                "--on-active=1s",
                                "--timer-property=AccuracySec=100ms",
                                "/usr/bin/systemctl",
                                "--no-block",
                                verb,
                            ])
                            .output()?;
                        ensure!(
                            result.status.success(),
                            "guest power request failed: {}",
                            String::from_utf8_lossy(&result.stderr)
                        );
                        return Ok(serde_json::Value::Null);
                    }
                    return Ok(serde_json::to_value(identity)?);
                }
                if let Request::Launch { command, logs } = request {
                    let launch = launch
                        .take()
                        .context("agent already launched an application")?;
                    backend =
                        Some(GuestBackend::launch(logs, || launch(&command))?);
                    return Ok(serde_json::Value::Null);
                }
                if terminate {
                    backend
                        .take()
                        .context("agent has not launched an application")?
                        .terminate()?;
                    return Ok(serde_json::Value::Null);
                }
                let backend = backend
                    .as_mut()
                    .context("agent has not launched an application")?;
                match request {
                    Request::Observe => {
                        Ok(serde_json::to_value(backend.observe()?)?)
                    }
                    Request::Logs => Ok(serde_json::to_value(backend.logs()?)?),
                    Request::Processes => {
                        Ok(serde_json::to_value(backend.processes()?)?)
                    }
                    Request::Signal { action } => {
                        action.validate()?;
                        let uuid = std::process::Command::new("sudo")
                            .args([
                                "-n",
                                "cat",
                                "/sys/class/dmi/id/product_uuid",
                            ])
                            .output()?;
                        ensure!(
                            uuid.status.success(),
                            "cannot read guest UUID"
                        );
                        let result = std::process::Command::new("sudo")
                            .arg("-n")
                            .arg(std::env::current_exe()?)
                            .args([
                                "signal",
                                "--pid",
                                &action.process.pid.to_string(),
                                "--start-time-ticks",
                                &action.process.start_time_ticks,
                                "--vm-uuid",
                                String::from_utf8(uuid.stdout)?.trim(),
                            ])
                            .output()?;
                        ensure!(
                            result.status.success(),
                            "guest signal failed: {}",
                            String::from_utf8_lossy(&result.stderr)
                        );
                        Ok(serde_json::Value::Null)
                    }
                    Request::Apply { action } => {
                        action.validate()?;
                        backend.apply(&action)?;
                        Ok(serde_json::Value::Null)
                    }
                    Request::Launch { .. }
                    | Request::Terminate
                    | Request::Identity
                    | Request::Power { .. } => {
                        unreachable!()
                    }
                }
            })();
            let response = match response {
                Ok(value) => Response::Ok { value },
                Err(error) => Response::Error {
                    message: format!("{error:#}"),
                },
            };
            let bytes = serde_json::to_vec(&response)?;
            ensure!(
                (bytes.len() as u64) < MAX_MESSAGE,
                "agent response exceeds size limit"
            );
            output.write_all(&bytes)?;
            output.write_all(b"\n")?;
            output.flush()?;
            if terminate {
                return Ok(());
            }
        }
    })();
    let cleanup = backend.map_or(Ok(()), |mut backend| backend.terminate());
    result.and(cleanup)
}

pub struct RemoteBackend {
    runtime: Runtime,
    child: Child,
    input: ChildStdin,
    output: BufReader<ChildStdout>,
    failed: bool,
}

impl RemoteBackend {
    pub fn launch(
        agent: &[String],
        command: Vec<String>,
        logs: Vec<LogOptions>,
    ) -> Result<Self> {
        let mut backend = Self::connect(agent)?;
        backend.launch_application(command, logs)?;
        Ok(backend)
    }

    pub fn connect(agent: &[String]) -> Result<Self> {
        let (program, args) =
            agent.split_first().context("empty agent command")?;
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()?;
        let mut child = {
            let _guard = runtime.enter();
            Command::new(program)
                .args(args)
                .stdin(Stdio::piped())
                .stdout(Stdio::piped())
                .stderr(Stdio::inherit())
                .kill_on_drop(true)
                .spawn()
                .context("start guest agent transport")?
        };
        let input = child.stdin.take().unwrap();
        let output = BufReader::new(child.stdout.take().unwrap());
        Ok(Self {
            runtime,
            child,
            input,
            output,
            failed: false,
        })
    }

    pub fn identity(&mut self) -> Result<Identity> {
        self.call(Request::Identity)
    }

    pub fn send_signal(&mut self, action: &SignalAction) -> Result<()> {
        self.call(Request::Signal {
            action: action.clone(),
        })
    }

    pub fn power(&mut self, operation: Lifecycle) -> Result<()> {
        self.call(Request::Power { operation })
    }

    pub fn launch_application(
        &mut self,
        command: Vec<String>,
        logs: Vec<LogOptions>,
    ) -> Result<()> {
        self.call(Request::Launch { command, logs })
    }

    fn call<T: DeserializeOwned>(&mut self, request: Request) -> Result<T> {
        ensure!(
            !self.failed,
            "guest agent transport is unusable after an earlier failure"
        );
        let result = self.runtime.block_on(async {
            tokio::time::timeout(RPC_TIMEOUT, async {
                let mut bytes = serde_json::to_vec(&request)?;
                bytes.push(b'\n');
                ensure!(bytes.len() as u64 <= MAX_MESSAGE, "agent request exceeds size limit");
                self.input.write_all(&bytes).await?;
                self.input.flush().await?;
                let mut line = Vec::new();
                (&mut self.output).take(MAX_MESSAGE + 1).read_until(b'\n', &mut line).await?;
                ensure!(!line.is_empty(), "guest agent disconnected");
                ensure!(line.len() as u64 <= MAX_MESSAGE && line.last() == Some(&b'\n'), "invalid or oversized agent response");
                match serde_json::from_slice::<Response>(&line)? {
                    Response::Ok { value } => Ok(serde_json::from_value(value)?),
                    Response::Error { message } => bail!("guest agent: {message}"),
                }
            }).await.context("guest agent request timed out; guest may be suspended or unresponsive")?
        });
        if result.is_err() {
            self.failed = true;
        }
        result
    }
}

impl Backend for RemoteBackend {
    fn processes(&mut self) -> Result<Vec<ProcessInfo>> {
        self.call(Request::Processes)
    }
    fn observe(&mut self) -> Result<(bool, Vec<Node>)> {
        self.call(Request::Observe)
    }
    fn logs(&mut self) -> Result<Vec<LogBatch>> {
        self.call(Request::Logs)
    }
    fn apply(&mut self, action: &Action) -> Result<()> {
        self.call(Request::Apply {
            action: action.clone(),
        })
    }
    fn terminate(&mut self) -> Result<()> {
        let result = if self.failed {
            Ok(())
        } else {
            self.call::<()>(Request::Terminate)
        };
        if !matches!(
            self.runtime.block_on(async {
                tokio::time::timeout(Duration::from_secs(3), self.child.wait())
                    .await
            }),
            Ok(Ok(_))
        ) {
            self.runtime.block_on(self.child.kill()).ok();
        }
        self.runtime.block_on(self.child.wait()).ok();
        result
    }
}

impl Drop for RemoteBackend {
    fn drop(&mut self) {
        self.runtime.block_on(self.child.kill()).ok();
        self.runtime.block_on(self.child.wait()).ok();
    }
}
