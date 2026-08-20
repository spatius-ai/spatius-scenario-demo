package ai.spatialwalk.scenes

import ai.spatialwalk.scenes.rtc.AvatarRtcSession
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Gap between the two video tiles. */
private val TileGap = 12.dp

/**
 * Right-hand video area: avatar on top, the student below, both square.
 *
 * The side length is derived from the **available height**: two squares plus the gap
 * between them must fit, hence side = (available height - gap) / 2, then capped by
 * [maxTileSize] so it doesn't get oversized on large screens. Deriving the side from a
 * fixed width instead would overflow and get clipped on tall-aspect devices.
 *
 * Placeholder content lives in [VideoPlaceholder], so swapping in real video only touches
 * that one place.
 */
@Composable
fun VideoPanel(
    modifier: Modifier = Modifier,
    maxTileSize: Dp = 360.dp,
    cameraGranted: Boolean = false,
    micEnabled: Boolean = false,
    session: AvatarRtcSession,
    /** Side length of one square tile, computed once by the caller per screen orientation. */
    tileSize: Dp,
    /** Whether the two tiles sit side by side (side by side in portrait, stacked in landscape). */
    horizontal: Boolean = false,
    /** Whether free talk has started. */
    freeTalk: Boolean = false,
    /** The record-audio permission was denied: the mic goes grey and tapping asks again. */
    micDenied: Boolean = false,
    onToggleFreeTalk: () -> Unit = {},
    /** Whether the student has their own video on. */
    cameraOn: Boolean = true,
    onToggleCamera: () -> Unit = {},
    onConnectedChange: (Boolean) -> Unit = {},
) {
    var avatarReady by remember { mutableStateOf(false) }
    /** Waiting-overlay copy: a fixed message by default, stage progress while connecting,
     * the reason on failure. */
    var overlayText by remember { mutableStateOf(Localization.t.teacherComing) }
    var overlayFailed by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        val tiles: @Composable () -> Unit = {
            // Top: the avatar. The classroom background sits under the render view since
            // the model has a transparent background.
            VideoTile(
                size = tileSize,
                gradient = listOf(Color(0xFF3D5AFE), Color(0xFF7C4DFF)),
            ) {
                Image(
                    painter = painterResource(R.drawable.classroom_background),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(tileSize),
                )
                AvatarStage(
                    modifier = Modifier.size(tileSize),
                    micEnabled = micEnabled,
                    session = session,
                    onConnectedChange = onConnectedChange,
                    // Stage progress is logged, not shown: the waiting overlay keeps one
                    // fixed message rather than exposing internal progress like
                    // "downloading 20%" to the student.
                    onStatus = {},
                    onReadyChange = { avatarReady = it },
                    onFailure = {
                        overlayText = it
                        overlayFailed = true
                    },
                )
                // Cover with an overlay while the model loads and connects, so there is
                // no long blank stretch.
                if (!avatarReady) {
                    TeacherComingOverlay(tileSize, overlayText, spinning = !overlayFailed)
                }
                // Mic toggle overlaid on the bottom right of the teacher's tile: red means
                // the mic is off, green means the call is live.
                MicButton(
                    active = freeTalk && micEnabled,
                    denied = micDenied,
                    onClick = onToggleFreeTalk,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp),
                )
            }
            // Bottom: the student. Keep the placeholder when the camera permission is
            // missing or the student turned their own video off.
            VideoTile(
                size = tileSize,
                gradient = listOf(Color(0xFF00897B), Color(0xFF26A69A)),
            ) {
                if (cameraGranted && cameraOn) {
                    CameraPreview(modifier = Modifier.size(tileSize))
                } else {
                    VideoPlaceholder(tileSize, Localization.t.me)
                }
                // Camera toggle on the bottom right of the student's tile, mirroring the
                // mic on the teacher's. Hidden without the permission — there is no video
                // to turn off, so the button would do nothing.
                if (cameraGranted) {
                    CameraButton(
                        active = cameraOn,
                        onClick = onToggleCamera,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp),
                    )
                }
            }
        }

        if (horizontal) {
            Row(horizontalArrangement = Arrangement.spacedBy(TileGap)) { tiles() }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(TileGap)) { tiles() }
        }
    }
}

/**
 * Mic toggle, overlaid on the bottom right of the teacher's tile.
 *
 * Red = mic off (tap to enter free talk and open the mic), green = call live (tap to hang
 * up). When the permission was denied it shows a grey crossed-out mic, and tapping asks
 * for the permission again.
 */
@Composable
private fun MicButton(
    active: Boolean,
    denied: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = when {
        denied -> Color(0xFF9E9E9E)
        active -> Color(0xFF2E9E5B)
        else -> Color(0xFFD64545)
    }
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Emoji rather than material-icons — pulling in the whole icon library for a
        // single glyph isn't worth it.
        Text(
            text = if (denied) "🚫" else "🎙",
            fontSize = 20.sp,
        )
    }
}

/**
 * Camera toggle, overlaid on the bottom right of the student's tile.
 *
 * Same color semantics as [MicButton]: green = video on, red = off. It only affects the
 * local preview and involves no RTC publishing — the student's video is never sent
 * upstream in the first place.
 */
@Composable
private fun CameraButton(
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (active) Color(0xFF2E9E5B) else Color(0xFFD64545))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (active) "📹" else "🚫",
            fontSize = 20.sp,
        )
    }
}

/**
 * Waiting overlay shown until the avatar is in place.
 *
 * Normally it spins and shows the current stage (waiting for network / downloading /
 * connecting); on failure it stops spinning and shows the reason, so a fault doesn't just
 * look like a spinner that never ends.
 */
@Composable
private fun TeacherComingOverlay(size: Dp, text: String, spinning: Boolean) {
    Column(
        modifier = Modifier
            .size(size)
            .background(Color.Black.copy(alpha = 0.45f)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        if (spinning) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(size * 0.12f),
            )
        }
        Text(
            text = text,
            color = Color.White,
            fontSize = (size.value * 0.055f).sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** One square video tile; the side length comes from the caller and [content] fills it. */
@Composable
private fun VideoTile(
    size: Dp,
    gradient: List<Color>,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(gradient)),
    ) {
        content()
    }
}

/** Placeholder for video content; replace this when wiring in a real feed. The avatar
 * circle scales proportionally with the tile's side length. */
@Composable
private fun VideoPlaceholder(tileSize: Dp, avatarText: String) {
    Box(
        modifier = Modifier.size(tileSize),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(tileSize * 0.32f)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = avatarText,
                color = Color.White,
                fontSize = (tileSize.value * 0.12f).sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
