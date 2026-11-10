// Properties of Now in Android (demoDebug or demoBenchmarkRelease), checked
// through the adb driver. See README.md for how to run it.
import { always, eventually, next, now } from "@antithesishq/bombadil";
import {
  actions,
  extract,
  string,
  weighted,
} from "@antithesishq/bombadil/external";

/** A node as the adb driver reports it. */
type Node = {
  id: string;
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
  editable: boolean;
  bounds: number[];
  children: Node[];
};

type Window = {
  type: string;
  package: string;
  title: string;
  active: boolean;
  nodes: Node[];
};

/** An activity start that would have left the app, which the driver blocked. */
type BlockedIntent = {
  action: string | null;
  data: string | null;
  package: string | null;
};

type State = {
  package: string | null;
  activity: string | null;
  focus: string | null;
  lastAction: unknown;
  /** Hand-offs to other apps the agent blocked during the last action. */
  blockedIntents: BlockedIntent[];
  windows: Window[];
};

// The app's package: demoDebug's, or demoBenchmarkRelease's (no suffix).
const APPS = [
  "com.google.samples.apps.nowinandroid.demo.debug",
  "com.google.samples.apps.nowinandroid.demo",
];
const isApp = (p: string | null) => p !== null && APPS.includes(p);
// The system dialog for the permissions the app asks for (notifications,
// unless the driver grants it): part of the app's flow, so the driver lets
// it through.
const PERMISSION_DIALOG = "com.android.permissioncontroller";
const TOP_LEVEL = ["For you", "Saved", "Interests"];
const CRASH_OR_ANR = /keeps stopping|has stopped|isn.t responding/;

type Flat = Omit<Node, "children" | "text" | "desc" | "bounds">;

function flatten(nodes: Node[], into: Flat[] = []): Flat[] {
  for (const { children, text, desc, bounds, ...node } of nodes) {
    into.push(node);
    flatten(children, into);
  }
  return into;
}

/** Nodes of the app's own windows (not the status bar, keyboard, ...). */
const appNodes = (s: State) =>
  flatten(
    s.windows
      .filter((w) => w.type === "application" && isApp(w.package))
      .flatMap((w) => w.nodes),
  );

const pkg = extract((s: State) => s.package);
/** Whether the keyboard is shown (Back then closes it instead of going back). */
const keyboard = extract((s: State) =>
  s.windows.some((w) => w.type === "inputMethod"),
);
const lastAction = extract((s: State) => JSON.stringify(s.lastAction));
/** The node the last action tapped, if it was a tap. */
const tapped = extract(
  (s: State) =>
    (s.lastAction as { Tap?: { node?: string } } | null)?.Tap?.node ?? null,
);
const blocked = extract((s: State) => s.blockedIntents ?? []);
const nodes = extract(appNodes);
/** Window titles and labels anywhere on screen that read like a crash or ANR dialog. */
const crashOrAnr = extract((s: State) =>
  s.windows.flatMap((w) =>
    [w.title, ...flatten(w.nodes).map((n) => n.label)].filter((l) =>
      CRASH_OR_ANR.test(l),
    ),
  ),
);
/** The topic detail screen being shown, if any (its root is tagged `topic:<id>`). */
const topic = extract(
  (s: State) =>
    appNodes(s).find((n) => n.resourceId.startsWith("topic:"))?.resourceId ??
    null,
);

const inApp = () => isApp(pkg.current);
const has = (label: string) => nodes.current.some((n) => n.label === label);
const onTopLevelScreen = () =>
  inApp() && nodes.current.some((n) => n.resourceId === "niaTopAppBar");
/**
 * Where Back would leave the app: the For you feed. Recognised by its tag
 * rather than the selected navigation item, which may not be drawn yet in
 * a best-effort state.
 */
const atStartDestination = () =>
  inApp() && nodes.current.some((n) => n.resourceId === "forYou:feed");

// Properties

/**
 * The app stays in front, or the system's permission dialog it asked for.
 * The driver blocks hand-offs to other apps (an article opening in the
 * browser), so nothing else should get there.
 */
export const appInForeground = always(
  () => inApp() || pkg.current === PERMISSION_DIALOG,
);

const isWebLink = (i: BlockedIntent) =>
  i.action === "android.intent.action.VIEW" && /^https?:\/\//.test(i.data ?? "");

/** The only hand-offs the app attempts are web links. */
export const onlyWebLinksLeaveTheApp = always(() =>
  blocked.current.every(isWebLink),
);

/** Tapping a news resource card tries to open its article's web page. */
export const articleTapOpensItsLink = always(() => {
  if (!inApp()) return true;
  const cards = new Set(
    nodes.current
      .filter((n) => n.resourceId.startsWith("newsResourceCard:"))
      .map((n) => n.id),
  );
  return next(
    () =>
      tapped.current === null ||
      !cards.has(tapped.current) ||
      blocked.current.some(isWebLink),
  );
});

/** No "keeps stopping" or "isn't responding" dialog ever shows. */
export const noCrashOrAnrDialog = always(() => crashOrAnr.current.length === 0);

/**
 * Every top-level screen shows the whole bottom navigation, at the latest
 * soon after it appears: the driver reports states best effort, so a
 * screen can be caught while the navigation is still being drawn.
 */
const navigationMissing = () => onTopLevelScreen() && !TOP_LEVEL.every(has);
export const bottomNavigationOnTopLevelScreens = always(
  now(navigationMissing).implies(
    eventually(() => !navigationMissing()).within(2, "seconds"),
  ),
);

/**
 * Back from a topic's screen always leaves that topic, unless the keyboard
 * is up: Back closes it first.
 */
export const backLeavesTopicScreen = always(() => {
  const shown = topic.current;
  if (shown === null || !inApp() || keyboard.current) return true;
  return next(() => lastAction.current !== '"Back"' || topic.current !== shown);
});

// Actions. Nodes are addressed by the ids the driver reports, which don't
// depend on what else is on screen; labels are there for reading traces.

const short = (label: string) => label.slice(0, 40);

/** Taps one of the app's visible, enabled, clickable nodes. */
const tap = actions(() =>
  inApp()
    ? nodes.current
        .filter((n) => n.clickable && n.enabled)
        .map((n) => ({ Tap: { node: n.id, label: short(n.label) } }))
    : [],
);

/** Scrolls one of the app's scrollable nodes a step. */
const scroll = actions(() =>
  inApp()
    ? nodes.current
        .filter((n) => n.scrollable)
        .flatMap((n) =>
          ["down", "up"].map((direction) => ({
            Scroll: { node: n.id, direction, label: short(n.label) },
          })),
        )
    : [],
);

/** Types into the search field when it's focused. */
const typeText = actions(() =>
  inApp() && nodes.current.some((n) => n.focused && n.editable)
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
