import { always } from "@antithesishq/bombadil";
import { actions, extract } from "@antithesishq/bombadil/external";

type State = {
  elements: { key: string | null; text: string | null }[];
};

const count = extract((state: State) => {
  const label = state.elements.find(element => element.key === "count")?.text;
  if (!label?.startsWith("Count: ")) throw new Error("missing counter label");
  return Number(label.slice(7));
});

// Intentionally false: four increments demonstrate test, shrink and replay.
export const bounded = always(() => count.current <= 3);
export const counter = actions(() => [
  { Tap: { key: "increment" } },
  { Tap: { key: "reset" } },
]);
