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

package de.tadris.fitness.steps

import android.Manifest
import android.content.Context
import android.content.Intent
import de.tadris.fitness.data.WorkoutTypeManager
import de.tadris.fitness.data.WorkoutDao
import de.tadris.fitness.recording.RecorderService
import de.tadris.fitness.recording.RecordingState
import de.tadris.fitness.recording.event.RecordingStateChangeEvent
import de.tadris.fitness.util.PermissionUtils

class StepActivityRecognizer(
    private val context: Context,
    private val workoutDao: WorkoutDao,
) {

    companion object {
        private const val STEPS_PER_MINUTE_THRESHOLD = 80
        private const val REQUIRED_ACTIVE_MINUTES = 4
        private const val COOLDOWN_AFTER_STOP_MS = 10 * 60 * 1000L
    }

    private val recentMinutes = ArrayDeque<Int>(REQUIRED_ACTIVE_MINUTES)

    fun onMinuteReceived(stepsInMinute: Int) {
        recentMinutes.addLast(stepsInMinute)
        if (recentMinutes.size > REQUIRED_ACTIVE_MINUTES) {
            recentMinutes.removeFirst()
        }

        if (recentMinutes.size == REQUIRED_ACTIVE_MINUTES
            && recentMinutes.all { it > STEPS_PER_MINUTE_THRESHOLD }
        ) {
            maybeStartRecording()
        }
    }

    private fun maybeStartRecording() {
        if (isRecordingRunning()) return
        if (!isCooldownElapsed()) return
        if (!hasLocationPermission()) return

        startRecording()
    }

    private fun isRecordingRunning(): Boolean {
        val event = RecordingStateChangeEvent.getCurrentOptional() ?: return false
        val state = event.newState
        return state == RecordingState.RUNNING || state == RecordingState.AUTO_PAUSE || state == RecordingState.MANUAL_PAUSE
    }

    private fun isCooldownElapsed(): Boolean {
        val lastEnd = workoutDao.getLastWorkout()?.end ?: return true
        if (lastEnd == -1L) return false // a workout is currently running according to DB
        return System.currentTimeMillis() - lastEnd > COOLDOWN_AFTER_STOP_MS
    }

    private fun hasLocationPermission(): Boolean {
        if (!PermissionUtils.checkPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
            if (!PermissionUtils.checkPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)) {
                return false
            }
        }
        return true
    }

    private fun startRecording() {
        val intent = Intent(context, RecorderService::class.java).apply {
            putExtra(RecorderService.EXTRA_WORKOUT_TYPE, WorkoutTypeManager.WORKOUT_TYPE_ID_AUTO)
            putExtra(RecorderService.EXTRA_AUTO_STARTED, true)
        }
        context.startForegroundService(intent)
    }
}
