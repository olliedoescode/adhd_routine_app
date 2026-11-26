# Accountability lock-screen takeover

## What you want

While a routine is running, turning the phone's screen on should show **the
routine**, not the normal lock screen. The user can still get past it — swipe it
away and the phone is theirs — but every time they pick the phone up during a
run they have to consciously dismiss the routine first. That friction is the
point: it catches the "I picked up my phone without thinking" moment and reminds
them what they meant to be doing.

## What Android actually allows

This is the important part, because the naive version of this feature does not
work on modern Android.

| Approach | Verdict |
| --- | --- |
| Replace the system lock screen | **Impossible.** Only the OEM/system can draw the keyguard. Third-party apps cannot. |
| Draw over the lock screen with `SYSTEM_ALERT_WINDOW` | **No.** Overlays are hidden while the keyguard is showing, and the permission is a scary one to ask for. |
| A full-screen-intent activity with `showOnLockScreen` | **Yes — this is the mechanism.** It is exactly how alarm clocks show over the lock screen. |
| `ACTION_USER_PRESENT` + `startActivity` | **Unreliable.** Background activity starts are blocked on Android 10+, so this silently fails most of the time. It already exists in `UserPresentReceiver` and is best-effort only. |

So the design is: **a full-screen-intent notification, kept alive for the whole
run, whose activity shows over the lock screen.** When the user turns the screen
on, Android shows the full-screen intent's activity on top of the keyguard. The
user swipes it away and is on their normal lock screen / home screen. Next time
they turn the screen on, the full-screen intent fires again.

### The one real limitation

A full-screen intent is shown **when the notification is posted**, and again
when the screen turns on **if the notification is still the most recent
full-screen-intent notification**. It is not a guaranteed "every single screen
on" hook — Android deliberately throttles it. In practice on a Samsung A54 this
gives the behaviour you want: the card is there when you pick the phone up, and
if you dismiss it, it comes back the next time the screen has been off for a
while.

The honest framing for the UI copy: *"Shows the routine over your lock screen
while it runs. You can always swipe it away."* Not *"locks your phone."*

## Design

### 1. `RunTakeoverActivity`

A new `ComponentActivity` in `alarm/`, modelled on the existing
[`AlarmActivity`](../app/src/main/java/com/adhd/routineplanner/alarm/AlarmActivity.kt):

- `setShowWhenLocked(true)` and `setTurnScreenOn(true)` so it appears over the
  keyguard and wakes the screen.
- `excludeFromRecents`, `launchMode="singleInstance"`, `showOnLockScreen`,
  `turnScreenOn` in the manifest — same as `AlarmActivity`.
- **No `requestDismissKeyguard`.** Unlike the alarm, this screen must *not* ask
  the user to unlock; it sits on top of the lock screen and is dismissed by a
  swipe, leaving the keyguard intact underneath.
- Reads the active `RunSession` from `RunSessionStore`, derives `runState`, and
  shows the current task, the time left and the next task.
- A **"Swipe up to dismiss"** affordance plus a plain **Dismiss** button. Both
  just `finish()`. Nothing is stopped — the run continues.
- If there is no active session (the run ended while the screen was off), it
  finishes immediately.
- Tapping the routine body opens the real run screen (`ACTION_OPEN_RUN`), so the
  takeover is also a shortcut into the app.

### 2. The full-screen-intent notification

The existing ongoing run notification is already `setOngoing(true)` and updated
every second. Add a **full-screen intent** to it pointing at
`RunTakeoverActivity`:

```kotlin
builder.setFullScreenIntent(takeoverPendingIntent, /* highPriority = */ true)
```

This is what makes Android show the takeover when the screen turns on. The
notification itself stays on the silent run channel; the full-screen intent is
what matters, not the channel importance.

`RunNotification.show` gains a `takeover: Boolean` parameter (default `true`).
When `false`, no full-screen intent is attached and the card behaves exactly as
it does today.

### 3. Settings toggle

Add `takeoverEnabled: Boolean = true` to `AlarmSettings` (it is the same
DataStore file, and it is an app-wide "how the app gets your attention" choice,
which is what that card already covers). A switch in the existing
`AlarmSettingsCard` labelled **"Take over the lock screen"** with the honest
sub-copy above.

`RunViewModel` reads the setting and passes it to `RunNotification.show`, so
turning it off restores the current behaviour with no other change.

### 4. Where it starts and stops

Nothing new is needed to start it: the run notification is already posted by
`RunViewModel` (manual run) and `AlarmReceiver.beginRun` (scheduled run). Adding
the full-screen intent to that notification is enough.

It stops when the notification is cancelled — `RunViewModel.stop()`,
`RunActionReceiver`-free now, and the finished-run path in `RunViewModel` all
already call `RunNotification.cancel`. Cancelling the notification removes the
full-screen intent, so the takeover stops appearing.

### 5. Permissions

`USE_FULL_SCREEN_INTENT` is already declared. On Android 14+ it is a
user-revocable permission for non-alarm apps; the existing
`PermissionChecklist` already checks `fullScreenIntentGranted`. Extend the
lock-screen explainer copy to describe the takeover and point at that
permission.

## Files touched

| File | Change |
| --- | --- |
| `alarm/RunTakeoverActivity.kt` | **New.** The over-lock-screen routine screen. |
| `alarm/RunNotification.kt` | Attach a full-screen intent when `takeover` is on. |
| `data/AlarmSettingsStore.kt` | Add `takeoverEnabled` + setter. |
| `viewmodel/RunViewModel.kt` | Read the setting, pass `takeover` to `show`. |
| `viewmodel/SettingsViewModel.kt` | Expose `setTakeoverEnabled`. |
| `ui/settings/SettingsScreen.kt` | Add the switch to `AlarmSettingsCard`. |
| `ui/routines/PermissionChecklist.kt` | Update the lock-screen copy. |
| `AndroidManifest.xml` | Register `RunTakeoverActivity`. |
| `README.md` | Document the feature. |

## Verification on the A54

1. Start a routine, lock the phone, wait a few seconds, turn the screen on.
   The routine screen should appear over the lock screen.
2. Swipe it away — you land on the normal lock screen, and the run is still
   going (check the notification).
3. Turn the screen off and on again — the routine screen returns.
4. Tap the routine body — the app opens on the run screen.
5. Turn the setting off, repeat step 1 — the normal lock screen appears.
6. End the run — the takeover stops appearing.

## Risk notes

- **Full-screen intents are throttled.** If the user dismisses it repeatedly in
  quick succession, Android may stop showing it for a while. That is the
  platform protecting against exactly the "unclosable app" pattern, and it is
  fine here: the feature is a nudge, not a lock.
- **Do not call `requestDismissKeyguard`.** That would unlock the phone, which
  is the opposite of what this is for.
- **Battery.** The activity is only alive while the screen is on and it is in
  front; it does no work in the background. The notification already ticks once
  per second.
- **Samsung specifics.** One UI may show its own "app is displaying over other
  apps" style notice the first time. The permissions checklist should mention
  allowing full-screen notifications.
