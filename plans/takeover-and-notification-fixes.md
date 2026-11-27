# Fix: notification re-fires on app open + lockscreen takeover misbehaves

Two related bugs, both rooted in how the ongoing run notification's
full-screen intent (FSI) is re-armed and how `RunTakeoverActivity` manages its
task.

## Bug 1 — Notification "re-given" when opening the app during a run

### Symptom
While a routine is running, opening the app (or the takeover appearing) causes
the run notification / takeover to fire again as if it were a fresh alert.

### Root cause
The FSI is re-armed with a **new request code** on paths that run whenever the
app is opened or resumed, not only on a genuine screen-on:

- [`RunTakeoverActivity.rearmNotification()`](../app/src/main/java/com/adhd/routineplanner/alarm/RunTakeoverActivity.kt:273)
  is called from `onResume`, and calls
  [`RunNotification.repostForTakeover()`](../app/src/main/java/com/adhd/routineplanner/alarm/RunNotification.kt:301),
  which calls `show(..., forceRepost = true)`.
- `forceRepost = true` bumps the FSI request code
  ([`RunNotification.kt:228`](../app/src/main/java/com/adhd/routineplanner/alarm/RunNotification.kt:228)),
  so Android treats it as a brand-new full-screen intent and re-launches the
  takeover.
- [`UserPresentReceiver`](../app/src/main/java/com/adhd/routineplanner/alarm/UserPresentReceiver.kt:122)
  also calls `repostForTakeover` on every `ACTION_SCREEN_ON`/`ACTION_USER_PRESENT`.

Because `RunTakeoverActivity.onResume` re-arms, and the takeover appearing is
itself a resume, this can loop: takeover shows → onResume re-arms → FSI fires →
takeover shows again.

### Fix
1. **Remove `rearmNotification()` from `RunTakeoverActivity.onResume`.** The
   takeover must never re-arm the FSI itself; that is what causes the loop.
   Keep the re-arm only in `UserPresentReceiver` (the genuine screen-on hook).
2. **Stop `RunViewModel` from re-posting with a fresh FSI on every field
   change.** It should keep the FSI attached (so the platform has one) but must
   not bump the request code. Only `UserPresentReceiver` should force a fresh
   request code, and only on a real screen-on.
3. **Guard the re-arm against app-foreground transitions.** In
   `UserPresentReceiver`, only force a re-post when the screen genuinely woke
   (`ACTION_SCREEN_ON`) — not on `ACTION_USER_PRESENT` when the app is already
   foreground (the user just opened the app). Use the existing
   `userPresent`/session state to avoid re-arming while the takeover is already
   visible.

## Bug 2 — Lockscreen takeover not working as intended

### Root causes
1. **`finishUnderlyingAppTask()` calls `finishAffinity()`**
   ([`RunTakeoverActivity.kt:338`](../app/src/main/java/com/adhd/routineplanner/alarm/RunTakeoverActivity.kt:338)).
   When the takeover shares the app's task (manual run / direct launch), this
   finishes the takeover itself before it can draw. It must finish only the
   activities *below* it, never itself.
2. **`UserPresentReceiver` launches the takeover directly on
   `ACTION_USER_PRESENT`** ([`UserPresentReceiver.kt:115`](../app/src/main/java/com/adhd/routineplanner/alarm/UserPresentReceiver.kt:115)).
   On unlock the keyguard is already gone, so `showWhenLocked` is moot and the
   takeover appears as a plain activity — and its `finishAffinity()` then tears
   down the app task the user just unlocked into.
3. **`RunTakeoverActivity` is `singleTop` with `FLAG_ACTIVITY_CLEAR_TOP`**
   ([`RunTakeoverActivity.kt:381`](../app/src/main/java/com/adhd/routineplanner/alarm/RunTakeoverActivity.kt:381)).
   `CLEAR_TOP` can finish/recreate the instance unexpectedly. For an FSI target
   that must reliably appear over the keyguard, `singleInstance` (like
   `AlarmActivity`) with `onNewIntent` handling is the correct model — the
   original code used `singleInstance` and the switch to `singleTop` introduced
   the reuse/finish problems.
