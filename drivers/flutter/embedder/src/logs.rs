//! Keeps stdout for the protocol and watches the app's logs for errors.
//!
//! The engine and the Dart VM write to stdout and stderr directly, so the
//! driver points both at a pipe, forwards what comes through to the real
//! stderr, and remembers the errors it sees: Flutter's "EXCEPTION CAUGHT
//! BY" reports and unhandled exceptions.

use std::fs::File;
use std::io::{BufRead, BufReader, Write};
use std::os::fd::FromRawFd;
use std::sync::Mutex;
use std::thread;

static ERRORS: Mutex<Vec<String>> = Mutex::new(Vec::new());

/// Errors seen since the last call.
pub fn take_errors() -> Vec<String> {
    std::mem::take(&mut ERRORS.lock().unwrap())
}

/// Redirects stdout and stderr to the log watcher and returns the real
/// stdout, for protocol messages.
pub fn redirect() -> std::io::Result<File> {
    unsafe {
        let protocol = libc::dup(1);
        let stderr = libc::dup(2);
        let mut fds = [0; 2];
        if protocol < 0 || stderr < 0 || libc::pipe(fds.as_mut_ptr()) != 0 {
            return Err(std::io::Error::last_os_error());
        }
        libc::dup2(fds[1], 1);
        libc::dup2(fds[1], 2);
        libc::close(fds[1]);
        let logs = File::from_raw_fd(fds[0]);
        let mut stderr = File::from_raw_fd(stderr);
        thread::spawn(move || {
            let mut watcher = Watcher::default();
            for line in BufReader::new(logs).split(b'\n') {
                let Ok(line) = line else { break };
                let _ = stderr.write_all(&line);
                let _ = stderr.write_all(b"\n");
                if let Some(error) = watcher.line(&String::from_utf8_lossy(&line)) {
                    let mut errors = ERRORS.lock().unwrap();
                    if errors.len() < 20 {
                        errors.push(error);
                    }
                }
            }
        });
        Ok(File::from_raw_fd(protocol))
    }
}

/// Recognizes errors in log lines.
#[derive(Default)]
pub struct Watcher {
    /// An error report being read, and how many more lines it takes.
    report: Option<(String, usize)>,
}

impl Watcher {
    /// Reads a line and returns an error once complete.
    pub fn line(&mut self, line: &str) -> Option<String> {
        let line = line.strip_prefix("flutter: ").unwrap_or(line).trim();
        if let Some(header) = line.find("EXCEPTION CAUGHT BY") {
            let title = line[header..].trim_end_matches(|c: char| !c.is_alphanumeric());
            // The next lines say what was thrown, and the message.
            let finished = self.report.take().map(|(report, _)| report);
            self.report = Some((title.to_string(), 2));
            return finished;
        }
        if let Some(at) = line.find("Unhandled Exception:") {
            return Some(line[at..].to_string());
        }
        let (report, remaining) = self.report.as_mut()?;
        if line.is_empty() {
            return None;
        }
        report.push_str(" | ");
        report.push_str(line);
        *remaining -= 1;
        if *remaining == 0 {
            return self.report.take().map(|(report, _)| report);
        }
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_flutter_error_reports() {
        let mut watcher = Watcher::default();
        let lines = [
            "flutter: ══╡ EXCEPTION CAUGHT BY WIDGETS LIBRARY ╞══════════",
            "flutter: The following StateError was thrown building Foo:",
            "flutter: Bad state: oops",
            "flutter: more",
        ];
        let errors: Vec<_> = lines.iter().filter_map(|l| watcher.line(l)).collect();
        assert_eq!(
            errors,
            ["EXCEPTION CAUGHT BY WIDGETS LIBRARY | The following StateError was thrown building Foo: | Bad state: oops"]
        );
    }

    #[test]
    fn reads_unhandled_exceptions() {
        let mut watcher = Watcher::default();
        assert_eq!(
            watcher.line("[ERROR:flutter/runtime/dart_vm_initializer.cc(40)] Unhandled Exception: boom"),
            Some("Unhandled Exception: boom".to_string())
        );
        assert_eq!(watcher.line("flutter: hello"), None);
    }
}
