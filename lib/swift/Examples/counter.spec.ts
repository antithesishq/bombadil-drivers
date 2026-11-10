import { always } from "@antithesishq/bombadil";
import { actions, extract, integer } from "@antithesishq/bombadil/external";

const count = extract((state: { count: number }) => state.count);
export const countAtMostThree = always(() => count.current <= 3);
export const counter = actions(() => [
  { Increment: { by: integer([1, 2]) } },
  "Reset",
]);