4. **`AlarmActivity.dismissToTakeover`** starts the takeover with a plain
   `startActivity` and no FSI backing, so it will not reappear on the next
   screen-on unless the notification is re-armed.

### Fix
1. **Make `RunTakeoverActivity` `singleInstance`** in the manifest (matching
   `AlarmActivity`), and keep the `onNewIntent` override to refresh state. Use
   `FLAG_ACTIVITY_NEW_TASK` only (drop `CLEAR_TOP`).
2. **Replace `finishUnderlyingAppTask()`** with a safe version that finishes
   only the app's `MainActivity` (not the takeover). Use
   `finishActivity`-style logic or check `isTaskRoot` and, when not root,
   finish the *other* activities via a targeted approach rather than
   `finishAffinity()`.
3. **Remove the direct-launch-on-unlock path** in `UserPresentReceiver`; rely on
   the FSI (the sanctioned over-keyguard mechanism) and only re-post on
   `ACTION_SCREEN_ON`. Keep `ACTION_USER_PRESENT` solely for
   `markUserPresent()`.
4. **Ensure the FSI is present whenever the takeover should appear**, and that
   `AlarmActivity.dismissToTakeover` re-posts the notification with the FSI
   before/after launching the takeover so the next screen-on works.

## Files to change
| File | Change |
| --- | --- |
| `alarm/RunTakeoverActivity.kt` | Remove `rearmNotification` from `onResume`; fix `finishUnderlyingAppTask`; drop `CLEAR_TOP`; keep `onNewIntent`. |
| `alarm/RunNotification.kt` | Ensure `show` can keep the FSI without bumping the request code; only `forceRepost` bumps it. |
| `alarm/UserPresentReceiver.kt` | Re-arm only on `ACTION_SCREEN_ON`; remove direct-launch-on-unlock; keep `markUserPresent` on `ACTION_USER_PRESENT`. |
| `viewmodel/RunViewModel.kt` | Post with `takeover = true` but `forceRepost = false` (no request-code bump). |
| `alarm/AlarmActivity.kt` | Re-post notification with FSI in `dismissToTakeover` so the next screen-on works. |
| `AndroidManifest.xml` | `RunTakeoverActivity` → `launchMode="singleInstance"`. |

## Verification
1. Start a run, open the app from the launcher — the notification must **not**
   re-alert or re-launch the takeover.
2. Lock the phone, wait, turn the screen on — the takeover appears over the
   keyguard.
3. Dismiss it, turn the screen off/on — it returns.
4. Unlock during a run — the takeover does not nuke the app task.
5. End the run — the takeover stops appearing.

---

# Round 2 — follow-up bugs after the first fix

After the first round the takeover worked only partially. Three new symptoms:

1. **"Works once, then stops."** Turn the phone on → takeover; turn off/on →
   takeover again (good); but after swiping it off and using the phone, the next
   lock-screen open shows nothing.
2. **Fingerprint without turning the screen on does nothing.** Unlocking with
   biometrics on an already-lit screen never fires the takeover.
3. **Dismiss re-fires the alarm almost immediately** and it cannot be stopped.

## Root cause (one villain: the FSI request-code bump)

`forceRepost = true` bumped the FSI request code to
`TAKEOVER_REQUEST_CODE + (now/1000 % 1000)`. That was wrong in two ways:

- **Only unique per second.** Two screen-ons in the same second — or a post
  that reused the base code — produced an FSI the platform had already
  consumed, so nothing fired. That is the "works once then stops" symptom.
- **A bumped code is a brand-new intent that Android launches immediately.**
  On the alarm Dismiss path (`AlarmActivity.dismissToTakeover` called
  `repostForTakeover` → `show(forceRepost = true)`), that made the takeover —
  and the alarm screen it was replacing — re-fire the instant it was dismissed.

