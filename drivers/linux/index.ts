import { extract as externalExtract } from "@antithesishq/bombadil/external";
import type { JSON } from "@antithesishq/bombadil";

export { actions } from "@antithesishq/bombadil/external";

export type Selector = { role: string; name: string }[];
export type Key = "Tab" | "Escape" | "Enter" | "Space" | "Ctrl" | "Shift" | "N" | "L" | "Z" | "F2" | "Delete";

export type Node = {
  selector: Selector;
  enabled: boolean;
  showing: boolean;
  focused: boolean;
  selected: boolean;
  editable: boolean;
  actions: string[];
  windowBounds: [number, number, number, number] | null;
  text: string | null;
};

export type Expectation =
  | { kind: "present"; target: Selector }
  | { kind: "absent"; target: Selector }
  | { kind: "text"; target: Selector; value: string }
  | { kind: "containsText"; target: Selector; value: string }
  | { kind: "focused"; target: Selector }
  | { kind: "selected"; target: Selector }
  | { kind: "enabled"; target: Selector; value: boolean };

export type DesktopAction = {
  target: Selector;
  input:
    | { kind: "click" }
    | { kind: "doubleClick" }
    | { kind: "replaceText"; text: string }
    | { kind: "key"; key: Key }
    | { kind: "chord"; keys: Key[] };
  expect: Expectation;
};

export type LifecycleAction = {
  lifecycle: "suspend" | "wake" | "shutdown" | "powerOff" | "powerOn" | "reboot" | "reset" | "wait";
  timeoutSeconds: number;
  settleSeconds?: number;
  expect: {
    power: "off" | "running" | "suspended" | "paused";
    boot?: "any" | "same" | "changed";
    agentConnected: boolean;
  };
};

export type ProcessTarget = { pid: number; startTimeTicks: string };

export type ProcessInfo = ProcessTarget & {
  ppid: number;
  uid: number;
  name: string;
  command: string | null;
  state: string;
};

export type SignalAction = {
  process: ProcessTarget;
  signal: "SIGKILL";
  timeoutSeconds: number;
};

export type Action = DesktopAction | LifecycleAction | SignalAction;

export type State = {
  appAlive: boolean | null;
  nodes: Node[];
  lastAction: Action | null;
  outcome: boolean | null;
  journals: LogBatch[] | null;
  processes: ProcessInfo[] | null;
  machine: {
    power: "off" | "running" | "suspended" | "paused";
    agentConnected: boolean;
    bootId: string | null;
    applicationGeneration: number;
    transition: LifecycleAction["lifecycle"] | null;
    events: JSON[];
    fault: string | null;
  } | null;
};

export type LogLevel = "emerg" | "alert" | "crit" | "err" | "warning" | "notice" | "info" | "debug";

export type LogOptions = {
  scope: (
    | { systemUnit: string }
    | { userUnit: string }
    | { identifier: string }
    | { kernel: true }
  ) & { module?: { field: string; value: string } };
  level: LogLevel;
  exact?: boolean;
};

export type LogEntry = {
  cursor: string;
  timestampMicros: string;
  priority: number;
  message: JSON;
  fields: { [key: string]: JSON };
};

export type LogBatch = { cursor: string; entries: LogEntry[] };

/** Index into the queries supplied to the driver's --log-options JSON file. */
export function extractLogs(index: number) {
  if (!Number.isInteger(index) || index < 0) throw new Error("Invalid journal query index");
  return extract((state) => {
    if (state.journals === null) return null;
    const batch = state.journals[index];
    if (!batch) throw new Error("Missing journal collector for log extractor");
    return batch;
  });
}

export function extract<T extends JSON>(query: (state: State) => T) {
  return externalExtract<State, T>(query);
}

export function extractProcesses() {
  return extract((state) => state.processes);
}
