import { always } from "@antithesishq/bombadil";
import { actions, extract, type DesktopAction, type Node } from "../index.ts";

const state = extract((state) => state);

export const appStaysAlive = always(() => state.current.appAlive !== false);
export const actionsHaveExpectedOutcomes = always(
  () => state.current.outcome !== false,
);

export const interact = actions((): DesktopAction[] => {
  const nodes = state.current.nodes;
  const leaf = (node: Node) => node.selector[node.selector.length - 1];
  const dialog = nodes.find((node) => leaf(node)?.role === "Dialog");
  if (dialog) {
    const cancel = nodes.find(
      (node) => leaf(node)?.name === "Cancel" && node.showing && node.enabled,
    );
    return cancel ? [{
      target: cancel.selector,
      input: { kind: "click" },
      expect: { kind: "absent", target: dialog.selector },
    }] : [];
  }
  const result: DesktopAction[] = [];
  const input = nodes.find((node) => leaf(node)?.name === "Test input" && node.editable);
  if (input) {
    for (const text of ["", "Bombadil 17", "aB9", "two words"]) {
      result.push({
        target: input.selector,
        input: { kind: "replaceText", text },
        expect: { kind: "text", target: input.selector, value: text },
      });
    }
  }
  const open = nodes.find((node) => leaf(node)?.name === "Open dialog");
  if (open) {
    const app = open.selector.slice(0, 1);
    result.push({
      target: open.selector,
      input: { kind: "click" },
      expect: { kind: "present", target: [...app, { role: "Dialog", name: "Test dialog" }] },
    });
  }
  return result;
});