The fingerprint bug was separate: `ACTION_USER_PRESENT` was made a no-op
(`markUserPresent()` then `return`), so a biometric unlock of an already-lit
screen (which sends only `USER_PRESENT`, no `SCREEN_ON`) fired nothing.

## Fix (round 2)

1. **Remove `forceRepost` entirely.** `RunNotification.show()` now always uses
   one **stable** FSI request code (`TAKEOVER_REQUEST_CODE`). Per-second
   refreshes are no-op updates that never launch the takeover.
2. **Re-arm by cancel + re-post, not by bumping the code.**
   `RunNotification.repostForTakeover()` is replaced by
   `RunNotification.rearmForScreenOn()`, which cancels the notification and
   posts it again with the *same stable* FSI code. A cancel+repost is a
   genuinely fresh post the platform fires the FSI for, while the stable code
   means it never launches early and never collides between wakes.
3. **`UserPresentReceiver` handles both open paths, exactly once each:**
   - `ACTION_SCREEN_ON` (power button, off→on) → `rearmForScreenOn()` → the
     over-keyguard takeover. Works whether the device is locked or (passwordless)
     already unlocked.
   - `ACTION_USER_PRESENT` (biometric unlock of an already-lit screen) →
     `markUserPresent()` **and** `launchRunScreen()` directly — the "home screen
     variant". A direct start is allowed because the device is now unlocked and
     interactive.
   The two actions are mutually exclusive in practice, so the takeover fires
   once per open, in the right variant, never twice.
4. **`AlarmActivity.dismissToTakeover` no longer re-posts.** It launches the
   takeover directly (the alarm screen is foreground, so a direct start is
   allowed) and finishes. The notification already carries the stable FSI for
   the whole run, and `UserPresentReceiver` re-arms it on the next genuine
   screen-on — so nothing needs re-posting on dismiss, and the alarm cannot
   re-fire.

## Files changed (round 2)
| File | Change |
| --- | --- |
| `alarm/RunNotification.kt` | Drop `forceRepost`; stable FSI code; `repostForTakeover` → `rearmForScreenOn` (cancel + repost). |
| `alarm/UserPresentReceiver.kt` | `SCREEN_ON` → re-arm FSI; `USER_PRESENT` → `markUserPresent` + launch run screen (home-screen variant). |
| `alarm/AlarmActivity.kt` | `dismissToTakeover` launches the takeover directly, no re-post (no immediate re-fire). |
| `viewmodel/RunViewModel.kt` | Drop the `forceRepost = false` argument (parameter removed). |

## Verification (round 2)
1. During a run, press power off then on repeatedly — the takeover appears on
   **every** wake, not just the first.
2. Swipe the takeover off, use the phone, then lock and wake again — it
   returns.
3. With the screen already on, unlock with a fingerprint — the run screen comes
   to the foreground (home-screen variant), no over-keyguard takeover.
4. Let a task alarm ring, tap Dismiss — the takeover appears once and the alarm
   does **not** re-fire.
5. End the run — nothing fires on subsequent wakes.

---

# Round 3 — round 2 did not hold

Round 2 targeted the wrong villain. The FSI request-code bump was a red herring.
The real causes were the per-task re-arm loop, the dismissable alarm screen, and
the death of the runtime receiver when the task was swiped away.

## Root causes (round 3)

1. **Tone/vibration repeating every ~3 seconds.**
   [`AlarmReceiver.handleAlarm`](../app/src/main/java/com/adhd/routineplanner/alarm/AlarmReceiver.kt:85)
   re-arms the remaining run alarms after a task fires, but it called
   `scheduleRunAlarms(routineId, startMs)` with no lower bound.
   `scheduleRunAlarms` has a grace window: a task whose trigger is already in the
   past but within `FIRST_TASK_GRACE_MS` (60 s) is pulled forward to
   `now + FIRST_TASK_LEAD_MS` (3 s). So the task that had *just* fired was inside
   its own grace window on every re-arm and got rescheduled for 3 seconds later —
   forever. Every fire re-armed itself: tone + vibration on a 3-second cycle. The
   same loop is what looked like the alarm "resetting itself" on the first runs.

