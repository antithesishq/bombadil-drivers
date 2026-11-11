use std::{
    io::{BufRead, Read, Write},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use anyhow::{Result, ensure};
use serde::Deserialize;
use serde_json::{Value, json};

use crate::{
    backend::Backend,
    model::{DesktopState, Operation},
};

const MAX_MESSAGE: u64 = 32 * 1024 * 1024;

/// An external session owns the backend, not the fuzzer or specification runtime.
pub struct Session<B: Backend> {
    backend: B,
    pending: Option<(Operation, Instant, Option<String>)>,
    outcome_timeout: Duration,
}

impl<B: Backend> Session<B> {
    pub fn new(backend: B, outcome_timeout: Duration) -> Result<Self> {
        ensure!(
            !outcome_timeout.is_zero(),
            "outcome timeout must be positive"
        );
        Ok(Self {
            backend,
            pending: None,
            outcome_timeout,
        })
    }

    pub fn apply(
        &mut self,
        action: Operation,
        state: &DesktopState,
    ) -> Result<()> {
        action.validate()?;
        ensure!(self.pending.is_none(), "an action is already pending");
        let started = Instant::now();
        match &action {
            Operation::Desktop(action) => self.backend.apply(action)?,
            Operation::Lifecycle(action) => self.backend.lifecycle(action)?,
            Operation::Signal(action) => self.backend.signal(action)?,
        }
        let boot = state.machine.as_ref().and_then(|m| m.boot_id.clone());
        self.pending = Some((action, started, boot));
        Ok(())
    }

    pub fn observe(&mut self) -> Result<DesktopState> {
        loop {
            if let Some(state) = self.poll()? {
                return Ok(state);
            }
            std::thread::sleep(Duration::from_millis(50));
        }
    }

    fn poll(&mut self) -> Result<Option<DesktopState>> {
        let mut machine = self.backend.machine()?;
        let available = machine.as_ref().is_none_or(|m| m.agent_connected);
        let observations = if available {
            (|| -> Result<_> {
                Ok((self.backend.observe()?, self.backend.processes()?))
            })()
        } else {
            Ok(((false, Vec::new()), Vec::new()))
        };
        let (app_alive, nodes, processes) = match observations {
            Ok(((alive, nodes), processes)) if available => {
                (Some(alive), nodes, Some(processes))
            }
            Ok(_) => (None, Vec::new(), None),
            Err(error) => {
                let Some(machine) = &mut machine else {
                    return Err(error);
                };
                machine.fault =
                    Some(format!("guest observation failed: {error:#}"));
                machine.agent_connected = false;
                (None, Vec::new(), None)
            }
        };
        let mut outcome = if let Some((action, started, boot)) = &self.pending {
            let (timeout, satisfied) = match action {
                Operation::Desktop(action) => (
                    self.outcome_timeout,
                    app_alive == Some(true)
                        && action.expect.satisfied(&nodes)?,
                ),
                Operation::Lifecycle(action) => (
                    Duration::from_secs(action.timeout_seconds),
                    machine.as_ref().is_some_and(|m| {
                        action.expect.satisfied(m, boot.as_deref())
                    }) && started.elapsed()
                        >= Duration::from_secs(action.settle_seconds),
                ),
                Operation::Signal(action) => (
                    Duration::from_secs(action.timeout_seconds),
                    processes.as_ref().is_some_and(|processes| {
                        !processes.iter().any(|p| {
                            p.pid == action.process.pid
                                && p.start_time_ticks
                                    == action.process.start_time_ticks
                                && p.state != "Z"
                        })
                    }),
                ),
            };
            if machine.as_ref().is_some_and(|m| m.fault.is_some())
                || started.elapsed() >= timeout
                || matches!(action, Operation::Desktop(_))
                    && app_alive != Some(true)
            {
                Some(false)
            } else if satisfied {
                Some(true)
            } else {
                return Ok(None);
            }
        } else {
            None
        };
        let journals = if machine.as_ref().is_none_or(|m| m.agent_connected) {
            match self.backend.logs() {
                Ok(logs) => Some(logs),
                Err(error) => {
                    let Some(machine) = &mut machine else {
                        return Err(error);
                    };
                    machine.fault =
                        Some(format!("guest journal failed: {error:#}"));
                    machine.agent_connected = false;
                    if self.pending.is_some() {
                        outcome = Some(false);
                    }
                    None
                }
            }
        } else {
            None
        };
        Ok(Some(DesktopState {
            timestamp: SystemTime::now(),
            app_alive,
            nodes,
            last_action: self.pending.take().map(|(action, _, _)| action),
            outcome,
            journals,
            machine,
            processes,
        }))
    }
}

impl<B: Backend> Drop for Session<B> {
    fn drop(&mut self) {
        let _ = self.backend.terminate();
    }
}

#[derive(Deserialize)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
enum Request {
    #[serde(rename = "apply_action")]
    Apply {
        action: Value,
    },
    Terminate {},
}

fn send(output: &mut impl Write, message: &Value) -> Result<()> {
    serde_json::to_writer(&mut *output, message)?;
    output.write_all(b"\n")?;
    output.flush()?;
    Ok(())
}

/// Serve Bombadil's version-0 JSONL protocol. Never retry uncertain input.
pub fn serve<B: Backend>(
    mut input: impl BufRead,
    mut output: impl Write,
    launch: impl FnOnce() -> Result<B>,
    outcome_timeout: Duration,
) -> Result<()> {
    send(
        &mut output,
        &json!({"type":"hello", "protocol":0, "name":"bombadil-linux", "stall_timeout_ms":330000}),
    )?;
    let result = (|| -> Result<()> {
        let mut session = Session::new(launch()?, outcome_timeout)?;
        let mut state = session.observe()?;
        let mut timestamp = 0_u128;
        let mut not_applied: Option<String> = None;
        loop {
            timestamp = timestamp
                .max(state.timestamp.duration_since(UNIX_EPOCH)?.as_micros());
            let mut message = json!({"type":"state", "state":state, "timestamp_us":timestamp as u64, "settled":false});
            if let Some(reason) = not_applied.take() {
                message["not_applied"] = reason.into();
            }
            send(&mut output, &message)?;
            let mut line = Vec::new();
            let count = Read::take(&mut input, MAX_MESSAGE + 1)
                .read_until(b'\n', &mut line)?;
            if count == 0 {
                break;
            }
            ensure!(
                count as u64 <= MAX_MESSAGE && line.last() == Some(&b'\n'),
                "invalid or oversized driver request"
            );
            match serde_json::from_slice::<Request>(&line)? {
                Request::Terminate {} => break,
                Request::Apply { action } => {
                    let action = serde_json::from_value::<Operation>(action)
                        .and_then(|action| {
                            action
                                .validate()
                                .map_err(serde::de::Error::custom)?;
                            Ok(action)
                        });
                    match action {
                        Ok(action) => session.apply(action, &state)?,
                        Err(error) => not_applied = Some(error.to_string()),
                    }
                    state = session.observe()?;
                }
            }
        }
        Ok(())
    })();
    if let Err(error) = &result {
        send(
            &mut output,
            &json!({"type":"error", "message":format!("{error:#}")}),
        )?;
    }
    result
}
