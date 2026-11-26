# Routine Planner App: Technical Specification

Android-only v1. A routine is an ordered list of timed tasks laid out on a minute-grid. The app plans it, runs it live, and fires alarms at task boundaries.

## 1. Product summary

- **Plan mode:** build a routine on a grid where each square is one minute. Pinch to change columns (10, 8, 6, 5). Drag tasks in from a tray, move them, and resize them.
- **Run mode:** a live view with a time cursor, past minutes in full colour and future minutes washed out, plus a water-fill box and red countdown for the current task.
- **Alarms:** the app schedules its own exact alarms at each task start.
- **Out of scope for v1:** iOS, cloud sync, accounts, editing while a routine is running, adaptive rescheduling when running late.

## 2. Tech stack

| Layer | Choice | Reason |
| --- | --- | --- |
| Language | Kotlin | Native Android, first-class API access |
| UI | Jetpack Compose + Canvas | Custom grid drawing and gestures |
| Architecture | MVVM, unidirectional data flow (ViewModel + StateFlow) | Testable, simple |
| Storage | Room (SQLite) | Local, offline, relational |
| DI | Hilt | Optional but tidy |
| Alarms | AlarmManager (exact alarms) | Survives Doze, full control |
| Alerts | NotificationManager + full-screen intent | Alarm screen over lock screen |
| Background | BroadcastReceivers (alarm, boot, time change) | Fires and reschedules |
| Fonts | DSEG seven-segment font | Digital countdown look |
| Testing | JUnit for logic, Compose UI tests for gestures | Core logic is pure Kotlin |

Flutter and React Native are ruled out: exact alarms, boot receivers and lock-screen behaviour need native APIs, and the grid needs low-level canvas and gesture control.

## 3. Core principle

**Store only an ordered list of tasks with durations. Derive everything else.**

- A task's start minute = sum of the durations of all earlier tasks.
- The grid is only a way to draw that timeline: `row = minute / cols`, `col = minute % cols`.
- Run state is derived from the wall clock, the routine start and the task list. Nothing about progress is stored, so the app resumes correctly after being killed.
- "Push later tasks forward" needs no special logic: inserting, moving or resizing changes the list, and the later starts recompute.

## 4. Data model (Room)

```kotlin
@Entity data class Routine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startMinuteOfDay: Int,     // minutes since midnight
    val endMinuteOfDay: Int,       // minutes since midnight; the window is fixed
    val daysOfWeek: Int,           // bitmask, Mon=1, Tue=2, Wed=4...
    val enabled: Boolean = true,
    val useBuffer: Boolean = true, // false = tasks keep their minimums, spare time stays empty
    val scheduledDate: String? = null // ISO yyyy-MM-dd for a one-off; null = weekly
)

@Entity(foreignKeys = [
    ForeignKey(entity = Routine::class, parentColumns = ["id"],
        childColumns = ["routineId"], onDelete = ForeignKey.CASCADE)])
data class PlacedTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routineId: Long,
    val minutes: Int,              // the task's MINIMUM length
    val position: Int,             // order within the routine
    val name: String,              // the task owns its own name
    val color: Int,                // ARGB
    val alarmEnabled: Boolean = false
)

@Entity(tableName = "stock_tasks")
data class StockTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val minutes: Int,              // default duration
    val color: Int,                // ARGB
    val icon: String,              // stable key, mapped to a Material icon in the UI
    val alarmEnabled: Boolean = false,
    val position: Int = 0          // display order in the picker
)
```

**A routine is either weekly or a one-off.** `Routine.scheduledDate` holds an ISO `yyyy-MM-dd` string for a one-off routine and is `null` for a weekly recurrence; `Routine.isRecurring` is the derived flag. The two are mutually exclusive: picking a date clears `daysOfWeek` to `DaysOfWeek.NONE`, and picking a weekday clears the date. A one-off runs on that single date and then disables itself — `AlarmReceiver` calls `setRoutineEnabled(id, false)` once `RoutineDates.hasExpired` is true, so it never fires again. `AlarmSchedule.nextRoutineStart` returns the single date's start (or `null` once it has passed) before falling through to the weekly loop. Storing the date as text keeps `domain/` free of Android types and lets it sort and compare as a plain string. `MIGRATION_4_5` adds the column as nullable, so every existing routine stays weekly.

**Stock tasks are a user-owned shortcut list, not a task library.** `stock_tasks` holds the reusable steps the user builds routines from (wake up, shower, get dressed, breakfast, brush teeth by default). They are offered as one-tap presets in the add-task dialog on the plan screen and in the routine creation screen, filling in the name, colour, duration and alarm. They are managed on the settings screen, where the user can add, edit and delete as many as they like; deleting them all is allowed and is not undone by a re-seed, because `StockTaskRepository.seedDefaultsIfEmpty()` only seeds when the table is empty on first run. Each stock task carries an `icon` key from `IconKeys`, mapped to a Material icon by `ui/settings/StockTaskIcons.kt`, so the data layer never references Compose. `MIGRATION_4_5` creates the table and also adds the missing `placed_tasks.alarmEnabled` column, which the domain type had always carried but the entity had silently dropped.

**There is no task library.** An earlier draft had a `TaskTemplate` table feeding a bottom tray; it was removed. A task carries its own `name` and `color`, typed and picked when the user adds it. `MIGRATION_2_3` drops the old `task_templates` table and copies each task's template name and colour onto the task itself.

Store a time as minutes-since-midnight to avoid a `LocalTime` converter.

**The routine is a fixed window.** `capacity = endMinuteOfDay - startMinuteOfDay` (day-wrap handled; a zero-length window is treated as a full day). `PlacedTask.minutes` is a **minimum**, not the length the grid draws. The spare time — `buffer = capacity - sum(minutes)` — is distributed across the tasks **proportionally to each task's minimum**, using the largest-remainder method, so the rendered lengths always sum to exactly `capacity` and the grid is a complete partition with no empty cells. See [`domain/Buffer.kt`](../app/src/main/java/com/adhd/routineplanner/domain/Buffer.kt).

