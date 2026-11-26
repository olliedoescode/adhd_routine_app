# Lock-screen "now playing" style card for the running routine

## What you actually want

You described the Spotify lock-screen card: press the power button, and the
current thing is right there with controls, no widget to add, no home screen
involvement.

That is **not** an app widget. It is a **media-style notification** — Android
promotes any notification that declares itself as media playback into a
compact, always-visible card on the lock screen, with action buttons. That is
the mechanism Spotify, YouTube Music and podcast apps use.

The current `RunWidget` (a Glance home-screen widget) is the wrong tool for
this and can never appear on a Samsung One UI 5+ lock screen. It should be
retired from this job.

## Why the current approach fails

| Attempt | Why it does not show on the lock screen |
|---|---|
| Glance widget with `widgetCategory="home_screen\|keyguard"` | Samsung One UI 5+ (Android 13+, your A54) removed third-party lock-screen widgets entirely. The `keyguard` flag is ignored by the launcher. |
| Ongoing notification on a `IMPORTANCE_LOW` channel | Low-importance notifications are collapsed into the "silent" section and are hidden on the lock screen by default. |
| `CATEGORY_PROGRESS` notification | Not a media category, so Android renders it as a plain card that the lock screen may hide. |

The notification is already correct in content — it shows the current task, the
time left and the next task. It is simply on the wrong channel with the wrong
category to be promoted to the lock screen.

## The fix: make the run notification a media-style card

Android promotes a notification to the media card when it has:

1. `setStyle(MediaStyle)` — `androidx.media.app.NotificationCompat.MediaStyle`
2. A `MediaSession` token attached via `setMediaSession(token)`
3. `setCategory(CATEGORY_TRANSPORT)` (or `CATEGORY_SERVICE`)
4. A channel at `IMPORTANCE_LOW` or higher that is **not** blocked from the
   lock screen (`VISIBILITY_PUBLIC`, already set)

Once those are in place, pressing the power button shows the card with the
routine name, the current task, the time left, and up to three action buttons.

### Proposed card layout

```
┌──────────────────────────────────────────────┐
│  Morning routine                             │
│  Shower · 4:12 left                          │
│  Next: Get dressed                           │
│  ────────────────────────────────────────    │
│   [ ⏭ Skip ]   [ ⏸ Pause ]   [ ⏹ End ]      │
└──────────────────────────────────────────────┘
```

- **Skip** — jump to the next task now (advance the session start time so the
  current task ends immediately).
- **Pause / Resume** — freeze the countdown; the session stores a paused
  offset.
- **End** — the same confirm-then-stop path as the on-screen button.

If pause is too much state to add safely, ship Skip + End first and add Pause
later. Skip and End are the two that matter on a lock screen.

## Implementation outline

### 1. Add the media dependency

`app/build.gradle.kts` — add `androidx.media:media:1.7.0` (needed for
`MediaSessionCompat` and `MediaStyle`). Check `gradle/libs.versions.toml` for
the existing version-catalog style and add it there.

### 2. Create a `MediaSessionCompat` holder

New file `app/src/main/java/com/adhd/routineplanner/alarm/RunMediaSession.kt`

- A `@Singleton` that owns one `MediaSessionCompat`.
- `activate(context)` / `release()`.
- Sets a `PlaybackState` whose `state` is `STATE_PLAYING` while running and
  `STATE_PAUSED` when paused, with `setActions(...)` advertising
  `ACTION_SKIP_TO_NEXT`, `ACTION_PAUSE`, `ACTION_PLAY`, `ACTION_STOP`.
- Sets `MediaMetadata` with the routine name as title, the current task as
  artist, and the next task as album — this is what the card displays.
- A `MediaSessionCompat.Callback` that dispatches the four actions to the
  `RunViewModel` / session store.

### 3. Rebuild `RunNotification` as a media notification

`app/src/main/java/com/adhd/routineplanner/alarm/RunNotification.kt`

- Swap `NotificationCompat.BigTextStyle` for
  `androidx.media.app.NotificationCompat.MediaStyle`
  `.setMediaSession(token)`
  `.setShowActionsInCompactView(0, 1, 2)`.
- Add three `NotificationCompat.Action`s (Skip, Pause/Resume, End) with
  `PendingIntent`s that broadcast to a new `RunActionReceiver`.
