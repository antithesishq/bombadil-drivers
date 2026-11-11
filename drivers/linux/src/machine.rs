use std::{
    io::{BufRead, BufReader, Write},
    os::unix::net::UnixStream,
    path::PathBuf,
    process::{Child, Command, Stdio},
    time::Duration,
};

use anyhow::{Context, Result, bail, ensure};
use serde::Deserialize;
use serde_json::{Value, json};

use crate::{
    agent::RemoteBackend,
    backend::Backend,
    journal::{LogBatch, LogOptions},
    model::{
        Action, Lifecycle, LifecycleAction, MachineState, Node, PowerState,
    },
    processes::{ProcessInfo, SignalAction},
};

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VmConfig {
    pub uuid: String,
    pub qmp_socket: PathBuf,
    pub start_command: Vec<String>,
}

struct Qmp {
    stream: BufReader<UnixStream>,
    events: Vec<Value>,
}

impl Qmp {
    fn connect(path: &PathBuf) -> Result<Option<Self>> {
        let stream = match UnixStream::connect(path) {
            Ok(stream) => stream,
            Err(error)
                if matches!(
                    error.kind(),
                    std::io::ErrorKind::NotFound
                        | std::io::ErrorKind::ConnectionRefused
                ) =>
            {
                return Ok(None);
            }
            Err(error) => return Err(error.into()),
        };
        stream.set_read_timeout(Some(Duration::from_secs(3)))?;
        stream.set_write_timeout(Some(Duration::from_secs(3)))?;
        let mut qmp = Self {
            stream: BufReader::new(stream),
            events: Vec::new(),
        };
        ensure!(qmp.read()?.get("QMP").is_some(), "invalid QMP greeting");
        qmp.command("qmp_capabilities")?;
        Ok(Some(qmp))
    }

    fn read(&mut self) -> Result<Value> {
        let mut line = Vec::new();
        let count = std::io::Read::take(&mut self.stream, 1024 * 1024 + 1)
            .read_until(b'\n', &mut line)?;
        ensure!(
            count > 0 && count <= 1024 * 1024 && line.last() == Some(&b'\n'),
            "QMP disconnected or sent an invalid message"
        );
        Ok(serde_json::from_slice(&line)?)
    }

    fn command(&mut self, command: &str) -> Result<Value> {
        serde_json::to_writer(
            self.stream.get_mut(),
            &json!({"execute":command}),
        )?;
        self.stream.get_mut().write_all(b"\n")?;
        self.stream.get_mut().flush()?;
        loop {
            let message = self.read()?;
            if message.get("event").is_some() {
                ensure!(self.events.len() < 256, "QMP event buffer overflow");
                self.events.push(message);
            } else if let Some(error) = message.get("error") {
                bail!("QMP {command}: {error}");
            } else if let Some(result) = message.get("return") {
                return Ok(result.clone());
            }
        }
    }
}

pub struct VmBackend {
    config: VmConfig,
    agent_command: Vec<String>,
    application: Vec<String>,
    logs: Vec<LogOptions>,
    agent: Option<RemoteBackend>,
    qmp: Option<Qmp>,
    child: Option<Child>,
    state: MachineState,
    reconnect: bool,
    previous_boot: Option<String>,
}

impl VmBackend {
    pub fn new(
        config: VmConfig,
        agent_command: Vec<String>,
        application: Vec<String>,
        logs: Vec<LogOptions>,
    ) -> Result<Self> {
        ensure!(
            !config.uuid.is_empty() && !config.start_command.is_empty(),
            "VM UUID and start command are required"
        );
        ensure!(
            !agent_command.is_empty() && !application.is_empty(),
            "agent and application commands are required"
        );
        let mut backend = Self {
            config,
            agent_command,
            application,
            logs,
            agent: None,
            qmp: None,
            child: None,
            state: MachineState {
                power: PowerState::Off,
                agent_connected: false,
                boot_id: None,
                application_generation: 0,
                transition: None,
                events: Vec::new(),
                fault: None,
            },
            reconnect: true,
            previous_boot: None,
        };
        backend.refresh()?;
        Ok(backend)
    }

