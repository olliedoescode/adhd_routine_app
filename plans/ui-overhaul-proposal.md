# UI Overhaul Proposal — "Nicer Vibes, More Ergonomic, More ADHD-Friendly"

> **Status:** Proposal / discussion document. Nothing here is implemented yet.
> This is a menu of ideas, ordered by impact and effort, with concrete
> implementation notes tied to the current code. Pick the ones you want and we
> can turn them into a phased plan.
>
> **Caveat on the implementation plan:** as you said, `plans/implementation-plan.md`
> has drifted from the code. This document is grounded in the **actual code** and
> the **run state machine** (`plans/run-state-machine.md`), which is the accurate
> source of truth for run behaviour. Where I reference a file, I've read it.

---

## 0. TL;DR — the ten changes that would matter most

If you only do ten things, do these. They are ordered by "how much better does
the app feel to a person with ADHD on a bad morning".

| # | Change | Why it matters | Effort |
| --- | --- | --- | --- |
| 1 | **Vertical timeline editor** replacing the minute-grid as the *default* edit view | The grid is clever but hostile to edit on a phone. A list of draggable blocks with visible durations is what people actually understand. | M–L |
| 2 | **"Now / Next" run screen** with one giant task card instead of a grid-first layout | During a run you need *one* thing on screen: what to do now and how long is left. The grid is a nice-to-have, not the primary. | M |
| 3 | **One-tap "Start this routine now"** from the routine list, plus a "resume" card | Removes the list → plan → run three-step just to start. | S |
| 4 | **Quick-add task sheet** with big preset chips and a duration slider (no clock wheels by default) | The hours/minutes/seconds wheels are fiddly. Most tasks are 5/10/15/20/30 min. | S–M |
| 5 | **"Skip / Done early / Need more time"** controls during a run | ADHD runs over- and under-shoot constantly. The run must bend, not break. | M |
| 6 | **A calm, warm visual theme** (soft gradients, rounded cards, one accent) replacing the flat near-black + white | "Nicer vibes" — the current theme is functional but cold. | S–M |
| 7 | **Empty states and first-run guidance** that teach by doing | A blank grid with a `+` is a wall for an ADHD brain. | S |
| 8 | **Routine templates / "starter packs"** (Morning, Wind-down, Meds, Gym) | Beats the blank page. One tap to a working routine you then tweak. | M |
| 9 | **Gentle "you're behind" handling** — the run auto-reflows instead of silently overrunning | Overrun currently just… happens. Make it visible and forgiving. | M |
| 10 | **A "brain dump" inbox** — dump tasks fast, sort them into routines later | Capturing a thought must be instant or it's lost. | M |

The rest of this document expands each of these, then covers run-screen design,
edit-screen design, visual language, and a long list of feature ideas (LLM,
Google Calendar, desktop companion, and ADHD-specific mechanics).

---

## 1. What the app is today (grounded in the code)

A quick, honest inventory so the proposals make sense.

### Screens and navigation
- [`AppNavHost.kt`](../app/src/main/java/com/adhd/routineplanner/ui/AppNavHost.kt:30)
  defines: `routines` (list) → `routines/new` (create) → `plan/{id}` → `run/{id}`,
  plus `settings` and `permissions`.
- The **routine list** ([`RoutineListScreen.kt`](../app/src/main/java/com/adhd/routineplanner/ui/routines/RoutineListScreen.kt:101))
  is a `LazyColumn` of cards with swipe-to-delete, long-press multi-select, and a
  top bar with `+` and a settings overflow.
- The **plan screen** ([`PlanScreen.kt`](../app/src/main/java/com/adhd/routineplanner/ui/plan/PlanScreen.kt:145))
  is the editor: a minute-grid (`GridCanvas`), a pinch-zoom, a resize strip, a
  drag-to-reorder system, a list-view toggle, and a bottom "drag here to remove"
  tray. It's the most complex screen in the app.
- The **run screen** ([`RunScreen.kt`](../app/src/main/java/com/adhd/routineplanner/ui/run/RunScreen.kt:91))
  is a fixed header (water box + countdown) over an auto-scrolling minute grid,
  with a single "End routine" pill.
- **Create** ([`RoutineCreateScreen.kt`](../app/src/main/java/com/adhd/routineplanner/ui/routines/RoutineCreateScreen.kt:125))
  is a long single-scroll form: name, start/end time pickers, one-off date vs
  weekly days, buffer switch, and a reorderable staged task list.
