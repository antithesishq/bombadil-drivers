// compass_app's specification plus a property that the app deliberately
// violates: deleting a booked trip (swiping it away on the home screen) is
// a feature, so Bombadil should find a way to empty the list.
import { always } from "@antithesishq/bombadil";
import { visible } from "../../spec/flutter.ts";

export * from "./spec.ts";

/** Trips on the home screen read like "Alaska, North America\n1 Jan - 1 Feb". */
const trip = /\n\d+ \w+ - \d+ \w+$/;

/**
 * The home screen, recognised by its button rather than by the route: a
 * booking opened from it is pushed (go_router's `context.push`), which
 * doesn't change the route the router reports.
 */
const onHome = () => visible.current.some((n) => n.label === "Book New Trip");

export const homeAlwaysListsTrips = always(
  () => !onHome() || visible.current.some((n) => trip.test(n.label ?? "")),
);
