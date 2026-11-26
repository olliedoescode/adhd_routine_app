# Run state machine — the ground truth

This document is the **single source of truth** for what the app should do in
every situation involving a routine run, an alarm, the lock screen and the
takeover. When something behaves wrong, compare the real behaviour against this
table; the table wins.

It covers **both** the *test run* (started from the plan screen with "Test run")
and the *actual scheduled run* (started by the routine's start alarm). They are
intended to behave **identically** — the same code path (`RunStarter.begin`)
drives both, so any difference is a bug.

---

## The two moving parts

1. **The run session** — `RunSessionStore` holds `(routineId, startMs)`. A run is
   "active" from the moment it starts until the user stops it or it finishes.
2. **The presence flag** — `RunSessionStore.userPresent`. This is the key
   discriminator. It means:

   > *"Has the user unlocked the phone at least once **since this run started**?"*

   - It is **reset to `false`** every time a run starts (`RunSessionStore.start`).
   - It is **set to `true`** only on `ACTION_USER_PRESENT` (a real unlock), never
     on `ACTION_SCREEN_ON` (the screen can be on while still locked).
   - It is **removed** when the run is cleared (`RunSessionStore.clear`).

   Why this matters: the user's rule is *"if they were already on their phone
   before the routine started, the first alarm should still take them to the
   takeover, because the routine has now started — but if they unlocked during
   the run and it is just the next task's alarm, don't drag them to the takeover
   again."* The presence flag captures exactly that: it is `false` at the start
   of every run (so the first alarm always takes over) and only becomes `true`
   once they unlock *during* the run (so later task alarms leave them alone).

---

## The three things that can happen

| Trigger | What fires it | Code |
| --- | --- | --- |
| **Routine starts** | The routine-start alarm (`taskIndex == -1`), or the "Test run" button | `AlarmReceiver.beginRun` → `RunStarter.beginScheduled` / `RunStarter.beginNow` → `RunStarter.begin` |
| **A task alarm rings** | An in-run task alarm for a task with `alarmEnabled` | `AlarmReceiver.handleAlarm` → `launchAlarmScreen` → `AlarmActivity` |
| **The phone is picked up** | Screen on / unlock while a run is active | `UserPresentReceiver` → `RunNotification.repostForTakeover` → `RunTakeoverActivity` |

---

## The state table

Legend for the **"After stopping the alarm"** column:

- **Takeover** = show `RunTakeoverActivity` (the lock-screen routine card).
- **No takeover** = just close the alarm screen; the user stays where they are.

| # | State | Run active? | Presence flag | What happens | After stopping the alarm |
| --- | --- | --- | --- | --- | --- |
| **A** | No run, phone idle | No | — | Nothing. No alarm, no takeover. | — |
| **B** | Routine starts while the user is **already holding/unlocked** the phone (was on their phone *before* the run began) | Yes (just started) | `false` (reset at start; they have not unlocked *since* start) | Run begins. The **first** task alarm rings. | **Takeover.** The routine has started; they must acknowledge it. (This is the case the user called out: "for #B it should take them to the takeover because the routine has started.") |
| **C** | Routine starts while the phone is **locked / screen off** | Yes (just started) | `false` | Run begins silently (ongoing notification posted). The first task alarm rings over the lock screen. | **Takeover.** They were locked; stopping the alarm takes them to the routine card. |
| **D** | User **unlocks during an already-running** routine, and it is just the **next task's alarm** | Yes (already running) | `true` (they unlocked during the run) | The task alarm rings. | **No takeover.** They already unlocked and acknowledged being on their phone during this run. |
| **E** | User is **still locked** mid-run and a task alarm rings | Yes (already running) | `false` (never unlocked since start) | The task alarm rings over the lock screen. | **Takeover.** They were locked; stopping the alarm takes them to the routine card. |
| **F** | User **turns the screen on** (or unlocks) while a run is active | Yes | unchanged | The takeover is re-posted via the full-screen intent and appears over the lock screen. | — (this *is* the takeover) |
| **G** | Run **finishes** or the user **stops** it | No | cleared | The ongoing notification is cancelled, in-run alarms are cancelled, the takeover stops appearing. | — |

### The one-line rule

> **Show the takeover after an alarm unless the user has unlocked the phone
> since this run started.** (Presence flag `false` → takeover; `true` → no
> takeover.) And **always** show the takeover when the screen is turned on
> during a run.

---

## Flowchart

```
                         ┌─────────────────────────────┐
                         │  Is a run session active?    │
                         └──────────────┬──────────────┘
                                        │
                 ┌──────────────────────┴───────────────────────┐
                 │ NO                                            │ YES
                 ▼                                               ▼
        ┌─────────────────┐                    ┌────────────────────────────────┐
        │  State A        │                    │  What triggered this?           │
        │  Do nothing.    │                    └───────────────┬────────────────┘
        └─────────────────┘                                    │
                        ┌──────────────────────┬───────────────┴───────────────┐
                        │                      │                               │
                        ▼                      ▼                               ▼
              ┌──────────────────┐   ┌──────────────────┐          ┌────────────────────┐
              │ ROUTINE STARTS   │   │ TASK ALARM RINGS │          │ SCREEN ON / UNLOCK │
              │ (start alarm or  │   │ (in-run alarm)   │          │ (UserPresentReceiver)│
              │  Test run button)│   └────────┬─────────┘          └──────────┬─────────┘
              └────────┬─────────┘            │                               │
                       │                      │                               │
                       ▼                      ▼                               ▼
        ┌──────────────────────────┐  ┌──────────────────────┐    ┌──────────────────────┐
        │ RunStarter.begin:        │  │ AlarmActivity rings. │    │ If USER_PRESENT:      │
        │  • session.start()       │  │ On Dismiss:          │    │   markUserPresent()   │
        │    → presence = false    │  │                      │    │ Repost takeover       │
        │  • scheduleRunAlarms()   │  │  present?            │    │ (full-screen intent)  │
        │  • post notification     │  │   ├─ false → TAKEOVER│    │ → RunTakeoverActivity │
        │  • (test) launch takeover│  │   └─ true  → close   │    │   (State F)           │
        └────────────┬─────────────┘  └──────────────────────┘    └──────────────────────┘
                     │
                     ▼
        ┌──────────────────────────┐
        │ First task alarm rings   │
        │ (State B or C)           │
        │ presence is still false  │
        │ → on Dismiss: TAKEOVER   │
        └──────────────────────────┘
```

---

## How each state maps to the code

| State | Code path | Key line |
| --- | --- | --- |
| **A** | `UserPresentReceiver` returns early when `sessionStore.session.firstOrNull() == null`. | `UserPresentReceiver.onReceive` |
| **B / C** | `RunStarter.begin` calls `sessionStore.start(...)` which sets `KEY_USER_PRESENT = false`. The first task alarm → `AlarmActivity.dismissToTakeover` reads `userPresent == false` → launches `RunTakeoverActivity`. | `RunSessionStore.start`, `AlarmActivity.dismissToTakeover` |
| **D** | `UserPresentReceiver` on `ACTION_USER_PRESENT` calls `sessionStore.markUserPresent()`. A later task alarm → `AlarmActivity.dismissToTakeover` reads `userPresent == true` → just `finish()`. | `UserPresentReceiver.onReceive`, `AlarmActivity.dismissToTakeover` |
| **E** | Same as B/C: presence never set, so `dismissToTakeover` shows the takeover. | `AlarmActivity.dismissToTakeover` |
| **F** | `UserPresentReceiver` → `RunNotification.repostForTakeover` (cancel → settle → re-post with full-screen intent) → `RunTakeoverActivity`. | `UserPresentReceiver.onReceive`, `RunNotification.repostForTakeover` |
| **G** | `RunViewModel.stop` cancels alarms + notification and clears the session (removing the presence flag). | `RunViewModel.stop`, `RunSessionStore.clear` |

---

## The notification race (why the takeover used to appear only once)

The ongoing run notification is re-posted **once per second** by
`RunViewModel`'s collector with `takeover = false`. The takeover is re-posted by
`UserPresentReceiver` → `RunNotification.repostForTakeover`, which does
`cancel()` → wait 250 ms → `show(takeover = true)`.

If the per-second update landed in that window it re-posted the notification as
an **update**, and Android does not re-fire a full-screen intent for an update —
so the takeover was silently suppressed. That is the "only works once" bug.

The fix is a single-writer guard in `RunNotification`:

- `takeoverInFlight` — set for the whole cancel/settle/post window; `show(takeover
  = false)` calls are dropped while it is set.
- `takeoverArmed` — set once the takeover notification is posted; the per-second
  updater keeps its hands off the card until `RunTakeoverActivity` calls
  `clearTakeoverArmed()` (i.e. until the takeover has actually been shown).

So the takeover post always wins, and the per-second updater resumes only after
the takeover is on screen.

---

## The double-notification bug

A task alarm used to post a **second** `CATEGORY_ALARM` notification on top of
the ongoing run notification. Now `AlarmReceiver.handleAlarm` launches
`AlarmActivity` **directly** for a task alarm (the exact-alarm broadcast grants a
temporary background-activity-start allowance), so there is exactly **one**
notification for the run — the ongoing card — and the alarm screen is the alert.

---

## Test run vs scheduled run — what must match

| Behaviour | Test run | Scheduled run |
| --- | --- | --- |
| Session start time | "now" | the routine's scheduled start (looked back 1 min for late delivery) |
| In-run task alarms scheduled | ✅ `scheduleRunAlarms` | ✅ `scheduleRunAlarms` |
| Ongoing notification posted | ✅ | ✅ |
| Takeover on start | ✅ launched directly (app is foreground, full-screen intent is suppressed while visible) | via full-screen intent (app is background) |
| First task alarm → takeover | ✅ (presence `false`) | ✅ (presence `false`) |
| Later task alarm after unlock → no takeover | ✅ (presence `true`) | ✅ (presence `true`) |
| Screen-on during run → takeover | ✅ | ✅ |

Both paths go through `RunStarter.begin`, so the only intended difference is
*how* the takeover is launched at the very start (direct vs full-screen intent),
because Android suppresses full-screen intents while the app is in the
foreground.
