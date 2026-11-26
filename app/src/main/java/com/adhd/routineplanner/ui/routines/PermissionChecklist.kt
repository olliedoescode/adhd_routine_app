package com.adhd.routineplanner.ui.routines

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adhd.routineplanner.alarm.AlarmPermissions
import com.adhd.routineplanner.domain.AlarmSchedule
import com.adhd.routineplanner.ui.theme.Background
import com.adhd.routineplanner.ui.theme.CountdownRed
import com.adhd.routineplanner.ui.theme.StockTaskColors
import com.adhd.routineplanner.ui.theme.Surface
import com.adhd.routineplanner.ui.theme.TextPrimary
import com.adhd.routineplanner.ui.theme.TextSecondary
import com.adhd.routineplanner.viewmodel.PermissionChecklistViewModel

/**
 * First-run permission checklist (spec section 15.10).
 *
 * One row per requirement with a status tick and a button that deep-links to
 * the right settings page: notifications, exact alarms, full-screen alerts and
 * battery set to Unrestricted. Statuses re-check on resume. A Test alarm in 1
 * minute button sits at the bottom so alarm reliability can be proven on the
 * device before run mode is built (spec section 20).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionChecklistScreen(
    onBack: () -> Unit,
    viewModel: PermissionChecklistViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val status by viewModel.status.collectAsStateWithLifecycle()
    val testAlarmAt by viewModel.testAlarmAt.collectAsStateWithLifecycle()
    val testAlarmExact by viewModel.testAlarmExact.collectAsStateWithLifecycle()

    // Re-check every permission whenever the screen resumes, so returning from
    // a settings page updates the ticks (spec section 15.10).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    // POST_NOTIFICATIONS is a runtime permission; request it inline.
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refresh() }

    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = { Text("Alarm setup", color = TextPrimary) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Back", color = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Alarms need a few permissions to fire reliably. Grant each one below.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )

            PermissionRow(
                title = "Notifications",
                description = "Shows the alarm notification.",
                granted = status.notificationsGranted,
                onFix = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        context.startActivity(AlarmPermissions.notificationSettingsIntent(context))
                    }
                },
            )

            PermissionRow(
                title = "Exact alarms",
                description = "Fires alarms at the exact task start.",
                granted = status.exactAlarmsGranted,
                onFix = {
                    AlarmPermissions.exactAlarmSettingsIntent(context)?.let {
                        context.startActivity(it)
                    }
                },
            )

            PermissionRow(
                title = "Full-screen alerts",
                description = "Shows the alarm screen and the routine takeover over " +
                    "the lock screen.",
                granted = status.fullScreenIntentGranted,
                onFix = {
                    AlarmPermissions.fullScreenIntentSettingsIntent(context)?.let {
                        context.startActivity(it)
                    }
                },
            )

            PermissionRow(
                title = "Battery unrestricted",
                description = "Stops Samsung from delaying alarms.",
                granted = status.batteryUnrestricted,
                onFix = { context.startActivity(AlarmPermissions.batterySettingsIntent(context)) },
            )

            Spacer(Modifier.height(8.dp))

            // Not a permission, but the single most common "it doesn't work"
            // report: Samsung One UI 5 and later removed third-party lock-screen
            // widgets. The run card is a media-style notification instead, which
            // Android promotes to the lock screen automatically while a routine
            // is running — no widget to add.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface)
                    .padding(12.dp),
            ) {
                Text(
                    text = "Lock screen",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "While a routine is running, a card appears on your lock " +
                        "screen automatically — just press the power button. It shows " +
                        "the current task, the time left and the next task. There is " +
                        "no widget to add.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "With \"Take over the lock screen\" on in Settings, turning " +
                        "the screen on during a run shows the routine itself instead of " +
                        "your lock screen. Swipe it away or tap Dismiss to use your " +
                        "phone as normal — it is a reminder, not a lock. This uses the " +
                        "Full-screen alerts permission above.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { viewModel.scheduleTestAlarm() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Test alarm now")
            }
            Text(
                text = "Tapping the button opens the alarm screen and plays the tone " +
                    "straight away, and also schedules a real alarm for 1 minute's time " +
                    "so you can lock the screen and confirm it fires on its own.",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
            testAlarmAt?.let { triggerAt ->
                val clock = AlarmSchedule.clockLabel(triggerAt)
                if (testAlarmExact) {
                    Text(
                        text = "Real alarm scheduled for $clock. Lock the screen to verify it fires.",
                        style = MaterialTheme.typography.labelMedium,
                        color = StockTaskColors[1],
                    )
                } else {
                    // Exact-alarm permission is missing, so the platform may
                    // defer the alarm by several minutes. Say so rather than
                    // implying the test proves on-time delivery.
                    Text(
                        text = "Real alarm requested for $clock, but exact alarms are " +
                            "not allowed, so it may arrive late. Grant \"Exact alarms\" " +
                            "above and try again.",
                        style = MaterialTheme.typography.labelMedium,
                        color = StockTaskColors[3],
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    granted: Boolean,
    onFix: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = if (granted) "Granted" else "Not granted",
            tint = if (granted) StockTaskColors[1] else CountdownRed,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
        if (!granted) {
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onFix) {
                Text("Fix")
            }
        }
    }
}
