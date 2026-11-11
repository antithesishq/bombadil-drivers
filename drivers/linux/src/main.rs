use std::{path::PathBuf, time::Duration};

use anyhow::{Result, ensure};
use bombadil_linux::{
    accessibility::Accessibility,
    agent::{self, RemoteBackend},
    backend::{Backend, DesktopBackend},
    journal::LogOptions,
    machine::{VmBackend, VmConfig},
    session,
};
use clap::{Parser, Subcommand};

#[derive(Parser)]
#[command(
    about = "Black-box Linux desktop driver (AT-SPI / Hyprland / ydotool)"
)]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Restricted helper installed in a disposable guest's sudo policy.
    Signal {
        #[arg(long)]
        pid: u32,
        #[arg(long)]
        start_time_ticks: String,
        #[arg(long)]
        vm_uuid: String,
    },
    /// Guest transport endpoint; keep this inside a disposable desktop VM.
    Agent {
        #[arg(long)]
        enable_input: bool,
    },
    Inspect {
        #[arg(long)]
        pid: u32,
    },
    /// Host-side version-0 JSONL driver for `bombadil external`.
    Run {
        #[arg(long)]
        enable_input: bool,
        /// JSON argv array for an SSH/guest-agent transport (no shell expansion).
        #[arg(long)]
        agent_command: PathBuf,
        #[arg(long, requires = "enable_lifecycle")]
        vm_config: Option<PathBuf>,
        #[arg(long, requires = "vm_config")]
        enable_lifecycle: bool,
        /// JSON array of journal queries, in the same order as extractLogs(index).
        #[arg(long)]
        log_options: Option<PathBuf>,
        #[arg(long, default_value_t = 3, value_parser = clap::value_parser!(u64).range(1..=10))]
        outcome_seconds: u64,
        #[arg(required = true, last = true)]
        command: Vec<String>,
    },
}

fn main() -> Result<()> {
    match Cli::parse().command {
        Command::Signal {
            pid,
            start_time_ticks,
            vm_uuid,
        } => {
            ensure!(
                std::path::Path::new("/etc/sudoers.d/bombadil-vm").is_file(),
                "signal helper requires a disposable Bombadil guest"
            );
            let actual =
                std::fs::read_to_string("/sys/class/dmi/id/product_uuid")?;
            ensure!(
                actual.trim().eq_ignore_ascii_case(&vm_uuid),
                "guest UUID mismatch"
            );
            bombadil_linux::processes::kill(
                &bombadil_linux::processes::ProcessTarget {
                    pid,
                    start_time_ticks,
                },
            )?;
        }
        Command::Agent { enable_input } => {
            ensure!(
                enable_input,
                "input is disabled; pass --enable-input only in a disposable VM"
            );
            agent::serve(DesktopBackend::launch)?;
        }
        Command::Inspect { pid } => {
            let runtime = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()?;
            let nodes = runtime.block_on(async {
                tokio::time::timeout(Duration::from_secs(10), async {
                    Accessibility::connect().await?.nodes(pid).await
                })
                .await?
            })?;
            println!("{}", serde_json::to_string_pretty(&nodes)?);
        }
        Command::Run {
            enable_input,
            agent_command,
            vm_config,
            enable_lifecycle: _,
            log_options,
            outcome_seconds,
            command,
        } => {
            ensure!(
                enable_input,
                "input is disabled; pass --enable-input only for a disposable VM"
            );
            session::serve(
                std::io::stdin().lock(),
                std::io::stdout().lock(),
                || -> Result<Box<dyn Backend>> {
                    let agent: Vec<String> = serde_json::from_reader(
                        std::fs::File::open(agent_command)?,
                    )?;
                    let logs: Vec<LogOptions> = log_options
                        .map(|path| -> Result<_> {
                            Ok(serde_json::from_reader(std::fs::File::open(
                                path,
                            )?)?)
                        })
                        .transpose()?
                        .unwrap_or_default();
                    if let Some(path) = vm_config {
                        let config: VmConfig = serde_json::from_reader(
                            std::fs::File::open(path)?,
                        )?;
                        Ok(Box::new(VmBackend::new(
                            config, agent, command, logs,
                        )?))
                    } else {
                        Ok(Box::new(RemoteBackend::launch(
                            &agent, command, logs,
                        )?))
                    }
                },
                Duration::from_secs(outcome_seconds),
            )?;
        }
    }
    Ok(())
}
