package ai.spatialwalk.scenes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Two screens: configure first, then enter the classroom.
 *
 * Same flow as the Web client — the config is written back to the backend's .env and
 * the avatar is picked there too; the client no longer decides which avatar to use.
 */
class MainActivity : ComponentActivity() {

    /** Hide the system bars in the classroom to avoid stray taps; the config screen needs
     * typing, so bring them back there. */
    private var immersive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Localization.init(this)
        enableEdgeToEdge()
        setContent {
            TutoringTheme {
                var scene by remember { mutableStateOf<String?>(null) }
                if (scene == "tutoring") {
                    ClassroomScreen(onExit = { scene = null; updateImmersive(false) })
                } else if (scene == "live") {
                    LiveRoomScreen(onExit = { scene = null; updateImmersive(false) })
                } else if (scene == "service") {
                    ServiceDeskScreen(onExit = { scene = null; updateImmersive(false) })
                } else if (scene == "companion") {
                    CompanionRoomScreen(onExit = { scene = null; updateImmersive(false) })
                } else {
                    ConfigScreen(onDone = { picked -> scene = picked; updateImmersive(true) })
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Once the user swipes the system bars in they stay visible — hide them again
        // when focus comes back.
        if (hasFocus && immersive) applyImmersive()
    }

    private fun updateImmersive(enabled: Boolean) {
        immersive = enabled
        if (enabled) applyImmersive() else showSystemBars()
    }

    private fun applyImmersive() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }
}