- **Settings** ([`SettingsScreen.kt`](../app/src/main/java/com/adhd/routineplanner/ui/settings/SettingsScreen.kt:94))
  covers alarm tone/volume/vibration, the lock-screen takeover toggle, and the
  stock-task library.

### Run behaviour (the accurate part)
- Run state is **derived from the wall clock**, never ticked
  ([`RunState.kt`](../app/src/main/java/com/adhd/routineplanner/domain/RunState.kt:31)).
  Phases: `BeforeStart`, `Running`, `Finished`.
- The **presence flag** + full-screen-intent takeover is the accountability
  mechanism ([`plans/run-state-machine.md`](run-state-machine.md)). This works and
  should be preserved through any redesign.
- The ongoing notification is the single source of "what's happening now"
  ([`RunViewModel.kt`](../app/src/main/java/com/adhd/routineplanner/viewmodel/RunViewModel.kt:194)).
- There is **no in-run interaction** beyond "End routine". You cannot skip, pause,
  extend, or mark a task done early. This is the biggest ergonomic gap.

### Visual language today
- Dark-only, near-black `#0E0F12` background, `#16181D` surface, white-ish text
  ([`Color.kt`](../app/src/main/java/com/adhd/routineplanner/ui/theme/Color.kt:6)).
- One accent per task from a 12-colour palette; red reserved for countdown.
- Typography is mostly Material defaults; the countdown uses a monospace
  fallback ([`Type.kt`](../app/src/main/java/com/adhd/routineplanner/ui/theme/Type.kt:19)).
- Everything is flat rectangles with 8–12dp corners. Functional, but cold.

---

## 2. Design principles for an ADHD brain

These are the lenses I used to judge every idea below. They come from how ADHD
actually behaves, not from generic "good UX".

1. **One thing at a time.** Working memory is the bottleneck. Every screen should
   answer exactly one question. The run screen answers "what now?"; the list
   answers "what's coming up?"; the editor answers "what's in this routine?".
2. **Reduce the cost of starting.** The distance between "I want to do my
   routine" and "the routine is running" should be one tap. Today it's
   list → plan → play.
3. **Make time visible and forgiving.** Time blindness is core ADHD. Show time
   as *space* (blocks, bars, water) and never punish overrun — reflow instead.
