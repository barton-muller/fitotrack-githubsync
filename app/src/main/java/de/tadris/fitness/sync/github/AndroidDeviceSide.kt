/*
 * Copyright (c) 2026 barton-muller
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

package de.tadris.fitness.sync.github

import android.content.Context
import de.tadris.fitness.Instance
import de.tadris.fitness.data.autoexport.source.WorkoutGpxExportSource
import de.tadris.fitness.sync.DeviceSide
import de.tadris.fitness.sync.WorkoutFile
import de.tadris.fitness.util.export.ImportWorkoutSaver
import de.tadris.fitness.util.export.gpx.GpxImporter

/** The workouts and settings of this device, as the sync engine sees them. */
class AndroidDeviceSide(context: Context) : DeviceSide {

    private val context = context.applicationContext
    private val db get() = Instance.getInstance(context).db
    private val preferences get() = Instance.getInstance(context).userPreferences.preferences

    override fun workoutStarts(): Set<Long> =
        db.workoutDao().getWorkouts().filter { it.end > it.start }.map { it.start }.toSet()

    override fun export(start: Long): WorkoutFile {
        val workout = db.workoutDao().getWorkoutByStart(start) ?: throw IllegalStateException("No workout starts at $start")
        val file = WorkoutGpxExportSource(workout.id).provideFile(context).file
        return WorkoutFile(file.name, file.readBytes())
    }

    override fun import(gpx: ByteArray): Pair<Long, Boolean> {
        val workouts = GpxImporter().readWorkouts(gpx.inputStream()).workouts
        val first = workouts.firstOrNull() ?: throw java.io.IOException("No workout in the file")
        var added = false
        for (data in workouts) {
            if (db.workoutDao().getWorkoutByStart(data.workout.start) == null) {
                ImportWorkoutSaver(context, data).save()
                added = true
            }
        }
        return first.workout.start to added
    }

    override fun delete(start: Long) {
        val workout = db.workoutDao().getWorkoutByStart(start) ?: return
        db.workoutDao().deleteWorkout(workout)
    }

    override fun settings(): String = PreferencesSnapshot.take(preferences)

    override fun applySettings(text: String) = PreferencesSnapshot.apply(preferences, text)
}
