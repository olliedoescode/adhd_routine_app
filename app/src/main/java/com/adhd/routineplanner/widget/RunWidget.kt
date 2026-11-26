package com.adhd.routineplanner.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.adhd.routineplanner.alarm.RunSnapshot
import com.adhd.routineplanner.data.RoutineRepository
import com.adhd.routineplanner.data.RunSessionStore
import com.adhd.routineplanner.domain.Phase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.firstOrNull

/**
 * The lock-screen / home-screen widget showing the task currently running and
 * the time left on it.
 *
 * It reads the same persisted [com.adhd.routineplanner.data.RunSession] the run
 * screen uses and derives the current task through [RunSnapshot], so the widget
 * always agrees with the notification and the on-screen countdown. When no run
 * is active it shows a calm "No routine running" card instead of disappearing,
 * so the widget the user placed stays put.
 *
 * The widget is refreshed by [RunWidgetUpdater] whenever the run state changes
 * (task boundary, start, finish, stop) and by the periodic update period
 * declared in the widget info XML, which keeps the "time left" roughly current
 * while the app is closed.
 */
class RunWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = loadSnapshot(context)
        provideContent {
            GlanceTheme {
                RunWidgetContent(snapshot)
            }
        }
    }

    /**
     * Reads the active session and derives the snapshot. Runs off the main
     * thread inside `provideGlance`, so the DataStore read and Room queries are
     * safe here.
     */
    private suspend fun loadSnapshot(context: Context): RunSnapshot? {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            RunWidgetEntryPoint::class.java,
        )
        val session = entryPoint.runSessionStore().session.firstOrNull() ?: return null
        return RunSnapshot.load(
            repository = entryPoint.routineRepository(),
            session = session,
            nowMs = System.currentTimeMillis(),
        )
    }
}

/** Hilt entry point so the widget can reach the app's singletons. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface RunWidgetEntryPoint {
    fun runSessionStore(): RunSessionStore
    fun routineRepository(): RoutineRepository
}

/** The widget's receiver, declared in the manifest. */
class RunWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RunWidget()
}

/** Colours mirroring the app's dark theme so the widget looks native. */
private val WidgetBackground = Color(0xFF0E0F12)
private val WidgetSurface = Color(0xFF16181D)
private val WidgetTextPrimary = Color(0xFFE8EAED)
private val WidgetTextSecondary = Color(0xFF9AA0A6)
private val WidgetCountdownRed = Color(0xFFFF3B30)

@Composable
private fun RunWidgetContent(snapshot: RunSnapshot?) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetBackground)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (snapshot == null) {
            Text(
                text = "No routine running",
                style = TextStyle(
                    color = ColorProvider(WidgetTextSecondary),
                    fontSize = 14.sp,
                ),
            )
            Spacer(modifier = GlanceModifier.height(4.dp))
            Text(
                text = "Start a routine to see it here.",
                style = TextStyle(
                    color = ColorProvider(WidgetTextSecondary),
                    fontSize = 12.sp,
                ),
            )
            // Glance composables cannot early-return from a Column scope, so the
            // "no run" branch is an if/else rather than a return.
        } else {
            // Routine name, small and secondary.
            Text(
                text = snapshot.routineName,
                style = TextStyle(
                    color = ColorProvider(WidgetTextSecondary),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
            Spacer(modifier = GlanceModifier.height(6.dp))

            // Current task name, large and primary.
            Text(
                text = snapshot.taskName ?: if (snapshot.isFinished) "Done" else "Ready",
                style = TextStyle(
                    color = ColorProvider(WidgetTextPrimary),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            Spacer(modifier = GlanceModifier.height(8.dp))

            // Time left, in the countdown colour while running. The "left"
            // suffix only makes sense while a task is counting down; before the
            // start and after the finish the label is a status word on its own.
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = timeLeftLabel(snapshot),
                    style = TextStyle(
                        color = ColorProvider(
                            if (snapshot.state.phase == Phase.Running) {
                                WidgetCountdownRed
                            } else {
                                WidgetTextPrimary
                            },
                        ),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                if (snapshot.state.phase == Phase.Running) {
                    Spacer(modifier = GlanceModifier.width(8.dp))
                    Text(
                        text = "left",
                        style = TextStyle(
                            color = ColorProvider(WidgetTextSecondary),
                            fontSize = 12.sp,
                        ),
                    )
                }
            }

            // A thin progress bar for the current task, drawn as two stacked
            // boxes so the widget needs no extra dependency.
            Spacer(modifier = GlanceModifier.height(8.dp))
            ProgressBar(progress = snapshot.state.progress)
        }
    }
}

/** "4:12" style time left, or a status word before start / after finish. */
private fun timeLeftLabel(snapshot: RunSnapshot): String = when (snapshot.state.phase) {
    Phase.BeforeStart -> "Starts soon"
    Phase.Finished -> "Finished"
    Phase.Running -> {
        val seconds = snapshot.state.secondsRemaining.coerceAtLeast(0)
        val minutes = seconds / 60
        val secs = seconds % 60
        "$minutes:${secs.toString().padStart(2, '0')}"
    }
}

/**
 * A simple determinate bar: a full-width track with a filled portion.
 *
 * Glance offers neither `fillMaxWidth(fraction)` nor a weighted
 * `defaultWeight(fraction)`, so the fill is given an explicit width in dp
 * instead. The track is a fixed [ProgressBarWidth] wide, which is a close
 * match for the widget's inner width at the default 4x1 size; the fill is that
 * width scaled by [progress].
 */
private val ProgressBarWidth = 140.dp

@Composable
private fun ProgressBar(progress: Float) {
    val clamped = progress.coerceIn(0f, 1f)
    Row(
        modifier = GlanceModifier
            .width(ProgressBarWidth)
            .height(6.dp)
            .background(WidgetSurface),
    ) {
        if (clamped > 0f) {
            Spacer(
                modifier = GlanceModifier
                    .width(ProgressBarWidth * clamped)
                    .height(6.dp)
                    .background(WidgetCountdownRed),
            )
        }
    }
}
