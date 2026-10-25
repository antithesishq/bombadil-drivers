// A property that Now in Android rightly violates, to see Bombadil find
// and shrink a violation: following a topic on the Interests tab turns
// its button into "Unfollow interest". Violating it takes at least two
// actions (open Interests, follow something), so there's something to
// shrink.
import { always } from "@antithesishq/bombadil";
import { extract } from "@antithesishq/bombadil/external";

export { nowInAndroid } from "./spec.ts";

type Node = { label: string; children: Node[] };

const shows = (nodes: Node[], label: string): boolean =>
  nodes.some((n) => n.label === label || shows(n.children, label));

const followingAnInterest = extract((s: { nodes: Node[] }) =>
  shows(s.nodes, "Unfollow interest"),
);

export const noInterestIsEverFollowed = always(
  () => !followingAnInterest.current,
);
