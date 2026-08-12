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

package de.tadris.fitness.ui.statistics

import android.os.Bundle
import androidx.activity.compose.setContent
import de.tadris.fitness.data.WorkoutType
import de.tadris.fitness.stats.WorkoutProperty
import de.tadris.fitness.stats.aggregation.AggregationSpan
import de.tadris.fitness.ui.FitoTrackActivity
import de.tadris.fitness.ui.theme.FitoTrackTheme

class DetailStatsActivity : FitoTrackActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        val property = intent.getSerializableExtra("property") as? WorkoutProperty ?: WorkoutProperty.LENGTH
        val summed = intent.getBooleanExtra("summed", false)
        val types = intent.getSerializableExtra("types") as? ArrayList<WorkoutType> ?: arrayListOf()
        val aggregationSpan = intent.getSerializableExtra("aggregationSpan") as? AggregationSpan ?: AggregationSpan.YEAR
        val xScale = intent.getFloatExtra("xScale", 1f)
        val xTrans = intent.getFloatExtra("xTrans", 0f)
        val yLabel = intent.getStringExtra("ylabel") ?: ""

        setContent {
            FitoTrackTheme {
                DetailStatsScreen(
                    property = property,
                    summed = summed,
                    types = types,
                    initialAggregationSpan = aggregationSpan,
                    initialXScale = xScale,
                    initialXTrans = xTrans,
                    yLabel = yLabel,
                    onBack = { finish() }
                )
            }
        }
    }
}
