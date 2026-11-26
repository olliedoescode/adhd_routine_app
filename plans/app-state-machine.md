# App State Machine — Ground Truth

This is the whole-app map: every state the app can be in, what triggers each
transition, and what *should* happen. Use it as the reference when debugging —
if the app does something not on this map, that's the bug.

The run/alarm/takeover slice is documented in far more detail in
[`plans/run-state-machine.md`](run-state-machine.md). This document covers the
rest of the app and links into that one for the alarm flow.

---

## 0. The two axes

Almost every confusing bug comes from mixing up two independent things:

1. **Where the user is** — which screen/activity is on top (navigation state).
2. **What the app is doing in the background** — is a run active, are alarms
   armed, is a notification showing (runtime state).

They are independent. The user can be on the routine list while a run is
active in the background. The alarm can fire while the app is closed. Keep
them separate in your head.

A third, smaller axis is **process state** (foreground / background / killed),
which matters because the run is derived from the wall clock, not stored
progress.

---

## 1. App-level navigation states

The app is a single `MainActivity` hosting a Compose `NavHost`
([`AppNavHost.kt`](../app/src/main/java/com/adhd/routineplanner/ui/AppNavHost.kt)).
Routes:

| Route | Screen | Entered by |
|---|---|---|
| `routines` | Routine list (home) | Default start; back from run; back from create |
| `routines/new` | Create routine | "+" on the list |
| `plan/{id}` | Plan editor | Tap a routine card; after creating a routine |
| `run/{id}` | Live run | Start-now from plan; alarm/notification/takeover; cold start with active session |
| `settings` | Settings | Gear on the list |
| `permissions` | Permission checklist | From the list / settings |

### Navigation state table

| # | Nav state | How you get here | What should happen |
|---|---|---|---|
| N1 | **Routine list** | Cold start with no active session; back from run; back from create | Shows all routines. Back button exits the app. |
| N2 | **Create routine** | Tap "+" | Full-page editor. Save → inserts routine+tasks in one transaction → navigates to `plan/{newId}` (create screen popped). Back → list, nothing saved. |
| N3 | **Plan editor** | Tap a routine card, or after create | Edit tasks/grid. "Start now" → `run/{id}`. Back → list. |
| N4 | **Live run** | Start-now, alarm, notification tap, takeover "Open", or cold start with a session | Shows the running routine. Back → list **and clears the run stack** (sets `exitedRunId`). Stop → clears session, cancels alarms, back to list. |
| N5 | **Settings** | Gear on list | Alarm tone/volume/vibration/duration, takeover toggle, stock-task library. Back → list. |
| N6 | **Permissions** | From list/settings | Checklist + test alarm. Back → previous screen. |

### Entry-point resolution (the tricky part)

`MainActivity.onCreate` decides the start destination
([`MainActivity.kt`](../app/src/main/java/com/adhd/routineplanner/MainActivity.kt)):

| # | Launch situation | Resolved start | Notes |
|---|---|---|---|
| E1 | Launched from **alarm / notification / takeover** with an explicit run id (`ACTION_OPEN_RUN`) | `run/{id}` | Intent action+extra are cleared after reading, so a later launcher tap is a clean cold start. |
| E2 | **Cold start** (process was dead) with an active session in `RunSessionStore` | `run/{sessionRoutineId}` | Reopens the run the user was in. |
| E3 | **Cold start** with no session | `routines` | Normal home. |
| E4 | **Activity recreated** (rotation / process death with saved state) | Whatever was showing | `savedInstanceState != null` → skips the session check so it doesn't yank the user to the run screen. |
| E5 | **Already running, brought to front** (bare launcher intent) | Stays where it was | `onNewIntent` ignores intents with no explicit run id. |
| E6 | **Already running, explicit run id** (new alarm/notification) | `run/{id}` | `onNewIntent` switches to run mode. |

`AppNavHost` then guards against stale re-entry: a `LaunchedEffect` on the run
id navigates to `run/{id}` only if it isn't the `handledRunId` and isn't the
`exitedRunId` (the run the user just backed out of). This is what stops "Stop
run → immediately reopened" loops.

---

## 2. Routine lifecycle

A routine is a row in the DB plus its tasks. It can be **enabled** or
**disabled**, and **recurring** or **one-off**.

| # | Routine state | Trigger | What should happen |
|---|---|---|---|
| R1 | **Enabled, recurring** | Default on create | Alarms armed for the next occurrence of its start time. Shows weekly day letters on the card. |
| R2 | **Disabled** | Toggle off on the card | Alarms cancelled (`setRoutineEnabled(false)` → reschedule). Card dimmed. |
| R3 | **Enabled, one-off (future)** | One-off routine with a future date | Alarms armed for that date. Card shows the date label instead of day letters. |
| R4 | **One-off expired** | Date passed | No next occurrence → no alarms. Should not fire again. |
| R5 | **Deleted (single)** | Swipe on the card | Removed; undo snackbar restores the routine (empty — tasks were cascade-deleted). |
| R6 | **Deleted (batch)** | Multi-select → bin → confirm | Removed; **no undo** (deliberate). |
| R7 | **Being edited** | Open plan editor | Edits commit to DB; alarms rescheduled on save. |

