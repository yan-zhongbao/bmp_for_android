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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.metronome.ui.theme.MetronomeTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        setContent {
            MetronomeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MetronomeScreen()
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
    val beatsPerMeasure: Int
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

    fun loadPreset(index: Int): Preset? {
        val bpm = prefs.getInt(presetBpmKey(index), -1)
        val beats = prefs.getInt(presetBeatsKey(index), -1)
        if (bpm <= 0 || beats <= 0) {
            return null
        }
        return Preset(bpm, beats)
    }

    fun savePreset(index: Int, bpm: Int, beats: Int) {
        prefs.edit()
            .putInt(presetBpmKey(index), bpm)
            .putInt(presetBeatsKey(index), beats)
            .apply()
    }

    private fun presetBpmKey(index: Int) = "preset_${index}_bpm"

    private fun presetBeatsKey(index: Int) = "preset_${index}_beats"
}

@Composable
private fun MetronomeScreen() {
    val context = LocalContext.current
    val store = remember {
        SettingsStore(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
    val initial = remember { store.loadSettings() }

    var bpm by remember { mutableIntStateOf(initial.bpm) }
    var beats by remember { mutableIntStateOf(initial.beatsPerMeasure) }
    var isPlaying by remember { mutableStateOf(initial.isPlaying) }
    var indicatorBeat by remember { mutableIntStateOf(-1) }

    var preset1 by remember { mutableStateOf(store.loadPreset(1)) }
    var preset2 by remember { mutableStateOf(store.loadPreset(2)) }
    var preset3 by remember { mutableStateOf(store.loadPreset(3)) }

    LaunchedEffect(bpm, beats, isPlaying) {
        store.saveSettings(bpm, beats, isPlaying)
    }

    LaunchedEffect(bpm, beats) {
        if (isPlaying) {
            MetronomeService.start(context, bpm, beats)
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
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Metronome",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(text = "BPM", style = MaterialTheme.typography.titleMedium)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(
                        onClick = { bpm = (bpm - 1).coerceAtLeast(30) }
                    ) {
                        Text(text = "-")
                    }
                    Text(
                        text = bpm.toString(),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    TextButton(
                        onClick = { bpm = (bpm + 1).coerceAtMost(240) }
                    ) {
                        Text(text = "+")
                    }
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
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(text = "Beats Per Measure", style = MaterialTheme.typography.titleMedium)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(
                        onClick = { beats = (beats - 1).coerceAtLeast(1) }
                    ) {
                        Text(text = "-")
                    }
                    Text(
                        text = beats.toString(),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    TextButton(
                        onClick = { beats = (beats + 1).coerceAtMost(12) }
                    ) {
                        Text(text = "+")
                    }
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
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(text = "Beat Indicator", style = MaterialTheme.typography.titleMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(beats) { index ->
                        val isAccent = index == 0
                        val isActive = index == indicatorBeat
                        val color = when {
                            isActive && isAccent -> MaterialTheme.colorScheme.primary
                            isActive -> MaterialTheme.colorScheme.secondary
                            else -> Color.LightGray
                        }
                        Box(
                            modifier = Modifier
                                .size(if (isAccent) 16.dp else 12.dp)
                                .background(color, CircleShape)
                        )
                    }
                }
                Text(
                    text = "First beat is accented",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(text = "Presets", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PresetButton(
                        label = "Preset 1",
                        preset = preset1,
                        modifier = Modifier.weight(1f),
                        onLoad = {
                            if (preset1 == null) {
                                Toast.makeText(context, "Preset 1 is empty", Toast.LENGTH_SHORT).show()
                            } else {
                                bpm = preset1!!.bpm
                                beats = preset1!!.beatsPerMeasure
                            }
                        },
                        onSave = {
                            store.savePreset(1, bpm, beats)
                            preset1 = Preset(bpm, beats)
                            Toast.makeText(context, "Saved to Preset 1", Toast.LENGTH_SHORT).show()
                        }
                    )
                    PresetButton(
                        label = "Preset 2",
                        preset = preset2,
                        modifier = Modifier.weight(1f),
                        onLoad = {
                            if (preset2 == null) {
                                Toast.makeText(context, "Preset 2 is empty", Toast.LENGTH_SHORT).show()
                            } else {
                                bpm = preset2!!.bpm
                                beats = preset2!!.beatsPerMeasure
                            }
                        },
                        onSave = {
                            store.savePreset(2, bpm, beats)
                            preset2 = Preset(bpm, beats)
                            Toast.makeText(context, "Saved to Preset 2", Toast.LENGTH_SHORT).show()
                        }
                    )
                    PresetButton(
                        label = "Preset 3",
                        preset = preset3,
                        modifier = Modifier.weight(1f),
                        onLoad = {
                            if (preset3 == null) {
                                Toast.makeText(context, "Preset 3 is empty", Toast.LENGTH_SHORT).show()
                            } else {
                                bpm = preset3!!.bpm
                                beats = preset3!!.beatsPerMeasure
                            }
                        },
                        onSave = {
                            store.savePreset(3, bpm, beats)
                            preset3 = Preset(bpm, beats)
                            Toast.makeText(context, "Saved to Preset 3", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
                Text(
                    text = "Tap to load. Long-press to save.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                if (isPlaying) {
                    MetronomeService.stop(context)
                } else {
                    MetronomeService.start(context, bpm, beats)
                }
                isPlaying = !isPlaying
            }
        ) {
            Text(text = if (isPlaying) "Stop" else "Start")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetButton(
    label: String,
    preset: Preset?,
    modifier: Modifier = Modifier,
    onLoad: () -> Unit,
    onSave: () -> Unit
) {
    val activeColor = MaterialTheme.colorScheme.primaryContainer
    val idleColor = MaterialTheme.colorScheme.surfaceVariant
    val bg = if (preset == null) idleColor else activeColor

    Surface(
        modifier = modifier
            .height(52.dp)
            .combinedClickable(onClick = onLoad, onLongClick = onSave),
        color = bg,
        shape = MaterialTheme.shapes.medium
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