4. **Externalise memory.** Anything the user has to remember ("did I take my
   meds?", "what's after this?") should be on screen or in a notification.
5. **Reward the small wins.** A visible "done" moment, a streak, a satisfying
   check — dopamine is the currency. The current run just says "Done".
6. **Forgive interruption.** Runs get abandoned. Resuming must be trivial and
   must not feel like failure.
7. **Low-arousal by default, high-arousal on demand.** Calm colours and gentle
   motion normally; strong alarm only at the boundary the user chose.
8. **Never a blank page.** Templates, presets, and suggestions beat a `+`.

---

## 3. The run screen — the heart of the app

This is where the app earns its keep, so it gets the most detail.

### 3.1 Problem with the current run screen
- The **grid is the hero**, but during a run the grid is mostly *decoration* —
  you can't act on it. The information you need (what now, how long, what next)
  is crammed into a 110dp header.
- There is **no way to interact**: no skip, no "done early", no "I need 5 more
  minutes", no pause. If life happens, the only button is "End routine".
- The **water box** is charming but abstract; the countdown is the real signal.
- Overrun is invisible: when a task's time is up the cursor just keeps going and
  the next task's alarm fires, but the screen doesn't acknowledge "you're behind".

### 3.2 Proposed run screen: "Now / Next" card layout

Replace the grid-first layout with a **single dominant task card** and demote the
grid to a collapsible strip.

```
┌───────────────────────────────────────────┐
│  Morning routine            ⏱ 07:42        │  ← thin top bar: routine + wall clock
├───────────────────────────────────────────┤
│                                           │
│   ┌───────────────────────────────────┐   │
│   │  NOW                              │   │
│   │                                   │   │
│   │  Shower                           │   │  ← huge task name
│   │                                   │   │
│   │        12:34                      │   │  ← huge countdown (the hero)
│   │   ████████████░░░░░░░░            │   │  ← progress bar / water fill
│   │                                   │   │
│   │  [ Done early ]  [ +5 min ]       │   │  ← in-run actions
│   └───────────────────────────────────┘   │
│                                           │
│   NEXT  →  Get dressed · 10 min           │  ← next task, tappable to peek
│                                           │
│   ┌───────────────────────────────────┐   │
│   │  ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░  │   │  ← collapsed grid strip (optional)
│   └───────────────────────────────────┘   │
│                                           │
│              [ End routine ]              │
└───────────────────────────────────────────┘
```

**Why this is better**
- The single card answers "what now?" unambiguously. The countdown is the largest
  element on screen, which matches how time-blind users need time presented.
- "Next" is always visible → externalised memory.
- The grid becomes a **collapsible progress strip** (tap to expand to the full
  grid if you want the overview). Keep the existing `RunGrid` for the expanded
  state — no need to throw it away.
- The water box can live *inside* the card as the progress fill, keeping the
  charm without making it the whole header.

### 3.3 In-run actions (the big ergonomic win)

Add a small action row to the current-task card:

| Action | Behaviour | Notes |
| --- | --- | --- |
| **Done early** | Mark current task complete, jump to next task's start *now*. | Recompute the run's effective start so the remaining tasks shift earlier. Needs a small change to how `runState` maps elapsed → index (see §3.5). |
| **+5 min** (and −5) | Extend/shrink the current task's rendered duration for *this run only*. | Store a per-run override map in `RunSessionStore`; don't mutate the routine. |
| **Skip** | Jump to the next task without marking done. | Same mechanism as Done early but no "completed" credit. |
| **Pause** | Freeze the countdown; show "Paused". | Optional — some users find pause a trap. Could be a setting. |

These are the difference between "the app tells me what to do" and "the app helps
me do it". For ADHD, the ability to *bend* the plan without abandoning it is
what prevents the "well, I've ruined it, might as well stop" spiral.

### 3.4 The "done" moment
When a run finishes, replace the current bare "Done" with a **completion card**:
- A satisfying check animation.
- "Morning routine complete · 58 min · 6 tasks".
- A gentle streak line: "3 mornings in a row 🔥" (see §7 streaks).
- Two buttons: "Done" (back to list) and "Run again".

This is the dopamine hit that makes the app worth opening tomorrow.

### 3.5 Implementation notes for in-run actions
- `RunState` is derived from `(routine, tasks, startMs, nowMs)`. To support
  "done early" and per-run overrides without breaking the wall-clock derivation,
  extend the derivation inputs with a small **run plan** persisted in
  `RunSessionStore`:
  ```kotlin
  data class RunPlan(
      val startMs: Long,
      val overrides: Map<Long, Int> = emptyMap(), // taskId -> minutes this run
      val completedTaskIds: Set<Long> = emptySet(),
      val skippedTaskIds: Set<Long> = emptySet(),
  )
  ```
  `runState` then computes the effective task list (apply overrides, drop
  completed/skipped) before laying out. This keeps the "derive from clock, never
  tick" property while allowing live edits.
- The ongoing notification and takeover read the same derived state, so they stay
  in sync automatically.
- **Preserve the presence-flag / takeover logic exactly** — it's correct and hard
  won. In-run actions don't touch it.

### 3.6 Before-start state
Today `BeforeStart` shows the next task name and a countdown to start. Keep that,
but make it a **"Starting in 4:59"** card with a big "Start now" button, so a
scheduled run can be pulled forward.

---

## 4. Creating and editing routines — the second-biggest win

### 4.1 Problem with the current editor
- The **minute-grid is the default editor**. It's a beautiful *visualisation* but
  a poor *editor* on a phone: dragging a 3-minute block with a finger, resizing by
  a strip, pinch-zooming — these are high-precision gestures on a low-precision
  input. For an ADHD user already low on executive function, this is friction.
- **Creating** is a long form with two time pickers, a date picker, a day row, a
  buffer switch, and a task list — all on one scroll. It's a lot to hold in mind.
- **Adding a task** opens a dialog with hours/minutes/seconds wheels. Most tasks
  are 5–30 minutes; the wheels are overkill.
- The **grid and list views** are a hidden toggle in an overflow menu — most users
  will never find the list view, which is the easier one.

### 4.2 Proposed: vertical timeline as the default editor

Make the **vertical timeline** the primary edit view (the one in
`plans/notes.md` already suggests this). The minute-grid becomes the optional
"overview" mode.

```
┌───────────────────────────────────────────┐
│  ← Morning routine          ⋮              │
│  07:00 – 08:00 · 60 min · 6 tasks          │
├───────────────────────────────────────────┤
│  07:00 ┃                                   │
│        ┃ ┌───────────────────────────────┐ │
│        ┃ │ ⠿  Shower           10 min  ⋮ │ │  ← drag handle, name, duration
│        ┃ └───────────────────────────────┘ │
│  07:10 ┃ ┌───────────────────────────────┐ │
│        ┃ │ ⠿  Get dressed       8 min  ⋮ │ │
│        ┃ └───────────────────────────────┘ │
│  07:18 ┃ ┌───────────────────────────────┐ │
│        ┃ │ ⠿  Breakfast        15 min  ⋮ │ │
│        ┃ └───────────────────────────────┘ │
│  07:33 ┃                                   │
│        ┃  ┌ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┐ │
│        ┃  │  + Add task                    │ │
│        ┃  └ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┘ │
│  08:00 ┃                                   │
├───────────────────────────────────────────┤
│  [ Grid overview ]        [ ▶ Run ]        │
└───────────────────────────────────────────┘
```

**Why this is better**
- **Blocks are proportional to duration** (a 15-min task is visibly taller than a
  5-min one) — time as space, without requiring pixel-precise gestures.
- **Reorder = drag the whole block** by its handle. No minute-level precision.
- **Resize = drag the block's bottom edge** or tap the duration to edit it
  numerically. Both are coarse, forgiving targets.
- **The clock gutter** (07:00, 07:10…) gives the time-blind user an anchor.
- **Add task** is an inline row at the end, not a hidden top-bar `+`.
- The **grid overview** stays available for people who like it, behind a toggle
  that's now visible in the bottom bar, not buried in an overflow menu.

### 4.3 Proposed: quick-add task sheet

Replace the hours/minutes/seconds wheel dialog with a **fast sheet**:

```
┌───────────────────────────────────────────┐
│  Add task                             ✕    │
├───────────────────────────────────────────┤
│  [ Shower ] [ Breakfast ] [ Meds ] [ … ]   │  ← stock-task chips, one tap
│                                            │
│  Name  ┌────────────────────────────────┐  │
│        │ Shower                         │  │
│        └────────────────────────────────┘  │
│                                            │
│  Duration                                  │
│  [ 5 ] [ 10 ] [ 15 ] [ 20 ] [ 30 ] [ 45 ]  │  ← preset chips
│  ──────●───────────────────────────  10 min│  ← slider for anything else
│                                            │
│  Colour  ● ● ● ● ● ● ● ● ● ● ● ●           │
│                                            │
│  🔔 Alarm at start            [ toggle ]   │
│                                            │
│              [ Add task ]                  │
└───────────────────────────────────────────┘
```

- **Preset duration chips** cover 90% of cases in one tap.
- A **slider** (1–60 min, snapping to 1) handles the rest — far easier than three
  wheels.
- **Stock-task chips** at the top fill name/colour/duration in one tap.
- Keep the wheel picker available behind a "fine-tune" link for the rare exact
  value, but don't lead with it.

### 4.4 Proposed: split creation into two steps

The current create screen is one long scroll. Split it:

1. **"What and when"** — name, start time, length (a single "how long is this
   routine?" slider or preset: 30/45/60/90 min), and repeat (weekly days or
   one-off date). This is the *frame*.
2. **"What's in it"** — the timeline editor from §4.2, pre-seeded with stock-task
   chips and (optionally) a template.

This matches how people think: first "I want a 60-minute morning routine", then
"what goes in it". It also means step 2 can be the *same* timeline component used
to edit an existing routine — one editor, not two.

### 4.5 Templates / starter packs
On the create screen, before the blank form, offer **template cards**:

- **Morning** (shower, dressed, breakfast, meds, pack bag)
- **Wind-down** (screens off, teeth, skincare, read, lights out)
- **Meds** (a 2-minute single-task routine with a strong alarm)
- **Gym** (commute, warm-up, workout, cool-down, shower)
- **Deep work start** (desk reset, phone away, pick task, 5-min plan)

Tapping one creates a fully-formed routine the user can tweak. This directly
attacks the blank-page problem and teaches the app's model by example.

### 4.6 Editing ergonomics
- **Inline rename**: tap the routine title in the plan top bar to rename in place
  (today it opens a settings sheet).
- **Undo everywhere**: the plan screen already has undo; extend it to the create
  screen and to routine settings.
- **Autosave**: the create screen currently discards everything on back. Consider
  autosaving a draft so an accidental back doesn't lose work (with a "discard
  draft?" prompt).
- **Duplicate routine**: a "Duplicate" action on the list — most routines are
  variations of each other.

---

## 5. The routine list — make it a dashboard

### 5.1 Problem
The list is a plain set of cards. It doesn't tell you **what's next today**, and
starting a routine is a three-tap journey (card → plan → play).

### 5.2 Proposed: "Today" header + one-tap start
```
┌───────────────────────────────────────────┐
│  Routines                          ⚙       │
├───────────────────────────────────────────┤
│  TODAY                                     │
│  ┌───────────────────────────────────────┐ │
│  │  Morning routine · 07:00 · in 1h 12m  │ │
│  │  [ ▶ Start now ]                      │ │  ← one tap to run
│  └───────────────────────────────────────┘ │
│  ┌───────────────────────────────────────┐ │
│  │  Wind-down · 22:00 · tonight          │ │
│  │  [ ▶ Start now ]                      │ │
│  └───────────────────────────────────────┘ │
│                                            │
│  ALL ROUTINES                              │
│  ┌───────────────────────────────────────┐ │
│  │  Morning routine        07:00   [on]  │ │
│  │  M T W T F S S · 60 min               │ │
│  └───────────────────────────────────────┘ │
│  …                                         │
└───────────────────────────────────────────┘
```

- A **"Today" section** surfaces routines scheduled for today, sorted by time,
  with a live "in 1h 12m" countdown and a **Start now** button.
- If a run is **currently active**, show a **"Resume" card** at the very top
  ("Morning routine running · Shower · 12:34 left · Resume").
- The rest is the existing card list, but with the **enable toggle** and a
  **next-run time** made more prominent.

### 5.3 Card improvements
- Show **next occurrence** ("Tomorrow 07:00" / "Today 07:00" / "Mon, Wed, Fri").
- Show **total duration** and **task count**.
- A **quick "Run" icon** on each card (long-press for "Edit").
- Keep swipe-to-delete + undo (it's good), but add a **"Duplicate"** swipe action
  on the other side.

---

## 6. Visual language — "nicer vibes"

The current theme is a flat, cold near-black. It's legible but has no warmth. The
goal is **calm, soft, and a little bit delightful** without becoming childish or
low-contrast.

### 6.1 Colour
- **Background**: keep dark, but warm it slightly — a very dark blue-charcoal
  (`#12131A`) rather than pure neutral, and consider a subtle vertical gradient
  (top slightly lighter) so the screen has depth.
- **Surface**: soft elevated cards (`#1C1E27`) with a 1dp hairline border at low
  opacity instead of relying on shadow (shadows are invisible on dark).
- **One accent**: pick a single brand accent (a warm coral or a soft violet) used
  for primary actions, the current task, and selection — instead of white-on-black
  everywhere. Task colours remain per-task.
- **Semantic colours**: keep red for "ending/urgent", amber for "last minute",
  add a **green/teal for "done"** (currently there's no positive colour).
- **Gradients**: a soft gradient fill inside the water box / progress bar (task
  colour → lighter) makes progress feel alive.

### 6.2 Shape and spacing
- **Rounded, generous corners**: 16–20dp on cards, 28dp on pills. The current
  8–12dp reads as "developer default".
- **More whitespace**: increase vertical rhythm; let the run card breathe.
- **Consistent elevation language**: cards are `Surface` + hairline border; sheets
  are `Surface` with a top rounded corner and a drag handle.

### 6.3 Typography
- Adopt a **friendly geometric sans** (e.g. Inter, Nunito, or Manrope — all OFL)
  for UI text; it reads warmer than the system default.
- **Big, bold numbers** for the countdown (keep tabular numerals). Consider
  bundling DSEG for the "digital" look, or go the other way with a very large
  humanist number — pick one and commit.
- **Clear hierarchy**: task name large, countdown huge, supporting text small and
  muted.

### 6.4 Motion
- **Gentle, purposeful** animations: card entrance (fade + slide up), progress
  fill (ease), task-boundary crossfade (already present at 200ms — keep it).
- **Haptic + visual "done"**: a check that scales in with a light haptic.
- **Avoid** bouncy/springy motion during a run (it's distracting); reserve delight
  for completion moments.

### 6.5 Accessibility
- Ensure **contrast** stays ≥ 4.5:1 for body text on the new surfaces.
- Respect **reduce-motion** settings (disable the water wave and cursor glide).
- Keep **large touch targets** (≥ 48dp) — especially the in-run action buttons.
- The grid is a Canvas; keep the existing `contentDescription` semantics and
  extend them to the new run card.

---

## 7. ADHD-specific features (beyond the UI)

These are mechanics, not just looks, but they're what make the app *feel* built
for ADHD.

### 7.1 Streaks and gentle gamification
- **Completion streak** per routine ("3 mornings in a row").
- **No punishment for a miss** — a missed day just doesn't add to the streak; it
  never resets to zero with a scary message. (Streak anxiety is real; keep it
  kind.)
- Optional **weekly summary**: "You ran 4 of 5 mornings this week."

### 7.2 "Body doubling" / focus companion
- A calm **ambient timer** mode: a full-screen, minimal countdown with a soft
  animation and no other UI, for when you just need a timer.
- Optional **lo-fi / ambient sound** during a run (off by default).

### 7.3 Transition warnings
- ADHD transitions are hard. Add a **"2 minutes until next task"** gentle nudge
  (a soft notification or in-app banner) *before* the boundary, separate from the
  hard alarm. Configurable per routine.

### 7.4 "Just start" mode
- A **2-minute micro-routine** generator: "I can't do the whole thing, give me the
  first step." Picks the first task and runs it alone. Lowers the activation
  energy barrier.

### 7.5 Brain-dump inbox
- A **quick-capture** button (from the list, or a home-screen widget / share
  target) to dump a task in one line. Later, sort inbox items into routines.
- Capturing must be **instant** — one field, one button, no routine required.

### 7.6 "What was I doing?" recovery
- If the app is opened mid-run after being away, show a **"You were on: Shower,
  3 min left"** card before anything else. (The takeover partly does this; make it
  a first-class in-app state too.)

### 7.7 Flexible "energy" scheduling
- Let a routine be tagged **low/medium/high energy**, and suggest a routine based
  on time of day or a one-tap "I'm low energy today" filter. (Advanced; later.)

---

## 8. Feature ideas you raised, plus more

### 8.1 LLM integration

You're right that it's complicated, but it's very doable in a **narrow, safe**
form. The key is to not make the LLM the brain of the app — make it a **helper**
that proposes, and the user disposes.

**Realistic v1: "Describe your routine" → draft**
- A text box: *"I need a 45-minute morning routine: shower, get dressed, quick
  breakfast, take meds, pack my bag."*
- The LLM returns a **structured draft** (JSON: task names, durations, order,
  colours) which is loaded into the **timeline editor** for the user to tweak.
- Nothing is saved until the user taps Create. The LLM never touches alarms or
  the run.
- **Where it runs**: start with a **cloud API** (simplest), but design the
  interface so a **local model** can be swapped in later (see §8.3).

**Realistic v2: "Adjust my routine"**
- *"Make this routine 10 minutes shorter"* or *"I always run over on breakfast,
  give it more time."* The LLM proposes a diff; the user accepts/rejects.

**Realistic v3: "Coach" during a run (careful)**
- A gentle in-run message ("You're 3 minutes behind — want to skip the last
  step?") generated from run state. This is where it gets risky (latency,
  reliability, cost), so keep it optional and rule-based first, LLM later.

**Design guardrails**
- **Structured output only** (JSON schema), validated before use.
- **Always preview, never auto-apply.**
- **Offline-first**: the app must work fully without the LLM.
- **Privacy**: if cloud, be explicit that routine text leaves the device; offer a
  local-only mode.
- **Cost/latency**: debounce, cache, and make it a deliberate action (a button),
  not something that fires on every keystroke.

**Implementation sketch**
- Add a `RoutineSuggester` interface with two implementations:
  `CloudLlmSuggester` and `LocalLlmSuggester` (later). The UI depends only on the
  interface.
- A new `ui/assist/` package with a "Describe it" sheet that calls the suggester
  and hands a `List<DraftTask>` to the existing create flow.
- Reuse `DraftTask` and the timeline editor — no new data model needed.

### 8.2 Google Calendar integration

Also very doable, and arguably higher-value than the LLM because it's
deterministic.

**Use cases**
1. **Import**: read calendar events (e.g. "Standup 09:00–09:15") and offer to
   build a routine around them, or block time for a routine.
2. **Export**: push each routine as a **recurring calendar event** so it shows in
   the user's normal calendar and on other devices.
3. **Conflict awareness**: warn if a routine overlaps a calendar event.

**Realistic v1: export routines as calendar events**
- Use the **Calendar Provider API** (`CalendarContract`) to insert a recurring
  event for each enabled routine. This needs `READ_CALENDAR`/`WRITE_CALENDAR`
  permissions and a chosen calendar.
- This is local and deterministic — no OAuth server needed if you write to the
  device calendar. (Full Google Calendar API with OAuth is a bigger lift; the
  device calendar provider is the pragmatic first step.)

**Realistic v2: import events as routine anchors**
- Read today's events and show them on the routine list / timeline as fixed
  blocks, so the user can plan around them.

**Realistic v3: two-way sync**
- Full Google Calendar API + OAuth. Only worth it once v1/v2 prove useful.

**Guardrails**
- Never silently modify the user's calendar; always confirm.
- Keep the app's own schedule authoritative for alarms; the calendar is a mirror.

### 8.3 Desktop companion + local LLM (the ambitious one)

This is the most interesting and the most complex. Here's a pragmatic path.

**The vision**
- A **desktop app** (or a local server) on the same network runs a **local LLM**
  and holds the "big picture" of your routines. The phone app talks to it over
  the LAN to generate/adjust routines, and the desktop can push routines to the
  phone.

**Why it's attractive**
- Local LLM = privacy + no per-token cost + works offline (on the LAN).
- The desktop has the compute; the phone has the sensors and the alarms.

**Pragmatic architecture**
1. **Shared data format**: routines/tasks are already simple entities. Define a
   small **JSON schema** (the same one the LLM suggester uses) as the wire format.
2. **Phone as a small server or client**: simplest is the **phone exposes a tiny
   local HTTP endpoint** (or uses **mDNS discovery**) that the desktop calls to
   push a routine. Alternatively the desktop exposes an endpoint the phone polls.
3. **Pairing**: a one-time pairing (QR code / PIN) over the LAN, storing a shared
   secret. Reuse the existing `pair-wireless.sh` spirit for dev.
4. **Transport**: plain HTTP over LAN with a shared token is fine for a personal
   tool; TLS is nice-to-have.
5. **Local LLM on desktop**: the desktop runs e.g. Ollama / llama.cpp, and a small
   bridge service turns "describe a routine" into the JSON schema and pushes it.

**Realistic v1 (much smaller)**
- Skip the LLM entirely at first. Build **"export/import routine as JSON"** (share
  sheet / file). This proves the data format and gives immediate value (backup,
  sharing between devices) without any networking.
- Then add **LAN sync** of that same JSON.

**Realistic v2**
- Desktop bridge with a local LLM that generates the JSON and pushes it.

**Guardrails**
- The phone must remain fully functional offline and standalone.
- Sync is **opt-in** and **conflict-aware** (last-write-wins with a visible
  "updated from desktop" notice).
- Never let the desktop control alarms directly; it only proposes routines.

### 8.4 More feature ideas (not LLM-related)

| Idea | What it is | Why it helps ADHD | Effort |
| --- | --- | --- | --- |
| **Home-screen widget v2** | Show current task + countdown + a "Done early" button | Act without opening the app | M |
| **Quick Settings tile** | A tile to start the next routine / see time left | One swipe from anywhere | S |
| **Share-target capture** | Share text into the app to create a task | Capture a thought from any app | S |
| **Voice quick-add** | "Add shower 10 minutes" via speech | Faster than typing when hands are busy | M |
| **Routine folders / tags** | Group routines (Morning, Evening, Work) | Keeps a long list scannable | S |
| **Per-task notes** | A short note on a task ("take with water") | Externalises detail without clutter | S |
| **Checklists inside a task** | Sub-steps within one timed block | For tasks that are really several steps | M |
| **"Running late" reflow** | One tap to shift the whole routine later | Life happens; don't rebuild the plan | M |
| **History / insights** | "You usually run over on breakfast" | Self-knowledge without judgement | M |
| **Wear OS companion** | Current task + countdown on the wrist | Glanceable, no phone pickup | L |
| **Backup / restore** | Export all routines to a file | Safety net, device migration | S |
| **Themes / accent picker** | Let the user pick the accent colour | Personal ownership → engagement | S |
| **Focus mode (Do Not Disturb)** | Auto-enable DND during a run | Removes the biggest distraction | M |
| **App-blocking during a run** | Optional block of chosen apps while running | Harder to escape into doom-scroll | L |
| **"Panic button"** | One tap to pause everything and breathe | For overwhelm moments | S |

---

## 9. Suggested roadmap (if you want to sequence this)

A pragmatic order that delivers value early and defers the big lifts.

### Phase A — Quick ergonomic wins (1–2 weeks)
- One-tap **Start now** from the routine list + **Resume** card.
- **Quick-add task sheet** (preset duration chips + slider).
- **Empty states** and first-run guidance.
- **Duplicate routine** action.
- Surface the **list/timeline view** toggle in the bottom bar (not overflow).

### Phase B — Run screen redesign (2–3 weeks)
- **Now / Next card** layout with the grid demoted to a collapsible strip.
- **In-run actions**: Done early, +5/−5 min, Skip.
- **Completion card** with a satisfying done moment.
- Extend `RunSessionStore` with a `RunPlan` (overrides + completed/skipped) and
  thread it through `runState` — **without touching the takeover/presence logic**.

### Phase C — Editor redesign (2–3 weeks)
- **Vertical timeline** as the default editor; grid as overview.
- **Two-step creation** (frame → contents).
- **Templates / starter packs**.

### Phase D — Visual refresh (1–2 weeks, can overlap)
- New **colour tokens** (warm dark, one accent, positive colour).
- **Rounded cards, hairline borders, gradients** in the water box.
- **Friendly typeface** + bigger countdown.
- **Motion polish** + reduce-motion support.

### Phase E — ADHD mechanics (ongoing)
- **Streaks** (kind, non-punishing).
- **Transition warnings** (2-min nudge).
- **Brain-dump inbox** + share-target capture.
- **"Just start" micro-routine**.

### Phase F — Integrations (larger, pick what excites you)
- **Export/import JSON** (foundation for everything else).
- **Google Calendar export** (device calendar provider first).
- **LLM "describe your routine"** (cloud first, local later).
- **Desktop companion + LAN sync** (last, most ambitious).

---

## 10. Open questions for you

These are the decisions that shape the work; I've listed my default answer for
each so we can move fast.

1. **Run screen: card-first or grid-first?** My default: card-first, grid as a
   collapsible strip. (Biggest single change; worth confirming.)
2. **Editor: timeline default or grid default?** My default: timeline default,
   grid as overview.
3. **In-run actions: which ones?** My default: Done early, +5/−5, Skip. Pause is
   optional and off by default.
4. **Visual direction: warm dark + one accent, or keep neutral?** My default:
   warm dark with a single coral/violet accent and a positive green.
5. **Which integration first?** My default: JSON export/import → Google Calendar
   export → LLM describe → desktop sync.
6. **How much gamification?** My default: gentle streaks + a completion card, no
   points/levels/leaderboards.

---

## 11. What I'd protect through the overhaul

A few things in the current code are genuinely good and should survive any
redesign:

- **Wall-clock-derived run state** ([`RunState.kt`](../app/src/main/java/com/adhd/routineplanner/domain/RunState.kt:31)) — never tick, always derive. Keep it.
- **The presence-flag + full-screen-intent takeover** ([`plans/run-state-machine.md`](run-state-machine.md)) — correct and hard-won. Don't regress it.
- **Undo on every edit** (plan screen) — extend it, don't remove it.
- **The single ongoing notification** as the source of "what now" — keep it in
  sync with the new run card.
- **Offline-first, local-only** — any integration must be additive and optional.
- **Large touch targets and the "one explicit exit" pattern** on the run screen —
  the current End-routine pill is well designed; keep that spirit.

---

*End of proposal. Pick the phases/ideas you want and I'll turn them into a
concrete, file-by-file implementation plan.*