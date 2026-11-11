use std::{
    fs::{self, File},
    io::{self, Read},
    os::fd::{AsRawFd, FromRawFd, OwnedFd},
    path::Path,
};

use anyhow::{Context, Result, ensure};
use serde::{Deserialize, Serialize};

const MAX_PROCESSES: usize = 32_768;
const MAX_COMMAND_BYTES: usize = 64 * 1024;
const MAX_TOTAL_COMMAND_BYTES: usize = 8 * 1024 * 1024;

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProcessTarget {
    pub pid: u32,
    pub start_time_ticks: String,
}

impl ProcessTarget {
    pub fn validate(&self) -> Result<()> {
        ensure!(self.pid > 0 && self.pid <= i32::MAX as u32, "invalid PID");
        let ticks: u64 = self
            .start_time_ticks
            .parse()
            .context("invalid startTimeTicks")?;
        ensure!(
            ticks.to_string() == self.start_time_ticks,
            "startTimeTicks must be canonical decimal"
        );
        Ok(())
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProcessInfo {
    pub pid: u32,
    pub ppid: u32,
    pub uid: u32,
    pub name: String,
    pub command: Option<String>,
    pub start_time_ticks: String,
    pub state: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum Signal {
    #[serde(rename = "SIGKILL")]
    Sigkill,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SignalAction {
    pub process: ProcessTarget,
    pub signal: Signal,
    pub timeout_seconds: u64,
}

impl SignalAction {
    pub fn validate(&self) -> Result<()> {
        self.process.validate()?;
        ensure!(
            (1..=60).contains(&self.timeout_seconds),
            "timeoutSeconds must be between 1 and 60"
        );
        Ok(())
    }
}

struct Stat {
    pid: u32,
    ppid: u32,
    name: String,
    start_time_ticks: u64,
    state: String,
}

fn parse_stat(stat: &str) -> Result<Stat> {
    let open = stat.find('(').context("stat has no command name")?;
    let close = stat.rfind(')').context("stat has no command end")?;
    ensure!(close > open, "invalid stat command name");
    let pid = stat[..open].trim().parse().context("invalid stat PID")?;
    let fields: Vec<_> = stat[close + 1..].split_whitespace().collect();
    ensure!(fields.len() >= 20, "truncated stat");
    ensure!(fields[0].len() == 1, "invalid stat state");
    Ok(Stat {
        pid,
        ppid: fields[1].parse().context("invalid stat PPID")?,
        name: stat[open + 1..close].to_owned(),
        start_time_ticks: fields[19]
            .parse()
            .context("invalid stat starttime")?,
        state: fields[0].to_owned(),
    })
}

fn read_stat(path: &Path) -> Result<Stat> {
    let bytes =
        fs::read(path).with_context(|| format!("read {}", path.display()))?;
    parse_stat(&String::from_utf8_lossy(&bytes))
}

fn read_command(path: &Path) -> Result<Option<String>> {
    let file = match File::open(path) {
        Ok(file) => file,
        Err(error) if error.kind() == io::ErrorKind::PermissionDenied => {
            return Ok(None);
        }
        Err(error) => return Err(error.into()),
    };
    let mut bytes = Vec::new();
    if let Err(error) = file
        .take(MAX_COMMAND_BYTES as u64 + 1)
        .read_to_end(&mut bytes)
    {
        if error.kind() == io::ErrorKind::PermissionDenied {
            return Ok(None);
        }
        return Err(error.into());
    }
    ensure!(
        bytes.len() <= MAX_COMMAND_BYTES,
        "process command too large"
    );
    if bytes.last() == Some(&0) {
        bytes.pop();
    }
    for byte in &mut bytes {
        if *byte == 0 {
            *byte = b' ';
        }
    }
    let command = String::from_utf8_lossy(&bytes).into_owned();
    ensure!(
        command.len() <= MAX_COMMAND_BYTES,
        "process command too large"
    );
    Ok(Some(command))
}

fn read_process(pid: u32, path: &Path) -> Result<ProcessInfo> {
    let stat = read_stat(&path.join("stat"))?;
    ensure!(stat.pid == pid, "stat PID does not match directory");
    let status = fs::read_to_string(path.join("status"))?;
    let uid = status
        .lines()
        .find_map(|line| line.strip_prefix("Uid:"))
        .and_then(|line| line.split_whitespace().next())
        .context("status missing UID")?
        .parse()
        .context("invalid status UID")?;
    Ok(ProcessInfo {
        pid,
        ppid: stat.ppid,
        uid,
        name: stat.name,
        command: read_command(&path.join("cmdline"))?,
        start_time_ticks: stat.start_time_ticks.to_string(),
        state: stat.state,
    })
}

fn is_race(error: &anyhow::Error) -> bool {
    error.downcast_ref::<io::Error>().is_some_and(|error| {
        error.kind() == io::ErrorKind::NotFound
            || error.raw_os_error() == Some(libc::ESRCH)
    })
}

pub fn snapshot() -> Result<Vec<ProcessInfo>> {
    let mut processes = Vec::new();
    let mut total_command_bytes = 0;
    for entry in fs::read_dir("/proc")? {
        let entry = match entry {
            Ok(entry) => entry,
            Err(error)
                if error.kind() == io::ErrorKind::NotFound
                    || error.raw_os_error() == Some(libc::ESRCH) =>
            {
                continue;
            }
            Err(error) => return Err(error.into()),
        };
        let name = entry.file_name();
        let Some(name) = name.to_str() else { continue };
        if name.is_empty() || !name.bytes().all(|byte| byte.is_ascii_digit()) {
            continue;
        }
        let pid = name.parse().context("invalid /proc PID")?;
        let process = match read_process(pid, &entry.path()) {
            Ok(process) => process,
            Err(error) if is_race(&error) => continue,
            Err(error) => {
                return Err(error).with_context(|| format!("process {pid}"));
            }
        };
        total_command_bytes += process.command.as_ref().map_or(0, String::len);
        ensure!(
            total_command_bytes <= MAX_TOTAL_COMMAND_BYTES,
            "total process commands too large"
        );
        ensure!(processes.len() < MAX_PROCESSES, "too many processes");
        processes.push(process);
    }
    processes.sort_unstable_by_key(|process| process.pid);
    Ok(processes)
}

pub fn kill(target: &ProcessTarget) -> Result<()> {
    target.validate()?;
    let fd = unsafe {
        libc::syscall(libc::SYS_pidfd_open, target.pid as libc::pid_t, 0)
    };
    if fd < 0 {
        return Err(io::Error::last_os_error()).context("pidfd_open");
    }
    let fd = unsafe { OwnedFd::from_raw_fd(fd as i32) };
    let stat = read_stat(Path::new(&format!("/proc/{}/stat", target.pid)))?;
    ensure!(stat.pid == target.pid, "process PID mismatch");
    ensure!(
        stat.start_time_ticks.to_string() == target.start_time_ticks,
        "process startTimeTicks mismatch"
    );
    let result = unsafe {
        libc::syscall(
            libc::SYS_pidfd_send_signal,
            fd.as_raw_fd(),
            libc::SIGKILL,
            std::ptr::null::<libc::siginfo_t>(),
            0,
        )
    };
    if result < 0 {
        return Err(io::Error::last_os_error()).context("pidfd_send_signal");
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use std::{
        os::unix::process::ExitStatusExt,
        process::{Child, Command},
    };

    use super::*;

    struct Sleep(Child);

    impl Sleep {
        fn spawn() -> Self {
            Self(Command::new("sleep").arg("60").spawn().unwrap())
        }

        fn target(&self) -> ProcessTarget {
            let pid = self.0.id();
            let stat =
                read_stat(Path::new(&format!("/proc/{pid}/stat"))).unwrap();
            ProcessTarget {
                pid,
                start_time_ticks: stat.start_time_ticks.to_string(),
            }
        }
    }

    impl Drop for Sleep {
        fn drop(&mut self) {
            let _ = self.0.kill();
            let _ = self.0.wait();
        }
    }

    #[test]
    fn stat_names_and_fields() {
        for name in ["simple", "space in name", "a (nested) name)", ") ("] {
            let mut fields = vec!["0"; 20];
            fields[0] = "S";
            fields[1] = "42";
            fields[19] = "987654";
            let input = format!("123 ({name}) {}", fields.join(" "));
            let stat = parse_stat(&input).unwrap();
            assert_eq!(stat.pid, 123);
            assert_eq!(stat.ppid, 42);
            assert_eq!(stat.name, name);
            assert_eq!(stat.state, "S");
            assert_eq!(stat.start_time_ticks, 987654);
        }
        assert!(parse_stat("123 (bad) S 42").is_err());
    }

    #[test]
    fn command_encoding_and_bounds() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("cmdline");
        fs::write(&path, b"").unwrap();
        assert_eq!(read_command(&path).unwrap(), Some(String::new()));
        fs::write(&path, b"sleep\0argument with spaces\0").unwrap();
        assert_eq!(
            read_command(&path).unwrap().as_deref(),
            Some("sleep argument with spaces")
        );
        fs::write(&path, vec![b'a'; MAX_COMMAND_BYTES]).unwrap();
        assert_eq!(
            read_command(&path).unwrap().unwrap().len(),
            MAX_COMMAND_BYTES
        );
        fs::write(&path, vec![b'a'; MAX_COMMAND_BYTES + 1]).unwrap();
        assert!(read_command(&path).is_err());
        fs::write(&path, vec![0xff; MAX_COMMAND_BYTES]).unwrap();
        assert!(read_command(&path).is_err());
    }

    #[test]
    fn validates_and_serializes_actions() {
        let mut action = SignalAction {
            process: ProcessTarget {
                pid: 1,
                start_time_ticks: "0".into(),
            },
            signal: Signal::Sigkill,
            timeout_seconds: 1,
        };
        assert!(action.validate().is_ok());
        for ticks in ["", "01", "+1", "-1", " 1", "18446744073709551616"] {
            action.process.start_time_ticks = ticks.into();
            assert!(action.validate().is_err());
        }
        action.process.start_time_ticks = u64::MAX.to_string();
        for pid in [0, i32::MAX as u32 + 1, u32::MAX] {
            action.process.pid = pid;
            assert!(action.validate().is_err());
        }
        action.process.pid = i32::MAX as u32;
        for timeout in [0, 61, u64::MAX] {
            action.timeout_seconds = timeout;
            assert!(action.validate().is_err());
        }
        action.timeout_seconds = 60;
        assert!(action.validate().is_ok());
        let value = serde_json::to_value(&action).unwrap();
        assert_eq!(value["signal"], "SIGKILL");
        assert_eq!(value["process"]["startTimeTicks"], u64::MAX.to_string());
        assert_eq!(value["timeoutSeconds"], 60);
        let mut unknown = value;
        unknown["extra"] = true.into();
        assert!(serde_json::from_value::<SignalAction>(unknown).is_err());
    }

    #[test]
    fn child_identity_and_pidfd_kill() {
        let mut child = Sleep::spawn();
        let mut control = Sleep::spawn();
        let target = child.target();
        let mut mismatch = target.clone();
        mismatch.start_time_ticks =
            (target.start_time_ticks.parse::<u64>().unwrap() + 1).to_string();
        assert!(
            kill(&mismatch)
                .unwrap_err()
                .to_string()
                .contains("mismatch")
        );
        assert!(child.0.try_wait().unwrap().is_none());
        assert!(control.0.try_wait().unwrap().is_none());
        let processes = snapshot().unwrap();
        assert!(processes.windows(2).all(|pair| pair[0].pid < pair[1].pid));
        let info = processes
            .iter()
            .find(|info| info.pid == target.pid)
            .unwrap();
        assert_eq!(info.start_time_ticks, target.start_time_ticks);
        assert_eq!(info.ppid, std::process::id());
        assert_eq!(info.uid, unsafe { libc::getuid() });
        kill(&target).unwrap();
        assert_eq!(child.0.wait().unwrap().signal(), Some(libc::SIGKILL));
        assert!(control.0.try_wait().unwrap().is_none());
    }
}
