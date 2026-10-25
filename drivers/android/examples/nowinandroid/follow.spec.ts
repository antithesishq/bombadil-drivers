// A property that Now in Android rightly violates, to see Bombadil find
// and shrink a violation: following a topic turns its toggle on.
import { always } from "@antithesishq/bombadil";
import { extract } from "@antithesishq/bombadil/external";

export { nowInAndroid } from "./spec.ts";

type Node = { checked: boolean; clickable: boolean; children: Node[] };

// Topic toggles stay clickable when on, unlike the checked radio buttons
// in the settings dialog.
const anyToggleOn = (nodes: Node[]): boolean =>
  nodes.some((n) => (n.checked && n.clickable) || anyToggleOn(n.children));

const following = extract((s: { nodes: Node[] }) => anyToggleOn(s.nodes));

export const noTopicIsEverFollowed = always(() => !following.current);