**Buffers are optional per routine.** `Routine.useBuffer` (default `true`) is the switch. `Buffer.layout(tasks, capacity, useBuffer)` short-circuits when `useBuffer` is `false`: every task is rendered at its minimum, the spare time is still reported as `buffer` but is **not** handed out, and `overrun` is clamped to `0`. The grid then draws the full window with the leftover time as empty cells at the end. The buffer maths is never removed, only bypassed, so every caller and unit test keeps working and the no-buffer path still returns a valid `Layout`. `Schedule.startMinutes`, `Schedule.renderedMinutes`, `runState`, `RunViewModel`, `GridCanvas` and `PlanScreen` all thread `routine.useBuffer` through, so the plan grid, the run screen and the alarm start minutes agree. `MIGRATION_3_4` adds the column with `DEFAULT 1`, so existing routines keep their stretched layout on upgrade.

**Durations are free-form.** The clock-face picker accepts any minute value from 1 up; the old `Durations.snap()` ladder is no longer applied to task durations. The picker clamps to `Buffer.maxDurationFor(capacity, otherTasksMinimums)` so a task cannot claim more than the window can hold.

**The wheels are physically bounded, not clamped after the fact.** `TimeWheel` takes a `maxValue` and filters its value list to `values.filter { it <= maxValue }` before building the `LazyColumn`. Because the wheel only ever contains legal values, it cannot be scrolled past the available window at all — there is no "scroll to 90 then snap back to 45" behaviour. The shared `DurationWheels` composable derives the per-wheel caps from `maxMinutes`: the hours wheel offers `0..(cap / 60)`, and the minutes and seconds wheels offer `0..59` when `cap >= 60`, otherwise `0..cap`. Both `TaskCreateDialog` and `TaskEditDialog` use this same composable, so the create and edit experiences are identical.

## 5. Package structure

```
app/
  data/
    db/            AppDatabase, RoutineDao, PlacedTaskDao
    model/         entities above
    RoutineRepository.kt
  domain/
    Buffer.kt          // proportional buffer distribution, maxDurationFor
    Schedule.kt        // rendered start times, colour-per-minute lookup
    RunState.kt        // pure function: routine + tasks + clock -> run state
    Reorder.kt         // insertionIndex, move, resize
    Drag.kt            // slot resolution against rendered boundaries
    TimeParts.kt       // clock-face hours/minutes/seconds
  alarm/
    AlarmScheduler.kt  // schedule / cancel / rescheduleAll
    AlarmReceiver.kt   // fires, posts notification, schedules next
    BootReceiver.kt    // BOOT_COMPLETED, TIME_SET, TIMEZONE_CHANGED
    AlarmActivity.kt   // full-screen alarm UI
  ui/
    plan/   PlanScreen, GridCanvas, TaskTray, TaskEditDialog, DragOverlay
    run/    RunScreen, RunGrid, WaterBox, Countdown
    routines/ RoutineListScreen, RoutineSettings, RoutineCreateSheet
    templates/ ColorPicker   // reusable swatch/slider widget only
    theme/  Color, Type, Theme
  viewmodel/ PlanViewModel, RunViewModel, RoutineListViewModel
  MainActivity.kt, App.kt (Hilt)
```

`domain/` is plain Kotlin with no Android imports, so it is unit-testable.

## 6. Data flow

UI emits an event, the ViewModel updates state and calls the Repository, the Repository writes to Room and calls `AlarmScheduler`, and Room's Flow pushes the new state back to the UI.

During a drag, the working list lives in ViewModel memory only. **Commit to Room and reschedule alarms only on release**, never on every move.

## 7. Plan mode

### 7.1 Grid rendering

One `Canvas` draws everything. Build a `minute -> task` lookup, then draw a rect per cell in its task colour. A task that wraps a row boundary is drawn as one rounded rect per row segment. Draw the name in the first segment, clipped, and minutes when zoomed in. Faint guide lines every 5 or 10 minutes, and a clock label per row at the left (`routineStart + row * cols`).

### 7.2 Zoom

Column steps are `[10, 8, 6, 5]`. Accumulate pinch scale with `detectTransformGestures`; past 1.25 step towards fewer columns, below 0.8 step back, reset the accumulator and fire a haptic. Animate cell size with `animateDpAsState`. Only `cols` changes, never the data.

### 7.3 Interactions

| Interaction | Gesture | Behaviour |
| --- | --- | --- |
| Add | Tap the + button in the top bar | Opens the task creation dialog (name, colour, clock-face duration); the new task is appended. Disabled while a run is active, while the routine is over-subscribed, or when the window is full |
| Move | Long-press placed task, drag | Remove from list, show live preview with others sliding forward, reinsert at drop |
| Select and resize | Tap placed task | Right-hand strip opens: vertical bar, 1 minute = fixed height, drag handle at the bottom. Minimum 1 minute. Strip scrolls for long tasks. Grid updates live |
| Delete | Drag to a trash zone or use the selection menu | Remove from list |

```kotlin
fun cellAt(pos: Offset, cellPx: Float, cols: Int) =
    (pos.y / cellPx).toInt() * cols + (pos.x / cellPx).toInt()

fun insertionIndex(tasks: List<PlacedTask>, minute: Int): Int {
    var start = 0
    tasks.forEachIndexed { i, t ->
        if (minute < start + t.minutes) return i
        start += t.minutes
    }
    return tasks.size
}
```

### 7.4 Behaviour decisions

