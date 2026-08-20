package ai.spatialwalk.scenes

import ai.spatialwalk.scenes.rtc.AgentClient
import ai.spatialwalk.scenes.rtc.RtcConfig
import android.content.Intent
import android.net.Uri
import coil.compose.AsyncImage
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.launch

/** Sample rate at which the avatar receives audio; the values are the set the Motion
 * Server supports. */
private val SampleRates = listOf("8000", "16000", "22050", "24000", "32000", "44100", "48000")
private const val DefaultSampleRate = "24000"

/** Required fields. Missing any one of them means no connection, so block on the button
 * instead. */
private val RequiredKeys = listOf(
    "SPATIUS_API_KEY", "SPATIUS_APP_ID",
    "AGORA_APP_ID", "AGORA_APP_CERTIFICATE", "AGORA_PIPELINE_ID",
)

/**
 * Public sample avatars, the same set as the Web config page (characters.ts in
 * spatius-avatar-demo). Keep this in sync with that file rather than starting a separate
 * list here.
 */
/**
 * A character to pick, from either source.
 *
 * `cover` is whatever Coil can load: the resource id of a bundled image for the built-in
 * list, an https URL for the list the backend serves. Keeping one type means the grid does
 * not have to know which it is showing.
 */
private data class AvatarOption(val id: String, val name: String, val cover: Any)

private val AvatarOptions = listOf(
    AvatarOption("41c62a7c-993c-4b6b-b6d3-549ce3c8be00", "Kian", R.drawable.avatar_kian),
    AvatarOption("dbb01388-7c57-47bf-ab59-c492caeb9d90", "Julian", R.drawable.avatar_julian),
    AvatarOption("d51ab422-3db7-47cc-afa8-7273b02bc70b", "Clara", R.drawable.avatar_clara),
    AvatarOption("c7069121-8245-4015-9940-82d0dc0c6bda", "Halima", R.drawable.avatar_halima),
    AvatarOption("8b86dda1-98ed-4acd-8a4e-b1a00ba69268", "Leyla", R.drawable.avatar_leyla),
    AvatarOption("566981dd-1d95-4844-953e-d67e18b2fde8", "Adrian", R.drawable.avatar_adrian),
    AvatarOption("981ed26d-fbfe-42eb-a5d5-56ebd104847b", "Haru", R.drawable.avatar_haru),
    AvatarOption("56f31c71-58ff-410f-85d2-11b9658c7b49", "Ethan", R.drawable.avatar_ethan),
    AvatarOption("e06640cb-e011-4806-bd3e-6b07575eff2e", "Samir", R.drawable.avatar_samir),
)

/**
 * Config screen.
 *
 * Configures the same backend and writes the same .env as the Web version; only the
 * layout differs, reworked for phones into a single scrolling column — the Web page's
 * side-by-side cards do not fit in portrait.
 *
 * It has one field the Web version doesn't: the backend URL. A web page can derive the
 * backend's location from location.hostname, but a phone cannot reach the dev machine's
 * localhost, so the LAN address has to be typed in — the backend prints it on startup,
 * and `/health` returns it as lanUrl.
 */
@OptIn(ExperimentalMaterial3Api::class)
/**
 * What the status line is currently reporting.
 *
 * A type with its own data rather than a finished string: assembled eagerly it freezes
 * whatever language was current at the time, so switching afterwards would leave the old
 * language sitting on screen.
 */
private sealed interface ConfigStatus {
    data object None : ConfigStatus
    data object Connecting : ConfigStatus
    data object BackendOnline : ConfigStatus
    data class ConnectFailed(val reason: String) : ConfigStatus
    data class LoadFailed(val reason: String) : ConfigStatus
    data class SaveFailed(val reason: String) : ConfigStatus
}

@Composable
private fun ConfigStatus.text(): String = when (this) {
    ConfigStatus.None -> ""
    ConfigStatus.Connecting -> Localization.t.connecting
    ConfigStatus.BackendOnline -> Localization.t.backendOnline
    is ConfigStatus.ConnectFailed -> Localization.t.connectFailedShort(reason)
    is ConfigStatus.LoadFailed -> Localization.t.loadConfigFailed(reason)
    is ConfigStatus.SaveFailed -> Localization.t.saveFailed(reason)
}