Key invariant: **enabled + has a future occurrence ⇒ alarms armed.** Disabled
or expired ⇒ no alarms. If an enabled routine doesn't fire, check this first.

---

## 3. Plan editor states

Backed by [`PlanViewModel.kt`](../app/src/main/java/com/adhd/routineplanner/viewmodel/PlanViewModel.kt).
The editor is a grid of tasks with drag/resize/undo.

| # | Plan state | Trigger | What should happen |
|---|---|---|---|
| P1 | **Idle** | Open plan | Grid shows tasks at their times. |
| P2 | **Task selected** | Tap a task | Selection highlight; edit actions available. |
| P3 | **Dragging** | Long-press + move a task | Live ghost follows the finger; drop commits a new time. |
| P4 | **Resizing** | Drag a task's edge | Duration changes; clamped to capacity/buffer. |
| P5 | **Undo available** | After any commit | Undo snackbar restores the previous task list. |
| P6 | **Editing locked** | A run is active for this routine | Structural edits blocked (`editingLocked`) so the live run isn't corrupted. |
| P7 | **Start now** | "Start now" button | `startRunNow()` → begins a run at the current time → `run/{id}`. |

---

## 4. Run lifecycle (summary — see run-state-machine.md)

Backed by [`RunViewModel.kt`](../app/src/main/java/com/adhd/routineplanner/viewmodel/RunViewModel.kt)
and [`RunState.kt`](../app/src/main/java/com/adhd/routineplanner/domain/RunState.kt).
The run is **derived from the wall clock** — nothing about progress is stored,
so a killed process resumes correctly by recomputing.

| # | Run phase | Meaning | What should happen |
|---|---|---|---|
| L1 | **BeforeStart** | `now < startMs` | Countdown to start. |
| L2 | **Running** | `startMs ≤ now < end` | Current task highlighted, countdown, ongoing notification, widget live. |
| L3 | **Finished** | `now ≥ end` | "Finished" state; ongoing notification dropped. |
| L4 | **Stopped** | User taps Stop | Session cleared, alarms cancelled, notification cancelled, widget reset, back to list. |

Two ways a run starts, and they must behave identically:

| Start path | Entry | Start time used |
|---|---|---|
| **Test / manual** | "Start now" on plan, or test alarm | `System.currentTimeMillis()` (now) |
| **Scheduled** | Alarm fires at the routine's start time | The routine's scheduled start (so opening late shows correct elapsed time) |

Both go through `RunStarter.begin()` → `sessionStore.start()` (which **resets
the presence flag**) → `scheduleRunAlarms()` (per-task alarms relative to the
actual start). This is the unification that fixed "test run ≠ scheduled run".

---

## 5. Alarm / takeover states (cross-reference)

The full table (states A–G) lives in
[`plans/run-state-machine.md`](run-state-machine.md). Summary of the
discriminator — the **presence flag** (`RunSessionStore.userPresent`):

| # | Situation | Presence flag | Takeover on alarm dismiss? |
|---|---|---|---|
| A | No run active | — | No alarm at all |
| B | Present *before* start, first alarm | `false` (reset at start) | **Yes** — routine has started |
| C | Locked at start | `false` | **Yes** |
| D | Unlocked *during* the run, next task alarm | `true` (set on unlock) | **No** — already acknowledged being on the phone |
| E | Locked mid-run, screen woken | `false` | **Yes** |
| F | Screen on during run | `false` | **Yes** |
| G | Run finished / stopped | cleared | No |

The flag is set **only** on `ACTION_USER_PRESENT` (real unlock), never on
`ACTION_SCREEN_ON`. That's what makes B (takeover) and D (no takeover) differ.

---

## 6. Settings states

Backed by [`SettingsViewModel.kt`](../app/src/main/java/com/adhd/routineplanner/viewmodel/SettingsViewModel.kt)
and [`AlarmSettingsStore.kt`](../app/src/main/java/com/adhd/routineplanner/data/AlarmSettingsStore.kt).

| # | Setting | Effect |
|---|---|---|
| S1 | **Alarm tone** | Rebuilds the alarm notification channel sound. Blank → system default. |
| S2 | **Alarm volume** | Applied when the alarm plays. |
| S3 | **Alarm vibration on/off + strength** | Applied to task alarms. |
| S4 | **Task vibration strength** | Applied to task boundaries / run start. |
| S5 | **Alarm duration** | How long the alarm rings before auto-stop. |
| S6 | **Tone preview on/off** | Quiet preview in the picker. |
| S7 | **Lock-screen takeover on/off** | Gates `RunTakeoverActivity` on screen wake during a run. |
| S8 | **Stock task library** | Add/edit/delete reusable tasks offered in the add-task picker. Deleting all is allowed and not re-seeded. |

