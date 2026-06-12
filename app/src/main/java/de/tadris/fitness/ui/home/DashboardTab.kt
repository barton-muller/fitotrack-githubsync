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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.tadris.fitness.Instance
import de.tadris.fitness.R
import de.tadris.fitness.data.Workout
import de.tadris.fitness.data.WorkoutType
import de.tadris.fitness.ui.dashboard.WorkoutListItem
import de.tadris.fitness.ui.health.BarChart
import de.tadris.fitness.ui.health.BarChartData
import de.tadris.fitness.util.Icon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardTab(
    contentPadding: PaddingValues,
    onStepDetails: () -> Unit,
    onSeeAllWorkouts: () -> Unit,
    onWorkoutClick: (Workout) -> Unit,
) {
    val vm: DashboardViewModel = viewModel()
    val todaySteps by vm.todaySteps.collectAsStateWithLifecycle()
    val weeklyStepChart by vm.weeklyStepChart.collectAsStateWithLifecycle()
    val weeklyWorkouts by vm.weeklyWorkouts.collectAsStateWithLifecycle()
    val latestWorkout by vm.latestWorkout.collectAsStateWithLifecycle()

    LazyColumn(
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
            start = 16.dp,
            end = 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            StepsTodayCard(
                todaySteps = todaySteps,
                stepGoal = vm.stepGoal,
                weeklyStepChart = weeklyStepChart,
                onDetails = onStepDetails,
            )
        }
        item {
            ThisWeekCard(
                weeklyWorkouts = weeklyWorkouts,
                weekStartMillis = vm.weekStartMillis,
            )
        }
        if (latestWorkout != null) {
            item {
                LatestWorkoutSection(
                    workout = latestWorkout!!,
                    onSeeAll = onSeeAllWorkouts,
                    onWorkoutClick = onWorkoutClick,
                )
            }
        }
    }
}

@Composable
private fun StepsTodayCard(
    todaySteps: Int,
    stepGoal: Int,
    weeklyStepChart: BarChartData,
    onDetails: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = stringResource(R.string.homeStepsToday).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onDetails) {
                    Text(stringResource(R.string.details) + " >")
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompactStepRing(
                    steps = todaySteps,
                    goal = stepGoal,
                    modifier = Modifier.size(100.dp),
                )
                Spacer(Modifier.width(24.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LabeledValue(
                        label = stringResource(R.string.homeActiveLabel).uppercase(),
                        value = "-- min",
                    )
                    LabeledValue(
                        label = stringResource(R.string.homeDistanceLabel).uppercase(),
                        value = "-- km",
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text(
                text = stringResource(R.string.homeThisWeekSteps).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )

            if (weeklyStepChart.entries.isNotEmpty()) {
                BarChart(
                    data = weeklyStepChart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    animate = true,
                )
            }
        }
    }
}

@Composable
private fun CompactStepRing(
    steps: Int,
    goal: Int,
    modifier: Modifier = Modifier,
) {
    val progress = if (goal > 0) (steps.toFloat() / goal).coerceIn(0f, 1f) else 0f
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.size(100.dp),
            strokeWidth = 8.dp,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = steps.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "of $goal",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ThisWeekCard(
    weeklyWorkouts: List<Workout>,
    weekStartMillis: Long,
) {
    val context = LocalContext.current
    val instance = Instance.getInstance(context)

    val totalDistance = weeklyWorkouts.sumOf { it.distance }.toInt()
    val totalDuration = weeklyWorkouts.sumOf { it.activeDuration }
    val workoutCount = weeklyWorkouts.size

    val distanceText = instance.distanceUnitUtils.getDistance(totalDistance)
    val timeText = instance.distanceUnitUtils.getHourMinuteTime(totalDuration)

    val todayLabel = stringResource(R.string.today)
    val weekStartLabel = remember(weekStartMillis) {
        SimpleDateFormat("EEE d", Locale.getDefault()).format(Date(weekStartMillis))
    }
    val weekRangeText = "$weekStartLabel — ${todayLabel.lowercase()}"

    val byType = remember(weeklyWorkouts) {
        weeklyWorkouts.groupBy { it.workoutTypeId }
            .entries.sortedByDescending { it.value.size }
            .take(3)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.homeThisWeek).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = weekRangeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                SummaryStatColumn(
                    label = "Distance",
                    value = distanceText,
                    modifier = Modifier.weight(1f),
                )
                SummaryStatColumn(
                    label = "Time",
                    value = timeText,
                    modifier = Modifier.weight(1f),
                )
                SummaryStatColumn(
                    label = "Workouts",
                    value = workoutCount.toString(),
                    modifier = Modifier.weight(1f),
                )
            }

            if (byType.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    byType.forEach { (_, workouts) ->
                        val workoutType = workouts.first().getWorkoutType(context)
                        val typeDistance = workouts.sumOf { it.distance }.toInt()
                        SportTypeCard(
                            workoutType = workoutType,
                            distance = instance.distanceUnitUtils.getDistance(typeDistance),
                            count = workouts.size,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(3 - byType.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryStatColumn(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SportTypeCard(
    workoutType: WorkoutType,
    distance: String,
    count: Int,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Surface(
                modifier = Modifier.size(32.dp),
                shape = CircleShape,
                color = Color(workoutType.color),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(Icon.getIcon(workoutType.icon)),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = distance,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "${workoutType.title} · ${count}×",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LatestWorkoutSection(
    workout: Workout,
    onSeeAll: () -> Unit,
    onWorkoutClick: (Workout) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.homeLatestWorkout).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onSeeAll) {
                Text(stringResource(R.string.homeSeeAll))
            }
        }
        WorkoutListItem(
            workout = workout,
            onClick = { onWorkoutClick(workout) },
            onLongClick = {},
        )
    }
}
