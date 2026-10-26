// A specification for any app run by the bombadil_flutter driver: explore what
// is on screen and check that the app never logs an error.
import { always } from "@antithesishq/bombadil";
import { errors, explore, lastAction } from "./flutter.ts";

export { explore };

/** No Flutter error or unhandled exception is ever logged. */
export const noErrors = always(() => errors.current.length === 0);

/** Actions generated from the screen always apply to it. */
export const actionsApply = always(() => lastAction.current?.ok !== false);
