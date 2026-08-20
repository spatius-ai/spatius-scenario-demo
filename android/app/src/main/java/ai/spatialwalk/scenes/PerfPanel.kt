package ai.spatialwalk.scenes

import ai.spatialwalk.scenes.rtc.AvatarRtcSession
import ai.spatius.avatarkit.performance.FrameRateMonitor
import ai.spatius.avatarkit.performance.PowerMonitor
import ai.spatius.avatarkit.rtc.AnimationSessionSummary
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong

/**
 * Frame rate and playback readout, behind a toggle. Mirrors the Web and iOS clients.
 *
 * Two sources, deliberately kept apart on screen because they answer different
 * questions. The frame rate monitor is the SDK's own, and measures how fast this device
 * renders. The playback stats come from the RTC player, and say how much of what the
 * network sent arrived in time. A session can render at a healthy rate while dropping
 * frames, and vice versa; merging them into one "performance" number would hide exactly
 * the case worth seeing.
 *
 * The monitor is off until switched on, which is also how the SDK ships it — enabling it
 * installs per-frame bookkeeping, so a demo left running should not pay for it.
 */
@Composable
fun PerfPanel(session: AvatarRtcSession?, modifier: Modifier = Modifier) {
    val s = Localization.t
    var open by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<FrameRateMonitor.FrameRateInfo?>(null) }
    // The SDK fires the callback on every rendered frame, not once per window, so binding
    // it straight to the UI repaints the numbers 25 times a second and they are
    // unreadable. The values are 2-second aggregates anyway — sampling them twice a
    // second loses nothing and gives the eye something it can actually follow.
    val lastShown = remember { AtomicLong(0L) }
    var playback by remember { mutableStateOf<AnimationSessionSummary?>(null) }
    var power by remember { mutableStateOf<PowerMonitor.Snapshot?>(null) }

    // Turning the monitor off on the way out matters: the callback holds this composable's
    // state, and the session outlives it when the panel is collapsed or the screen leaves.
    DisposableEffect(session, open) {
        if (open) {
            session?.setPerfMonitor(true) { next ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastShown.get() >= 500) {
                    lastShown.set(now)
                    info = next
                }
            }
        }
        onDispose {
            session?.setPerfMonitor(false)
        }
    }

    // Playback counters are cumulative totals with no callback, so they are polled.
    LaunchedEffect(session, open) {
        if (!open) {
            info = null
            playback = null
            power = null
            return@LaunchedEffect
        }
        while (true) {
            playback = session?.playbackStats
            power = session?.samplePower()
            delay(1000)
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (open) {
            Column(
                modifier = Modifier
                    .width(210.dp)
                    // border before clip, or the rounded clip cuts the border away.
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                        RoundedCornerShape(12.dp),
                    )
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                val current = info
                if (current != null) {
                    StatRow(s.perfFps, "%.0f".format(current.fps), lead = true)
                    StatRow(s.perfPresentationFps, "%.0f".format(current.presentationFps))
                    StatRow(s.perfJank, "%.0f%%".format(current.jankRatioPercent))
                    StatRow(s.perfFrameTime, "%.1f ms".format(current.averageFrameTimeMs))
                    StatRow(s.perfIntervalP95, "%.1f ms".format(current.frameIntervalP95Ms))
                    StatRow(s.perfCpu, "%.0f%%".format(current.cpuUsagePercent))
                } else {
                    Text(
                        s.perfWaiting,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }

                playback?.let { stats ->
                    Text(
                        s.perfPlayback,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                    StatRow(s.perfFramesTotal, stats.totalFrames.toString())
                    StatRow(s.perfDropped, stats.totalDropped.toString())
                    StatRow(s.perfLost, stats.totalLost.toString())
                    StatRow(s.perfStarved, stats.jitterStarved.toString())
                }

                power?.let { p ->
                    Text(
                        s.perfDevice,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                    if (p.cpuTempC > 0f) StatRow(s.perfCpuTemp, "%.1f°C".format(p.cpuTempC))
                    if (p.powerMw > 0f) StatRow(s.perfPower, "%.0f mW".format(p.powerMw))
                    if (p.batteryTempC > 0f) StatRow(s.perfBatteryTemp, "%.1f°C".format(p.batteryTempC))
                }

                Text(
                    s.perfNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        Text(
            s.perfTitle,
            style = MaterialTheme.typography.labelMedium,
            color = if (open) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                    RoundedCornerShape(999.dp),
                )
                .clip(RoundedCornerShape(999.dp))
                .background(
                    if (open) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                )
                .clickable { open = !open }
                .padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun StatRow(label: String, value: String, lead: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = if (lead) MaterialTheme.typography.bodyMedium
            else MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
