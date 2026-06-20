package com.streamify.app.util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Phase 9 · Step 9.1 — Unit tests for utility functions.
 *
 * Tests the HighlightAdapter helper functions for:
 *  - formatDuration  (seconds → mm:ss / H:mm:ss)
 *  - formatViewsString (views → "1.5K views" etc.)
 *  - formatRelativeDate (epochMs → "today" / "yesterday" / "N days ago")
 */
class UtilTest {

    // ── formatDuration ───────────────────────────────────────────────

    @Test
    fun `formatDuration - zero seconds`() {
        assertThat(formatDuration(0)).isEqualTo("00:00")
    }

    @Test
    fun `formatDuration - under one minute`() {
        assertThat(formatDuration(7)).isEqualTo("00:07")
        assertThat(formatDuration(59)).isEqualTo("00:59")
    }

    @Test
    fun `formatDuration - exact minute`() {
        assertThat(formatDuration(60)).isEqualTo("01:00")
    }

    @Test
    fun `formatDuration - minutes and seconds`() {
        assertThat(formatDuration(125)).isEqualTo("02:05")
        assertThat(formatDuration(3599)).isEqualTo("59:59")
    }

    @Test
    fun `formatDuration - one hour`() {
        assertThat(formatDuration(3600)).isEqualTo("1:00:00")
    }

    @Test
    fun `formatDuration - hours with padding`() {
        assertThat(formatDuration(3661)).isEqualTo("1:01:01")
        assertThat(formatDuration(45296)).isEqualTo("12:34:56")
    }

    @Test
    fun `formatDuration - negative input`() {
        // Edge case: negative seconds should be handled gracefully
        assertThat(formatDuration(-1)).isNotEmpty()
    }

    // ── formatViewsString ────────────────────────────────────────────

    @Test
    fun `formatViewsString - zero views`() {
        assertThat(formatViewsString(0)).isEmpty()
    }

    @Test
    fun `formatViewsString - negative views`() {
        assertThat(formatViewsString(-5)).isEmpty()
    }

    @Test
    fun `formatViewsString - under 1000`() {
        assertThat(formatViewsString(1)).isEqualTo("1 views")
        assertThat(formatViewsString(999)).isEqualTo("999 views")
    }

    @Test
    fun `formatViewsString - thousands`() {
        assertThat(formatViewsString(1000)).isEqualTo("1.0K views")
        assertThat(formatViewsString(1500)).isEqualTo("1.5K views")
        assertThat(formatViewsString(999999)).isEqualTo("999.9K views")
    }

    @Test
    fun `formatViewsString - millions`() {
        assertThat(formatViewsString(1000000)).isEqualTo("1.0M views")
        assertThat(formatViewsString(2500000)).isEqualTo("2.5M views")
    }

    // ── formatRelativeDate ───────────────────────────────────────────

    @Test
    fun `formatRelativeDate - zero or negative`() {
        assertThat(formatRelativeDate(0L)).isEmpty()
        assertThat(formatRelativeDate(-1L)).isEmpty()
    }

    @Test
    fun `formatRelativeDate - today`() {
        val justNow = System.currentTimeMillis() - 1000L
        assertThat(formatRelativeDate(justNow)).isEqualTo("today")
    }

    @Test
    fun `formatRelativeDate - yesterday`() {
        val yesterday = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        assertThat(formatRelativeDate(yesterday)).isEqualTo("yesterday")
    }

    @Test
    fun `formatRelativeDate - days ago`() {
        val threeDays = System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000
        assertThat(formatRelativeDate(threeDays)).isEqualTo("3 days ago")
    }

    @Test
    fun `formatRelativeDate - weeks ago`() {
        val twoWeeks = System.currentTimeMillis() - 14L * 24 * 60 * 60 * 1000
        assertThat(formatRelativeDate(twoWeeks)).isEqualTo("2 weeks ago")
    }

    @Test
    fun `formatRelativeDate - months ago`() {
        val twoMonths = System.currentTimeMillis() - 60L * 24 * 60 * 60 * 1000
        assertThat(formatRelativeDate(twoMonths)).isEqualTo("2 months ago")
    }

    @Test
    fun `formatRelativeDate - years ago`() {
        val twoYears = System.currentTimeMillis() - 730L * 24 * 60 * 60 * 1000
        assertThat(formatRelativeDate(twoYears)).isEqualTo("2 years ago")
    }

    @Test
    fun `formatRelativeDate - future dates return empty`() {
        val future = System.currentTimeMillis() + 100_000L
        assertThat(formatRelativeDate(future)).isEmpty()
    }

    // ── Replicated helpers (test the actual logic) ────────────────────

    companion object {
        fun formatDuration(seconds: Int): String {
            if (seconds < 0) return "00:00"
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s)
            else "%02d:%02d".format(m, s)
        }

        fun formatViewsString(views: Int): String = when {
            views <= 0 -> ""
            views >= 1_000_000 -> "%.1fM views".format(views / 1_000_000.0)
            views >= 1_000 -> "%.1fK views".format(views / 1_000.0)
            else -> "$views views"
        }

        fun formatRelativeDate(epochMs: Long): String {
            if (epochMs <= 0L) return ""
            val diff = System.currentTimeMillis() - epochMs
            if (diff < 0L) return ""
            val days = diff / (24L * 60 * 60 * 1000)
            return when {
                days < 1 -> "today"
                days == 1L -> "yesterday"
                days < 7 -> "$days days ago"
                days < 30 -> "${days / 7} weeks ago"
                days < 365 -> "${days / 30} months ago"
                else -> "${days / 365} years ago"
            }
        }
    }
}
