// Properties of flutter/samples' compass_app, run through
// the bombadil_flutter driver. See the README for how to run it.
import { always, next, now } from "@antithesishq/bombadil";
import {
  errors,
  explore,
  lastAction,
  route,
  topScope,
  visible,
} from "../../spec/flutter.ts";

export { explore };

/** Every route the app's router defines. */
const routes = [
  /^\/$/,
  /^\/search$/,
  /^\/results$/,
  /^\/activities$/,
  /^\/booking$/,
  /^\/booking\/\d+$/,
];

/** No Flutter error or unhandled exception is ever logged. */
export const noErrors = always(() => errors.current.length === 0);

/** The app always reports one of its routes. */
export const knownRoute = always(
  () =>
    route.current !== null && routes.some((r) => r.test(route.current!)),
);

/** Something on screen can always be tapped or scrolled. */
export const somethingToDo = always(() =>
  visible.current.some((n) =>
    n.actions.some((a) => a === "tap" || a.startsWith("scroll")),
  ),
);

/** Actions generated from the screen always apply to it. */
export const actionsApply = always(() => lastAction.current?.ok !== false);

/**
 * Back, away from home, always does something: it leaves the page, or
 * closes what's on top of it, such as a dialog.
 */
export const backDoesSomething = always(
  now(() => route.current !== "/")
    .and(next(() => lastAction.current?.action === "Back"))
    .implies(() => {
      const [route0, scope0] = [route.current, topScope.current];
      return next(
        () => route.current !== route0 || topScope.current !== scope0,
      );
    }),
);
