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

package de.tadris.fitness.ui.statistics.fragments

import android.content.Intent
import android.view.MotionEvent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.CombinedChart
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.CandleData
import com.github.mikephil.charting.data.CombinedData
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.listener.ChartTouchListener
import com.github.mikephil.charting.listener.OnChartGestureListener
import de.tadris.fitness.R
import de.tadris.fitness.data.WorkoutType
import de.tadris.fitness.data.preferences.UserPreferences
import de.tadris.fitness.stats.NoDataException
import de.tadris.fitness.stats.StatsProvider
import de.tadris.fitness.stats.WorkoutProperty
import de.tadris.fitness.stats.aggregation.AggregationSpan
import de.tadris.fitness.ui.FitoTrackActivity
import de.tadris.fitness.ui.charts.DataSetStyles
import de.tadris.fitness.ui.components.SectionHeader
import de.tadris.fitness.ui.statistics.DetailStatsActivity
import de.tadris.fitness.ui.statistics.components.CombinedChartComponent
import de.tadris.fitness.ui.statistics.workout_type_picker.WorkoutTypeSelection

@Composable
fun StatsHistoryScreen() {
    val context = LocalContext.current
    val activity = context as FitoTrackActivity
    val statsProvider = remember { StatsProvider(context) }
    val preferences = remember { UserPreferences(context) }

    var selectedTypes by remember {
        mutableStateOf(preferences.statisticsSelectedTypes.filterNotNull().ifEmpty {
            de.tadris.fitness.data.WorkoutTypeManager.getInstance().getAllTypes(context)
        })
    }
    var aggregationSpan by remember { mutableStateOf(AggregationSpan.YEAR) }

    var speedSwapped by remember { mutableStateOf(false) }
    var distanceSwapped by remember { mutableStateOf(true) }
    var durationSwapped by remember { mutableStateOf(true) }

    // Explore state
    var exploreProperty by remember { mutableStateOf(WorkoutProperty.PAUSE_DURATION) }
    var exploreSummed by remember { mutableStateOf(false) }

    // Chart Data States
    var speedData by remember { mutableStateOf<CombinedData?>(null) }
    var distanceData by remember { mutableStateOf<CombinedData?>(null) }
    var durationData by remember { mutableStateOf<CombinedData?>(null) }
    var exploreData by remember { mutableStateOf<CombinedData?>(null) }

    val updateCharts = {
        // Speed
        val speedProp = if (speedSwapped) WorkoutProperty.AVG_PACE else WorkoutProperty.AVG_SPEED
        speedData = try {
            val candleSet = statsProvider.getCandleData(aggregationSpan, selectedTypes, speedProp)
            CombinedData().apply {
                setData(CandleData(candleSet))
                setData(LineData(DataSetStyles.applyBackgroundLineStyle(context, StatsProvider.convertCandleToMeanLineData(candleSet))))
            }
        } catch (_: NoDataException) { null }

        // Distance
        distanceData = try {
            CombinedData().apply {
                if (distanceSwapped) {
                    setData(BarData(statsProvider.getSumData(aggregationSpan, selectedTypes, WorkoutProperty.LENGTH)))
                } else {
                    val candleSet = statsProvider.getCandleData(aggregationSpan, selectedTypes, WorkoutProperty.LENGTH)
                    setData(CandleData(candleSet))
                    setData(LineData(DataSetStyles.applyBackgroundLineStyle(context, StatsProvider.convertCandleToMeanLineData(candleSet))))
                }
            }
        } catch (_: NoDataException) { null }

        // Duration
        durationData = try {
            CombinedData().apply {
                if (durationSwapped) {
                    setData(BarData(statsProvider.getSumData(aggregationSpan, selectedTypes, WorkoutProperty.DURATION)))
                } else {
                    val candleSet = statsProvider.getCandleData(aggregationSpan, selectedTypes, WorkoutProperty.DURATION)
                    setData(CandleData(candleSet))
                    setData(LineData(DataSetStyles.applyBackgroundLineStyle(context, StatsProvider.convertCandleToMeanLineData(candleSet))))
                }
            }
        } catch (_: NoDataException) { null }

        // Explore
        exploreData = try {
            CombinedData().apply {
                if (exploreSummed && exploreProperty.summable()) {
                    setData(BarData(statsProvider.getSumData(aggregationSpan, selectedTypes, exploreProperty)))
                } else {
                    val candleSet = statsProvider.getCandleData(aggregationSpan, selectedTypes, exploreProperty)
                    setData(CandleData(candleSet))
                    setData(LineData(DataSetStyles.applyBackgroundLineStyle(context, StatsProvider.convertCandleToMeanLineData(candleSet))))
                }
            }
        } catch (_: NoDataException) { null }
    }

    LaunchedEffect(selectedTypes, aggregationSpan, speedSwapped, distanceSwapped, durationSwapped, exploreProperty, exploreSummed) {
        updateCharts()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        AndroidView(
            factory = { ctx ->
                WorkoutTypeSelection(ctx, null).apply {
                    addOnWorkoutTypeSelectListener { _ ->
                        selectedTypes = getSelectedWorkoutTypes()
                        preferences.setStatisticsSelectedTypes(ArrayList(selectedTypes))
                    }
                    setSelectedWorkoutTypes(selectedTypes)
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        HistoryChartSection(
            title = if (speedSwapped) stringResource(WorkoutProperty.AVG_PACE.titleRes) else stringResource(WorkoutProperty.AVG_SPEED.titleRes),
            onToggle = { speedSwapped = !speedSwapped },
            data = speedData,
            aggregationSpan = aggregationSpan,
            onLongClick = { chart ->
                val prop = if (speedSwapped) WorkoutProperty.AVG_PACE else WorkoutProperty.AVG_SPEED
                navigateToDetail(activity, prop, false, selectedTypes, aggregationSpan, chart)
            }
        )

        HistoryChartSection(
            title = if (distanceSwapped) stringResource(R.string.workoutDistanceSum) else stringResource(WorkoutProperty.LENGTH.titleRes),
            onToggle = { distanceSwapped = !distanceSwapped },
            data = distanceData,
            aggregationSpan = aggregationSpan,
            onLongClick = { chart ->
                navigateToDetail(activity, WorkoutProperty.LENGTH, distanceSwapped, selectedTypes, aggregationSpan, chart)
            }
        )

        HistoryChartSection(
            title = if (durationSwapped) stringResource(R.string.workoutDurationSum) else stringResource(WorkoutProperty.DURATION.titleRes),
            onToggle = { durationSwapped = !durationSwapped },
            data = durationData,
            aggregationSpan = aggregationSpan,
            onLongClick = { chart ->
                navigateToDetail(activity, WorkoutProperty.DURATION, durationSwapped, selectedTypes, aggregationSpan, chart)
            }
        )

        // Explore Section
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var expanded by remember { mutableStateOf(false) }
            SectionHeader(
                title = stringResource(exploreProperty.titleRes),
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            IconButton(onClick = { expanded = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_swap_vertical),
                                    contentDescription = "Select property"
                                )
                            }
                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }) {
                                WorkoutProperty.values().forEach { property ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(property.titleRes)) },
                                        onClick = {
                                            exploreProperty = property
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }

                        if (exploreProperty.summable()) {
                            Text(
                                text = stringResource(R.string.sum),
                                style = MaterialTheme.typography.labelMedium
                            )
                            Switch(
                                checked = exploreSummed,
                                onCheckedChange = { exploreSummed = it },
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            )

            CombinedChartComponent(
                combinedData = exploreData,
                aggregationSpan = aggregationSpan,
                modifier = Modifier.fillMaxWidth().height(250.dp),
                onChartInit = { chart ->
                    setupChartLongClick(chart) {
                        navigateToDetail(activity, exploreProperty, exploreSummed, selectedTypes, aggregationSpan, chart)
                    }
                }
            )
        }
    }
}

private fun setupChartLongClick(chart: CombinedChart, onLongClick: () -> Unit) {
    chart.setOnChartGestureListener(object : OnChartGestureListener {
        override fun onChartGestureStart(me: MotionEvent?, lastPerformedGesture: ChartTouchListener.ChartGesture?) {}
        override fun onChartGestureEnd(me: MotionEvent?, lastPerformedGesture: ChartTouchListener.ChartGesture?) {
            if (lastPerformedGesture == ChartTouchListener.ChartGesture.LONG_PRESS) {
                onLongClick()
            }
        }
        override fun onChartLongPressed(me: MotionEvent?) {}
        override fun onChartDoubleTapped(me: MotionEvent?) {}
        override fun onChartSingleTapped(me: MotionEvent?) {}
        override fun onChartFling(me1: MotionEvent?, me2: MotionEvent?, vX: Float, vY: Float) {}
        override fun onChartScale(me: MotionEvent?, sX: Float, sY: Float) {
            // Update aggregation span based on zoom if needed
        }
        override fun onChartTranslate(me: MotionEvent?, dX: Float, dY: Float) {}
    })
}

private fun navigateToDetail(
    activity: FitoTrackActivity,
    property: WorkoutProperty,
    summed: Boolean,
    types: List<WorkoutType>,
    aggregationSpan: AggregationSpan,
    chart: CombinedChart
) {
    val intent = Intent(activity, DetailStatsActivity::class.java).apply {
        putExtra("property", property)
        putExtra("summed", summed)
        putExtra("types", ArrayList(types))
        putExtra("aggregationSpan", aggregationSpan)
        putExtra("xScale", chart.scaleX)
        putExtra("xTrans", chart.lowestVisibleX)
        val label = if (chart.data != null && chart.legend.entries.isNotEmpty()) chart.legend.entries[0].label else ""
        putExtra("ylabel", label)
    }
    activity.startActivity(intent)
}

@Composable
fun HistoryChartSection(
    title: String,
    onToggle: () -> Unit,
    data: CombinedData?,
    aggregationSpan: AggregationSpan,
    onLongClick: (CombinedChart) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(
            title = title,
            trailingContent = {
                IconButton(onClick = onToggle) {
                    Icon(
                        painter = painterResource(R.drawable.ic_swap_vertical),
                        contentDescription = "Toggle"
                    )
                }
            }
        )

        CombinedChartComponent(
            combinedData = data,
            aggregationSpan = aggregationSpan,
            modifier = Modifier.fillMaxWidth().height(250.dp),
            onChartInit = { chart ->
                setupChartLongClick(chart) { onLongClick(chart) }
            }
        )
    }
}