---

## 7. Permission states

Backed by [`PermissionChecklistViewModel.kt`](../app/src/main/java/com/adhd/routineplanner/viewmodel/PermissionChecklistViewModel.kt)
and [`AlarmPermissions.kt`](../app/src/main/java/com/adhd/routineplanner/alarm/AlarmPermissions.kt).

| # | Permission | Why it matters | If missing |
|---|---|---|---|
| PM1 | **Notifications** | Ongoing run card + alarm notifications | No notifications at all |
| PM2 | **Exact alarms** | On-time firing | Alarms fire inexactly (late) |
| PM3 | **Full-screen intent** | Alarm/takeover over the lock screen | Alarm shows as a heads-up only |
| PM4 | **Battery optimisation off** | Reliable background firing | Alarms may be delayed/killed |
| PM5 | **Test alarm** | Proves delivery on the device | — |

The checklist re-reads status on `ON_RESUME`. The test alarm schedules a
one-off one minute ahead and reports whether it could be exact.

---

## 8. Widget states

Backed by [`RunWidgetUpdater.kt`](../app/src/main/java/com/adhd/routineplanner/widget/RunWidgetUpdater.kt)
and [`RunWidget.kt`](../app/src/main/java/com/adhd/routineplanner/widget/RunWidget.kt).

| # | Widget state | Trigger |
|---|---|---|
| W1 | **No routine running** | No session / run stopped |
| W2 | **Running — current task** | Live run; flips at each task boundary |
| W3 | **Cleared** | Run finished or stopped |

The widget is refreshed by the run's notification collector each tick and
explicitly on stop (because the collector doesn't see the clear when the screen
is popped).

---

## 9. Process / background states

Because progress is derived from the clock, these are mostly about *waking the
app back up correctly*.

| # | Process state | Trigger | What should happen |
|---|---|---|---|
| X1 | **Foreground** | User in app | Normal. |
| X2 | **Background, run active** | User leaves app mid-run | Ongoing notification + widget keep updating; alarms still armed. |
| X3 | **Killed, run active** | OS kills process | On next open, `RunSessionStore` session → `run/{id}` recomputed from clock (E2). |
| X4 | **Reboot / time / timezone change** | `BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED` | `BootReceiver` → `rescheduleAll()` rebuilds every alarm from the DB. |
| X5 | **Alarm fires, app closed** | `AlarmReceiver` | Launches `AlarmActivity` directly (no duplicate notification); on dismiss routes to takeover per presence (section 5). |

---

## 10. Master flowchart (navigation + run)

```
                         ┌─────────────────────────┐
   launcher / cold start │  MainActivity.onCreate   │
                         │  resolve start dest      │
                         └───────────┬─────────────┘
              explicit run id? ──────┤
                    │ yes            │ no
                    ▼                ▼
              ┌──────────┐    active session? ──yes──► run/{id}
              │ run/{id} │           │ no
              └────┬─────┘           ▼
                   │            ┌──────────────┐
                   │            │ routines     │◄──────────────┐
                   │            │ (home)       │               │
                   │            └──┬───┬───┬───┘               │
                   │        "+" │   │gear│   │ tap card        │
                   │            ▼   │    ▼   ▼                 │
                   │      routines/new  settings  plan/{id}    │
                   │            │ save      │        │         │
                   │            └──────────►│        │ start   │
                   │                        │        ▼ now     │
                   │                        │     run/{id} ────┤
                   └────────────────────────┴────────►│        │
                                                      │ back   │
                                                      ▼        │
                                                   routines ───┘
```

Run phase sub-machine (inside `run/{id}`):

```
BeforeStart ──(now ≥ start)──► Running ──(now ≥ end)──► Finished
     │                            │
     │                            └──(Stop)──► Stopped ──► back to routines
     └──(Stop)──► Stopped
```

---

## 11. "If it does X, the bug is Y" quick map

| Symptom | Likely state/transition to inspect |
|---|---|
| Takeover only works once | RunTakeoverActivity launch mode + RunNotification takeover guards (section 5) |
| Alarm doesn't fire in test run | `RunStarter.begin` → `scheduleRunAlarms` (section 4) |
| Scheduled run ≠ test run | Both must go through `RunStarter.begin` (section 4) |
| Double notification | `AlarmReceiver` launching `AlarmActivity` directly (section 9, X5) |
| Stop run reopens the run | `exitedRunId` guard in `AppNavHost` (section 1) |
| App opens run screen unexpectedly | Entry resolution E2/E4 (section 1) |
| Takeover when it shouldn't (state D) | Presence flag set on unlock, not screen-on (section 5) |
| No takeover when it should (state B) | Presence flag reset at `sessionStore.start()` (section 5) |
| Alarms gone after reboot | `BootReceiver.rescheduleAll` (section 9, X4) |
| Enabled routine never fires | Routine lifecycle R1–R4 (section 2) |
