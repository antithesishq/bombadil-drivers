// Specification helpers for apps run by bombadil-flutter-driver: the
// state it reports, and action generators for the semantics nodes that
// are on screen.
import { actions, extract, string, weighted } from "@antithesishq/bombadil/external";

export type Node = {
  id: number;
  label?: string;
  value?: string;
  hint?: string;
  tooltip?: string;
  identifier?: string;
  flags: string[];
  actions: string[];
  customActions?: { id: number; label: string | null }[];
  /** `[x, y, width, height]` in logical pixels on screen. */
  rect: [number, number, number, number];
  /** Not hidden, and at least partly on screen. */
  visible: boolean;
  scroll?: { position: number; min: number; max: number };
  children: Node[];
};

export type State = {
  /** The route the app last reported on `flutter/navigation`. */
  route: string | null;
  size: [number, number];
  tree: Node;
  /** Flutter errors and unhandled exceptions logged since the last state. */
  errors: string[];
  /** Whether the app asked to close (`SystemNavigator.pop`). */
  exitRequested: boolean;
  /** Whether the app went quiet before the driver reported the state. */
  settled: boolean;
  lastAction?: { action: any; ok: boolean; error?: string };
};

/** A node without its children, as extracted. */
export type Element = Omit<Node, "children">;

export function flatten(node: Node, into: Element[] = []): Element[] {
  const { children, ...element } = node;
  into.push(element);
  for (const child of children) flatten(child, into);
  return into;
}

/** A short description of a node, for checks and debugging. */
export function describe(node: Element): string {
  return node.label || node.tooltip || node.value || node.hint || `#${node.id}`;
}

export const route = extract((s: State) => s.route);
export const errors = extract((s: State) => s.errors);
export const exitRequested = extract((s: State) => s.exitRequested);
export const lastAction = extract((s: State) => s.lastAction ?? null);
/** The visible nodes. */
export const visible = extract((s: State) => flatten(s.tree).filter((n) => n.visible));

const supporting = (action: string) =>
  visible.current.filter((n) => n.actions.includes(action));

export const tap = actions(() => supporting("tap").map((n) => ({ Tap: { node: n.id } })));

export const setText = actions(() =>
  visible.current
    .filter((n) => n.flags.includes("textField") && !n.flags.includes("readOnly"))
    .map((n) => ({ SetText: { node: n.id, text: string({ Text: [0, 12] }) } })),
);

export const scroll = actions(() =>
  (["ScrollUp", "ScrollDown", "ScrollLeft", "ScrollRight"] as const).flatMap((tag) => {
    const name = tag[0].toLowerCase() + tag.slice(1);
    return supporting(name).map((n) => ({ [tag]: { node: n.id } }));
  }),
);

export const adjust = actions(() => [
  ...supporting("increase").map((n) => ({ Increase: { node: n.id } })),
  ...supporting("decrease").map((n) => ({ Decrease: { node: n.id } })),
  ...supporting("longPress").map((n) => ({ LongPress: { node: n.id } })),
  ...supporting("dismiss").map((n) => ({ Dismiss: { node: n.id } })),
]);

/** Taps, text, scrolls, adjustments and back, on what's on screen. */
export const explore = weighted([
  [10, tap],
  [3, setText],
  [3, scroll],
  [1, adjust],
  [2, "Back"],
]);

/**
 * The ID of the last node that scopes a route: the topmost route, such as
 * an open dialog. It changes when a route is pushed or popped.
 */
export const topScope = extract((s: State) => {
  const scopes = flatten(s.tree).filter((n) => n.flags.includes("scopesRoute"));
  return scopes.length > 0 ? scopes[scopes.length - 1].id : null;
});
