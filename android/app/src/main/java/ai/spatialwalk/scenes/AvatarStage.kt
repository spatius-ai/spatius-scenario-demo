package ai.spatialwalk.scenes

import ai.spatialwalk.scenes.rtc.AvatarRtcSession
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Avatar rendering view; on mount it connects to the conversational agent over RTC.
 *
 * The session lifecycle follows this Composable: load the avatar and connect on enter,
 * disconnect on leave.
 *
 * @param micEnabled whether to publish the microphone; driven by the caller from the
 *   record-audio permission and the interaction state
 * @param onStatus stage progress. The waiting overlay shows one fixed message, so the
 *   default only logs it for later inspection
 */
@Composable
fun AvatarStage(
    modifier: Modifier = Modifier,
    session: AvatarRtcSession,
    onConnectedChange: (Boolean) -> Unit = {},
    micEnabled: Boolean = false,
    onStatus: (String) -> Unit = { android.util.Log.d("AvatarStage", it) },
    /** Called with true once the avatar's first frame has rendered, to drop the waiting overlay. */
    onReadyChange: (Boolean) -> Unit = {},
    /** Called with an error message when the session fails to start; the waiting overlay
     * stops spinning and shows the reason. */
    onFailure: (String) -> Unit = {},
) {
    val context = LocalContext.current

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            // Don't initialize the SDK here: the appId is only known once the backend
            // issues credentials, and creating an empty view does not depend on
            // initialization — what actually needs it is AvatarManager.load(), which
            // runs after the credentials arrive.
            //
            // The view instance is owned by the session: rotation makes Compose rebuild
            // the AndroidView, and only by reusing the same AvatarView does the
            // established RTC session keep its render target.
            session.obtainAvatarView(ctx)
        },
    )

    // Publish the mic per the current toggle as soon as the connection is up. A separate
    // effect observing session.isConnected won't work — that is a plain property, not
    // State, so changes trigger no recomposition and the effect would run exactly once,
    // in the not-yet-connected state.
    var connected by remember { mutableStateOf(false) }

    // start is idempotent internally, so recomposition never starts a second agent.
    LaunchedEffect(Unit) {
        // Subscribe to the first-frame notification and sync the current value right away —
        // when the view is reused the first-frame callback does not fire again, and waiting
        // only on the callback would leave the rebuilt UI stuck on the waiting overlay forever.
        session.onRenderedChange = onReadyChange
        if (session.hasRendered) onReadyChange(true)

        val view = session.obtainAvatarView(context)
        try {
            session.start(view, onStatus)
            connected = true
            onConnectedChange(true)
        } catch (e: Exception) {
            android.util.Log.e("AvatarStage", "session start failed", e)
            onFailure(Localization.t.connectFailed(e.message.orEmpty()))
        }
    }

    // The mic toggle follows the permission and the caller's state; it only takes effect
    // once the connection is ready.
    LaunchedEffect(micEnabled, connected) {
        if (!connected) return@LaunchedEffect
        runCatching {
            if (micEnabled) session.publishMic() else session.unpublishMic()
        }.onFailure { onStatus(Localization.t.micFailed) }
    }

    // Deliberately no onDispose { session.stop() } here: rotation takes this Composable
    // out of the composition tree, which would tear down a perfectly healthy session.
    // The session is owned by the caller (ClassroomScreen) and stopped only when the
    // classroom is really exited.
}