- **No gaps.** The routine is a fixed window and the buffer is distributed across the tasks, so the grid has no empty cells. Spare time is not a task the user creates.
- **The grid always shows the whole window.** `GridCanvas` sizes itself to `maxOf(capacityMinutes, Schedule.renderedMinutes(window, placed))`. With no tasks the buffer layout is empty, so without this the grid would collapse to a few rows; the `maxOf` keeps the full routine window visible (for example a 1 hour routine always draws 60 cells) even before the first task is added. The extra rows are the over-subscribed case only when the minimums genuinely exceed the window.
- **Deadline overflow:** if the minimums exceed the window, the cells past the routine end are tinted red.
- **Undo:** snackbar after every committed edit.
- **Haptics:** light tick on each cell crossed while dragging.

## 8. Run mode

### 8.1 Time source

Recompute from the wall clock every second, never count ticks:

```kotlin
val nowMs by produceState(System.currentTimeMillis()) {
    while (true) { value = System.currentTimeMillis(); delay(1000 - value % 1000) }
}
```

`runState(tasks, startMs, nowMs)` returns elapsed seconds, current task index (-1 not started, `size` finished), seconds remaining in the task and progress 0..1.

### 8.2 Screen layout

Top: header box with task name, water fill and red `-M:SS`. Middle: grid, vertically centred; if it exceeds the screen, scroll so the cursor's row stays centred. `keepScreenOn` while open.

### 8.3 Grid colouring

- Past minutes: full task colour.
- Future minutes: `lerp(color, background, 0.7f)`.
- Current minute: washed out, with the left fraction filled bright up to the cursor.
- Thin borders lighter than the background.

### 8.4 Cursor

Position is fractional minutes: `x = (elapsedMin % cols) * cellPx`, so it moves 1/60 of a cell per second. Smooth with `animateFloatAsState(tween(1000, LinearEasing))`. It wraps to the next row at the row end.

### 8.5 Water box

Canvas clipped to a rounded rect: washed colour background, bright water body of height `progress * boxHeight` rising from the bottom, and a sine wave on the surface driven by an infinite phase animation (about 3 s per cycle). Task name in white with a subtle shadow on top. At a task boundary the box resets and the name swaps.

### 8.6 Countdown

Red `-M:SS` in the DSEG font (or tabular numerals) to prevent digit jitter.

### 8.7 Edge cases

- Before start: "Starts in -M:SS", whole grid washed out.
- After end: "Done", whole grid in full colour.
- Clock or timezone change: self-corrects on the next tick.
- Editing is disabled while a routine is running.

## 9. Alarms

The app owns its alarms through `AlarmManager`. Using the system Clock app's `ACTION_SET_ALARM` is not suitable: alarms can't be reliably edited or deleted, and each needs user confirmation.

