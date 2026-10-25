// A property that Now in Android rightly violates, to see Bombadil find
// and shrink a violation: following a topic marks its toggle checked.
import { always } from "@antithesishq/bombadil";
import { extract } from "@antithesishq/bombadil/external";

export { nowInAndroid } from "./spec.ts";

type Node = { checked: boolean; children: Node[] };

const anyChecked = (nodes: Node[]): boolean =>
  nodes.some((n) => n.checked || anyChecked(n.children));

const following = extract((s: { nodes: Node[] }) => anyChecked(s.nodes));

export const noTopicIsEverFollowed = always(() => !following.current);
