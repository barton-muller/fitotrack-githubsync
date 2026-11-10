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

package de.tadris.fitness.data

enum class WorkoutCapability(val id: String) {
    GPS("gps"),
    REPETITIONS("repetitions");

    companion object {
        /**
         * Preference mode key used for workouts without GPS capability.
         * Kept as "indoor" for backwards compatibility with existing SharedPreferences.
         */
        const val PREFERENCE_MODE_NON_GPS = "indoor"

        @JvmStatic
        fun fromId(id: String): WorkoutCapability? = entries.find { it.id == id }

        @JvmStatic
        fun parseSet(value: String?): Set<WorkoutCapability> {
            if (value.isNullOrEmpty()) return emptySet()
            return value.split(",").mapNotNull { fromId(it.trim()) }.toSet()
        }

        @JvmStatic
        fun toStorageString(capabilities: Set<WorkoutCapability>): String =
            capabilities.joinToString(",") { it.id }
    }
}
