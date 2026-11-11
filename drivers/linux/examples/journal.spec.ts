import { always } from "@antithesishq/bombadil";
import { actions, extractLogs } from "../index.ts";

// Run the driver with --log-options examples/journal.json.
const storage = extractLogs(0);
export const noStorageErrors = always(() =>
  storage.current !== null && storage.current.entries.length === 0);
export const idle = actions(() => []);