2. **The alarm screen could be dismissed.**
   Nothing stopped Home/Recents or a tap outside the activity. `AlarmActivity`
   has no keep-alive of its own: going home does not destroy it, so `stopAlarm()`
   in `onDestroy` never ran, and the tone kept playing from a background activity
   with no visible way back.

3. **Takeover stopped working after swiping the app away.**
   [`UserPresentReceiver`](../app/src/main/java/com/adhd/routineplanner/alarm/UserPresentReceiver.kt:58)
   is registered at runtime in `App.onCreate`. Swiping the task away killed the
   process, so the receiver (and the run) died with it, and nothing re-bootstrapped
   it on the next wake.

4. **Fingerprint unlock fired nothing.**
   A plain broadcast receiver cannot `startActivity` from the background on
   Android 10+ (Background Activity Launch restriction). Without a sanctioned
   exemption the launch was silently dropped.

## Fix (round 3)

### 1. Never re-arm the task that just fired

[`scheduleRunAlarms`](../app/src/main/java/com/adhd/routineplanner/alarm/AlarmScheduler.kt:215)
takes a new `firstTaskIndex: Int = 0` and skips every task below it:

```kotlin
tasks.forEachIndexed { index, task ->
    if (index < firstTaskIndex) return@forEachIndexed
    ...
}
```

[`AlarmReceiver.rearmRunAlarms`](../app/src/main/java/com/adhd/routineplanner/alarm/AlarmReceiver.kt:144)
now passes `taskIndex + 1`. The just-fired task can never re-enter the grace
window, so the 3-second loop is gone. The default keeps `RunStarter` (which
legitimately schedules from index 0) working unchanged.

### 2. The alarm screen is no longer dismissable

- `setFinishOnTouchOutside(false)` — tapping outside does nothing.
- An `OnBackPressedCallback` that swallows back — back does nothing.
- [`onUserLeaveHint`](../app/src/main/java/com/adhd/routineplanner/alarm/AlarmActivity.kt:425)
  re-pins the screen with `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_REORDER_TO_FRONT`
  when the user tries Home/Recents, so the only exits are **Snooze** and
  **Dismiss**.
- A `leavingIntentionally` flag is set by Snooze, Dismiss, Open run and test
  finish so the re-pin does not fight a deliberate exit.
- [`onNewIntent`](../app/src/main/java/com/adhd/routineplanner/alarm/AlarmActivity.kt:204)
  returns early when the intent names the same routine *and* the same task index
  while the player is live, so a re-post can no longer stop and restart the tone
  (the "resets itself" symptom).

### 3. The run survives a swipe-away

New [`RunForegroundService`](../app/src/main/java/com/adhd/routineplanner/alarm/RunForegroundService.kt:51)
(a `START_STICKY` foreground service, type `specialUse`):

- [`RunNotification.show()`](../app/src/main/java/com/adhd/routineplanner/alarm/RunNotification.kt:122)
  posts the notification, then calls `RunForegroundService.ensureRunning(context, notification)`.
- If the process is killed and the service restarts with a null intent, it
  rebuilds the notification from `RunSnapshot.load(...)` (placeholder first,
  snapshot second) so the run is visible again and `UserPresentReceiver` is
  re-registered by `App.onCreate`.
- `RunNotification.cancel()` calls `RunForegroundService.stop(context)` so the
  service never outlives the run.

### 4. The takeover works on the fingerprint path

`SYSTEM_ALERT_WINDOW` ("Display over other apps") is a sanctioned BAL exemption.
`UserPresentReceiver` reads `AlarmPermissions.overlayGranted(context)` once per
event:

