package com.convx.music.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcertDurationPlannerTest {
    @Test
    fun shortConcertReservesTimeForNarrationAndTransitions() {
        val count = ConcertDurationPlanner.estimateSongCount(15 * 60L)
        assertTrue(count in 4..8)
        val plan = ConcertDurationPlanner.create(15 * 60L, count)
        assertEquals(15 * 60L, plan.estimatedTotalSeconds)
        assertTrue(plan.transitionSeconds > 0)
        assertTrue(plan.narrationSeconds > 0)
    }

    @Test
    fun maximumFourHourConcertIsNotCappedAtThirtyTracks() {
        assertTrue(ConcertDurationPlanner.estimateSongCount(240 * 60L) > 30)
    }

    @Test
    fun highlightPlannerChoosesTheHighEnergyWindow() {
        val energy = ByteArray(120) { 10 }
        for (second in 60 until 90) energy[second] = 220.toByte()
        val window = ConcertHighlightPlanner.select(
            durationSeconds = 120,
            requestedWindowSeconds = 30,
            energyProfile = energy,
            bpm = null,
            anchorBeatMs = null,
        )
        assertEquals(60_000L, window?.startMs)
        assertEquals(90_000L, window?.endMs)
        assertTrue(window?.usedEnergyAnalysis == true)
    }

    @Test
    fun highlightPlannerFallsBackToTheMiddleWhenAnalysisIsMissing() {
        val window = ConcertHighlightPlanner.select(120, 30, null, null, null)
        assertEquals(45_000L, window?.startMs)
        assertEquals(75_000L, window?.endMs)
        assertTrue(window?.usedEnergyAnalysis == false)
    }

    @Test
    fun generatedConcertNameUsesTheConcertTheme() {
        val title = ConcertNameGenerator.generate("late night alternative R&B", null)
        assertTrue(title.contains("Late night alternative R&B"))
        assertTrue(title.contains("Convx Live Experience"))
    }
}
