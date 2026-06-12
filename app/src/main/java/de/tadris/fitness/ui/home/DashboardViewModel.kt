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

package de.tadris.fitness.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.tadris.fitness.Instance
import de.tadris.fitness.data.Workout
import de.tadris.fitness.health.StepTrackingService
import de.tadris.fitness.ui.health.BarChartData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.Locale

class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    private val workoutDao = Instance.getInstance(app).db.workoutDao()
    private val stepDao = Instance.getInstance(app).db.stepDao()
    private val prefs = Instance.getInstance(app).userPreferences

    private val _stepGoal = MutableStateFlow(prefs.dailyStepGoal)
    val stepGoal: StateFlow<Int> = _stepGoal

    fun setDailyStepGoal(goal: Int) {
        prefs.dailyStepGoal = goal
        _stepGoal.value = goal
    }

    val todaySteps: StateFlow<Int> = combine(
        stepDao.getTodayStepCountFlow(todayMidnight(), todayMidnight() + DAY_MS),
        StepTrackingService.pendingSteps,
    ) { persisted, pending -> persisted + pending }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val todayActiveMinutes: StateFlow<Int> =
        stepDao.getTodayActiveMinutesFlow(todayMidnight(), todayMidnight() + DAY_MS)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val todayDistanceMeters: StateFlow<Int> = todaySteps
        .map { steps -> (steps * prefs.measurements.stepLength).toInt() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val weeklyStepChart: StateFlow<BarChartData> = combine(
        todaySteps,
        StepTrackingService.pendingSteps,
    ) { _, _ -> buildLast7DaysStepData() }
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            BarChartData(emptyList(), emptyList())
        )

    val weeklyWorkouts: StateFlow<List<Workout>> = workoutDao.getWorkoutsFlow()
        .map { workouts ->
            val (weekStart, weekEnd) = currentWeekRange()
            workouts.filter { it.start >= weekStart && it.start < weekEnd }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val latestWorkout: StateFlow<Workout?> = workoutDao.getWorkoutsFlow()
        .map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val weekStartMillis: Long get() = currentWeekRange().first

    private fun currentWeekRange(): Pair<Long, Long> {
        val firstDay = prefs.firstDayOfWeek
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

    private fun buildLast7DaysStepData(): BarChartData {
        val rangeStart = todayMidnight() - 6 * DAY_MS
        val rangeEnd = todayMidnight() + DAY_MS
        val samples = stepDao.getStepsInRange(rangeStart, rangeEnd)

        val byDay = mutableMapOf<Int, Int>()
        for (sample in samples) {
            val day = ((sample.windowStart - rangeStart) / DAY_MS).toInt().coerceIn(0, 6)
            byDay[day] = (byDay[day] ?: 0) + sample.steps
        }

        byDay[6] = (byDay[6] ?: 0) + StepTrackingService.pendingSteps.value

        val entries = List(7) { i -> byDay[i] ?: 0 }
        val shortWeekdays = DateFormatSymbols.getInstance(Locale.getDefault()).shortWeekdays
        val dayCal = Calendar.getInstance()
        dayCal.timeInMillis = rangeStart
        val labels = List(7) { i ->
            val c = dayCal.clone() as Calendar
            c.add(Calendar.DAY_OF_YEAR, i)
            shortWeekdays[c.get(Calendar.DAY_OF_WEEK)]
        }
        return BarChartData(entries, labels)
    }

    companion object {
        private const val DAY_MS = 24 * 60 * 60 * 1000L

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
