use std::os::unix::fs::PermissionsExt;

use bombadil_linux::journal::{Journal, LogOptions};
use serde_json::json;

#[test]
fn scoped_journals_keep_cursors_levels_and_collection_failures() {
    // Run with an isolated PATH rather than mutating the test runner's environment.
    if std::env::var_os("BOMBADIL_JOURNAL_TEST_CHILD").is_none() {
        let temp = tempfile::tempdir().unwrap();
        let program = temp.path().join("journalctl");
        std::fs::write(&program, r#"#!/bin/sh
mode=$(cat "$JOURNAL_TEST_MODE_FILE" 2>/dev/null)
if [ "$mode" = access ]; then echo 'Permission denied' >&2; exit 0; fi
row='{"__CURSOR":"first","__REALTIME_TIMESTAMP":"123456789","PRIORITY":"3","MESSAGE":"storage failed","TARGET":"storage"}'
case " $* " in
  *' --after-cursor='*)
    case " $* " in *' SYSLOG_IDENTIFIER=fixture '*) ;; *) exit 9;; esac
    case " $* " in *' TARGET=storage '*) ;; *) exit 9;; esac
    case "$mode" in
      malformed) echo not-json; exit;;
      byte-overflow) head -c 4194305 /dev/zero; exit;;
      overflow) i=0; while [ "$i" -le 1000 ]; do echo "$row"; i=$((i+1)); done; exit;;
    esac
    case " $* " in *' --priority=4..4 '*) exit;; *' --priority=0..4 '*) ;; *) exit 8;; esac
    case " $* " in *' --after-cursor=first '*) exit;; esac
    echo "$row";;
  *' --cursor='*)
    if [ "$mode" = lost ]; then echo "$row"
    else case " $* " in *' --cursor=first '*) echo "$row";; *) echo '{"__CURSOR":"baseline"}';; esac
    fi;;
  *) echo '{"__CURSOR":"baseline"}';;
esac
"#).unwrap();
        std::fs::set_permissions(
            &program,
            std::fs::Permissions::from_mode(0o700),
        )
        .unwrap();
        let result = std::process::Command::new(
            std::env::current_exe().unwrap(),
        )
        .args([
            "--exact",
            "scoped_journals_keep_cursors_levels_and_collection_failures",
            "--nocapture",
        ])
        .env("BOMBADIL_JOURNAL_TEST_CHILD", "1")
        .env("JOURNAL_TEST_MODE_FILE", temp.path().join("mode"))
        .env(
            "PATH",
            format!(
                "{}:{}",
                temp.path().display(),
                std::env::var("PATH").unwrap()
            ),
        )
        .output()
        .unwrap();
        assert!(
            result.status.success(),
            "{}{}",
            String::from_utf8_lossy(&result.stdout),
            String::from_utf8_lossy(&result.stderr)
        );
        return;
    }
    let options = |exact| -> LogOptions {
        serde_json::from_value(json!({"scope":{"identifier":"fixture", "module":{"field":"TARGET", "value":"storage"}}, "level":"warning", "exact":exact})).unwrap()
    };
    let mut journal = Journal::start(vec![options(false), options(true)])
        .unwrap()
        .unwrap();
    let batches = journal.poll().unwrap();
    assert_eq!(batches[0].entries.len(), 1);
    assert_eq!(batches[0].entries[0].message, "storage failed");
    assert_eq!(batches[0].entries[0].fields["TARGET"], "storage");
    assert_eq!(batches[0].cursor, "first");
    assert!(batches[1].entries.is_empty());
    assert!(journal.poll().unwrap().iter().all(|b| b.entries.is_empty()));
    for (mode, message) in [
        ("lost", "cursor was lost"),
        ("malformed", "invalid journal JSON"),
        ("byte-overflow", "collection overflow"),
        ("overflow", "entry limit exceeded"),
        ("access", "incomplete access"),
    ] {
        std::fs::write(std::env::var("JOURNAL_TEST_MODE_FILE").unwrap(), mode)
            .unwrap();
        let started = Journal::start(vec![options(false)]);
        let error = match started {
            Err(error) => error,
            Ok(Some(mut journal)) => journal.poll().unwrap_err(),
            Ok(None) => panic!("missing journal"),
        };
        assert!(error.to_string().contains(message), "{mode}: {error}");
    }
}

#[test]
fn invalid_journal_scopes_fail_before_querying_the_service() {
    for scope in [
        json!({}),
        json!({"identifier":""}),
        json!({"kernel":false}),
        json!({"identifier":"fixture","module":{"field":"PRIORITY","value":"7"}}),
    ] {
        let options: LogOptions =
            serde_json::from_value(json!({"scope":scope,"level":"warning"}))
                .unwrap();
        assert!(Journal::start(vec![options]).is_err());
    }
}
