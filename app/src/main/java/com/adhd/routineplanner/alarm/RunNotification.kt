package com.adhd.routineplanner.alarm

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.core.app.NotificationCompat
import com.adhd.routineplanner.MainActivity
import com.adhd.routineplanner.R
import com.adhd.routineplanner.data.AlarmSettings
import com.adhd.routineplanner.data.RoutineRepository
import com.adhd.routineplanner.data.RunSessionStore
import com.adhd.routineplanner.domain.IconKeys
import com.adhd.routineplanner.domain.Phase
import com.adhd.routineplanner.domain.RunState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.firstOrNull

/**
 * The single ongoing notification shown while a routine is running
 * (spec section 15.8).
 *
 * It is a plain, ongoing notification — deliberately *not* a media
 * notification. There is no `MediaSession` and no `MediaStyle`: the run has no
 * transport controls, so a media card only added a now-playing panel the user
 * could not interact with. A normal ongoing notification is enough to stay
 * pinned in the shade and on the lock screen.
 *
 * It is *live* in the sense that matters: it is re-posted once per second with
 * the current task and the `m:ss` countdown, it is `setOngoing(true)` so it
 * cannot be swiped away, and it is `VISIBILITY_PUBLIC` on a high-importance
 * channel so it stays on the lock screen. None of that requires a media
 * session — a media session is only needed for transport controls, which this
 * card deliberately does not have.
 *
 * It is also *colourful*: the small icon and the app name are tinted with a
 * per-phase accent ([accentFor]) and the card is colourised, so it reads as a
 * green "running" card, an amber "starting soon" card or a blue "finished"
 * card rather than a grey slab.
 *
 * It lives on the [NotificationChannels.RUN_CHANNEL_ID] channel (high
 * importance, but muted per notification), and is updated in place once per
 * second by [com.adhd.routineplanner.viewmodel.RunViewModel].
 *
 * Layout, most important first:
 *  - title: the task currently running ("Shower")
 *  - second line: time left on that task, then the next task
 *  - third line: the routine name, which matters least
 *
 * Tapping the notification opens [MainActivity] with
 * [AlarmActivity.ACTION_OPEN_RUN], which lands the user straight on the run
 * screen for that routine.
 *
 * There is at most one active run, so there is at most one of these
 * notifications; [NOTIFICATION_ID] is a constant and every update replaces the
 * previous one.
 */
object RunNotification {

    /** Stable id: one ongoing run notification at a time. */
    const val NOTIFICATION_ID: Int = 0x7E57_0001

    /**
     * Request code for the "open the run screen" content intent.
     *
     * Deliberately distinct from [TAKEOVER_REQUEST_CODE]. `PendingIntent`
     * equality ignores extras, so two `getActivity` calls with the same request
     * code and the same target activity are considered the *same* intent — the
     * second call silently overwrote the first. Sharing a code between the
     * content intent and the full-screen intent meant the full-screen intent
     * could be replaced by the plain "open run" intent, so the takeover never
     * launched.
     */
    private const val CONTENT_REQUEST_CODE: Int = 0x7E57_0002

    /** Request code for the takeover full-screen intent's PendingIntent. */
    private const val TAKEOVER_REQUEST_CODE: Int = 0x7E57_0003

    /**
     * True while a takeover re-post is mid-flight.
     *
     * Kept only so [cancel] can reset it; the notification now carries the
     * full-screen intent *continuously* (see [show]), so there is no longer a
     * cancel/settle/post race to guard against. The field is retained so the
     * public surface ([clearTakeoverArmed], [cancel]) stays stable for callers.
     */
    @Volatile
    private var takeoverInFlight: Boolean = false

    /**
     * Retained for API compatibility with [RunTakeoverActivity] and [cancel].
     *
     * The old design armed the takeover only for a single screen-on and had the
     * per-second updater leave the notification alone until it was consumed.
     * That is exactly what made the takeover appear only once: every plain
     * update stripped the full-screen intent, so a later screen-on had nothing
     * to fire. The notification now keeps the full-screen intent attached for
     * the whole run, so this flag no longer gates anything.
     */
    @Volatile
    private var takeoverArmed: Boolean = false

