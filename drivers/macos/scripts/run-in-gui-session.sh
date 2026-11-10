#!/bin/sh
# Runs a Bombadil external driver in the user's GUI (Aqua) session, as a
# one-off LaunchAgent whose stdin and stdout are FIFOs that this script
# connects to its own stdin and stdout. Its stderr is copied to ours.
#
#   run-in-gui-session.sh <driver> [arguments...]
#
# Use it when Bombadil runs outside the GUI session, for example over ssh,
# and the driver needs the Accessibility permission: macOS only grants
# that to processes in the GUI session, and holds the process macOS deems
# responsible for the driver (for a LaunchAgent, the driver itself) to
# it, rather than sshd or the terminal Bombadil runs in.
set -eu

if [ $# -lt 1 ]; then
  echo "usage: $0 <driver> [arguments...]" >&2
  exit 2
fi

dir=$(mktemp -d "${TMPDIR:-/tmp}/bombadil-gui.XXXXXX")
label="dev.bombadil.gui-session.${dir##*.}"
domain="gui/$(id -u)"
mkfifo "$dir/in" "$dir/out"
: >"$dir/err"

xml() { printf '%s' "$1" | sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g'; }
{
  cat <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>Label</key><string>$label</string>
<key>RunAtLoad</key><true/>
<key>ProcessType</key><string>Interactive</string>
<key>StandardInPath</key><string>$(xml "$dir/in")</string>
<key>StandardOutPath</key><string>$(xml "$dir/out")</string>
<key>StandardErrorPath</key><string>$(xml "$dir/err")</string>
<key>EnvironmentVariables</key><dict>
<key>BOMBADIL_RUN_ID</key><string>$(xml "${BOMBADIL_RUN_ID:-}")</string>
<key>PATH</key><string>$(xml "$PATH")</string>
</dict>
<key>ProgramArguments</key><array>
EOF
  for argument in "$@"; do
    printf '<string>%s</string>\n' "$(xml "$argument")"
  done
  echo '</array></dict></plist>'
} >"$dir/agent.plist"

pids=""
cleanup() {
  # Booting the agent out kills the driver's process group, so the app it
  # launched too.
  launchctl bootout "$domain/$label" 2>/dev/null || true
  sleep 0.2
  # shellcheck disable=SC2086
  kill $pids 2>/dev/null || true
  rm -rf "$dir"
}
trap cleanup EXIT
trap 'exit 143' TERM INT HUP

launchctl bootstrap "$domain" "$dir/agent.plist"
tail -f "$dir/err" >&2 &
pids="$pids $!"
# Without job control, background jobs read /dev/null unless told otherwise.
exec 3<&0
cat <&3 >"$dir/in" &
pids="$pids $!"
cat "$dir/out" &
out=$!
pids="$pids $out"
# `wait` returns early for signals, so the traps above run promptly. The
# driver's stdout closes when it exits.
wait "$out"
