package com.adhd.routineplanner.ui.routines

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adhd.routineplanner.alarm.AlarmPermissions
import com.adhd.routineplanner.domain.AlarmSchedule
import com.adhd.routineplanner.ui.common.SettingsCard
import com.adhd.routineplanner.ui.common.SettingsDivider
import com.adhd.routineplanner.ui.theme.Accent
import com.adhd.routineplanner.ui.theme.Background
import com.adhd.routineplanner.ui.theme.CountdownAmber
import com.adhd.routineplanner.ui.theme.CountdownRed
import com.adhd.routineplanner.ui.theme.StockTaskColors
import com.adhd.routineplanner.ui.theme.TextPrimary
import com.adhd.routineplanner.ui.theme.TextSecondary
import com.adhd.routineplanner.viewmodel.PermissionChecklistViewModel

/**
 * The "Alarm setup" section of the settings page (spec section 15.10).
 *
 * This used to be its own screen, reachable from a separate "Alarm setup" entry
 * in the home menu. That split the alarm story across two pages, which is why
 * the settings page felt strange: the things that decide whether an alarm fires
 * sat apart from the things that decide how it sounds. Both now live here, as
 * one section of one page.
 *
 * One row per requirement with a status tick and an "Allow" button that
 * deep-links to the right settings page: notifications, exact alarms,
 * full-screen alerts, display over other apps, and battery set to Unrestricted.
 * Statuses re-check whenever the page resumes, so returning from a system
 * settings screen updates the ticks. A "Test alarm now" button sits at the
 * bottom so alarm reliability can be proven on the device.
 */
@Composable
fun AlarmSetupCard(
    modifier: Modifier = Modifier,
    viewModel: PermissionChecklistViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val status by viewModel.status.collectAsStateWithLifecycle()
    val testAlarmAt by viewModel.testAlarmAt.collectAsStateWithLifecycle()
    val testAlarmExact by viewModel.testAlarmExact.collectAsStateWithLifecycle()

    // Re-check every permission whenever the page resumes, so returning from a
    // settings page updates the ticks (spec section 15.10).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    // POST_NOTIFICATIONS is a runtime permission; request it inline.
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refresh() }

    val grantedCount = listOf(
        status.notificationsGranted,
        status.exactAlarmsGranted,
        status.fullScreenIntentGranted,
        status.overlayGranted,
        status.batteryUnrestricted,
    ).count { it }
    val missing = 5 - grantedCount

    SettingsCard(modifier) {
        Text(
            text = if (missing == 0) {
                "Everything is allowed"
            } else {
                "$missing still to allow"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (missing == 0) StockTaskColors[1] else CountdownAmber,
            modifier = Modifier.padding(top = 14.dp, bottom = 12.dp),
        )

        SettingsDivider()

        PermissionRow(
            title = "Notifications",
            description = "Shows the alarm notification.",
            granted = status.notificationsGranted,
            onAllow = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    context.startActivity(AlarmPermissions.notificationSettingsIntent(context))
                }
            },
        )

        SettingsDivider()

        PermissionRow(
            title = "Exact alarms",
            description = "Fires alarms at the exact task start.",
            granted = status.exactAlarmsGranted,
            onAllow = {
                AlarmPermissions.exactAlarmSettingsIntent(context)?.let {
                    context.startActivity(it)
                }
            },
        )

        SettingsDivider()

        PermissionRow(
            title = "Full-screen alerts",
            description = "Shows the alarm screen and the routine takeover over the lock screen.",
            granted = status.fullScreenIntentGranted,
            onAllow = {
                AlarmPermissions.fullScreenIntentSettingsIntent(context)?.let {
                    context.startActivity(it)
                }
            },
        )

        SettingsDivider()

        PermissionRow(
            title = "Display over other apps",
            description = "Lets the takeover open on every unlock, including fingerprint.",
            granted = status.overlayGranted,
            onAllow = { context.startActivity(AlarmPermissions.overlaySettingsIntent(context)) },
        )

        SettingsDivider()

        PermissionRow(
            title = "Battery unrestricted",
            description = "Stops Samsung from delaying alarms.",
            granted = status.batteryUnrestricted,
            onAllow = { context.startActivity(AlarmPermissions.batterySettingsIntent(context)) },
        )

        SettingsDivider()

        // Not a permission, but the single most common "it doesn't work"
        // report: Samsung One UI 5 and later removed third-party lock-screen
        // widgets. The run card is a media-style notification instead, which
        // Android promotes to the lock screen automatically while a routine
        // is running — no widget to add.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
        ) {
            Text(
                text = "On the lock screen",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "While a routine runs, a card appears on your lock screen on " +
                    "its own — there is no widget to add. With the takeover on, waking " +
                    "the phone shows the routine instead; swipe it away to use your " +
                    "phone as normal.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }

        SettingsDivider()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
        ) {
            Button(
                onClick = { viewModel.scheduleTestAlarm() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = Background,
                ),
            ) {
                Text("Test alarm now")
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Opens the alarm screen straight away, and also schedules a real " +
                    "alarm a minute from now so you can lock the phone and check it " +
                    "fires on its own.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )

            testAlarmAt?.let { triggerAt ->
                Spacer(Modifier.height(8.dp))
                val clock = AlarmSchedule.clockLabel(triggerAt)
                if (testAlarmExact) {
                    Text(
                        text = "Real alarm set for $clock. Lock the screen to verify it fires.",
                        style = MaterialTheme.typography.labelMedium,
                        color = StockTaskColors[1],
                    )
                } else {
                    // Exact-alarm permission is missing, so the platform may
                    // defer the alarm by several minutes. Say so rather than
                    // implying the test proves on-time delivery.
                    Text(
                        text = "Requested for $clock, but exact alarms are not allowed, " +
                            "so it may arrive late. Allow \"Exact alarms\" above and try " +
                            "again.",
                        style = MaterialTheme.typography.labelMedium,
                        color = CountdownAmber,
                    )
                }
            }
        }
    }
}

/** One requirement: tick, name, explanation, and an Allow button when missing. */
@Composable
private fun PermissionRow(
    title: String,
    description: String,
    granted: Boolean,
    onAllow: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (granted) {
                Icons.Filled.CheckCircle
            } else {
                Icons.Filled.RadioButtonUnchecked
            },
            contentDescription = if (granted) "Allowed" else "Not allowed",
            tint = if (granted) StockTaskColors[1] else CountdownRed.copy(alpha = 0.85f),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
        if (!granted) {
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onAllow) {
                Text("Allow", color = Accent, fontWeight = FontWeight.Medium)
            }
        }
    }
}
