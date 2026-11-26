# ADHD Routine App

An Android routine planner built for people with ADHD. You lay out a routine as a
timeline of timed tasks on a minute-grid, then run it live with a countdown, a
water-fill progress box, and alarms at every task boundary. Everything is local:
no accounts, no cloud, no network.

The full product and technical specification lives in
[`Routine Planner App Technical Specification (1).md`](Routine%20Planner%20App%20Technical%20Specification%20(1).md).

---

## What the app does

### Plan a routine

A routine is an ordered list of tasks with durations, inside a fixed time window
(for example 07:00–08:00). The plan screen draws that window as a grid where
**each square is one minute**.

- **Add a task** from the top bar: give it a name, a colour and a duration using
  the clock-face picker. Reusable steps ("Shower", "Get dressed", "Breakfast")
  are offered as one-tap presets from your stock-task list.
- **Move a task** by long-pressing and dragging it. The other tasks slide out of
  the way with a live preview, and the change is only saved on release.
- **Resize a task** by tapping it: a strip opens on the right where one minute is
  a fixed height and you drag the handle to change the length.
- **Zoom** with a pinch to switch between 10, 8, 6 and 5 columns.
- **Undo** any committed edit from the snackbar.

The routine window is always fully drawn. Spare time is not wasted: by default it
is shared out across the tasks in proportion to their lengths, so the grid is a
complete partition with no empty cells. Turn the buffer off per routine and each
task keeps its minimum length, with the leftover time left blank at the end.

### Run a routine

Press Run and the app switches to run mode:

- A **water-fill box** shows the current task's name and fills up as the task
  progresses.
- A large **countdown** shows the time left in the current task. It turns amber
  in the last minute and red in the last ten seconds.
- The **grid** shows past minutes in full colour, future minutes washed out, and
  a cursor that creeps across the current minute.
- The screen stays awake while a run is open.

Run state is always derived from the wall clock, never counted tick by tick, so
the app picks up exactly where it should after being killed or backgrounded.

### Alarms

The app schedules its own exact alarms at each task start, so you get a
notification (and optionally a full-screen alarm over the lock screen) when it is
time to move on. Alarms survive reboots and are rescheduled when the clock or
timezone changes.

Every task boundary is felt:

- **Task with an alarm** — the full alarm: a high-priority notification, the
  full-screen alarm screen, the alarm tone and vibration.
- **Task without an alarm** — a short double vibration, so you notice the task
  changed without a full alarm going off.

### Ongoing notification

While a routine is running a single **ongoing notification** stays pinned in the
shade and on the lock screen. It is a plain notification, not a media card, and
it is laid out most-important-first:

1. **Current task** as the header — what you should be doing right now.
2. **Time left on that task, then the next task** on one line, with the time
   counting down in `m:ss` (for example `1:59 left · Next: Get dressed`).
3. **The routine name** on the line below, since which routine it is matters
   least.

There is no progress bar: a determinate bar takes the second line of the
collapsed notification, which hid the time left and the next task until you
expanded it. The text is the point, so it is always visible.

Tapping it opens the run screen.

### Lock-screen takeover (accountability)

An optional setting, **"Take over the lock screen"**, on by default. When it is
on, turning the screen on during a run shows the **routine itself over the lock
screen** — the current task, the time left, the next task and a progress bar —
instead of your normal lock screen. Tapping it opens the run screen.

It is deliberately **not a lock**. Swipe it away or tap Dismiss and you are back
on your normal lock screen with full access to your phone. The point is
accountability: when you pick up your phone on autopilot mid-routine, the first
thing you see is what you should be doing, and you have to consciously dismiss it
every time. Turn it off in Settings → Accountability if you would rather not have
it.

Under the hood this uses an Android **full-screen intent** on the ongoing run
notification, which is the only way a third-party app can draw over the keyguard.
It needs the "Full-screen alerts" permission from the setup checklist.

Because Android blocks background activity starts, the takeover is delivered by
**re-posting the run notification when the screen wakes while locked**, so the
platform itself launches the takeover over the keyguard. If the phone is already
unlocked, the takeover is launched directly instead. The activity also asks the
system to dismiss the keyguard once it is showing, because on a secure lock
screen the keyguard is otherwise drawn on top of it.

### Home-screen widget

