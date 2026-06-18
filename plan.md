1. Modify `ProxyServiceState.kt`:
   - Introduce `data class LogEntry(val id: Long, val text: String, val isError: Boolean, val isWarning: Boolean, val isSuccess: Boolean, val isHeader: Boolean)`
   - Add a private `logCounter = 0L` to `ProxyServiceState` object.
   - Change `_logs` from `MutableStateFlow<List<String>>` to `MutableStateFlow<List<LogEntry>>`
   - Update `logs: StateFlow<List<LogEntry>>`.
   - Update `addLog(msg: String)` to parse the message, increment `logCounter`, construct a `LogEntry`, and append it to `_logs` (respecting `MAX_LOG_LINES`).
   - Leave `file.appendText("$msg\n")` unchanged so it writes raw strings to log files.
2. Modify `ProxyViewModel.kt`:
   - Change `val logs: StateFlow<List<String>>` to `StateFlow<List<LogEntry>>`
3. Modify `LogsScreen.kt`:
   - Change `itemsIndexed(logs, key = { index, _ -> index }) { _, line -> LogLine(line = line) }` to `items(logs, key = { it.id }) { line -> LogLine(line = line) }`.
   - Update `exportLogs()` inside `LogsScreen` (if there's any clipboard copying) to map `logs` back to strings: `logs.joinToString("\n") { it.text }`.
   - Modify `LogLine(line: LogEntry)` to consume the pre-computed booleans (`isHeader`, `isError`, `isWarning`, `isSuccess`) instead of parsing string every recomposition.
   - Remove `val lower = line.lowercase()` and string parsing logic from `LogLine`.
4. Run formatting, lint, and tests.
