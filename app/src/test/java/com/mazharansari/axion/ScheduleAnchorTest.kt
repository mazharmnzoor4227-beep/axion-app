package com.mazharansari.axion

import com.mazharansari.axion.data.ScheduleEntry
import com.mazharansari.axion.data.ScheduleType
import com.mazharansari.axion.work.ScheduleEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * P0-7 (HI-5): the stable scheduler anchor. All cases use fixed wall-clock
 * times in the system zone, so they are deterministic on any machine.
 * 2026-11-16 is a Monday, 2026-11-20 a Friday.
 */
class ScheduleAnchorTest {
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun daily(hour: Int = 9, minute: Int = 0, anchor: Long = 0) =
        ScheduleEntry("d1", "Daily", "do it", ScheduleType.DAILY, hour, minute, anchorEpochMs = anchor)

    private fun weekly(dayOfWeek: Int = 1, hour: Int = 9, minute: Int = 0, anchor: Long = 0) =
        ScheduleEntry("w1", "Weekly", "do it", ScheduleType.WEEKLY, hour, minute, dayOfWeek = dayOfWeek, anchorEpochMs = anchor)

    private fun once(at: Long) =
        ScheduleEntry("o1", "Once", "do it", ScheduleType.ONCE, 9, 0, atEpochMs = at, anchorEpochMs = at)

    @Test fun dailyNextRunSameDay() {
        assertEquals(ms(2026, 11, 16, 9, 0), ScheduleEngine.nextRunMs(daily(), ms(2026, 11, 16, 8, 0)))
    }

    @Test fun dailyNextRunTomorrowAfterTimePassed() {
        assertEquals(ms(2026, 11, 17, 9, 0), ScheduleEngine.nextRunMs(daily(), ms(2026, 11, 16, 10, 0)))
    }

    @Test fun weeklyNextRunSameDay() {
        assertEquals(ms(2026, 11, 16, 9, 0), ScheduleEngine.nextRunMs(weekly(), ms(2026, 11, 16, 8, 0)))
    }

    @Test fun weeklyNextRunNextWeekAfterTimePassed() {
        assertEquals(ms(2026, 11, 23, 9, 0), ScheduleEngine.nextRunMs(weekly(), ms(2026, 11, 16, 10, 0)))
    }

    @Test fun weeklySkipsToRightWeekday() {
        // Monday 08:00, every Friday 09:00 -> Friday of the same week.
        assertEquals(ms(2026, 11, 20, 9, 0), ScheduleEngine.nextRunMs(weekly(dayOfWeek = 5), ms(2026, 11, 16, 8, 0)))
    }

    @Test fun anchorForKeepsFutureMatchingAnchor() {
        val anchor = ms(2026, 11, 17, 9, 0)
        assertEquals(anchor, ScheduleEngine.anchorFor(daily(anchor = anchor), ms(2026, 11, 16, 10, 0)))
    }

    @Test fun anchorForRollsPastAnchorForward() {
        // Stored anchor Monday 09:00 is past by Tuesday 10:00 -> next is Wednesday 09:00.
        val stale = ms(2026, 11, 16, 9, 0)
        assertEquals(ms(2026, 11, 18, 9, 0), ScheduleEngine.anchorFor(daily(anchor = stale), ms(2026, 11, 17, 10, 0)))
    }

    @Test fun anchorForRejectsAnchorFromEditedEntry() {
        // Entry now says 10:30, but the stored anchor still says 09:00 -> recompute.
        val oldAnchor = ms(2026, 11, 16, 9, 0)
        assertEquals(ms(2026, 11, 16, 10, 30), ScheduleEngine.anchorFor(daily(hour = 10, minute = 30, anchor = oldAnchor), ms(2026, 11, 16, 8, 0)))
    }

    @Test fun anchorForWithoutAnchorIsNextRun() {
        assertEquals(ms(2026, 11, 16, 9, 0), ScheduleEngine.anchorFor(daily(), ms(2026, 11, 16, 8, 0)))
    }

    @Test fun anchorForOnceIsFixedTime() {
        val at = ms(2026, 11, 20, 9, 0)
        assertEquals(at, ScheduleEngine.anchorFor(once(at), ms(2026, 11, 16, 8, 0)))
    }

    @Test fun anchorMatchesChecksWallClock() {
        assertTrue(ScheduleEngine.anchorMatches(daily(), ms(2026, 11, 16, 9, 0)))
        assertFalse(ScheduleEngine.anchorMatches(daily(), ms(2026, 11, 16, 9, 30)))
        assertFalse(ScheduleEngine.anchorMatches(daily(), ms(2026, 11, 16, 10, 0)))
        assertFalse(ScheduleEngine.anchorMatches(daily(), 0L))
    }

    @Test fun anchorMatchesChecksWeekday() {
        assertTrue(ScheduleEngine.anchorMatches(weekly(dayOfWeek = 1), ms(2026, 11, 16, 9, 0)))
        assertFalse(ScheduleEngine.anchorMatches(weekly(dayOfWeek = 1), ms(2026, 11, 17, 9, 0)))
    }

    @Test fun anchorMatchesOnceIsExact() {
        val at = ms(2026, 11, 20, 9, 0)
        assertTrue(ScheduleEngine.anchorMatches(once(at), at))
        assertFalse(ScheduleEngine.anchorMatches(once(at), at + 60_000L))
    }

    @Test fun periodLengths() {
        assertEquals(24L * 3_600_000L, ScheduleEngine.periodMs(daily()))
        assertEquals(7L * 24L * 3_600_000L, ScheduleEngine.periodMs(weekly()))
        assertEquals(0L, ScheduleEngine.periodMs(once(ms(2026, 11, 20, 9, 0))))
    }
}
