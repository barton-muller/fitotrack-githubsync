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

package de.tadris.fitness.recording

import de.tadris.fitness.data.CalculatedValues
import de.tadris.fitness.data.WorkoutData
import de.tadris.fitness.data.WorkoutTypeManager
import de.tadris.fitness.util.totalDistance

class WorkoutTypeClassifier(
    private val data: WorkoutData,
    private val calculatedValues: CalculatedValues,
    private val activeDurationMs: Long,
) {

    companion object {
        private const val WALKING_RUNNING_THRESHOLD = 7.0 / 3.6   // m/s (7 km/h)
        private const val RUNNING_CYCLING_THRESHOLD = 18.0 / 3.6  // m/s (18 km/h)
        private const val HIKING_ELEVATION_PER_KM = 25.0           // m/km
        private const val HIKING_MIN_DISTANCE = 1000.0             // m
    }

    private val distance by lazy { data.samples.totalDistance }

    fun classify(): String {

        val avgSpeed = if (activeDurationMs > 0) distance / (activeDurationMs / 1000.0) else 0.0

        return when {
            avgSpeed >= RUNNING_CYCLING_THRESHOLD -> WorkoutTypeManager.WORKOUT_TYPE_ID_CYCLING
            avgSpeed >= WALKING_RUNNING_THRESHOLD -> WorkoutTypeManager.WORKOUT_TYPE_ID_RUNNING
            else -> classifyWalkingOrHiking()
        }
    }

    private fun classifyWalkingOrHiking(): String {
        if (distance < HIKING_MIN_DISTANCE) return WorkoutTypeManager.WORKOUT_TYPE_ID_WALKING
        val (ascent, descent) = calculatedValues.getAscentAndDescent()
        val elevationPerKm = (ascent + descent) / (distance / 1000.0)
        return if (elevationPerKm > HIKING_ELEVATION_PER_KM) {
            WorkoutTypeManager.WORKOUT_TYPE_ID_HIKING
        } else {
            WorkoutTypeManager.WORKOUT_TYPE_ID_WALKING
        }
    }
}