- **Scheduling:** `setExactAndAllowWhileIdle` (or `setAlarmClock`) with a `PendingIntent` per task. Request code derived from the routine id and task index so it can be cancelled or updated.
- **Only the next occurrence** per task is scheduled. When an alarm fires, `AlarmReceiver` posts the notification and schedules the next one (next task, or the next valid day's routine start).
- **Granularity is per task.** `PlacedTask.alarmEnabled` is the only switch; there is no routine-level alarm mode. `AlarmScheduler` schedules exactly the tasks with `alarmEnabled = true`, at their **rendered** start minutes (`Schedule.startMinutes(routine, tasks)`), so an alarm matches the block the user sees.
- **The alarm switch is editable everywhere.** The create dialog and the edit dialog both show the same `AlarmToggle` (🔔/🔕, "Alarm at the start of this task"). The edit dialog seeds it from `task.alarmEnabled` and writes back through `PlanViewModel.setTaskAlarm(taskId, enabled)` on save, so a task's alarm state can be inspected and changed after creation, not only when it is first made.
- **Reschedule triggers:** routine saved, toggled or deleted; `BOOT_COMPLETED`; `TIME_SET`; `TIMEZONE_CHANGED`. All of these call `rescheduleAll()`, which cancels and rebuilds from the database.
- **Alarm UI:** high-priority notification with a full-screen intent showing "Next: Shower (10 min)".
- **Test alarm fires immediately, then again a minute later.** The checklist's "Test alarm now" button does two things: it launches `AlarmActivity` directly (via `startActivity`, `FLAG_ACTIVITY_NEW_TASK`) so the alarm screen and tone appear instantly as proof the UI and audio work, and it also schedules a real `setAlarmClock` one minute ahead whose show intent is `AlarmActivity` (not `MainActivity`). This removes the earlier confusion where the test appeared to do nothing for about three minutes and then only produced a notification. The scheduled-time line reads "Real alarm scheduled for HH:MM" when exact alarms are available and "Real alarm requested for HH:MM" when the inexact fallback is used.
- **The delayed test alarm uses its own task index.** `AlarmActivity` is declared `launchMode="singleInstance"`, so the immediate preview creates the one and only instance. If the delayed alarm reused the same task index, its full-screen intent would be delivered to that already-open instance and no fresh screen or tone would appear. The preview therefore uses `TEST_PREVIEW_TASK_INDEX` (`-1`) and the delayed alarm uses `TEST_DELAYED_TASK_INDEX` (`-2`), giving the delayed `PendingIntent` a distinct identity. `AlarmReceiver.postTestNotification` also calls `startActivity` on the alarm screen directly (inside a try/catch that swallows background-start restrictions) as a belt-and-braces guarantee that the delayed alarm is actually seen and heard, in addition to the full-screen intent.

### Permissions

| Permission | Why |
| --- | --- |
| `SCHEDULE_EXACT_ALARM` (Android 12+) | Exact alarms; user grants in settings. `USE_EXACT_ALARM` is an alternative but Play policy is strict |
| `POST_NOTIFICATIONS` (Android 13+) | Notifications |
| `USE_FULL_SCREEN_INTENT` | Alarm screen over lock screen |
| `RECEIVE_BOOT_COMPLETED` | Reschedule after reboot |
| `WAKE_LOCK`, `VIBRATE` | Alarm behaviour |

Use a first-run flow that explains and requests each permission in context, and a settings banner if exact alarms are denied.

## 10. Theme and colours

- Background near-black (`#0E0F12`), cells on the dark background, grid borders about `#2A2D33` at 0.5 to 1dp.
- About 12 stock colours tuned for dark (`StockTaskColors`). A new task takes the next unused one.
- Colour picker: swatch row plus custom hue and brightness sliders, saved to `PlacedTask.color`.
- `washed()` blends any colour toward the background, so user-picked colours need no extra variants.

## 11. Known risks

- **Manufacturer battery killers** (Xiaomi, Samsung and others) can suppress alarms: add an in-app prompt for battery-optimisation exemption.
- **Exact alarm permission denial:** fall back to inexact alarms with a visible warning.
- **Gesture conflicts:** pinch, long-press drag and scroll share one surface. Use explicit gesture states and test on small screens.
- **Play Store policy:** exact alarm and full-screen intent permissions are reviewed, so justify them as core functionality.

## 12. Build order

1. Domain logic and unit tests: schedule, buffer distribution, insertion, move, resize, run state.
2. Static grid rendering from a hardcoded list.
3. Pinch zoom with column snapping.
4. Resize strip with continuous pinch/drag resizing.
5. Move with live preview, then tray drag-in.
6. Room and repository.
7. AlarmScheduler, receivers, boot handling, permissions flow.
8. Run mode: cursor, colouring, water box, countdown.
9. Colour picker, routine creation sheet, polish, undo, haptics.
10. Device testing across manufacturers and Android versions.

## 13. Later versions

Adaptive scheduling (finish early or late and the rest shifts), editing during a run, backup and export, iOS, cloud sync, widgets, and routine sharing.

# Part 2: Detailed Grid, UI and Device Specification

This part extends sections 1 to 13 and supersedes them where they differ. Section 11 (Known risks) is replaced by section 17.

## 14. Grid specification

### 14.1 Model

- 1 cell = 1 minute. A routine of `T = capacityMinutes` minutes has exactly T cells in reading order (left to right, top to bottom). `rows = ceil(T / cols)`.
- `cols` is one of 10, 8, 6 or 5 (the zoom steps). Default is 10 each time a routine is opened.
- Cells are square: `cellDp = (gridWidthDp - gutterDp) / cols`. The gutter is a 44dp left column for clock labels.
- On the A54 (roughly 400dp wide in portrait) a cell is about 30dp at 10 columns up to about 60dp at 5 columns.
- **There are no empty cells.** The buffer is distributed across the tasks, so the rendered lengths sum to exactly `capacity` and every cell belongs to a task. The grid is a complete partition of the routine window. `GridCanvas` takes `capacityMinutes` and builds its layout from `Buffer.layout(tasks, capacityMinutes)`.
- Maximum routine length is 1440 minutes. A routine may cross midnight: clock labels wrap at 24h and alarms use absolute dates.

### 14.2 Draw order (single Canvas)

1. Background
2. Empty-cell fill with thin borders (only for the over-subscribed case, where the minimums exceed the window)
3. Task cell fills
4. Task outlines and names
5. Guide lines every 5 or 10 minutes
6. Overflow tint (cells past the routine end, in red)
7. Selection outline
8. Drag preview and insertion marker
9. Run mode only: washed overlay for future minutes, progress fill in the current minute, cursor

Row clock labels are drawn in the left gutter: `routineStart + row * cols` minutes.

### 14.3 Zoom levels

| Columns | Approx cell | Each block shows |
| --- | --- | --- |
| 10 | 30dp | Colour, truncated name on its first segment |
| 8 | 38dp | Name |
| 6 | 50dp | Name and minutes |
| 5 | 60dp | Name, minutes and start time |

Pinch behaviour:

- Pinch in (fingers together) gives fewer columns and bigger cells; pinch out the reverse.
- Accumulate scale from `detectTransformGestures`. Crossing 1.25 steps towards fewer columns, crossing 0.8 steps back. Reset the accumulator after each step and fire a haptic tick.
- Keep the minute under the fingers' focal point stationary by adjusting the scroll offset after each step.
- Animate cell size over about 200ms.
- Only `cols` changes. The data is never touched.

### 14.4 Block rendering

- A task of `m` **rendered** minutes starting at minute `s` covers `[s, s + m)`. `m` comes from `Buffer.layout(...).slots[i].minutes`, never from `task.minutes`. Per row it forms one contiguous segment, drawn as one rounded rect.
- Corners are rounded only at the true start and true end of the task. Edges where the task wraps to the next row are square.
- Cell borders are 0.5dp, lighter than the background (about `#2A2D33`).
- The name is drawn in the first segment, clipped and ellipsised. If the first segment is only the last cell of a row, draw the name in the next row's segment instead.
- Tasks of 1 or 2 minutes get no label but are still selectable.
- Label colour (white or near-black) is chosen from the task colour's luminance so custom colours stay readable.

### 14.5 Hit-testing

Pixel to `(row, col)`, then `minute = row * cols + col`, then the task via a prefix-sum array and binary search. The prefix sums are the **rendered** starts (`Schedule.startMinutes(routine, tasks)`), so hit-testing agrees with what is drawn. The arrays `startMinutes[]` and `colorOfMinute[]` are rebuilt only when the task list or the routine window changes.

### 14.6 Scrolling

The Canvas sits in a vertical scroll container sized `rows * cellPx` and draws only the visible rows. In plan mode scrolling is user-driven. In run mode it is automatic and keeps the cursor row vertically centred.

### 14.7 Grid capabilities

| Action | Gesture | Result |
| --- | --- | --- |
| Zoom | Two-finger pinch | Columns step 10, 8, 6, 5 |
| Scroll | One-finger vertical swipe | Scrolls the grid |
| Select | Tap a block | Block outlined, resize strip opens |
| Deselect | Tap empty area | Strip closes |
| Add | Tap the + button in the top bar | Opens the task creation dialog; the new task is appended. Disabled while running, over-subscribed, or full |
| Move | Long-press a block, then drag | Reorders; later tasks shift forward |
| Resize | Two-finger pinch on the strip, drag the strip handle, or the +/- buttons | Changes minutes; later tasks shift |
| Delete | Drag a block to the tray, or the strip's delete button | Removes the task |
| Undo | Snackbar after any committed edit | Restores the previous list |

Rules: no gaps between tasks (the buffer fills them), durations are free-form minimums clamped to the routine's remaining capacity (§15.4), and every committed edit renumbers `position` and reschedules alarms.

## 15. UI by screen

### 15.1 Navigation

Single activity with Compose Navigation: RoutineList, then RoutineCreate, Settings, Plan(routineId) and Run(routineId). Routine creation is a **full page** (`Routes.ROUTINE_CREATE`), not a sheet, and settings is its own destination (`Routes.SETTINGS`); both are reached from the routine list. On save the create screen is popped and replaced by the new routine's plan, so Back from the plan returns to the list rather than the form. The task editor and routine settings remain bottom sheets. The permissions checklist is reachable from the list's overflow menu and shown on first launch. `AlarmActivity` is separate and launched by the full-screen intent. Portrait only in v1.

### 15.2 Routine list

- The page is **all black**: the scaffold and the top bar both use `Background`, with no coloured surfaces.
- The top bar holds the "Routines" title, a settings icon whose dropdown offers **Settings** and **Alarm setup**, and the **+** new-routine button to the right of it. There is no FAB.
- Each card shows the name with the enable switch on the right, then the schedule, then a three-column row of **Start**, **End** and **Length**. There is no per-card overflow menu and no Run button: deletion is by swipe, and running is started from the plan screen.
- The schedule line is the seven **MTWTFSS** letters, set close together (6dp apart) at a single size, greyed out when the day is inactive or the routine is disabled, and bright with a small dot above when active. A one-off routine shows its date instead, formatted as `Tue 25 Nov`.
- Tap a card to open Plan. Swipe to delete with an undo snackbar.
- Enabled routines start automatically at their start time on their active days; a one-off starts on its single date and then disables itself.

### 15.3 Plan screen layout

```
+---------------------------+------+
| top bar: back, name,      |      |
| start chip, length, +, Run|      |
+---------------------------+  R   |
| clock |  GRID (scrolls)   |  E   |
| labels|                   |  S   |
|       |                   |  I   |
|       |                   |  Z   |
|       |                   |  E   |
+---------------------------+------+
| tray:  drag a task here to remove|
+----------------------------------+
```

The resize strip column exists only while a block is selected.

- **Top bar:** back, routine name (tap opens settings), start-time chip, total length and end time, a **+** add-task button, Run button, overflow (routine settings, **Grid view / List view**, zoom -/+). The **+** is the primary way to add a task: it is always visible in the top bar rather than hidden in the bottom tray, and it is disabled (greyed) while a run is active, while the routine is over-subscribed, or when the window is full.
- **List view** ([`ui/plan/TaskListEditor.kt`](../app/src/main/java/com/adhd/routineplanner/ui/plan/TaskListEditor.kt)) is an alternative to the grid, toggled from the overflow menu. It shows each task's core information — colour bar, name, alarm icon, start time and duration — and reorders by long-press and drag, mapping the move onto the same `moveTask` call the grid's drag uses so it goes through the same undo snapshot. Tapping a row opens the task editor. The grid remains the default because it is the only way to see how a routine fills its window; the list is the easier way to read and reorder a long routine. The toggle is hidden while a run is active.
- **Tray:** no longer holds an add button. It is purely a drag-to-remove target: it reads "Drag a task here to remove it" at rest and turns red with "Release to remove" while a block is dragged over it. There is no task library; the top-bar **+** opens the task creation dialog (§15.6), where the user types a name, picks a colour and sets a duration on the clock face. The new task is appended to the routine.

### 15.4 Task editor and resize strip

- The resize strip opens on the right when a block is selected. The grid reflows into the narrower area (cell size recalculated, animated about 150ms).
- Width 72dp. From the top: close and delete buttons, task name, large minute count with -1 and +1 buttons, then the vertical ruler.

**Durations are free-form.** The old fixed ladder (`5, 10, 15, 20, 25, 30, 45, 60, 90, 120`) is gone. A task's duration is any whole number of minutes from 1 up, chosen on a **clock face** — the same `TimeWheel` control used by the task editor and the creation sheet. The old −1/+1 "Adjust" block and the Earlier/Later buttons have been removed from the edit dialog; the clock face is the single way to set a duration, and the resize strip (§15.4) remains the way to fine-tune a placed task.

**The duration is a minimum.** The routine window is fixed, so the grid draws the task's *rendered* length, which includes its share of the buffer (§4). The editor shows how much room is left:

- `TaskEditDialog` and `TaskCreateDialog` take a `maxMinutes` from `Buffer.maxDurationFor(capacity, otherTasksMinimums)`.
- The wheels only offer values up to `maxMinutes` (the value list is filtered, so the wheel cannot scroll past the limit), and the dialog shows "Up to N min available" or "Routine is full at N min".
- `PlanViewModel.setTaskDuration` itself clamps only to ≥1; over-subscription is surfaced in the UI rather than silently truncated.

**Ruler.** One tick per minute, 44dp apart, labelled every 5 minutes. A bar in the task colour runs from the top to the 48dp handle at the current position.

**Resizing is continuous and reversible.** There is no "one gesture = one step" behaviour. Two input methods, both of which keep changing the duration for as long as the finger keeps moving, and both of which reverse immediately if the user reverses direction mid-gesture without lifting:

1. **Two-finger pinch anywhere on the strip.** Spreading the fingers lengthens the task, pinching them together shortens it. The mapping is logarithmic — `steps = ln(accumulatedScale) / ln(1.25)` — so a 25% change in finger separation is one minute, matching the grid's own zoom threshold, and so pinching in and back out returns to exactly the starting duration. The accumulated scale and the starting duration are captured on gesture start, so the mapping is always relative to where the fingers went down.
2. **Drag the handle up or down.** The strip accumulates total finger travel from the gesture origin rather than per-event deltas, and maps it to a minute at 44dp per minute. Dragging down then back up within one gesture walks down then up.

The grid updates live from ViewModel working state, with a haptic tick on each minute change. The edit commits on release; releasing at the same duration is a no-op and shows no undo snackbar.

- Long tasks make the strip taller than the screen: it has its own scroll and auto-scrolls when the handle is within 64dp of the bottom edge.

### 15.5 Drag and drop

**Start:** long-press for about 350ms with a haptic. For a placed block, the original cells show a faint dashed placeholder.

**While dragging:**

- A ghost (name and minutes, scaled 1.05, elevated) floats about 40dp above the finger so the finger doesn't hide it.
- The hovered minute maps to an insertion index. The layout is recomputed with the task inserted there, and later blocks animate sliding forward (about 150ms). A bright insertion marker shows at the boundary.
- Within 72dp of the grid's top or bottom edge the grid auto-scrolls.
- Dragging a placed block over the tray turns the tray red (Remove).

**Drop:**

- Over a task: insert before it. Below the last task: append.
- On the grid: commit, persist, reschedule alarms. On the tray: delete. Anywhere else: cancel, no change.
- A second finger, back gesture or system cancel also cancels the drag.

Slot resolution uses the **rendered** boundaries (`Drag.boundariesOf(routine, tasks)`), so a drop lands where the user sees the block edge, not where the minimums would put it.

### 15.6 Routine creation page and task editor

**Routine creation page** ([`ui/routines/RoutineCreateScreen.kt`](../app/src/main/java/com/adhd/routineplanner/ui/routines/RoutineCreateScreen.kt)) — a full-page destination, not a sheet, laid out in the order the user thinks about a routine:

- **Name** field.
- **When** — start and end time pickers with a live summary: `N min window · M claimed · K spare`.
- **Date** — a row showing the one-off date (defaulting to tomorrow) with a **calendar icon** on the right. Tapping it opens a `DatePickerDialog`; its dismiss row has a **Recurring** button that clears the date. A dated routine is a one-off that runs once and then disables itself.
- **Repeat every week** — the seven MTWTFSS toggles. Tapping any of them clears the one-off date and makes the routine recurring; with none highlighted it stays a one-off. The date and the weekdays are mutually exclusive by construction.
- A **Use buffer** switch, matching the one in routine settings: on spreads spare time across the tasks, off keeps every task at its minimum and leaves the spare time as empty grid cells.
- A staged task list. Each row shows a colour dot, name and minutes. **Long-press and drag to reorder**; tap to edit; a remove button deletes the row.
- An **Add task** row opens `DraftTaskDialog`, which mirrors `TaskEditDialog` exactly: name field, colour swatches with the `SelectionOutline` selection ring, the shared clock-face `DurationWheels` clamped to the time still available in the window, an "Up to X min available" line, and the same `AlarmToggle`. When creating (not editing) it also lists the user's **stock tasks** as one-tap presets that fill in the name, colour, duration and alarm. The available cap is `capacity - claimed` for a new task and `capacity - (claimed - draft.minutes)` when editing a staged task, floored at `MIN_TASK_MINUTES`.
- Back and Create. Create maps each `DraftTask` to a `PlacedTask` (including `alarmEnabled`) and calls `RoutineCreateViewModel.create`, which delegates to `RoutineRepository.insertRoutineWithTasks` (that method renumbers positions from list order). The screen is then popped and replaced by the new routine's plan.

**Task creation dialog** (`TaskCreateDialog`) also lists the stock tasks as presets above the name field, so the same shortcuts are available when adding a task to an existing routine from the plan screen.

**Task editor** (`TaskEditDialog`) — the same fields for a task already on the grid: name, colour, clock-face duration clamped to `maxMinutes`, an **alarm toggle** identical to the one in the creation dialog (seeded from `task.alarmEnabled`, saved through `setTaskAlarm`), and delete. The old Adjust buttons (−1 min / +1 min, Earlier / Later) are gone; the clock face and the resize strip cover duration changes.

### 15.7 Routine settings (bottom sheet)

Name, **start time and end time** (two time pickers, with a live `N min window` line), a **Date** row with a calendar button that sets a one-off date or clears it back to weekly, active days (7 toggles, greyed while a one-off date is set), enabled switch, and a **Use buffer** switch. The buffer switch's subtitle explains the current state: "Spare time is shared out across tasks" when on, "Tasks keep their minimum; spare time stays empty" when off. The routine-level alarm mode is gone: alarms are per task (`PlacedTask.alarmEnabled`).

### 15.7a Settings page

Reached from the routine list's overflow menu. The first (and currently only) section is **Stock tasks**: a list of the user's reusable tasks, each row showing its icon, name, default duration and whether it alarms, with a delete action. An **Add stock task** row opens a dialog with a name field, an icon grid over `IconKeys.ALL`, the shared colour swatches, the clock-face duration wheels and an alarm switch. The user can add, edit and delete as many as they like. The defaults seeded on first run are Wake up (1 min, alarm), Shower (10), Get dressed (5), Breakfast (15) and Brush teeth (3).

### 15.8 Run screen

- **Header box:** 110dp tall, 16dp corners. Washed task colour as background, bright water rising from the bottom by `taskProgress`, animated sine wave on the surface. Task name centred in white with a subtle shadow, red `-M:SS` below it in the DSEG font.
- **Grid:** vertically centred, past minutes full colour, future minutes washed out, current minute filled up to the cursor, thin vertical cursor line moving 1/60 of a cell per second.
- **Exit:** an X button (also back) opens a confirm dialog. Confirming stops the run and cancels the remaining alarms.
- **States:** before start shows Starts in -M:SS with everything washed out; running; finished shows Done with everything in full colour.
- **At a task boundary:** water drains and refills over about 300ms, the name crossfades, and a haptic fires.
- Screen stays on. No editing while a run is active.

### 15.9 Alarm screen

Launched over the lock screen (`setShowWhenLocked`, `setTurnScreenOn`). Shows the routine name and Next: task (N min), with a Dismiss button. A short tone plays with `USAGE_ALARM` and stops on dismiss or after about 5 seconds. Tapping the text opens the Run screen. If full-screen intents are not allowed, a heads-up notification with the same text and an Open action is used instead.

### 15.10 First-run checklist

One row per requirement with a status tick and a button that deep-links to the right settings page: notifications, exact alarms, full-screen alerts, battery set to Unrestricted, and not in Samsung's sleeping apps. Statuses re-check on resume. A **Test alarm now** button sits at the bottom: it opens the alarm screen and plays the tone immediately, and also schedules a real alarm one minute ahead so the full end-to-end path (AlarmManager → receiver → full-screen intent) can be verified. Because `AlarmActivity` is `singleInstance`, the delayed alarm uses a different task index from the immediate preview and the receiver also starts the screen directly, so the second alarm really does appear and sound rather than being swallowed by the already-open preview instance.

### 15.11 Visual tokens

| Token | Value |
| --- | --- |
| Background | `#0E0F12` |
| Surface | `#16181D` |
| Grid line | `#2A2D33` |
| Primary text | `#E8EAED` |
| Secondary text | `#9AA0A6` |
| Countdown and overflow red | `#FF3B30` |
| Selection outline | `#FFFFFF` |
| Washed factor | `lerp(color, background, 0.7)` |

Stock task colours (avoiding pure red so it stays reserved for the countdown): `#4F8EF7`, `#34C7A1`, `#F2994A`, `#9B6BF2`, `#F2C94C`, `#6FCF97`, `#56CCF2`, `#F28CB1`, `#BB8F6A`, `#8E9AAF`, `#A3D65C`, `#F26D6D`.

Touch targets are at least 48dp. Animations are 150 to 200ms, except the run cursor (1000ms linear). Haptics: tick per cell crossed and per zoom step, long-press when lifting a block, confirm on drop.

## 16. State and ViewModel contracts

```kotlin
data class PlanUiState(
    val routine: Routine,               // carries startMinuteOfDay + endMinuteOfDay
    val tasks: List<PlacedTask>,        // committed list; minutes are MINIMUMS
    val preview: List<PlacedTask>?,     // non-null while dragging or resizing
    val cols: Int,
    val selectedId: Long?,
    val gesture: GridGesture,
    val undo: List<PlacedTask>?
) {
    val capacityMinutes: Int            // end - start, day-wrap handled
    val totalMinutes: Int               // sum of minimums
    val bufferMinutes: Int              // capacity - totalMinutes
    val overrunMinutes: Int             // max(0, -bufferMinutes)
    fun maxDurationFor(taskId: Long?): Int
}

sealed interface PlanEvent {
    data class ZoomStep(val direction: Int) : PlanEvent
    data class Select(val taskId: Long?) : PlanEvent
    data class DragStart(val source: DragSource) : PlanEvent
    data class DragMove(val minute: Int?, val overTray: Boolean) : PlanEvent
    object DragDrop : PlanEvent
    object DragCancel : PlanEvent
    data class ResizePreview(val minutes: Int) : PlanEvent
    object ResizeCommit : PlanEvent
    object Undo : PlanEvent
}
```

**Commit pipeline:** snapshot the current list for undo, write the new list in one Room transaction (renumbering `position`), then call `AlarmScheduler.rescheduleRoutine(id)`. Undo restores the snapshot and reschedules. Previews never touch Room or alarms.

**Rendered timeline:** `PlanUiState` stores minimums, but every consumer that draws or times the grid derives rendered lengths from `Buffer.layout(tasks, capacityMinutes)`. `RunUiState` exposes `renderedMinutes: List<Int>` and `currentTaskMinutes` for the same reason, so the spoken description and the countdown agree with the drawn blocks.

**Run session:** the active run is stored as `(routineId, startMs)` in DataStore. Scheduled runs use today's occurrence of the routine start time. A manual Run uses the current time. `RunViewModel` combines that with the task list and a once-per-second clock flow, and derives all state through `runState()`, so a killed process resumes correctly.

## 17. Target device (Samsung Galaxy A54) and risk mitigations

- **Platform:** `minSdk 33` (the A54 shipped with Android 13). Target the latest SDK and test the Android 14+ alarm and full-screen rules, since One UI updates raise the Android version.
- **Distribution:** sideload the APK (USB from Android Studio). There is no Play review. If published later, declare alarm-app use in the Play Console and keep the notification fallback.

| Risk | Mitigation |
| --- | --- |
| Samsung battery management delays or kills alarms | Use `setAlarmClock()` (treated as a real alarm, exempt from Doze). Request `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. In Settings set the app to Battery > Unrestricted, turn off Put unused apps to sleep, and keep it out of Sleeping and Deep sleeping apps. The checklist screen checks and deep-links these |
| Exact alarms denied | Declare `USE_EXACT_ALARM` (auto-granted on Android 13+). Check `canScheduleExactAlarms()` and deep-link to Special app access > Alarms and reminders if needed |
| Full-screen intent revoked (Android 14+) | Check `NotificationManager.canUseFullScreenIntent()`, deep-link with `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`, and fall back to a high-priority heads-up notification |
| Do Not Disturb silences alarms | `USAGE_ALARM` audio attributes and `CATEGORY_ALARM` notifications |
| Alarms lost after reboot | `BOOT_COMPLETED`, `TIME_SET` and `TIMEZONE_CHANGED` receivers call `rescheduleAll()`. The app must have been opened once after install to receive these |
| Gesture conflicts | State machine below, plus the rules that follow |

```kotlin
sealed interface GridGesture {
    object Idle : GridGesture
    object Scrolling : GridGesture
    object Zooming : GridGesture
    data class DraggingTask(val taskId: Long) : GridGesture
    object Resizing : GridGesture
}
```

- Scrolling is the default. Task drags start only after a long-press (`detectDragGesturesAfterLongPress`), so ordinary swipes always scroll.
- Only consume pointer events after the long-press fires.
- A second finger during a drag cancels it and switches to Zooming. Zoom is ignored while dragging.
- Auto-scroll near the edges so scrolling and dragging never need to happen together.
- The resize strip is a separate surface with its own vertical drag.
- Samsung's gesture navigation uses left and right edge swipes for back, so keep about 24dp of horizontal padding on the grid or use `systemGestureExclusionRects`.

## 18. Accessibility, performance and privacy

- **Accessibility:** every gesture has a button equivalent (the top-bar **+** to add, +/- resize on the strip, zoom -/+ in the overflow menu, move earlier or later in the selection actions). Content descriptions on blocks read name, start time and length. Text respects the system font scale.
- **Performance:** draw only visible rows, precompute lookup arrays, reuse `Path` objects in draw code. In run mode, read the clock state inside the draw scope so only the Canvas redraws each second, not the whole screen. The wave animation lives only in the header Canvas.
- **Privacy:** all data stays on the device. The app declares no `INTERNET` permission.

## 19. Testing

**Unit tests (domain):** task start times; buffer distribution (proportional, largest-remainder, over-subscribed routines); `insertionIndex` at the first task and last task; move; resize clamping to 1 minute; `runState` before start, exactly on a boundary, in the final second and after the end; routines crossing midnight; DST changes.

**UI tests:** pinch steps columns and keeps the focal minute stable; a swipe scrolls while a long-press drags; a second finger cancels a drag; resize updates later tasks.

**Device tests on the A54:**

- Test alarm now (immediate screen + tone), then the scheduled real alarm 1 minute ahead with the screen off
- A 30 minute routine with the screen off and the phone unplugged
- Reboot, then confirm alarms are rescheduled
- Force Doze with `adb shell dumpsys deviceidle force-idle`
- Change time zone and system time
- Do Not Disturb on, and battery saver on
- App swiped away from recents

**v1 acceptance:** in all of the above, each alarm fires within a few seconds of its scheduled time.

## 20. Build order addition

Follow section 12, and build the first-run checklist and Test alarm button during step 7 so alarm reliability is verified on the A54 before run mode is built on top of it.

## 21. Revision log

- **Schema v5 / redesign.** `Routine.scheduledDate` adds one-off routines that run once and disable themselves; `stock_tasks` adds a user-owned shortcut list with icons, managed on a new settings page and offered in the add-task dialogs. `placed_tasks.alarmEnabled` is now actually persisted (it had been dropped by the entity). The routine list is all black with the add button beside the overflow menu, MTWTFSS letters with an active dot instead of day chips, and no per-card Run button. Routine creation is a full page with a date/calendar section and a weekly-repeat section. The plan screen gains a list view alongside the grid.

- **Time wheels bounded by available time.** `TimeWheel` gained a `maxValue` and filters its value list, so the h/m/s wheels cannot scroll past the routine's remaining capacity. A shared `DurationWheels` composable computes the per-wheel caps from `maxMinutes` and is used by both the create and edit dialogs (§4, §15.4).
- **Adjust controls removed.** The −1/+1 min and Earlier/Later buttons are gone from `TaskEditDialog`; the clock face and the resize strip are the only duration controls (§15.4, §15.6).
- **Alarm toggle parity.** The edit dialog now shows and edits the same per-task alarm switch as the create dialog, wired to `PlanViewModel.setTaskAlarm` (§9, §15.6).
- **Add-task affordance moved to the top bar.** A **+** button in the plan top bar is the primary add action; the bottom tray is now only a drag-to-remove target (§7.3, §14.7, §15.3).
- **Grid shows the full window.** `GridCanvas` renders `maxOf(capacityMinutes, renderedMinutes)`, so an empty routine still draws its whole window instead of collapsing to a few rows (§7.4, §14.1).
- **Test alarm fires immediately.** "Test alarm now" launches `AlarmActivity` straight away and schedules a real alarm one minute ahead whose show intent is `AlarmActivity`, fixing the earlier silent ~3 minute delay (§9, §15.10).
- **Startup crash fixes (Room).** `MIGRATION_2_3` now migrates `routines` before `placed_tasks` reads `alarmMode`, and the stale `alarmEnabled` column was removed from the `placed_tasks` migration so the schema matches `3.json` (§4).
- **Per-routine buffer switch.** `Routine.useBuffer` (default `true`) is stored in a new column added by `MIGRATION_3_4` (database version 4). `Buffer.layout` takes a `useBuffer` flag and, when it is `false`, renders every task at its minimum and leaves the spare time as empty cells instead of distributing it. The flag is threaded through `Schedule`, `runState`, `RunViewModel`, `GridCanvas` and `PlanScreen`, and a **Use buffer** switch appears in both routine settings and the creation sheet (§4, §15.6, §15.7).
- **Creation-sheet task dialog parity.** `DraftTaskDialog` now reuses the shared `DurationWheels` and `AlarmToggle` (made `internal` in `TaskEditDialog.kt`), the `SelectionOutline` colour swatches and the "Up to X min available" line, so adding a task during routine creation looks and behaves like the full task editor. `DraftTask` gained `alarmEnabled`, which `RoutineListScreen` now persists into the new `PlacedTask` (§15.6).
- **Delayed test alarm actually fires.** The immediate preview and the one-minute-later real alarm now use distinct task indices (`TEST_PREVIEW_TASK_INDEX` = −1, `TEST_DELAYED_TASK_INDEX` = −2) so the `singleInstance` `AlarmActivity` cannot swallow the second alarm, and `AlarmReceiver.postTestNotification` also starts the alarm screen directly as a fallback (§9, §15.10).
