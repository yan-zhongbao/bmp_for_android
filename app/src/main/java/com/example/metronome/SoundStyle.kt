package com.example.metronome

import androidx.annotation.StringRes

enum class SoundStyle(val id: Int, @StringRes val labelResId: Int) {
    CLASSIC(0, R.string.sound_classic),
    SHORT(1, R.string.sound_short),
    SOFT(2, R.string.sound_soft),
    WOOD(3, R.string.sound_wood),
    DRUM(4, R.string.sound_drum),
    METAL(5, R.string.sound_metal);

    companion object {
        fun fromId(id: Int): SoundStyle {
            return values().firstOrNull { it.id == id } ?: CLASSIC
        }
    }
}
