// Properties of Now in Android (demoDebug), checked through the adb
// driver. See README.md for how to run it.
import { always, next } from "@antithesishq/bombadil";
import {
  actions,
  extract,
  integer,
  string,
  weighted,
} from "@antithesishq/bombadil/external";

/** A node as the adb driver reports it. */
type Node = {
  resourceId: string;
  class: string;
  text: string;
  desc: string;
  label: string;
  clickable: boolean;
  scrollable: boolean;
  enabled: boolean;
  checked: boolean;
  selected: boolean;
  focused: boolean;
  bounds: number[];
  children: Node[];
};

type State = {
  package: string | null;
  activity: string | null;
  focus: string | null;
  lastAction: unknown;
  nodes: Node[];
};

const APP = "com.google.samples.apps.nowinandroid.demo.debug";
// Tapping a news item opens its article in the system browser.
const BROWSER = "org.chromium.webview_shell";
const TOP_LEVEL = ["For you", "Saved", "Interests"];

type Flat = Omit<Node, "children" | "text" | "desc" | "bounds">;

/** All nodes depth first, in the order the driver matches selectors. */
function flatten(nodes: Node[], into: Flat[] = []): Flat[] {
  for (const { children, text, desc, bounds, ...node } of nodes) {
    into.push(node);
    flatten(children, into);
  }
  return into;
}

const pkg = extract((s: State) => s.package);
const focus = extract((s: State) => s.focus ?? "");
const lastAction = extract((s: State) => JSON.stringify(s.lastAction));
const nodes = extract((s: State) => flatten(s.nodes));
/** The topic detail screen being shown, if any (its root is tagged `topic:<id>`). */
const topic = extract(
  (s: State) =>
    flatten(s.nodes).find((n) => n.resourceId.startsWith("topic:"))
      ?.resourceId ?? null,
);

const inApp = () => pkg.current === APP;
const has = (label: string) => nodes.current.some((n) => n.label === label);
const onTopLevelScreen = () =>
  inApp() && nodes.current.some((n) => n.resourceId === "niaTopAppBar");
/** Where Back would leave the app. */
const atStartDestination = () =>
  onTopLevelScreen() &&
  nodes.current.some((n) => n.selected && n.label === "For you");

// Properties

/** The app stays in front, except for articles it opens in the browser. */
export const appInForeground = always(
  () => pkg.current === APP || pkg.current === BROWSER,
);

/** No "keeps stopping" or "isn't responding" dialog ever shows. */
export const noCrashOrAnrDialog = always(
  () =>
    !/^Application (Error|Not Responding)/.test(focus.current) &&
    !nodes.current.some((n) => /keeps stopping|isn.t responding/.test(n.label)),
);

/** Every top-level screen shows the whole bottom navigation. */
export const bottomNavigationOnTopLevelScreens = always(
  () => !onTopLevelScreen() || TOP_LEVEL.every(has),
);

/** Back from a topic's screen always leaves that topic. */
export const backLeavesTopicScreen = always(() => {
  const shown = topic.current;
  if (shown === null || !inApp()) return true;
  return next(() => lastAction.current !== '"Back"' || topic.current !== shown);
});

// Actions

/** Taps one of the visible, enabled, clickable nodes. */
const tap = actions(() => {
  if (!inApp()) return [];
  const seen = new Map<string, number>();
  const taps = [];
  for (const n of nodes.current) {
    const key = JSON.stringify([n.label, n.resourceId]);
    const index = seen.get(key) ?? 0;
    seen.set(key, index + 1);
    if (n.clickable && n.enabled) {
      taps.push({ Tap: { label: n.label, resourceId: n.resourceId, index } });
    }
  }
  return taps;
});

const scroll = actions(() =>
  inApp()
    ? [
        { Scroll: { direction: "down", percent: integer([20, 80]) } },
        { Scroll: { direction: "up", percent: integer([20, 80]) } },
      ]
    : [],
);

/** Types into the search field when it's focused. */
const typeText = actions(() =>
  inApp() && nodes.current.some((n) => n.focused && n.class === "EditText")
    ? [{ TypeText: { text: string({ Regexp: "[a-z]{1,8}( [a-z]{1,8})?" }) } }]
    : [],
);

/** Back, except where it would close the app. */
const back = actions(() => (atStartDestination() ? [] : ["Back"]));

export const nowInAndroid = weighted([
  [10, tap],
  [3, scroll],
  [1, typeText],
  [3, back],
]);
