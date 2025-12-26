package com.example.metronome

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.metronome.ui.theme.MetronomeTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        setContent {
            MetronomeTheme {
                val config = LocalConfiguration.current
                val density = LocalDensity.current
                val minSide = min(config.screenWidthDp, config.screenHeightDp).toFloat()
                val uiScale = (minSide / 360f).coerceIn(0.9f, 1.15f)
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, uiScale)
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        MetronomeScreen()
                    }
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) {
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                1001
            )
        }
    }
}

private data class MetronomeSettings(
    val bpm: Int,
    val beatsPerMeasure: Int,
    val isPlaying: Boolean
)

private data class Preset(
    val bpm: Int,
    val beatsPerMeasure: Int,
    val soundStyleId: Int
)

private data class PendingSave(
    val index: Int,
    val bpm: Int,
    val beatsPerMeasure: Int,
    val soundStyleId: Int
)

private class SettingsStore(private val prefs: SharedPreferences) {
    fun loadSettings(): MetronomeSettings {
        val bpm = prefs.getInt(KEY_BPM, 120)
        val beats = prefs.getInt(KEY_BEATS, 4)
        val isPlaying = prefs.getBoolean(KEY_IS_PLAYING, false)
        return MetronomeSettings(bpm, beats, isPlaying)
    }

    fun saveSettings(bpm: Int, beats: Int, isPlaying: Boolean) {
        prefs.edit()
            .putInt(KEY_BPM, bpm)
            .putInt(KEY_BEATS, beats)
            .putBoolean(KEY_IS_PLAYING, isPlaying)
            .apply()
    }

    fun loadSoundStyle(): Int {
        return prefs.getInt(KEY_SOUND_STYLE, SoundStyle.CLASSIC.id)
    }

    fun saveSoundStyle(soundStyleId: Int) {
        prefs.edit().putInt(KEY_SOUND_STYLE, soundStyleId).apply()
    }

    fun loadPreset(index: Int): Preset? {
        val bpm = prefs.getInt(presetBpmKey(index), -1)
        val beats = prefs.getInt(presetBeatsKey(index), -1)
        val soundStyleId = prefs.getInt(presetSoundKey(index), SoundStyle.CLASSIC.id)
        if (bpm <= 0 || beats <= 0) {
            return null
        }
        return Preset(bpm, beats, soundStyleId)
    }

    fun savePreset(index: Int, bpm: Int, beats: Int, soundStyleId: Int) {
        prefs.edit()
            .putInt(presetBpmKey(index), bpm)
            .putInt(presetBeatsKey(index), beats)
            .putInt(presetSoundKey(index), soundStyleId)
            .apply()
    }

    fun loadPresetName(index: Int): String? {
        val name = prefs.getString(presetNameKey(index), null)?.trim()
        return if (name.isNullOrEmpty()) null else name
    }

    fun savePresetName(index: Int, name: String?) {
        val key = presetNameKey(index)
        val trimmed = name?.trim()
        prefs.edit().apply {
            if (trimmed.isNullOrEmpty()) {
                remove(key)
            } else {
                putString(key, trimmed)
            }
        }.apply()
    }

    private fun presetBpmKey(index: Int) = "preset_${index}_bpm"

    private fun presetBeatsKey(index: Int) = "preset_${index}_beats"

    private fun presetSoundKey(index: Int) = "preset_${index}_sound"

    private fun presetNameKey(index: Int) = "preset_${index}_name"
}

