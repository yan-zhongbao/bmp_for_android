package com.example.metronome

enum class AccentLevel {
    STRONG,
    SECONDARY,
    REGULAR
}

object AccentPattern {
    fun levelForBeat(index: Int, beatsPerMeasure: Int): AccentLevel {
        if (index == 0) {
            return AccentLevel.STRONG
        }
        return when (beatsPerMeasure) {
            6 -> if (index == 3) AccentLevel.SECONDARY else AccentLevel.REGULAR
            9 -> if (index == 3 || index == 6) AccentLevel.SECONDARY else AccentLevel.REGULAR
            else -> AccentLevel.REGULAR
        }
    }
}