A small Glance widget shows the current task and time left on the home screen
while a routine is running.

### Routines and scheduling

- Routines can be **weekly** (pick the days) or a **one-off** (pick a date). A
  one-off runs once and then turns itself off.
- Each routine can be enabled or disabled, and its alarms cancelled, from the
  routine list.
- A first-run **permissions checklist** walks through the Android permissions
  and Samsung battery settings that alarms need to fire reliably.

---

## How it is built

| Layer | Choice |
| --- | --- |
| Language | Kotlin |
| UI | Jetpack Compose + Canvas (custom grid drawing and gestures) |
| Architecture | MVVM with unidirectional data flow (ViewModel + StateFlow) |
| Storage | Room (SQLite), local and offline |
| Preferences | DataStore (active run session, alarm settings) |
| DI | Hilt |
| Alarms | `AlarmManager` exact alarms |
| Alerts | `NotificationManager`, full-screen intent, `MediaSessionCompat` media card |
| Background | `BroadcastReceiver`s for alarms, boot and time changes |
| Widget | Glance |
| Testing | JUnit for pure logic, Compose UI tests for gestures |

### Core principle

**Store only an ordered list of tasks with durations. Derive everything else.**

- A task's start minute is the sum of the durations before it.
- The grid is just a way to draw that timeline: `row = minute / cols`,
  `col = minute % cols`.
- Run state is a pure function of the routine, the task list and the wall clock,
  so nothing about progress is stored and the app resumes correctly after being
  killed.

`domain/` is plain Kotlin with no Android imports, which is why the schedule,
buffer and run-state logic is unit-testable.

### Package layout

```
app/src/main/java/com/adhd/routineplanner/
  App.kt                  Hilt application
  MainActivity.kt         Single activity, Compose host
  data/                   Room entities, DAOs, repositories, DataStore stores
  domain/                 Pure Kotlin: Buffer, Schedule, RunState, Drag, Reorder
  alarm/                  AlarmScheduler, receivers, alarm UI, run notification
  ui/plan/                Plan screen, grid canvas, drag overlay, task dialogs
  ui/run/                 Run screen, run grid, water box, countdown
  ui/routines/            Routine list, creation, settings, permissions checklist
  ui/settings/            Settings screen and stock-task management
  ui/theme/               Colour tokens, typography, dark theme
  viewmodel/              Plan, Run, RoutineList, RoutineCreate, Settings
  widget/                 Glance home-screen widget
```

---

## Development environment

This project is set up to be **written in VS Code** and **deployed from either
VS Code or Android Studio**, over **wireless debugging** to a Samsung Galaxy A54.

### What is installed

| Component | Location | Notes |
| --- | --- | --- |
| JDK 21 (Temurin) | `~/.local/share/jdk/jdk-21.0.13.1+2` | Android Gradle Plugin 8.x needs JDK 17–21. The system default is JDK 25, which AGP rejects, so Gradle is pinned to this JDK via `org.gradle.java.home` in [`gradle.properties`](gradle.properties). |
| Android SDK | `~/Android/Sdk` | Platform 36, build-tools 36.0.0, platform-tools (adb). |
| Android Studio | `~/.local/share/android-studio` | Launches from the app menu as "Android Studio". Used for Compose previews and the Run button. |
| Gradle | via `./gradlew` | Wrapper pinned to 8.14.3. |

The shell profile (`~/.bashrc`) exports `JAVA_HOME`, `ANDROID_HOME`,
`ANDROID_SDK_ROOT` and adds `adb` to `PATH`. Open a new terminal after setup for
these to apply.

### Toolchain versions

| Tool | Version |
| --- | --- |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.2.21 |
| KSP | 2.2.21-2.0.5 |
| Compose BOM | 2026.06.01 (Compose 1.11.4) |
| Hilt | 2.57.2 |
| Room | 2.8.5 |
| compileSdk / targetSdk / minSdk | 36 / 35 / 33 |

> Compose BOM 2026.06.01 is the newest that works with AGP 8.x. Newer BOMs pull
> Compose 1.12+, which requires AGP 9.1+. Upgrading AGP is a separate, deliberate
> task.

---

## Daily workflow

### Writing code

Open the project folder in VS Code. Recommended extensions are listed in
[`.vscode/extensions.json`](.vscode/extensions.json) and VS Code will prompt to
install them.