    fn refresh(&mut self) -> Result<()> {
        if let Some(child) = &mut self.child
            && let Some(status) = child.try_wait()?
        {
            self.child = None;
            if !status.success()
                && !matches!(
                    self.state.transition,
                    Some(Lifecycle::PowerOff | Lifecycle::Shutdown)
                )
            {
                self.state.fault = Some(format!("VM process exited: {status}"));
            }
        }
        if self.qmp.is_none() {
            self.qmp = Qmp::connect(&self.config.qmp_socket)?;
            if let Some(qmp) = &mut self.qmp {
                let uuid = qmp.command("query-uuid")?;
                ensure!(
                    uuid["UUID"].as_str().is_some_and(
                        |uuid| uuid.eq_ignore_ascii_case(&self.config.uuid)
                    ),
                    "QMP UUID does not match the configured VM"
                );
            }
        }
        let previous_power = self.state.power.clone();
        if let Some(qmp) = &mut self.qmp {
            match qmp.command("query-status") {
                Ok(status) => {
                    self.state.power = match status["status"].as_str() {
                        Some("running") => PowerState::Running,
                        Some("suspended") => PowerState::Suspended,
                        Some("shutdown") => PowerState::Off,
                        Some(
                            "paused" | "prelaunch" | "inmigrate"
                            | "postmigrate",
                        ) => PowerState::Paused,
                        other => {
                            bail!("unsupported QMP power state: {other:?}")
                        }
                    };
                    self.state.events.append(&mut qmp.events);
                    ensure!(
                        self.state.events.len() <= 256,
                        "machine event buffer overflow"
                    );
                }
                Err(error) => {
                    self.state.events.append(&mut qmp.events);
                    self.qmp = None;
                    if Qmp::connect(&self.config.qmp_socket)?.is_some() {
                        return Err(error);
                    }
                    self.state.power = PowerState::Off;
                }
            }
        } else {
            self.state.power = PowerState::Off;
        }
        if self
            .state
            .events
            .iter()
            .any(|event| event["event"] == "WATCHDOG")
        {
            self.state.fault = Some("VM watchdog fired".into());
        }
        if self.state.power == PowerState::Off {
            self.agent = None;
            if previous_power != PowerState::Off
                && !matches!(
                    self.state.transition,
                    Some(
                        Lifecycle::Shutdown
                            | Lifecycle::PowerOff
                            | Lifecycle::Reboot
                            | Lifecycle::Reset
                    )
                )
            {
                self.state.fault = Some("VM powered off unexpectedly".into());
            }
        }
        self.state.agent_connected = false;
        if self.state.power == PowerState::Running
            && self.state.fault.is_none()
            && !matches!(
                self.state.transition,
                Some(Lifecycle::Suspend | Lifecycle::Shutdown)
            )
        {
            if let Some(agent) = &mut self.agent {
                match agent.identity() {
                    Ok(identity) => {
                        ensure!(
                            identity
                                .vm_uuid
                                .eq_ignore_ascii_case(&self.config.uuid),
                            "SSH guest does not match the configured VM"
                        );
                        if self
                            .state
                            .boot_id
                            .as_ref()
                            .is_some_and(|boot| boot != &identity.boot_id)
                        {
                            self.state.fault =
                                Some("guest rebooted unexpectedly".into());
                        } else {
                            self.state.boot_id = Some(identity.boot_id);
                            self.state.agent_connected = true;
                        }
                    }
                    Err(error) => {
                        self.agent = None;
                        if !self.reconnect {
                            self.state.fault = Some(format!(
                                "guest agent disconnected: {error:#}"
                            ));
                        }
                    }
                }
            }
            if self.agent.is_none()
                && self.reconnect
                && let Ok(mut agent) =
                    RemoteBackend::connect(&self.agent_command)
                && let Ok(identity) = agent.identity()
            {
                ensure!(
                    identity.vm_uuid.eq_ignore_ascii_case(&self.config.uuid),
                    "SSH guest does not match the configured VM"
                );
                let changed = self
                    .previous_boot
                    .as_ref()
                    .is_none_or(|boot| boot != &identity.boot_id);
                if changed
                    && agent
                        .launch_application(
                            self.application.clone(),
                            self.logs.clone(),
                        )
                        .is_ok()
                {
                    self.state.boot_id = Some(identity.boot_id);
                    self.state.application_generation += 1;
                    self.state.agent_connected = true;
                    self.agent = Some(agent);
                    self.reconnect = false;
                }
            }
        }
        let complete = match self.state.transition {
            Some(Lifecycle::Suspend) => {
                self.state.power == PowerState::Suspended
            }
            Some(Lifecycle::Shutdown | Lifecycle::PowerOff) => {
                self.state.power == PowerState::Off
            }
            Some(
                Lifecycle::Wake
                | Lifecycle::PowerOn
                | Lifecycle::Reboot
                | Lifecycle::Reset,
            ) => self.state.agent_connected,
            Some(Lifecycle::Wait) => true,
            None => false,
        };
        if complete {
            self.state.transition = None;
        }
        Ok(())
    }