@Composable
fun ConfigScreen(onDone: (scene: String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var baseUrl by remember { mutableStateOf(RtcConfig.baseUrl(context)) }
    val config = remember { mutableStateMapOf<String, String>() }
    var status by remember { mutableStateOf<ConfigStatus>(ConfigStatus.None) }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf(1) }
    val t = Localization.t

    // Fetch the backend's current config on entry: whatever is already filled in shows up
    // as is, with nothing to re-enter.
    LaunchedEffect(Unit) {
        runCatching { AgentClient.fetchConfig(context) }
            .onSuccess { config.putAll(it); loaded = true }
            .onFailure { status = ConfigStatus.LoadFailed(it.message.orEmpty()) }
    }

    val ready = RequiredKeys.all { config[it].orEmpty().isNotBlank() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (step == 1) t.stepCredentials else t.stepAvatar,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                LangToggle()
            }
        }

        if (step == 1) {
        // ---- Backend URL. Phone-only, and it has to be filled in first: every other
        //      setting is read from and written to it.
        item {
            SectionCard(t.sectionBackendUrl) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(t.fieldBackendUrl) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        capitalization = KeyboardCapitalization.None,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint(t.backendUrlHint)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            status = ConfigStatus.Connecting
                            RtcConfig.setBaseUrl(context, baseUrl)
                            scope.launch {
                                AgentClient.health(context, baseUrl)
                                    .onSuccess {
                                        status = ConfigStatus.BackendOnline
                                        runCatching { AgentClient.fetchConfig(context) }
                                            .onSuccess { config.putAll(it); loaded = true }
                                    }
                                    .onFailure { status = ConfigStatus.ConnectFailed(it.message.orEmpty()) }
                                busy = false
                            }
                        },
                    ) { Text(t.testConnection) }
                }
                if (status !is ConfigStatus.None) {
                    Text(status.text(), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // ---- Credentials. Read-only display — copying five secrets across apps on a
        //      phone is miserable, and the IME mangles the keys (auto-capitalization and
        //      autocorrect) in ways that aren't visible afterwards. The user is already at
        //      a computer running the backend; filling them in there once covers all three
        //      clients.
        item {
            SectionCard(t.sectionCredentials) {
                RequiredKeys.forEach { key ->
                    val filled = config[key].orEmpty().isNotBlank()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(key, style = MaterialTheme.typography.bodySmall)
                        Text(
                            if (filled) t.credentialConfigured else t.credentialMissing,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (filled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Hint(t.credentialsHint)
            }
        }

        // ---- End of step one; next up is picking the avatar.
        item {
            Button(
                enabled = ready,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    step = 2
                    RtcConfig.setBaseUrl(context, baseUrl)
                    // Save once before step two: the credentials were copied over one at a
                    // time from various consoles, and switching away from the app and back
                    // should not mean starting over. A failed save is not blocking — the
                    // last step submits the whole set again.
                    scope.launch { runCatching { AgentClient.saveConfig(context, config.toMap()) } }
                },
            ) { Text(t.next) }
            if (!ready && loaded) {
                Text(
                    t.fillAllFirst,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
        } else {
            item {
                AvatarStepContent(
                    config = config,
                    status = status,
                    busy = busy,
                    onBack = { step = 1 },
                    onStart = { picked ->
                        busy = true
                        scope.launch {
                            runCatching { AgentClient.saveConfig(context, config.toMap()) }
                                .onSuccess { onDone(picked) }
                                .onFailure { status = ConfigStatus.SaveFailed(it.message.orEmpty()) }
                            busy = false
                        }
                    },
                )
            }
        }
    }
}

/** Step two: avatar, voice, scene. Mirrors the three cards in the Web version's second step. */
@Composable
private fun AvatarStepContent(
    config: MutableMap<String, String>,
    status: ConfigStatus,
    busy: Boolean,
    onBack: () -> Unit,
    onStart: (scene: String) -> Unit,
) {
    val t = Localization.t
    val context = LocalContext.current
    var scene by remember { mutableStateOf("tutoring") }

    // The cast comes from the backend so adding a character does not mean rebuilding the
    // app. The built-in list stands in until the request lands, and stays if it fails —
    // an unreachable backend should not leave the step with nothing to pick.
    var avatars by remember { mutableStateOf(AvatarOptions) }
    LaunchedEffect(Unit) {
        runCatching { AgentClient.fetchCatalogue(context) }
            .onSuccess { fetched ->
                if (fetched.isNotEmpty()) {
                    avatars = fetched.map { AvatarOption(it.id, it.name, it.coverUrl) }
                }
            }
    }

    SectionCard(t.sectionAvatar) {
        AvatarGrid(
            items = avatars,
            selectedId = config["SPATIUS_AVATAR_ID"],
            idOf = { it.id },
            nameOf = { it.name },
            onSelect = { config["SPATIUS_AVATAR_ID"] = it },
        ) { option ->
            AsyncImage(
                model = option.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)),
            )
        }
    }

    // The voice belongs to the agent in the console and cannot be changed from here, so
    // point the way with screenshots; the sample rate has to match it.
    SectionCard(t.sectionTtsModel) {
        GuideImage(R.drawable.guide_agora_voice, AgoraConsole)
        GuideImage(R.drawable.guide_agora_5, AgoraConsole)
        SampleRateDropdown(
            value = config["AGORA_AVATAR_SAMPLE_RATE"].orEmpty().ifEmpty { DefaultSampleRate },
            onChange = { config["AGORA_AVATAR_SAMPLE_RATE"] = it },
        )
        Hint(
            t.sampleRateNote
        )
    }

    // One card per scene, picked here rather than on a screen of its own: which scene to
    // open is one more choice among the ones already on this page.
    SectionCard(t.sectionScene) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SceneOption(
                label = t.sceneTutoring,
                image = R.drawable.classroom_background,
                selected = scene == "tutoring",
                onClick = { scene = "tutoring" },
            )
            SceneOption(
                label = t.sceneLive,
                image = R.drawable.live_room_bg,
                selected = scene == "live",
                onClick = { scene = "live" },
            )
            SceneOption(
                label = t.sceneService,
                image = R.drawable.bank_hall_bg,
                selected = scene == "service",
                onClick = { scene = "service" },
            )
            SceneOption(
                label = t.sceneCompanion,
                image = R.drawable.companion_room_bg,
                selected = scene == "companion",
                onClick = { scene = "companion" },
            )
        }
    }

    Button(
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
        onClick = { onStart(scene) },
    ) { Text(if (busy) t.saving else t.start) }

    if (status !is ConfigStatus.None) {
        Text(
            status.text(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val AgoraConsole = "https://console.agora.io/"

/**
 * The avatars as tiles rather than one full-width row each.
 *
 * A row apiece turned nine characters into a list longer than the screen, where the face —
 * the only thing that distinguishes one from another — was a thumbnail beside the name.
 * As tiles the picture carries the choice and the whole cast is visible at once.
 *
 * Built from Rows rather than LazyVerticalGrid: this sits inside a LazyColumn, and a lazy
 * grid nested in a lazy scroller has no bounded height to measure against.
 */
@Composable
private fun <T> AvatarGrid(
    items: List<T>,
    selectedId: String?,
    idOf: (T) -> String,
    nameOf: (T) -> String,
    onSelect: (String) -> Unit,
    thumbnail: @Composable (T) -> Unit,
) {
    BoxWithConstraints {
        // Three across on a phone held upright, more as the window gets wider — a tile
        // narrower than about this stops being a face and starts being an icon.
        val columns = (maxWidth / 118.dp).toInt().coerceIn(3, 6)

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { option ->
                        val selected = selectedId == idOf(option)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                // Selection needs to read at a glance across a grid, which
                                // a tint alone does not do once the tiles are small.
                                .border(
                                    width = if (selected) 2.dp else 0.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = RoundedCornerShape(10.dp),
                                )
                                .clickable { onSelect(idOf(option)) }
                                .padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            thumbnail(option)
                            Text(
                                nameOf(option),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    // Keeps a short last row's tiles the same width as a full row's.
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** Console screenshot. Tapping opens the matching console; there is no hover-to-zoom on a
 * phone, so the whole image is laid out full width. */
@Composable
private fun GuideImage(resId: Int, url: String) {
    val context = LocalContext.current
    Image(
        painter = painterResource(resId),
        contentDescription = null,
        contentScale = ContentScale.FillWidth,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            },
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SampleRateDropdown(value: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(Localization.t.fieldSampleRate) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SampleRates.forEach { rate ->
                DropdownMenuItem(
                    text = { Text(rate) },
                    onClick = { onChange(rate); expanded = false },
                )
            }
        }
    }
}


/**
 * One scene to pick from.
 *
 * A row rather than the Web client's grid of squares: a phone has the width for one
 * across, and stacking them keeps the thumbnails big enough to tell apart.
 */
@Composable
private fun SceneOption(
    label: String,
    image: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            )
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            painter = painterResource(image),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 78.dp, height = 52.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
