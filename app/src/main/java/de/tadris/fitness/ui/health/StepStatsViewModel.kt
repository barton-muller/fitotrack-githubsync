/*
 * Copyright (c) 2026 Jannis Scheibe <jannis@tadris.de>
 *
 * This file is part of FitoTrack
 *
 * FitoTrack is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     FitoTrack is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.tadris.fitness.ui.health

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.tadris.fitness.Instance
import de.tadris.fitness.R
import de.tadris.fitness.health.StepTrackingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.Locale

enum class StepStatsPeriod(val labelRes: Int) {
    DAY(R.string.day),
    WEEK(R.string.week),
    MONTH(R.string.month),
    YEAR(R.string.year),
}

class StepStatsViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = Instance.getInstance(app).db.stepDao()
    private val prefs = Instance.getInstance(app).userPreferences

    val selectedPeriod = MutableStateFlow(StepStatsPeriod.WEEK)

    val chartData: StateFlow<BarChartData> = combine(
        selectedPeriod,
        StepTrackingService.pendingSteps,
    ) { period: StepStatsPeriod, _: Int ->
        buildChartData(period)
    }
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            BarChartData(emptyList(), emptyList())
        )

    private fun buildChartData(period: StepStatsPeriod): BarChartData {
        return when (period) {
            StepStatsPeriod.DAY -> buildDayData()
            StepStatsPeriod.WEEK -> buildWeekData()
            StepStatsPeriod.MONTH -> buildMonthData()
            StepStatsPeriod.YEAR -> buildYearData()
        }
    }

    private fun buildDayData(): BarChartData {
        val dayStart = todayMidnight()
        val dayEnd = dayStart + DAY_MS
        val samples = dao.getStepsInRange(dayStart, dayEnd)
        val byWindow = samples.associateBy { windowIndex(it.windowStart, dayStart) }

        val slotCount = 96 // 24h / 15min
        val mutableEntries = MutableList(slotCount) { i -> byWindow[i]?.steps ?: 0 }
        val nowWindow = windowIndex(System.currentTimeMillis(), dayStart).coerceIn(0, slotCount - 1)
        mutableEntries[nowWindow] =
            mutableEntries[nowWindow] + StepTrackingService.pendingSteps.value

        return BarChartData(
            mutableEntries,
            listOf("0", "6", "12", "18"),
            mutableEntries.maxOrNull()
        )
    }

    private fun buildWeekData(): BarChartData {
        val firstDay = prefs.firstDayOfWeek // Calendar constant: 1=Sun, 2=Mon, …
        val (weekStart, weekEnd) = currentWeekRange(firstDay)
        val samples = dao.getStepsInRange(weekStart, weekEnd)

        val byDay = mutableMapOf<Int, Int>()
        for (sample in samples) {
            val day = dayOfWeekIndex(sample.windowStart, weekStart)
            byDay[day] = (byDay[day] ?: 0) + sample.steps
        }
        val entries = List(7) { i -> byDay[i] ?: 0 }

        val shortWeekdays = DateFormatSymbols.getInstance(Locale.getDefault()).shortWeekdays
        val cal = Calendar.getInstance()
        cal.timeInMillis = weekStart
        val labels = List(7) { i ->
            val c = cal.clone() as Calendar
            c.add(Calendar.DAY_OF_YEAR, i)
            shortWeekdays[c.get(Calendar.DAY_OF_WEEK)]
        }
        return BarChartData(entries, labels, prefs.dailyStepGoal)
    }

    private fun buildMonthData(): BarChartData {
        val (monthStart, monthEnd, daysInMonth) = currentMonthRange()
        val samples = dao.getStepsInRange(monthStart, monthEnd)
        val byDay = mutableMapOf<Int, Int>()
        for (sample in samples) {
            val cal = Calendar.getInstance()
            cal.timeInMillis = sample.windowStart
            val day = cal.get(Calendar.DAY_OF_MONTH) - 1
            byDay[day] = (byDay[day] ?: 0) + sample.steps
        }
        val entries = List(daysInMonth) { i -> byDay[i] ?: 0 }
        val labels = (1..daysInMonth step 7).map { it.toString() }
        return BarChartData(entries, labels, prefs.dailyStepGoal)
    }

    private fun buildYearData(): BarChartData {
        val (yearStart, yearEnd) = currentYearRange()
        val samples = dao.getStepsInRange(yearStart, yearEnd)
        val byMonth = mutableMapOf<Int, Int>()
        for (sample in samples) {
            val cal = Calendar.getInstance()
            cal.timeInMillis = sample.windowStart
            val month = cal.get(Calendar.MONTH)
            byMonth[month] = (byMonth[month] ?: 0) + sample.steps
        }
        val entries = List(12) { i -> byMonth[i] ?: 0 }
        val labels = DateFormatSymbols.getInstance(Locale.getDefault()).shortMonths
            .take(12)
            .mapIndexed { i, name -> if (i % 2 == 0) name else "" }
        return BarChartData(entries, labels, entries.maxOrNull())
    }

    private fun windowIndex(timestamp: Long, dayStart: Long): Int =
        ((timestamp - dayStart) / WINDOW_MS).toInt()

    private fun dayOfWeekIndex(timestamp: Long, weekStart: Long): Int =
        ((timestamp - weekStart) / DAY_MS).toInt().coerceIn(0, 6)

    private fun currentWeekRange(firstDay: Int): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        val daysFromStart = (dow - firstDay + 7) % 7
        cal.add(Calendar.DAY_OF_YEAR, -daysFromStart)
        val weekStart = cal.timeInMillis
        return weekStart to weekStart + 7 * DAY_MS
    }

    private data class MonthRange(val start: Long, val end: Long, val daysInMonth: Int)

    private fun currentMonthRange(): MonthRange {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val monthStart = cal.timeInMillis
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        return MonthRange(monthStart, monthStart + daysInMonth * DAY_MS, daysInMonth)
    }

    private fun currentYearRange(): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.MONTH, Calendar.JANUARY)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val yearStart = cal.timeInMillis
        cal.set(Calendar.MONTH, Calendar.DECEMBER)
        cal.set(Calendar.DAY_OF_MONTH, 31)
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        return yearStart to cal.timeInMillis
    }

    companion object {
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val WINDOW_MS = 15 * 60 * 1000L

        fun todayMidnight(): Long {
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
    }
}
