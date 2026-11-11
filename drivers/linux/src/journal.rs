use std::{collections::BTreeMap, process::Stdio, time::Duration};

use anyhow::{Context, Result, ensure};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use tokio::{io::AsyncReadExt, process::Command, runtime::Runtime};

const MAX_ENTRIES: usize = 1000;
const MAX_BYTES: u64 = 4 * 1024 * 1024;

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LogOptions {
    scope: Scope,
    level: Level,
    #[serde(default)]
    exact: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Scope {
    system_unit: Option<String>,
    user_unit: Option<String>,
    identifier: Option<String>,
    kernel: Option<bool>,
    module: Option<Module>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Module {
    field: String,
    value: String,
}

#[derive(Clone, Copy, Debug, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
enum Level {
    Emerg,
    Alert,
    Crit,
    Err,
    Warning,
    Notice,
    Info,
    Debug,
}

impl LogOptions {
    fn matches(&self) -> Result<Vec<String>> {
        let mut fields = Vec::new();
        for (field, value) in [
            ("_SYSTEMD_UNIT", &self.scope.system_unit),
            ("_SYSTEMD_USER_UNIT", &self.scope.user_unit),
            ("SYSLOG_IDENTIFIER", &self.scope.identifier),
        ] {
            if let Some(value) = value {
                ensure!(
                    !value.is_empty() && !value.contains('\0'),
                    "empty or invalid journal scope"
                );
                fields.push(format!("{field}={value}"));
            }
        }
        if let Some(kernel) = self.scope.kernel {
            ensure!(kernel, "kernel journal scope must be true");
            fields.push("_TRANSPORT=kernel".into());
        }
        ensure!(
            !fields.is_empty(),
            "log extractor requires a journal source scope"
        );
        if let Some(module) = &self.scope.module {
            ensure!(
                !module.field.is_empty()
                    && module.field.bytes().all(|b| b.is_ascii_uppercase()
                        || b.is_ascii_digit()
                        || b == b'_')
                    && !module.field.as_bytes()[0].is_ascii_digit(),
                "invalid journal module field"
            );
            ensure!(
                !module.value.is_empty() && !module.value.contains('\0'),
                "empty or invalid journal module"
            );
            ensure!(
                ![
                    "_SYSTEMD_UNIT",
                    "_SYSTEMD_USER_UNIT",
                    "SYSLOG_IDENTIFIER",
                    "_TRANSPORT",
                    "PRIORITY"
                ]
                .contains(&module.field.as_str()),
                "module field must not replace a journal source or severity field"
            );
            fields.push(format!("{}={}", module.field, module.value));
        }
        Ok(fields)
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LogEntry {
    pub cursor: String,
    pub timestamp_micros: String,
    pub priority: u8,
    pub message: Value,
    pub fields: BTreeMap<String, Value>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct LogBatch {
    pub cursor: String,
    pub entries: Vec<LogEntry>,
}

pub struct Journal {
    runtime: Runtime,
    queries: Vec<(LogOptions, String)>,
}

impl Journal {
    pub fn start(options: Vec<LogOptions>) -> Result<Option<Self>> {
        if options.is_empty() {
            return Ok(None);
        }
        for option in &options {
            option.matches()?;
        }
        let mut journal = Self {
            runtime: tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()?,
            queries: Vec::new(),
        };
        for option in options {
            let rows = journal.read(&option, &["--lines=1".into()])?;
            let cursor = rows
                .last()
                .and_then(|row| row.get("__CURSOR"))
                .and_then(Value::as_str)
                .context(
                    "journal has no readable cursor; check journal access",
                )?
                .to_string();
            journal.queries.push((option, cursor));
        }
        Ok(Some(journal))
    }

    fn read(
        &self,
        option: &LogOptions,
        args: &[String],
    ) -> Result<Vec<BTreeMap<String, Value>>> {
        self.runtime.block_on(async {
            tokio::time::timeout(Duration::from_secs(3), async {
                let mut command = Command::new("journalctl");
                command.args([
                    "--boot",
                    "--no-pager",
                    "--output=json",
                    "--all",
                ]);
                if option.scope.user_unit.is_some() {
                    command.arg("--user");
                }
                let mut child = command
                    .args(args)
                    .env("LC_ALL", "C")
                    .stdin(Stdio::null())
                    .stdout(Stdio::piped())
                    .stderr(Stdio::piped())
                    .kill_on_drop(true)
                    .spawn()
                    .context("start journalctl")?;
                let mut stdout = Vec::new();
                let mut stderr = Vec::new();
                let mut stdout_pipe =
                    child.stdout.take().unwrap().take(MAX_BYTES + 1);
                let mut stderr_pipe = child.stderr.take().unwrap().take(65537);
                tokio::try_join!(
                    async {
                        stdout_pipe.read_to_end(&mut stdout).await?;
                        ensure!(
                            stdout.len() as u64 <= MAX_BYTES,
                            "journal collection overflow"
                        );
                        Ok::<_, anyhow::Error>(())
                    },
                    async {
                        stderr_pipe.read_to_end(&mut stderr).await?;
                        ensure!(
                            stderr.len() <= 65536,
                            "journal diagnostic overflow"
                        );
                        Ok::<_, anyhow::Error>(())
                    },
                )?;
                let status = child.wait().await?;
                ensure!(
                    status.success() && stderr.is_empty(),
                    "journalctl failed or reported incomplete access: {}",
                    String::from_utf8_lossy(&stderr)
                );
                stdout
                    .split(|b| *b == b'\n')
                    .filter(|line| !line.is_empty())
                    .map(|line| {
                        serde_json::from_slice(line)
                            .context("invalid journal JSON")
                    })
                    .collect::<Result<Vec<_>>>()
            })
            .await
            .context("journal collection timed out")?
        })
    }

    pub fn poll(&mut self) -> Result<Vec<LogBatch>> {
        let mut batches = Vec::new();
        for (option, cursor) in &self.queries {
            let anchor = self.read(
                option,
                &[format!("--cursor={cursor}"), "--lines=+1".into()],
            )?;
            ensure!(
                anchor
                    .first()
                    .and_then(|row| row.get("__CURSOR"))
                    .and_then(Value::as_str)
                    == Some(cursor),
                "journal cursor was lost; collection may be incomplete"
            );
            let priority = option.level as u8;
            let mut args = vec![
                format!("--after-cursor={cursor}"),
                format!("--lines=+{}", MAX_ENTRIES + 1),
                format!(
                    "--priority={}..{priority}",
                    if option.exact { priority } else { 0 }
                ),
                "--".into(),
            ];
            args.extend(option.matches()?);
            let rows = self.read(option, &args)?;
            ensure!(
                rows.len() <= MAX_ENTRIES,
                "journal entry limit exceeded; collection may be incomplete"
            );
            let entries = rows
                .into_iter()
                .map(|fields| {
                    let string = |key| {
                        fields
                            .get(key)
                            .and_then(Value::as_str)
                            .map(str::to_string)
                            .with_context(|| {
                                format!("journal entry missing {key}")
                            })
                    };
                    let cursor = string("__CURSOR")?;
                    let timestamp_micros = string("__REALTIME_TIMESTAMP")?;
                    let priority: u8 = string("PRIORITY")?.parse()?;
                    ensure!(priority <= 7, "invalid journal priority");
                    let message =
                        fields.get("MESSAGE").cloned().unwrap_or(Value::Null);
                    Ok(LogEntry {
                        cursor,
                        timestamp_micros,
                        priority,
                        message,
                        fields,
                    })
                })
                .collect::<Result<Vec<_>>>()?;
            batches.push(LogBatch {
                cursor: entries.last().map_or_else(
                    || cursor.clone(),
                    |entry| entry.cursor.clone(),
                ),
                entries,
            });
        }
        for ((_, cursor), batch) in self.queries.iter_mut().zip(&batches) {
            cursor.clone_from(&batch.cursor);
        }
        Ok(batches)
    }
}
