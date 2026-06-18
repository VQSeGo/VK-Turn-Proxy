## 2025-01-20 - Use of Flow for Lists/Logs
**Learning:** Logs are emitted as a new `List<String>` on every new entry, causing complete recomposition of `LazyColumn` items if we pass the whole list without proper `items` keyed identification or fast pathing. Currently `itemsIndexed(logs, key = { index, _ -> index })` uses index which is bad for prepend, but for append it might be okay. Wait, `logs.lastIndex` is used but if we append, `LazyColumn` might still recompose.
**Action:** Use `key` optimally. But wait, `logs` only keeps max 200 items. Is `itemsIndexed` with index good enough? No, when items are dropped from top, indices shift, causing complete recomposition. The key should probably be unique. Let's see if this is an issue.

## 2025-01-20 - Log rendering optimization in Compose
**Learning:** `itemsIndexed(logs, key = { index, _ -> index })` causes issues. When `logs.size` exceeds `MAX_LOG_LINES`, the first item is dropped. This means all items shift down one index. Because the `key` is based on the `index`, Compose will recompose ALL items in the list, even though they haven't changed except for shifting up. We should use a more stable key, or let Compose use the item's identity if the strings are unique, but wait, duplicate logs might appear.
**Action:** Let's create a wrapper or use a unique ID for each log line to prevent full recomposition. Wait, a simpler way is to wrap `LogEntry(val id: Int, val text: String)` and use `id` as the key. This will ensure appending a new item and dropping the oldest item only recomposes those specific items (or rather, no recomposition of the middle ones). Let's see if this optimization applies and how many lines it is.

## 2025-01-20 - Log rendering optimization in Compose
**Learning:** `itemsIndexed(logs, key = { index, _ -> index })` causes issues. When `logs.size` exceeds `MAX_LOG_LINES`, the first item is dropped. This means all items shift down one index. Because the `key` is based on the `index`, Compose will recompose ALL items in the list, even though they haven't changed except for shifting up.
**Action:** Use a unique ID for each log line. Create a `LogEntry` data class in `ProxyServiceState` to hold an ID and the text. Update the viewmodel to return this type and use `id` as the key in the `LazyColumn`.

## 2025-01-20 - Memoization of log rendering
**Learning:** `LogLine` composable in `LogsScreen` is recalculating text colors and string containments on every recomposition. Also, it's inside a `itemsIndexed` block.
**Action:** Extract log item data model to include precomputed log levels or wrap the LogLine composable in a memoized layout if needed. But changing `itemsIndexed` key is higher priority.

## 2025-01-20 - Log rendering optimization in Compose
**Learning:** In Compose, `LazyColumn` key should be stable and unique. `index` is not stable when elements are removed from the top (which happens because `logs` is bounded to `MAX_LOG_LINES`). Thus, when it hits the limit, all elements shift index and the whole visible list is recomposed. Furthermore, `LogLine` performs 14+ `.contains` string searches on every single recomposition!
**Action:** Extract log item data into a stable `LogEntry` wrapper. We can parse the log line *once* when it is added to the log list and assign it an ID and a level (Error, Warning, Success, Header, Normal). This solves the shifting key issue and avoids string manipulation during composition, drastically improving the performance of the logs screen.

## 2025-01-20 - Log data model optimization
**Learning:** `LogLine` currently takes a raw `String` and uses string operations for styling. This is inefficient inside a `LazyColumn`. Also `logs` emits a plain `List<String>`.
**Action:** Let's refactor `logs` to emit a custom `LogEntry` with an `id: Long`, `text: String`, and `level: LogLevel` properties. `ProxyServiceState` will parse incoming strings into `LogEntry`. This achieves O(1) rendering time per item inside the `LazyColumn`.

## 2025-01-20 - Global monotonic ID for logs
**Learning:** We need a way to assign a unique ID to each log line so that Compose can track it efficiently.
**Action:** Create a `LogEntry(val id: Long, val text: String, val isError: Boolean, val isWarning: Boolean, val isSuccess: Boolean, val isHeader: Boolean)` class. Maintain a monotonic counter in `ProxyServiceState` that increments for each new log. This completely sidesteps index shifting performance issues in `items` or `itemsIndexed`.
