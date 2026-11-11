import { always } from "@antithesishq/bombadil";
import { actions, extract, extractProcesses, type SignalAction } from "../index.ts";

const state = extract((state) => state);
const processes = extractProcesses();

export const noMachineFault = always(() => state.current.machine?.fault == null);
export const actionsHaveExpectedOutcomes = always(() => state.current.outcome !== false);

export const interact = actions((): SignalAction[] => {
  const current = processes.current;
  if (current === null) return [];
  return current.filter((process) => process.name === "bombadil-victim" && process.state !== "Z")
    .map((process) => ({
      process: { pid: process.pid, startTimeTicks: process.startTimeTicks },
      signal: "SIGKILL",
      timeoutSeconds: 10,
    }));
});