- Change `setCategory(CATEGORY_PROGRESS)` to `CATEGORY_TRANSPORT`.
- Keep `setOngoing(true)`, `setOnlyAlertOnce(true)`, `setShowWhen(false)`.
- Keep the existing `body()` / `nextLine()` copy from `RunSnapshot` for the
  text lines.

### 4. Add a `RunActionReceiver`

New file `app/src/main/java/com/adhd/routineplanner/alarm/RunActionReceiver.kt`

- `@AndroidEntryPoint` `BroadcastReceiver` with three actions:
  `ACTION_SKIP`, `ACTION_PAUSE`, `ACTION_RESUME`, `ACTION_END`.
- Skip: advance the session so the current task ends now.
- Pause/Resume: write a paused flag + offset to `RunSessionStore`.
- End: clear the session, cancel alarms, cancel the notification, refresh the
  widget.
- Register in `AndroidManifest.xml` with `android:exported="false"`.

### 5. Raise the run channel to lock-screen visibility

`app/src/main/java/com/adhd/routineplanner/alarm/NotificationChannels.kt`

- `ensureRunChannel` currently early-returns if the channel exists, so an
  existing install keeps the old settings. Add a versioned id
  (`run_channel_v2`) so the new importance/visibility actually applies.
- Set `IMPORTANCE_LOW` (media cards work at LOW) and
  `lockscreenVisibility = VISIBILITY_PUBLIC` (already set).
- Explicitly `setSound(null, null)` and `enableVibration(false)` so it stays
  silent — the alarm channel still does the ringing.

### 6. Pause support in the session (optional, phase 2)

`app/src/main/java/com/adhd/routineplanner/data/RunSessionStore.kt`

- Add `KEY_PAUSED_AT` and `KEY_PAUSED_ACCUM_MS`.
- `RunSession` gains `pausedAtMs: Long?` and `pausedAccumMs: Long`.
- `runState(...)` subtracts the paused time from elapsed.
- `RunViewModel` shows a paused state and stops the countdown.

This is the only invasive part. If it is deferred, the Pause button is simply
omitted and the card shows Skip + End.

### 7. Retire the Glance widget from the lock-screen story

- `run_widget_info.xml` — drop `keyguard` from `widgetCategory` and update the
  comment to say it is a home-screen widget only.
- `PermissionChecklist.kt` — replace the "Lock screen" explainer card with
  copy that says the run card appears on the lock screen automatically while a
  routine is running, and that no widget needs adding.
- Keep `RunWidget` itself: it is still a valid home-screen widget for anyone
  who wants it. It just is not the lock-screen answer.

## Files touched

| File | Change |
|---|---|
| `app/build.gradle.kts` | add `androidx.media` |
| `gradle/libs.versions.toml` | version entry |
| `alarm/RunMediaSession.kt` | **new** — session + metadata + callbacks |
| `alarm/RunActionReceiver.kt` | **new** — skip / pause / end broadcasts |
| `alarm/RunNotification.kt` | MediaStyle + actions + transport category |
| `alarm/NotificationChannels.kt` | versioned run channel, lock-screen visible |
| `data/RunSessionStore.kt` | pause fields (phase 2) |
| `domain/RunState.kt` | subtract paused time (phase 2) |
| `viewmodel/RunViewModel.kt` | pause state + skip action (phase 2) |
| `AndroidManifest.xml` | register `RunActionReceiver` |
| `res/xml/run_widget_info.xml` | drop `keyguard` |
| `ui/routines/PermissionChecklist.kt` | new lock-screen copy |

## Verification on the A54

1. Start a routine.
2. Press the power button.
3. The card should be on the lock screen with the task name and time left.
4. Tap Skip — the card flips to the next task.
5. Tap End — the card disappears and the run stops.

If the card does not appear, check Settings → Apps → Routine Planner →
Notifications → "Routine in progress" is not set to "Silent" or hidden from the
lock screen. Samsung sometimes downgrades a channel the user has not touched.

## Risk notes

- `MediaStyle` requires a real `MediaSession` token; a fake token renders as a
  plain notification. The session must be activated before the notification is
  posted.
- The session must be released when the run ends, or the card lingers.
- Pause is the only part that touches persisted state; Skip and End reuse the
  existing session and alarm paths.
