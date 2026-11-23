/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.music.ai

import kotlin.math.ceil

/** A duration estimate shown before a Convx Live session is prepared. */
data class ConcertDurationPlan(
    val requestedSeconds: Long,
    val estimatedSongCount: Int,
    val averageMusicSecondsPerSong: Long,
    val transitionSeconds: Long,
    val narrationSeconds: Long,
    val estimatedTotalSeconds: Long,
) {
    val isWithinOneMinute: Boolean get() = kotlin.math.abs(estimatedTotalSeconds - requestedSeconds) <= 60L
}

/**
 * Plans music time rather than dividing the requested time by the raw track length.
 * The estimate reserves time for transitions and short Teq introductions first.
 * Actual song windows are constrained later by verified duration/audio analysis.
 */
object ConcertDurationPlanner {
    /** One six-second instrumental crossfade follows each of the four Teq cue positions. */
    const val DEFAULT_TRANSITION_SECONDS = 6L
    const val ESTIMATED_NARRATION_SECONDS = 9L
    const val ESTIMATED_NARRATION_CUES = 4
    const val TARGET_MUSIC_SECONDS_PER_SONG = 178L

    fun cueIndexes(songCount: Int): List<Int> {
        if (songCount <= 1) return if (songCount == 1) listOf(0) else emptyList()
        return listOf(0, songCount / 3, (songCount * 2) / 3, songCount - 1).distinct().sorted()
    }

    fun estimateSongCount(targetSeconds: Long): Int {
        val target = targetSeconds.coerceAtLeast(60L)
        // Four narration/transition moments are used for normal full-length sets.
        val reserved = (ESTIMATED_NARRATION_SECONDS + DEFAULT_TRANSITION_SECONDS) * ESTIMATED_NARRATION_CUES
        val musicSeconds = (target - reserved).coerceAtLeast(1L)
        return ceil(musicSeconds.toDouble() / TARGET_MUSIC_SECONDS_PER_SONG)
            .toInt()
            .coerceIn(4, 80)
    }

    fun create(targetSeconds: Long, actualSongCount: Int): ConcertDurationPlan {
        val target = targetSeconds.coerceAtLeast(0L)
        val count = actualSongCount.coerceAtLeast(0)
        val cueCount = cueIndexes(count).size
        val narration = ESTIMATED_NARRATION_SECONDS * cueCount
        val transitions = DEFAULT_TRANSITION_SECONDS * cueCount
        val music = (target - narration - transitions).coerceAtLeast(0L)
        val averageMusic = if (count == 0) 0L else music / count
        return ConcertDurationPlan(
            requestedSeconds = target,
            estimatedSongCount = count,
            averageMusicSecondsPerSong = averageMusic,
            transitionSeconds = transitions,
            narrationSeconds = narration,
            estimatedTotalSeconds = music + transitions + narration,
        )
    }

    fun formatDuration(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0L)
        val hours = safe / 3600
        val minutes = (safe % 3600) / 60
        val remaining = safe % 60
        return if (hours > 0) "%d hr %02d min".format(hours, minutes)
        else if (remaining > 0) "%d min %02d sec".format(minutes, remaining)
        else "$minutes min"
    }
}


/** A verified playback window inside a source song, in milliseconds. */
data class ConcertTrackWindow(
    val startMs: Long,
    val endMs: Long,
    val usedEnergyAnalysis: Boolean = false,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/** Selects a high-energy segment from Convx's cached one-value-per-second energy profile. */
object ConcertHighlightPlanner {
    fun select(
        durationSeconds: Int,
        requestedWindowSeconds: Long,
        energyProfile: ByteArray?,
        bpm: Float?,
        anchorBeatMs: Float?,
    ): ConcertTrackWindow? {
        if (durationSeconds <= 0) return null
        val windowSeconds = requestedWindowSeconds.coerceAtLeast(30L).coerceAtMost(durationSeconds.toLong()).toInt()
        if (durationSeconds <= windowSeconds) return ConcertTrackWindow(0L, durationSeconds * 1_000L)

        val latestStart = durationSeconds - windowSeconds
        val energy = energyProfile?.map { it.toInt() and 0xff }
        val energyData = energy?.takeIf { it.size >= durationSeconds }
        var bestStart = if (energyData != null) {
            val prefix = LongArray(energyData.size + 1)
            for (i in energyData.indices) prefix[i + 1] = prefix[i] + energyData[i]
            var start = 0
            var best = Long.MIN_VALUE
            for (candidate in 0..minOf(latestStart, energyData.size - windowSeconds)) {
                val score = prefix[candidate + windowSeconds] - prefix[candidate]
                if (score > best) { best = score; start = candidate }
            }
            start
        } else {
            // If no valid energy profile is cached, use the centre of the song rather than
            // inventing a chorus timestamp.
            latestStart / 2
        }

        val beatIntervalMs = bpm?.takeIf { it in 40f..220f }?.let { 60_000.0 / it }
        if (beatIntervalMs != null && anchorBeatMs != null && anchorBeatMs.isFinite()) {
            val rawMs = bestStart * 1_000.0
            val beatNumber = kotlin.math.round((rawMs - anchorBeatMs) / beatIntervalMs)
            val snapped = anchorBeatMs + beatNumber * beatIntervalMs
            if (snapped.isFinite()) bestStart = (snapped / 1_000.0).toInt().coerceIn(0, latestStart)
        }

        val startMs = bestStart * 1_000L
        return ConcertTrackWindow(startMs, (bestStart + windowSeconds) * 1_000L, usedEnergyAnalysis = energyData != null)
    }
}

/** A generated name based on the theme, not a hard-coded generic "Concert" label. */
object ConcertNameGenerator {
    fun generate(theme: String, seedArtist: String? = null): String {
        val source = theme.trim().ifBlank { seedArtist.orEmpty() }
            .replace(Regex("\\s+"), " ")
            .trim(' ', ',', '.', '!', '?', ':', ';', '-', '—')
        val clean = source.split(' ').take(5).joinToString(" ")
            .ifBlank { "The Sound" }
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val title = "$clean — The Convx Live Experience"
        return title.take(92)
    }
}