    fn transition(&mut self, operation: Lifecycle) -> Result<()> {
        match operation {
            Lifecycle::PowerOn => {
                ensure!(
                    self.state.power == PowerState::Off,
                    "powerOn requires an off VM"
                );
                ensure!(self.qmp.is_none(), "QEMU must exit before powerOn");
                let (program, args) =
                    self.config.start_command.split_first().unwrap();
                self.child = Some(
                    Command::new(program)
                        .args(args)
                        .stdin(Stdio::null())
                        .stdout(Stdio::null())
                        .stderr(Stdio::inherit())
                        .spawn()
                        .context("start VM")?,
                );
                self.reconnect = true;
                self.previous_boot = self.state.boot_id.clone();
            }
            Lifecycle::PowerOff | Lifecycle::Reset | Lifecycle::Wake => {
                ensure!(
                    self.state.power != PowerState::Off,
                    "operation requires a powered VM"
                );
                if operation == Lifecycle::Wake {
                    ensure!(
                        self.state.power == PowerState::Suspended,
                        "wake requires a suspended VM"
                    );
                }
                if operation == Lifecycle::Reset {
                    ensure!(
                        self.state.power == PowerState::Running,
                        "reset requires a running VM"
                    );
                }
                let command = match operation {
                    Lifecycle::PowerOff => "quit",
                    Lifecycle::Reset => "system_reset",
                    _ => "system_wakeup",
                };
                self.qmp
                    .as_mut()
                    .context("QMP is unavailable")?
                    .command(command)?;
                if matches!(operation, Lifecycle::PowerOff | Lifecycle::Reset) {
                    self.agent = None;
                }
                if operation == Lifecycle::Reset {
                    self.reconnect = true;
                    self.previous_boot = self.state.boot_id.clone();
                }
            }
            Lifecycle::Suspend | Lifecycle::Shutdown | Lifecycle::Reboot => {
                ensure!(
                    self.state.agent_connected,
                    "operation requires a connected running guest"
                );
                self.agent
                    .as_mut()
                    .context("guest agent is unavailable")?
                    .power(operation)?;
                if operation == Lifecycle::Shutdown {
                    self.agent = None;
                }
                if operation == Lifecycle::Reboot {
                    self.agent = None;
                    self.reconnect = true;
                    self.previous_boot = self.state.boot_id.clone();
                }
            }
            Lifecycle::Wait => {}
        }
        Ok(())
    }
}

impl Backend for VmBackend {
    fn processes(&mut self) -> Result<Vec<ProcessInfo>> {
        self.agent
            .as_mut()
            .context("guest agent is unavailable")?
            .processes()
    }
    fn signal(&mut self, action: &SignalAction) -> Result<()> {
        action.validate()?;
        ensure!(
            self.state.agent_connected && self.state.fault.is_none(),
            "signals require a connected running guest"
        );
        if let Err(error) = self
            .agent
            .as_mut()
            .context("guest agent is unavailable")?
            .send_signal(action)
        {
            self.state.fault = Some(format!("{error:#}"));
            self.state.agent_connected = false;
            self.agent = None;
        }
        Ok(())
    }
    fn machine(&mut self) -> Result<Option<MachineState>> {
        if let Err(error) = self.refresh() {
            self.state.fault = Some(format!("{error:#}"));
            self.state.agent_connected = false;
        }
        Ok(Some(self.state.clone()))
    }
    fn observe(&mut self) -> Result<(bool, Vec<Node>)> {
        self.agent
            .as_mut()
            .context("guest agent is unavailable")?
            .observe()
    }
    fn logs(&mut self) -> Result<Vec<LogBatch>> {
        self.agent
            .as_mut()
            .context("guest agent is unavailable")?
            .logs()
    }
    fn apply(&mut self, action: &Action) -> Result<()> {
        ensure!(
            self.state.agent_connected && self.state.fault.is_none(),
            "desktop input requires a connected running guest"
        );
        self.agent
            .as_mut()
            .context("guest agent is unavailable")?
            .apply(action)
    }
    fn lifecycle(&mut self, action: &LifecycleAction) -> Result<()> {
        action.validate()?;
        ensure!(
            self.state.fault.is_none(),
            "machine already has a lifecycle fault"
        );
        self.state.transition = Some(action.lifecycle);
        if let Err(error) = self.transition(action.lifecycle) {
            self.state.fault = Some(format!("{error:#}"));
        }
        Ok(())
    }
    fn terminate(&mut self) -> Result<()> {
        if self.state.agent_connected
            && let Some(agent) = &mut self.agent
        {
            agent.terminate()?;
        }
        self.agent = None;
        Ok(())
    }
}