@Composable
private fun MetronomeScreen() {
    val config = LocalConfiguration.current
    val minSide = min(config.screenWidthDp, config.screenHeightDp).toFloat()
    val uiScale = (minSide / 360f).coerceIn(0.9f, 1.15f)
    val horizontalPadding = (16.dp * uiScale).coerceIn(12.dp, 20.dp)
    val verticalPadding = (12.dp * uiScale).coerceIn(8.dp, 16.dp)
    val sectionSpacing = (10.dp * uiScale).coerceIn(6.dp, 12.dp)
    val rowSpacing = (8.dp * uiScale).coerceIn(6.dp, 10.dp)
    val indicatorSpacing = (6.dp * uiScale).coerceIn(4.dp, 8.dp)
    val cardPadding = (12.dp * uiScale).coerceIn(8.dp, 14.dp)
    val adjustButtonSize = (48.dp * uiScale).coerceIn(40.dp, 56.dp)
    val presetButtonHeight = (48.dp * uiScale).coerceIn(40.dp, 56.dp)
    val accentDotSize = (12.dp * uiScale).coerceIn(8.dp, 14.dp)
    val regularDotSize = accentDotSize * 0.75f
    val scrollState = rememberScrollState()

    val context = LocalContext.current
    val store = remember {
        SettingsStore(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
    val initial = remember { store.loadSettings() }

    var bpm by remember { mutableIntStateOf(initial.bpm) }
    var beats by remember { mutableIntStateOf(initial.beatsPerMeasure) }
    var isPlaying by remember { mutableStateOf(initial.isPlaying) }
    var indicatorBeat by remember { mutableIntStateOf(-1) }
    var showBpmDialog by remember { mutableStateOf(false) }
    var bpmInput by remember { mutableStateOf("") }
    var renameIndex by remember { mutableStateOf<Int?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var pendingSave by remember { mutableStateOf<PendingSave?>(null) }
    var showSoundDialog by remember { mutableStateOf(false) }

    var preset1 by remember { mutableStateOf(store.loadPreset(1)) }
    var preset2 by remember { mutableStateOf(store.loadPreset(2)) }
    var preset3 by remember { mutableStateOf(store.loadPreset(3)) }
    var presetName1 by remember { mutableStateOf(store.loadPresetName(1)) }
    var presetName2 by remember { mutableStateOf(store.loadPresetName(2)) }
    var presetName3 by remember { mutableStateOf(store.loadPresetName(3)) }
    var soundStyle by remember { mutableStateOf(SoundStyle.fromId(store.loadSoundStyle())) }

    val defaultPreset1 = stringResource(id = R.string.label_preset_1)
    val defaultPreset2 = stringResource(id = R.string.label_preset_2)
    val defaultPreset3 = stringResource(id = R.string.label_preset_3)
    val presetLabel1 = presetName1 ?: defaultPreset1
    val presetLabel2 = presetName2 ?: defaultPreset2
    val presetLabel3 = presetName3 ?: defaultPreset3

    LaunchedEffect(bpm, beats, isPlaying) {
        store.saveSettings(bpm, beats, isPlaying)
    }

    LaunchedEffect(soundStyle) {
        store.saveSoundStyle(soundStyle.id)
    }

    LaunchedEffect(bpm, beats, soundStyle) {
        if (isPlaying) {
            MetronomeService.start(context, bpm, beats, soundStyle.id)
        }
    }

    LaunchedEffect(isPlaying, bpm, beats) {
        if (!isPlaying) {
            indicatorBeat = -1
            return@LaunchedEffect
        }
        indicatorBeat = 0
        val intervalMs = (60000L / bpm.coerceAtLeast(1)).coerceAtLeast(1)
        while (isActive) {
            delay(intervalMs)
            indicatorBeat = (indicatorBeat + 1) % beats.coerceAtLeast(1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(scrollState)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalArrangement = Arrangement.spacedBy(sectionSpacing)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(id = R.string.title_main),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(cardPadding),
                verticalArrangement = Arrangement.spacedBy(sectionSpacing)
            ) {
                Text(text = stringResource(id = R.string.label_bpm), style = MaterialTheme.typography.titleMedium)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(rowSpacing)
                ) {
                    AdjustButton(
                        label = "-",
                        size = adjustButtonSize,
                        onClick = { bpm = (bpm - 1).coerceAtLeast(30) }
                    )
                    Text(
                        text = bpm.toString(),
                        style = MaterialTheme.typography.displaySmall,
                        modifier = Modifier
                            .clickable {
                                bpmInput = bpm.toString()
                                showBpmDialog = true
                            }
                    )
                    AdjustButton(
                        label = "+",
                        size = adjustButtonSize,
                        onClick = { bpm = (bpm + 1).coerceAtMost(240) }
                    )
                }
                androidx.compose.material3.Slider(
                    value = bpm.toFloat(),
                    onValueChange = { bpm = it.roundToInt().coerceIn(30, 240) },
                    valueRange = 30f..240f
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(cardPadding),
                verticalArrangement = Arrangement.spacedBy(sectionSpacing)
            ) {
                Text(
                    text = stringResource(id = R.string.label_beats_per_measure),
                    style = MaterialTheme.typography.titleMedium
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(rowSpacing)
                ) {
                    AdjustButton(
                        label = "-",
                        size = adjustButtonSize,
                        onClick = { beats = (beats - 1).coerceAtLeast(1) }
                    )
                    Text(
                        text = beats.toString(),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    AdjustButton(
                        label = "+",
                        size = adjustButtonSize,
                        onClick = { beats = (beats + 1).coerceAtMost(12) }
                    )
                }
                androidx.compose.material3.Slider(
                    value = beats.toFloat(),
                    onValueChange = { beats = it.roundToInt().coerceIn(1, 12) },
                    valueRange = 1f..12f,
                    steps = 10
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(cardPadding),
                verticalArrangement = Arrangement.spacedBy(sectionSpacing)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(id = R.string.label_beat_indicator),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(id = R.string.label_sound),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(indicatorSpacing),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(beats) { index ->
                            val level = AccentPattern.levelForBeat(index, beats)
                            val isStrong = level == AccentLevel.STRONG
                            val isSecondary = level == AccentLevel.SECONDARY
                            val isActive = index == indicatorBeat
                            val color = when {
                                isActive && isStrong -> MaterialTheme.colorScheme.primary
                                isActive && isSecondary -> MaterialTheme.colorScheme.secondary
                                isActive -> MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                                isStrong -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                isSecondary -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f)
                                else -> Color.LightGray
                            }
                            val size = when {
                                isStrong -> accentDotSize
                                isSecondary -> accentDotSize * 0.85f
                                else -> regularDotSize
                            }
                            Box(
                                modifier = Modifier
                                    .size(size)
                                    .background(color, CircleShape)
                            )
                        }
                    }
                    TextButton(
                        onClick = { showSoundDialog = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = stringResource(id = soundStyle.labelResId),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(cardPadding),
                verticalArrangement = Arrangement.spacedBy(sectionSpacing)
            ) {
                Text(text = stringResource(id = R.string.label_presets), style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(rowSpacing)
                ) {
                    PresetButton(
                        label = presetLabel1,
                        preset = preset1,
                        modifier = Modifier.weight(1f),
                        height = presetButtonHeight,
                        onLoad = {
                            if (preset1 == null) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.toast_preset_empty, 1),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                bpm = preset1!!.bpm
                                beats = preset1!!.beatsPerMeasure
                                soundStyle = SoundStyle.fromId(preset1!!.soundStyleId)
                            }
                        },
                        onLongClick = {
                            pendingSave = PendingSave(1, bpm, beats, soundStyle.id)
                            renameIndex = 1
                            renameInput = presetLabel1
                        }
                    )
                    PresetButton(
                        label = presetLabel2,
                        preset = preset2,
                        modifier = Modifier.weight(1f),
                        height = presetButtonHeight,
                        onLoad = {
                            if (preset2 == null) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.toast_preset_empty, 2),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                bpm = preset2!!.bpm
                                beats = preset2!!.beatsPerMeasure
                                soundStyle = SoundStyle.fromId(preset2!!.soundStyleId)
                            }
                        },
                        onLongClick = {
                            pendingSave = PendingSave(2, bpm, beats, soundStyle.id)
                            renameIndex = 2
                            renameInput = presetLabel2
                        }
                    )
                    PresetButton(
                        label = presetLabel3,
                        preset = preset3,
                        modifier = Modifier.weight(1f),
                        height = presetButtonHeight,
                        onLoad = {
                            if (preset3 == null) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.toast_preset_empty, 3),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                bpm = preset3!!.bpm
                                beats = preset3!!.beatsPerMeasure
                                soundStyle = SoundStyle.fromId(preset3!!.soundStyleId)
                            }
                        },
                        onLongClick = {
                            pendingSave = PendingSave(3, bpm, beats, soundStyle.id)
                            renameIndex = 3
                            renameInput = presetLabel3
                        }
                    )
                }
                Text(
                    text = stringResource(id = R.string.label_presets_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height((6.dp * uiScale).coerceIn(4.dp, 8.dp)))

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                if (isPlaying) {
                    MetronomeService.stop(context)
                } else {
                    MetronomeService.start(context, bpm, beats, soundStyle.id)
                }
                isPlaying = !isPlaying
            }
        ) {
            Text(text = stringResource(id = if (isPlaying) R.string.button_stop else R.string.button_start))
        }
    }

    if (showBpmDialog) {
        AlertDialog(
            onDismissRequest = { showBpmDialog = false },
            title = { Text(text = stringResource(id = R.string.dialog_bpm_title)) },
            text = {
                OutlinedTextField(
                    value = bpmInput,
                    onValueChange = { input -> bpmInput = input.filter { it.isDigit() } },
                    label = { Text(text = stringResource(id = R.string.dialog_bpm_label)) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val value = bpmInput.toIntOrNull()
                        if (value != null) {
                            bpm = value.coerceIn(30, 240)
                        }
                        showBpmDialog = false
                    }
                ) {
                    Text(text = stringResource(id = R.string.button_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBpmDialog = false }) {
                    Text(text = stringResource(id = R.string.button_cancel))
                }
            }
        )
    }

    if (renameIndex != null) {
        val defaultName = when (renameIndex) {
            1 -> defaultPreset1
            2 -> defaultPreset2
            else -> defaultPreset3
        }
        AlertDialog(
            onDismissRequest = { renameIndex = null },
            title = { Text(text = stringResource(id = R.string.dialog_preset_name_title)) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text(text = stringResource(id = R.string.dialog_preset_name_label)) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = renameInput.trim()
                        val custom = if (trimmed.isEmpty() || trimmed == defaultName) null else trimmed
                        val index = renameIndex ?: return@TextButton
                        val save = pendingSave
                        if (save != null && save.index == index) {
                            store.savePreset(index, save.bpm, save.beatsPerMeasure, save.soundStyleId)
                            when (index) {
                                1 -> preset1 = Preset(save.bpm, save.beatsPerMeasure, save.soundStyleId)
                                2 -> preset2 = Preset(save.bpm, save.beatsPerMeasure, save.soundStyleId)
                                3 -> preset3 = Preset(save.bpm, save.beatsPerMeasure, save.soundStyleId)
                            }
                            Toast.makeText(
                                context,
                                context.getString(R.string.toast_preset_saved, index),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        store.savePresetName(index, custom)
                        when (index) {
                            1 -> presetName1 = custom
                            2 -> presetName2 = custom
                            3 -> presetName3 = custom
                        }
                        renameIndex = null
                        pendingSave = null
                    }
                ) {
                    Text(text = stringResource(id = R.string.button_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { renameIndex = null; pendingSave = null }) {
                    Text(text = stringResource(id = R.string.button_cancel))
                }
            }
        )
    }

    if (showSoundDialog) {
        AlertDialog(
            onDismissRequest = { showSoundDialog = false },
            title = { Text(text = stringResource(id = R.string.label_sound)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SoundStyle.values().forEach { style ->
                        TextButton(
                            onClick = {
                                soundStyle = style
                                showSoundDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(id = style.labelResId),
                                fontWeight = if (style == soundStyle) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSoundDialog = false }) {
                    Text(text = stringResource(id = R.string.button_cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetButton(
    label: String,
    preset: Preset?,
    modifier: Modifier = Modifier,
    height: Dp,
    onLoad: () -> Unit,
    onLongClick: () -> Unit
) {
    val activeColor = MaterialTheme.colorScheme.primaryContainer
    val idleColor = MaterialTheme.colorScheme.surfaceVariant
    val bg = if (preset == null) idleColor else activeColor

    Surface(
        modifier = modifier
            .height(height)
            .combinedClickable(onClick = onLoad, onLongClick = onLongClick),
        color = bg,
        shape = MaterialTheme.shapes.medium
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AdjustButton(
    label: String,
    size: Dp,
    onClick: () -> Unit
) {
    val fontSize = with(LocalDensity.current) { (size * 0.5f).toSp() }
    Button(
        modifier = Modifier.size(size),
        onClick = onClick,
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(text = label, fontSize = fontSize)
    }
}
