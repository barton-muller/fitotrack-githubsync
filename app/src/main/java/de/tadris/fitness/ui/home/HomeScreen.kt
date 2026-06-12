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

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import de.tadris.fitness.R
import de.tadris.fitness.data.Workout
import de.tadris.fitness.data.WorkoutDao
import de.tadris.fitness.data.WorkoutType
import de.tadris.fitness.ui.dashboard.DashboardFab
import de.tadris.fitness.ui.dashboard.DashboardScreen
import de.tadris.fitness.ui.health.StepsScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    workoutDao: WorkoutDao,
    onWorkoutClick: (Workout) -> Unit = {},
    onDeleteWorkout: (Workout) -> Unit = {},
    onRecord: () -> Unit = {},
    onRecordLast: (WorkoutType) -> Unit = {},
    onEnterWorkout: () -> Unit = {},
    onImport: () -> Unit = {},
    onSettings: () -> Unit = {},
    onStatistics: () -> Unit = {},
    onStepStats: () -> Unit = {},
) {
    val context = LocalContext.current
    val workouts by workoutDao.getWorkoutsFlow().collectAsState(initial = emptyList())
    val lastWorkoutType = remember(workouts) { workouts.firstOrNull()?.getWorkoutType(context) }

    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_HOME) }
    var fabExpanded by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = selectedTab != TAB_HOME) {
        selectedTab = TAB_HOME
        fabExpanded = false
    }

    Scaffold(
        topBar = {
            Crossfade(targetState = selectedTab, label = "topbar_crossfade") { tab ->
                when (tab) {
                    TAB_HOME -> TopAppBar(
                        title = { Text("FitoTrack") },
                    )

                    TAB_WORKOUTS -> TopAppBar(
                        title = { Text(stringResource(R.string.workouts)) },
                        actions = {
                            IconButton(onClick = onStatistics) {
                                Icon(
                                    painter = painterResource(R.drawable.outline_bar_chart_24),
                                    contentDescription = stringResource(R.string.workout_statistics),
                                )
                            }
                        },
                    )

                    else -> TopAppBar(
                        title = { Text(stringResource(R.string.tabSteps)) },
                        actions = {
                            IconButton(onClick = onStepStats) {
                                Icon(
                                    painter = painterResource(R.drawable.outline_bar_chart_24),
                                    contentDescription = stringResource(R.string.stepStatistics),
                                )
                            }
                        },
                    )
                }
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == TAB_HOME,
                    onClick = { selectedTab = TAB_HOME; fabExpanded = false },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.outline_home_24),
                            contentDescription = null,
                        )
                    },
                    label = { Text(stringResource(R.string.tabHome)) },
                )
                NavigationBarItem(
                    selected = selectedTab == TAB_WORKOUTS,
                    onClick = { selectedTab = TAB_WORKOUTS },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_run),
                            contentDescription = null,
                        )
                    },
                    label = { Text(stringResource(R.string.tabWorkouts)) },
                )
                NavigationBarItem(
                    selected = selectedTab == TAB_STEPS,
                    onClick = { selectedTab = TAB_STEPS; fabExpanded = false },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.outline_steps_24),
                            contentDescription = null,
                        )
                    },
                    label = { Text(stringResource(R.string.tabSteps)) },
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onSettings,
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                        )
                    },
                    label = { Text(stringResource(R.string.tabSettings)) },
                )
            }
        },
        floatingActionButton = {
            if (selectedTab == TAB_WORKOUTS) {
                DashboardFab(
                    expanded = fabExpanded,
                    lastWorkoutType = lastWorkoutType,
                    onToggle = { fabExpanded = !fabExpanded },
                    onRecord = { fabExpanded = false; onRecord() },
                    onRecordLast = { type -> fabExpanded = false; onRecordLast(type) },
                    onEnterWorkout = { fabExpanded = false; onEnterWorkout() },
                    onImport = { fabExpanded = false; onImport() },
                )
            }
        },
    ) { innerPadding ->
        Crossfade(targetState = selectedTab, label = "content_crossfade") { tab ->
            when (tab) {
                TAB_HOME -> DashboardTab(
                    contentPadding = innerPadding,
                    onStepDetails = onStepStats,
                    onSeeAllWorkouts = { selectedTab = TAB_WORKOUTS },
                    onWorkoutClick = onWorkoutClick,
                )

                TAB_WORKOUTS -> DashboardScreen(
                    workouts = workouts,
                    contentPadding = innerPadding,
                    onWorkoutClick = onWorkoutClick,
                    onDeleteWorkout = onDeleteWorkout,
                    onSettings = onSettings,
                )

                TAB_STEPS -> StepsScreen(contentPadding = innerPadding)
            }
        }
    }
}

private const val TAB_HOME = 0
private const val TAB_WORKOUTS = 1
private const val TAB_STEPS = 2
