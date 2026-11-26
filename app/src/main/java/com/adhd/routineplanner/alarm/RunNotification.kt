package com.adhd.routineplanner.alarm

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.core.app.NotificationCompat
import com.adhd.routineplanner.MainActivity
import com.adhd.routineplanner.data.AlarmSettings
import com.adhd.routineplanner.data.RoutineRepository
import com.adhd.routineplanner.data.RunSessionStore
import com.adhd.routineplanner.domain.Phase
import com.adhd.routineplanner.domain.RunState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
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

    /** Request code for the takeover full-screen intent's PendingIntent. */
    private const val TAKEOVER_REQUEST_CODE: Int = 0x7E57_0003

    /**
     * True while [repostForTakeover] is mid-flight (between its cancel and its
     * re-post).
     *
     * The per-second updater in
     * [com.adhd.routineplanner.viewmodel.RunViewModel] calls [show] with
     * `takeover = false`. If that lands during the cancel/settle/post window it
     * re-posts the notification as an *update* before the takeover post is
     * treated as new, which suppresses the full-screen intent — the exact race
     * that made the takeover appear only once. While this flag is set, [show]
     * calls from the updater are dropped so the takeover post always wins.
     */
    @Volatile
    private var takeoverInFlight: Boolean = false

    /**
     * True once a takeover has been armed for the current screen-on.
     *
     * After [repostForTakeover] posts the full-screen-intent notification, the
     * per-second updater must not immediately overwrite it with a plain
     * `takeover = false` update, because that would strip the full-screen intent
     * before the platform has launched the activity. The updater therefore
     * leaves the armed notification alone until the next genuine content change
     * *after* the takeover has been consumed. [clearTakeoverArmed] is called
     * when the takeover activity is shown or the run stops.
     */
    @Volatile
    private var takeoverArmed: Boolean = false

    /**
     * How long to wait between cancelling and re-posting the notification in
     * [repostForTakeover], so the asynchronous cancel lands first and the post
     * is treated as a genuinely new notification (which is what re-fires the
     * full-screen intent).
     */
    private const val CANCEL_SETTLE_MS: Long = 250L

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
    ) {
        // The per-second updater posts with `takeover = false`. If a takeover
        // re-post is mid-flight, or one is already armed and waiting for the
        // platform to launch the activity, a plain update would overwrite the
        // full-screen intent and suppress the takeover. Drop those updates; the
        // takeover post (or the next genuine content change after it is
        // consumed) will refresh the card.
        if (!takeover && (takeoverInFlight || takeoverArmed)) return

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
            NOTIFICATION_ID,
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
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
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

        manager.notify(NOTIFICATION_ID, builder.build())
    }

    /** Removes the ongoing notification, e.g. when the run is stopped. */
    fun cancel(context: Context) {
        // A cancelled run has no takeover to arm; clear the guards so a later
        // run is not blocked from posting.
        takeoverArmed = false
        takeoverInFlight = false
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
    }

    /**
     * Clears the "takeover armed" guard once the takeover has been shown (or is
     * no longer wanted).
     *
     * Called by [RunTakeoverActivity] when it appears, so the per-second updater
     * can resume refreshing the ongoing card normally. Until then the updater
     * leaves the armed notification untouched so the full-screen intent is not
     * stripped before the platform launches the activity.
     */
    fun clearTakeoverArmed() {
        takeoverArmed = false
    }

    /**
     * Re-posts the run notification with its full-screen intent so the platform
     * launches [RunTakeoverActivity] over the keyguard.
     *
     * This exists because Android 10+ blocks background activity starts: a
     * broadcast receiver cannot simply call `startActivity` when the screen
     * wakes. A full-screen intent is the sanctioned exception.
     *
     * **A full-screen intent only fires for a notification the platform treats
     * as new.** Re-posting the same id with the same content is an *update*, and
     * Android does not re-deliver the full-screen intent for an update — which
     * is why the takeover only ever appeared the first time a run started and
     * never again on later screen wakes. The fix is to cancel the notification
     * first so the next post is genuinely new, then post it again.
     *
     * The cancel and the post are separated by a short delay because
     * `NotificationManager.cancel` is asynchronous: posting immediately can be
     * coalesced with the pending cancel and delivered as an update to the
     * notification that is still being torn down, which again suppresses the
     * full-screen intent. A few hundred milliseconds is enough for the cancel to
     * land and is imperceptible to the user.
     *
     * The snapshot is rebuilt from the session so the card is correct even if
     * the run view model is not alive (the app may have been killed).
     */
    suspend fun repostForTakeover(context: Context, routineId: Long) {
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

        // Block the per-second updater for the whole cancel/settle/post window
        // so it cannot re-post a plain update that would suppress the takeover.
        takeoverInFlight = true
        try {
            // Cancel first: an update to an existing notification does not
            // re-fire its full-screen intent, so the post below has to be a new
            // one. The delay lets the asynchronous cancel actually land before
            // the post.
            cancel(context)
            delay(CANCEL_SETTLE_MS)
            // Arm the takeover so the updater keeps its hands off the card until
            // the platform has launched the activity (see [clearTakeoverArmed]).
            takeoverArmed = true
            show(
                context = context,
                routineId = snapshot.routineId,
                routineName = snapshot.routineName,
                taskName = snapshot.taskName,
                state = snapshot.state,
                nextTaskName = snapshot.nextTaskName,
                takeover = true,
            )
        } finally {
            takeoverInFlight = false
        }
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
     * The few Hilt bindings [repostForTakeover] needs.
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