Useful tasks are in [`.vscode/tasks.json`](.vscode/tasks.json) — run them with
**Terminal → Run Task**:

- **Android: Build debug APK**
- **Android: Deploy to phone**
- **Android: Deploy + logs**
- **Android: Reconnect wireless + deploy**
- **Android: Pair wireless debugging**
- **Android: Run unit tests**

### Deploying from VS Code

```bash
./deploy.sh            # build, install, launch
./deploy.sh --build    # build only, no device needed
./deploy.sh --install  # install the existing APK
./deploy.sh --logs     # install, launch, stream logcat
./deploy.sh --wireless # reconnect wireless adb, then build and install
./deploy.sh --unlock   # clear a stale build lock
```

### Deploying from Android Studio

Open the project in Android Studio, select the phone in the device dropdown, and
press **Run**. Android Studio shares the same SDK and the same wireless adb
connection, so no extra setup is needed.

### Building from both without collisions

Android Studio and `deploy.sh` share `app/build/`, so two simultaneous builds
fail with a Gradle lock error. `deploy.sh` takes an exclusive lock on
`.gradle-build.lock` before building:

- If nothing else is building, it proceeds immediately.
- If Android Studio is mid-build, it prints
  `Another Gradle build is running (Android Studio?)` and waits up to 120 seconds
  for the lock.
- If the wait expires, it exits with a message rather than corrupting the build
  directory.

Tune the wait with `DEPLOY_LOCK_WAIT=300 ./deploy.sh`. If a build was killed with
`Ctrl+C` or `kill -9` and left a stale lock, clear it with `./deploy.sh --unlock`
(or the **Android: Clear stale build lock** VS Code task).

The lock only protects builds started through `deploy.sh`. Android Studio does
not know about it, so pressing **Run** in Studio while `deploy.sh` is building
will still collide. The practical rule: **use one build trigger at a time.**

---

## Wireless debugging (one-time pairing)

1. On the phone: **Settings → About phone → Software information**, tap **Build
   number** seven times to enable Developer options.
2. **Settings → Developer options → Wireless debugging → On**.
3. Tap **Pair device with pairing code**. Note the **IP:port** and the **6-digit
   code**.
4. On this machine:

   ```bash
   ./pair-wireless.sh
   ```

   Enter the pairing IP:port and code, then the **connect** IP:port from the main
   Wireless debugging screen (it is a different port from the pairing dialog).

The connect address is saved to `.wireless-address` (gitignored). After a reboot
or network change, reconnect with:

```bash
./deploy.sh --wireless
```

### USB fallback

If wireless is unavailable, connect the phone by USB with USB debugging enabled.
`./deploy.sh` will find it automatically.

---

## Testing

Pure logic in `domain/` and the view models is covered by JUnit tests under
`app/src/test/`. Run them with:

```bash
./gradlew :app:testDebugUnitTest
```

Compose gesture tests live under `app/src/androidTest/` and run on a device or
emulator.

---

## Troubleshooting

**`Unsupported class file major version` or a JDK error**
Gradle is using the wrong JDK. Confirm `org.gradle.java.home` in
[`gradle.properties`](gradle.properties) points at the JDK 21 path, and that the
directory still exists.

**`No device connected`**
Run `adb devices`. If empty, re-pair with `./pair-wireless.sh`, or plug in USB.
Wireless debugging must be on and both devices on the same Wi-Fi network.

**`Dependency requires Android Gradle plugin 9.1.0 or higher`**
A dependency was bumped past what AGP 8.x supports. Pin the Compose BOM back to
2026.06.01, or upgrade AGP deliberately.

**`Build directory is locked`**
Android Studio and a terminal build are running at the same time. Close one. If
`deploy.sh` reports the lock is still held after waiting, either let the Studio
build finish or run `./deploy.sh --unlock` if the previous build was killed.

**Alarms do not fire on the A54**
Samsung battery management suppresses alarms. Set the app to **Battery →
Unrestricted**, disable **Put unused apps to sleep**, and keep it out of
**Sleeping apps**. The first-run permissions checklist automates these checks.

**The lock-screen card does not appear**
It only shows while a routine is running. Check that notifications are allowed
for the app and that the run notification is not blocked on the lock screen in
**Settings → Notifications → Lock screen**.
