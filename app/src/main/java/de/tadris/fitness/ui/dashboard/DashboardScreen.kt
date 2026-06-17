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
package de.tadris.fitness.ui.dashboard

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.tadris.fitness.Instance
import de.tadris.fitness.R
import de.tadris.fitness.data.Workout
import de.tadris.fitness.data.WorkoutDao
import de.tadris.fitness.data.WorkoutType
import de.tadris.fitness.ui.components.BarChart
import de.tadris.fitness.ui.components.BarChartData
import de.tadris.fitness.ui.steps.StepGoalDialog
import de.tadris.fitness.util.Icon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    workoutDao: WorkoutDao,
    onWorkoutClick: (Workout) -> Unit = {},
    onDeleteWorkout: (Workout) -> Unit = {},
    onRecord: () -> Unit = {},
    onRecordLast: (WorkoutType) -> Unit = {},
    onEnterWorkout: () -> Unit = {},
    onImport: () -> Unit = {},
    onSettings: () -> Unit = {},
    onStatistics: () -> Unit = {},
    onStepDetails: () -> Unit = {},
    onActiveMinuteStats: () -> Unit = {},
) {
    val context = LocalContext.current
    val stepSensorSupported = remember(context) {
        (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager)
            .getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
    }
    val workouts by workoutDao.getWorkoutsFlow().collectAsState(initial = emptyList())
    val lastWorkoutType = remember(workouts) { workouts.firstOrNull()?.getWorkoutType(context) }

    val vm: DashboardViewModel = viewModel()
    val todaySteps by vm.todaySteps.collectAsStateWithLifecycle()
    val stepGoal by vm.stepGoal.collectAsStateWithLifecycle()
    val todayActiveMinutes by vm.todayActiveMinutes.collectAsStateWithLifecycle()
    val todayDistanceMeters by vm.todayDistanceMeters.collectAsStateWithLifecycle()
    val weeklyStepChart by vm.weeklyStepChart.collectAsStateWithLifecycle()
    val weeklyWorkouts by vm.weeklyWorkouts.collectAsStateWithLifecycle()
    val stepTrackingPromptDismissed by vm.stepTrackingPromptDismissed.collectAsStateWithLifecycle()

    var fabExpanded by rememberSaveable { mutableStateOf(false) }
    var workoutToDelete by remember { mutableStateOf<Workout?>(null) }
    var showGoalDialog by rememberSaveable { mutableStateOf(false) }
    val userPreferences = Instance.getInstance(context).userPreferences
    var showFirstStartDialog by rememberSaveable {
        val isFirstStart = userPreferences.firstStart
        if (isFirstStart) userPreferences.firstStart = false
        mutableStateOf(isFirstStart)
    }

    if (showGoalDialog) {
        StepGoalDialog(
            currentGoal = stepGoal,
            onConfirm = { goal ->
                vm.setDailyStepGoal(goal)
                showGoalDialog = false
            },
            onDismiss = { showGoalDialog = false },
        )
    }

    if (showFirstStartDialog) {
        FirstStartDialog(
            onSettings = { showFirstStartDialog = false; onSettings() },
            onDismiss = { showFirstStartDialog = false },
        )
    }

    if (workoutToDelete != null) {
        AlertDialog(
            onDismissRequest = { workoutToDelete = null },
            title = { Text(stringResource(R.string.deleteWorkout)) },
            text = { Text(stringResource(R.string.deleteWorkoutMessage)) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteWorkout(workoutToDelete!!)
                    workoutToDelete = null
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { workoutToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FitoTrack") },
                actions = {
                    IconButton(onClick = onStatistics) {
                        Icon(
                            painter = painterResource(R.drawable.ic_bar_chart),
                            contentDescription = stringResource(R.string.workout_statistics),
                        )
                    }
                    IconButton(onClick = onSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            DashboardFab(
                expanded = fabExpanded,
                lastWorkoutType = lastWorkoutType,
                onToggle = { fabExpanded = !fabExpanded },
                onRecord = { fabExpanded = false; onRecord() },
                onRecordLast = { type -> fabExpanded = false; onRecordLast(type) },
                onEnterWorkout = { fabExpanded = false; onEnterWorkout() },
                onImport = { fabExpanded = false; onImport() },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 88.dp,
                start = 16.dp,
                end = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (vm.stepTrackingEnabled || todaySteps > 0) {
                item {
                    StepsTodayCard(
                        todaySteps = todaySteps,
                        stepGoal = stepGoal,
                        todayActiveMinutes = todayActiveMinutes,
                        todayDistanceMeters = todayDistanceMeters,
                        weeklyStepChart = weeklyStepChart,
                        onDetails = onStepDetails,
                        onGoalClick = { showGoalDialog = true },
                        onActiveMinuteClick = onActiveMinuteStats,
                    )
                }
            } else if (stepSensorSupported && !stepTrackingPromptDismissed) {
                item {
                    StepTrackingPromptCard(
                        onMore = onStepDetails,
                        onDismiss = { vm.dismissStepTrackingPrompt() },
                    )
                }
            }
            if (weeklyWorkouts.isNotEmpty()) {
                item {
                    ThisWeekCard(
                        weeklyWorkouts = weeklyWorkouts,
                        rangeStartMillis = vm.last7DaysStartMillis,
                    )
                }
            }
            if (workouts.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.hintAddWorkout),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 32.dp, start = 16.dp, end = 16.dp),
                    )
                }
            } else {
                items(workouts, key = { it.id }) { workout ->
                    WorkoutListItem(
                        workout = workout,
                        onClick = { onWorkoutClick(workout) },
                        onLongClick = { workoutToDelete = workout },
                    )
                }
            }
        }
    }
}

@Composable
private fun StepsTodayCard(
    todaySteps: Int,
    stepGoal: Int,
    todayActiveMinutes: Int,
    todayDistanceMeters: Int,
    weeklyStepChart: BarChartData,
    onDetails: () -> Unit,
    onGoalClick: () -> Unit = {},
    onActiveMinuteClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val distanceText = remember(todayDistanceMeters) {
        Instance.getInstance(context).distanceUnitUtils.getDistance(todayDistanceMeters)
    }
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
                    modifier = Modifier
                        .size(130.dp)
                        .clickable(onClick = onGoalClick),
                )
                Spacer(Modifier.width(24.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LabeledValue(
                        label = stringResource(R.string.homeActiveLabel).uppercase(),
                        value = "$todayActiveMinutes min",
                        onClick = onActiveMinuteClick,
                    )
                    LabeledValue(
                        label = stringResource(R.string.homeDistanceLabel).uppercase(),
                        value = distanceText,
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text(
                text = stringResource(R.string.homeLast7DaysSteps).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )

            if (weeklyStepChart.entries.isNotEmpty()) {
                BarChart(
                    data = weeklyStepChart.copy(horizontal = stepGoal),
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
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(steps, goal) {
        progress = if (goal > 0) (steps.toFloat() / goal).coerceIn(0f, 1f) else 0f
    }
    val displayedProgress by animateFloatAsState(
        progress,
        ProgressIndicatorDefaults.ProgressAnimationSpec
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { displayedProgress },
            modifier = Modifier.size(130.dp),
            strokeWidth = 8.dp,
            trackColor = MaterialTheme.colorScheme.outlineVariant,
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
private fun LabeledValue(label: String, value: String, onClick: (() -> Unit)? = null) {
    Column(modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier) {
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
    rangeStartMillis: Long,
) {
    val context = LocalContext.current
    val instance = Instance.getInstance(context)

    val totalDistance = weeklyWorkouts.sumOf { it.distance }.toInt()
    val totalDuration = weeklyWorkouts.sumOf { it.activeDuration }
    val workoutCount = weeklyWorkouts.size

    val distanceText = instance.distanceUnitUtils.getDistance(totalDistance)
    val timeText = instance.distanceUnitUtils.getHourMinuteTime(totalDuration)

    val todayLabel = stringResource(R.string.today)
    val rangeStartLabel = remember(rangeStartMillis) {
        SimpleDateFormat("EEE d", Locale.getDefault()).format(Date(rangeStartMillis))
    }
    val weekRangeText = "$rangeStartLabel — ${todayLabel.lowercase()}"

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
                    text = stringResource(R.string.homeLast7DaysWorkouts).uppercase(),
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
                    val resources = LocalResources.current
                    byType.forEach { (_, workouts) ->
                        val workoutType = workouts.first().getWorkoutType(context)
                        val totalDistance = workouts.sumOf { it.distance }.toInt()
                        val totalReps = workouts.sumOf { it.repetitions }
                        val metric = if (totalDistance == 0 && totalReps > 0) {
                            "$totalReps ${
                                resources.getQuantityString(
                                    workoutType.repeatingExerciseName,
                                    totalReps
                                )
                            }"
                        } else {
                            instance.distanceUnitUtils.getDistance(totalDistance)
                        }
                        SportTypeCard(
                            workoutType = workoutType,
                            metric = metric,
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
    metric: String,
    count: Int,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
                text = metric,
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
private fun StepTrackingPromptCard(
    onMore: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.stepTrackingPromptTitle).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.stepTrackingPromptMessage),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.dismiss))
                }
                TextButton(onClick = onMore) {
                    Text(stringResource(R.string.more))
                }
            }
        }
    }
}

@Composable
private fun FirstStartDialog(
    onSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setPreferencesTitle)) },
        text = { Text(stringResource(R.string.setPreferencesMessage)) },
        confirmButton = {
            TextButton(onClick = onSettings) {
                Text(stringResource(R.string.settings))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
