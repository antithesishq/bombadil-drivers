import { always } from "@antithesishq/bombadil";
import { actions, extract, type Action } from "../index.ts";

const state = extract((state) => state);

export const noMachineFault = always(() => state.current.machine?.fault == null);
export const actionsHaveExpectedOutcomes = always(() => state.current.outcome !== false);

export const interact = actions((): Action[] => {
  const machine = state.current.machine;
  if (!machine || machine.transition !== null) return [];
  if (machine.power === "off") return [{
    lifecycle: "powerOn",
    timeoutSeconds: 120,
    settleSeconds: 0,
    expect: { power: "running", boot: "changed", agentConnected: true },
  }];
  if (machine.power === "suspended") return [{
    lifecycle: "wake",
    timeoutSeconds: 120,
    settleSeconds: 65,
    expect: { power: "running", boot: "same", agentConnected: true },
  }];
  const result: Action[] = [{
    lifecycle: "powerOff",
    timeoutSeconds: 120,
    settleSeconds: 0,
    expect: { power: "off", agentConnected: false },
  }];
  if (machine.power !== "running") return result;
  result.push({
    lifecycle: "reset",
    timeoutSeconds: 120,
    settleSeconds: 0,
    expect: { power: "running", boot: "changed", agentConnected: true },
  });
  if (!machine.agentConnected) return result;
  result.push({
    lifecycle: "shutdown",
    timeoutSeconds: 120,
    settleSeconds: 0,
    expect: { power: "off", agentConnected: false },
  }, {
    lifecycle: "reboot",
    timeoutSeconds: 120,
    settleSeconds: 0,
    expect: { power: "running", boot: "changed", agentConnected: true },
  }, {
    lifecycle: "suspend",
    timeoutSeconds: 120,
    settleSeconds: 0,
    expect: { power: "suspended", boot: "same", agentConnected: false },
  });
  if (state.current.appAlive !== true) return result;
  const input = state.current.nodes.find((node) => node.editable && node.enabled && node.showing &&
    node.selector[node.selector.length - 1]?.name === "Test input");
  if (input && input.text !== null) {
    for (const text of ["", "Bombadil 17", "aB9", "two words"]) {
      result.push({
        target: input.selector,
        input: { kind: "replaceText", text },
        expect: { kind: "text", target: input.selector, value: text },
      });
    }
  }
  return result;
});