    /**
     * Posts or updates the ongoing notification for [routineId].
     *
     * [taskName] is the task currently running, or `null` before the start and
     * after the finish. [nextTaskName] is the task after it. [state] supplies
     * the phase and the seconds remaining so the copy matches the on-screen
     * countdown exactly.
     *
     * [takeover] attaches a full-screen intent to [RunTakeoverActivity], so
     * turning the screen on during the run shows the routine over the lock
     * screen (plans/accountability-lockscreen.md). It is driven by the
     * "Take over the lock screen" setting. The full-screen intent only fires
     * because [NotificationChannels.RUN_CHANNEL_ID] is `IMPORTANCE_HIGH`.
     */
    fun show(
        context: Context,
        routineId: Long,
        routineName: String,
        taskName: String?,
        state: RunState,
        nextTaskName: String? = null,
        takeover: Boolean = false,
        taskIcon: String? = null,
        paused: Boolean = false,
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Only the silent run channel matters here; the alarm channel's tone is
        // irrelevant to this notification, so defaults are fine.
        NotificationChannels.ensureCreated(context, AlarmSettings())

        val routineLabel = routineName.ifBlank { "Routine" }
        // Share the exact copy with the lock-screen widget so the two never
        // disagree about the current task, the next task or the time left.
        val snapshot = RunSnapshot(
            routineId = routineId,
            routineName = routineLabel,
            taskName = taskName,
            state = state,
            nextTaskName = nextTaskName,
            taskIcon = taskIcon,
            // A paused run's countdown is frozen, so the card has to say so —
            // otherwise a stalled "12:04 left" reads as a broken notification.
            paused = paused,
        )

        // The header is the current task, not the routine: the thing you look
        // for when you glance at your phone is "what should I be doing now".
        val title = snapshot.title()
        // Line two: time left on this task, then what comes next.
        val detail = snapshot.detailLine()
        // Line three: the routine name, which is the least important part.
        val routineLine = snapshot.routineLine()

        val openRun = PendingIntent.getActivity(
            context,
            CONTENT_REQUEST_CODE,
            openRunIntent(context, routineId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // A colour per phase, so the card is not a grey slab: green while a task
        // is running, amber before the start, blue once finished. The colour is
        // applied to the small icon and the title, which is what Android tints
        // in the collapsed row and on the lock screen.
        val accent = accentFor(state.phase)

        val builder = NotificationChannels.runBuilder(context)
            .setContentTitle(title)
            .setContentText(detail)
            // The current task's own icon, so a stock task keeps its identity
            // once it is running. Falls back to the generic task icon before the
            // start, after the finish, and for hand-typed tasks.
            .setSmallIcon(smallIconFor(snapshot.taskIcon))
            // Tint the small icon and the app name with the phase colour. This
            // is the "colourful" part: without it the card is monochrome.
            .setColor(accent)
            .setColorized(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            // MAX, not DEFAULT: a full-screen intent is only honoured for a
            // high-priority notification. The channel is already HIGH, but the
            // per-notification priority is what the platform checks when
            // deciding whether to launch the takeover over the keyguard.
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openRun)
            // Live on the lock screen: PUBLIC means the full content is shown
            // even when the device is locked, rather than being hidden behind
            // "Contents hidden". Combined with the channel's own
            // VISIBILITY_PUBLIC and IMPORTANCE_HIGH, this is what keeps the
            // ongoing card visible on the lock screen without being a media
            // notification.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // The channel keeps a sound configured so the platform does not
            // classify it as a *silent* channel (silent channels are dropped
            // from the lock screen on several OEM builds). The sound is muted
            // here instead, per notification, so the card never makes a noise.
            //
            // `setDefaults(0)` is deliberately *not* used: on some builds it
            // marks the notification as silent, which has the same lock-screen
            // problem the channel sound was added to avoid. `setSilent(true)`
            // alone mutes it without that side effect.
            .setSilent(true)
            // No progress bar. A determinate bar takes the second line of the
            // collapsed row, which pushed the time left and the next task out of
            // sight until the notification was expanded. The text is the point,
            // so the bar is gone and the detail line is always visible.

        // The accountability takeover (plans/accountability-lockscreen.md): a
        // full-screen intent is the only way a third-party app can put a screen
        // over the keyguard. Android fires it when the screen turns on, so the
        // user sees the routine instead of the normal lock screen. It is
        // dismissable — the activity just finishes — so this is friction, not a
        // lock. Gated by the "Take over the lock screen" setting.
        if (takeover) {
            // One *stable* request code for the whole run. The old code bumped
            // the code on every "forced" re-post to make Android treat it as a
            // brand-new intent. That was the root of two bugs:
            //
            //  - It was only unique per second, so two screen-ons in the same
            //    second (or a post that reused the base code) produced an FSI the
            //    platform had already consumed, and nothing fired — the takeover
            //    "worked once then stopped".
            //  - A bumped code is a *new* full-screen intent, which Android
            //    launches *immediately*. On the alarm Dismiss path that made the
            //    takeover (and the alarm) re-fire the instant it was dismissed.
            //
            // A stable code means re-posting identical content is a no-op update
            // that fires nothing, which is exactly what we want for the
            // per-second refreshes. To make the takeover appear on a genuine
            // screen-on, [rearmForScreenOn] cancels and re-posts the whole
            // notification, which the platform treats as a fresh post and fires.
            val takeoverIntent = PendingIntent.getActivity(
                context,
                TAKEOVER_REQUEST_CODE,
                RunTakeoverActivity.intent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setFullScreenIntent(takeoverIntent, /* highPriority = */ true)
        }

        // Expanded view: the detail line, then the routine name underneath.
        //
        // The routine name is deliberately *not* set as the summary text: a
        // summary renders at the top of the expanded notification, which put the
        // routine name above the task and duplicated it. It belongs at the
        // bottom, as the least important line.
        val expanded = buildString {
            append(detail)
            if (routineLine.isNotEmpty()) {
                append('\n')
                append(routineLine)
            }
        }
        builder.setStyle(NotificationCompat.BigTextStyle().bigText(expanded))

        val notification = builder.build()
        manager.notify(NOTIFICATION_ID, notification)
        // Keep the process alive for the whole run with a foreground service.
        // Without it, swiping the app away from Recents killed the process, the
        // runtime-registered UserPresentReceiver stopped receiving SCREEN_ON /
        // USER_PRESENT, and the lock-screen takeover stopped working until the
        // app was reopened. The service holds this same notification, so the
        // user sees no difference on screen.
        RunForegroundService.ensureRunning(context, notification)
    }

    /** Removes the ongoing notification, e.g. when the run is stopped. */
    fun cancel(context: Context) {
        // A cancelled run has no takeover to arm; clear the guards so a later
        // run is not blocked from posting.
        takeoverArmed = false
        takeoverInFlight = false
        // Drop the foreground service first so its notification cannot be
        // re-shown after we cancel it.
        RunForegroundService.stop(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
    }

    /**
     * A minimal card for [RunForegroundService] to post *synchronously* when the
     * system restarts it (START_STICKY) with no intent, e.g. after the process
     * was reclaimed. It is replaced by the live card as soon as the persisted
     * session loads (see [postSnapshot]).
     */
    fun buildPlaceholder(context: Context): Notification =
        NotificationChannels.runBuilder(context)
            .setContentTitle("Routine running")
            .setContentText("Keeping your routine active")
            .setSmallIcon(R.drawable.ic_task_generic)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

    /**
     * Posts the live run card from a [RunSnapshot]. Used by
     * [RunForegroundService] when it rebuilds after a process restart, and by
     * [rearmForScreenOn] when re-arming the full-screen intent on a wake.
     */
    fun postSnapshot(context: Context, snapshot: RunSnapshot, takeover: Boolean) {
        show(
            context = context,
            routineId = snapshot.routineId,
            routineName = snapshot.routineName,
            taskName = snapshot.taskName,
            state = snapshot.state,
            nextTaskName = snapshot.nextTaskName,
            takeover = takeover,
            taskIcon = snapshot.taskIcon,
        )
    }

    /**
     * Retained for API compatibility; the takeover no longer needs a guard.
     *
     * The notification keeps its full-screen intent attached for the whole run
     * (see [show]), so there is nothing to "clear" when the takeover appears.
     * [RunTakeoverActivity] still calls this on create/new-intent; it is a
     * harmless no-op now.
     */
    fun clearTakeoverArmed() {
        takeoverArmed = false
    }

    /**
     * Re-arms the takeover for a genuine screen-on.
     *
     * Called from [UserPresentReceiver] on `ACTION_SCREEN_ON` — i.e. every time
     * the screen transitions off→on, whether the device is locked or not.
     *
     * ## Why cancel-then-repost, not a request-code bump
     *
     * Android fires a full-screen intent when the screen turns on *if the
     * notification carrying it is present and the intent has not already been
     * consumed for that post*. Re-posting identical content is a no-op update
     * that fires nothing, so a plain re-post would leave the takeover stuck
     * after the first dismissal ("works once then stops").
     *
     * The old fix bumped the FSI request code to force a "new" intent. That was
     * wrong in two ways: the code was only unique per second (so two wakes in
     * the same second collided and nothing fired), and a bumped code is a brand
     * new intent that Android launches *immediately* — which is what made the
     * alarm re-fire the instant it was dismissed.
     *
     * Cancelling the notification and posting it again is a genuinely fresh
     * post with the *same stable* FSI request code. The platform treats the
     * re-post as new and fires the full-screen intent on this screen-on, while
     * the stable code means the per-second refreshes in between remain no-op
     * updates that never fire.
     *
     * The snapshot is rebuilt from the session so the card is correct even if
     * the run view model is not alive (the app may have been killed).
     */
    suspend fun rearmForScreenOn(context: Context, routineId: Long) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            RunNotificationEntryPoint::class.java,
        )
        val session = entryPoint.runSessionStore().session.firstOrNull() ?: return
        if (session.routineId != routineId) return
        val repository = entryPoint.routineRepository()
        val snapshot = RunSnapshot.load(repository, session, System.currentTimeMillis()) ?: return
        // A finished run has nothing to take over; posting a full-screen intent
        // for it would flash an empty screen over the keyguard.
        if (snapshot.isFinished) return

        // Cancel first so the following notify() is a genuinely fresh post the
        // platform will fire the full-screen intent for. The stable request code
        // is reused, so this never creates a "new" intent that launches early.
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)

        show(
            context = context,
            routineId = snapshot.routineId,
            routineName = snapshot.routineName,
            taskName = snapshot.taskName,
            state = snapshot.state,
            nextTaskName = snapshot.nextTaskName,
            takeover = true,
            taskIcon = snapshot.taskIcon,
        )
    }

    /**
     * The accent colour for a phase, used to tint the small icon and the app
     * name so the card is colourful rather than monochrome.
     *
     * The values match the app's own palette (see `ui/theme/Color.kt`): the
     * green is the "running" colour, the amber is the countdown-warning colour
     * and the blue is the calm "done" colour. They are plain ARGB ints here
     * because a notification builder wants an `Int`, not a Compose `Color`.
     */
    /**
     * Maps a stock-task icon key to the notification's small-icon drawable.
     *
     * Small icons must be Android resources, not Compose [ImageVector]s, so each
     * key has a matching white-on-transparent vector under `res/drawable`. An
     * unknown key (or `null` before the start / after the finish) falls back to
     * the generic task icon.
     */
    private fun smallIconFor(iconKey: String?): Int = when (iconKey) {
        IconKeys.ALARM -> R.drawable.ic_task_alarm
        IconKeys.SHOWER -> R.drawable.ic_task_shower
        IconKeys.DRESS -> R.drawable.ic_task_dress
        IconKeys.BREAKFAST -> R.drawable.ic_task_breakfast
        IconKeys.BRUSH_TEETH -> R.drawable.ic_task_brush_teeth
        IconKeys.MEDICATION -> R.drawable.ic_task_medication
        IconKeys.WATER,
        IconKeys.WASH_FACE,
        IconKeys.WASH_UP,
        -> R.drawable.ic_task_water
        IconKeys.MEDITATE -> R.drawable.ic_task_meditate
        IconKeys.STRETCH,
        IconKeys.WARM_UP,
        IconKeys.WORKOUT,
        -> R.drawable.ic_task_workout
        IconKeys.NIGHT -> R.drawable.ic_task_night
        // Everything else — reading, cooking, laundry, admin, custom tasks —
        // has no dedicated silhouette yet, so it shares the generic mark rather
        // than borrowing a wrong one.
        else -> R.drawable.ic_task_generic
    }

    private fun accentFor(phase: Phase): Int = when (phase) {
        Phase.Running -> Color.rgb(0x34, 0xC7, 0xA1)
        Phase.BeforeStart -> Color.rgb(0xFF, 0xB0, 0x20)
        Phase.Finished -> Color.rgb(0x4F, 0x8E, 0xF7)
    }

    /**
     * The intent that opens the app directly in run mode for [routineId].
     *
     * Reuses [AlarmActivity.ACTION_OPEN_RUN] and its extra so [MainActivity]
     * has a single code path for "open the run screen", whether the user came
     * from an alarm or from this notification.
     */
    private fun openRunIntent(context: Context, routineId: Long): Intent =
        Intent(context, MainActivity::class.java).apply {
            // `setAction`, not `action =`: inside `apply` the name resolves to
            // the read-only `Intent.action` getter.
            setAction(AlarmActivity.ACTION_OPEN_RUN)
            putExtra(AlarmActivity.EXTRA_ROUTINE_ID, routineId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

    /**
     * The few Hilt bindings [rearmForScreenOn] needs.
     *
     * This object is not injectable (it is a plain singleton called from a
     * broadcast receiver and a view model), so the dependencies are pulled from
     * the application component on demand rather than held as fields.
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface RunNotificationEntryPoint {
        fun runSessionStore(): RunSessionStore
        fun routineRepository(): RoutineRepository
    }
}