- `USER_PRESENT` + takeover + overlay granted → `launchTakeover()` directly
  (works for fingerprint unlock, no screen-off needed).
- `SCREEN_ON` + takeover + overlay granted → `launchTakeover()`.
- `SCREEN_ON` + takeover but **no** overlay → fall back to `RunNotification.rearmForScreenOn`.
- takeover off → run screen (home-screen variant), as before.

[`AlarmPermissions`](../app/src/main/java/com/adhd/routineplanner/alarm/AlarmPermissions.kt:35)
gained `overlayGranted`, `overlaySettingsIntent`, and an `overlayGranted` field on
`AlarmPermissionStatus`; [`PermissionChecklist`](../app/src/main/java/com/adhd/routineplanner/ui/routines/PermissionChecklist.kt:65)
shows a "Display over other apps" row with a fix button.

## Files changed (round 3)

| File | Change |
| --- | --- |
| `alarm/AlarmScheduler.kt` | `firstTaskIndex` on `scheduleRunAlarms` (interface, `NoOp`, impl) + loop guard. |
| `alarm/AlarmReceiver.kt` | `rearmRunAlarms(context, routineId, taskIndex + 1)`. |
| `alarm/AlarmActivity.kt` | Outside-touch + back blocked; `onUserLeaveHint` re-pin; `leavingIntentionally`; `onNewIntent` same-task guard. |
| `alarm/RunForegroundService.kt` | **New.** `START_STICKY` `specialUse` FGS; notification-or-rebuild-from-snapshot; `ensureRunning`/`stop`. |
| `alarm/RunNotification.kt` | `show()` starts the FGS; `cancel()` stops it; `buildPlaceholder` + `postSnapshot` for the null-intent restart. |
| `AndroidManifest.xml` | `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `SYSTEM_ALERT_WINDOW`; `<service>` with `specialUse` + subtype property. |
| `alarm/AlarmPermissions.kt` | `overlayGranted`, `overlaySettingsIntent`, `overlayGranted` on the status data class. |
| `ui/routines/PermissionChecklist.kt` | "Display over other apps" row. |
| `alarm/UserPresentReceiver.kt` | Overlay-aware: launch the takeover directly on `SCREEN_ON`/`USER_PRESENT` when granted. |

## Verification (round 3)

The Gradle build could not be run in this environment, so each change was
verified by re-reading the edited region and tracing every call site:

1. `scheduleRunAlarms` call sites checked: `RunStarter` (2-arg, uses the default
   `0`) and `AlarmReceiver` (3-arg with `taskIndex + 1`). No other callers.
2. `AlarmPermissionStatus(` is constructed in exactly one place
   (`AlarmPermissions.status`), and it now passes `overlayGranted`.
3. `RunForegroundService` uses an explicitly typed `val provided: Notification?`
   so `getParcelableExtra` infers `T` correctly, and it cancels its
   `CoroutineScope` in `onDestroy`.
4. `RunNotification.runBuilder` returns a `NotificationCompat.Builder`;
   `buildPlaceholder` and `postSnapshot` build and notify consistently with
   `show()`.
5. `UserPresentReceiver` KDoc/function ordering checked so each KDoc sits above
   its own function.

Manual checks on the device:

1. Let a run's task alarm ring — the tone plays once and **does not** repeat
   every 3 seconds. Let it ring for a minute to be sure.
2. While it rings, press Home and Recents — the alarm screen comes straight back.
   Tapping outside and pressing back do nothing. Only Snooze/Dismiss leave it.
3. Tap Dismiss — the tone stops and stays stopped; the takeover appears once.
4. Start a run, swipe the app away, lock and unlock — the takeover returns.
5. With "Display over other apps" granted, unlock with a fingerprint without
   pressing power first — the takeover appears.
6. End the run — the notification clears and nothing fires on later wakes.
