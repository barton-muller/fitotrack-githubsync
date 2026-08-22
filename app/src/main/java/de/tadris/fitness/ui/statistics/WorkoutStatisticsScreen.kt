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

import android.view.View
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.tadris.fitness.Instance
import de.tadris.fitness.R
import de.tadris.fitness.ui.statistics.fragments.StatsHistoryFragment
import de.tadris.fitness.ui.statistics.fragments.StatsOverviewFragment
import de.tadris.fitness.ui.statistics2.StatisticsScreen
import de.tadris.fitness.ui.steps.StepStatisticsAdapter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutStatisticsScreen(onBack: () -> Unit) {
    val activity = LocalActivity.current ?: return

    val overviewFragment = remember { StatsOverviewFragment() }
    val historyFragment = remember { StatsHistoryFragment() }
    val stepAdapter = remember { StepStatisticsAdapter(activity) }

    val stepDao = remember { Instance.getInstance(activity).db.stepDao() }
    val hasSteps by stepDao.hasAnyStepsFlow().collectAsStateWithLifecycle(initialValue = true)

    val stepsLabel = stringResource(R.string.tabSteps)
    val workoutsLabel = stringResource(R.string.tabWorkouts)
    val historyLabel = stringResource(R.string.stats_history_title)

    val tabs = remember(hasSteps) {
        buildList {
            if (hasSteps) add(stepsLabel)
            add(workoutsLabel)
            add(historyLabel)
        }
    }

    var selectedTabIndex by remember { mutableIntStateOf(0) }

    val stepsTabIndex = if (hasSteps) 0 else -1
    val workoutsTabIndex = if (hasSteps) 1 else 0
    val historyTabIndex = if (hasSteps) 2 else 1

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.statistics)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            PrimaryTabRow(selectedTabIndex = selectedTabIndex) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(title) },
                    )
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                if (hasSteps && selectedTabIndex == stepsTabIndex) {
                    StatisticsScreen(
                        adapter = stepAdapter,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                LegacyFragmentTab(
                    fragment = overviewFragment,
                    tag = "stat_overview",
                    selected = selectedTabIndex == workoutsTabIndex,
                    modifier = Modifier.fillMaxSize(),
                )
                LegacyFragmentTab(
                    fragment = historyFragment,
                    tag = "stat_history",
                    selected = selectedTabIndex == historyTabIndex,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun LegacyFragmentTab(
    fragment: Fragment,
    tag: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val activity = LocalActivity.current as FragmentActivity
    val fm = activity.supportFragmentManager
    val containerId = remember(tag) { View.generateViewId() }
    val committed = remember(tag) { BooleanArray(1) }

    LaunchedEffect(selected) {
        fm.findFragmentByTag(tag)?.let { f ->
            fm.beginTransaction()
                .apply { if (selected) show(f) else hide(f) }
                .commit()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (!activity.isFinishing && !activity.isDestroyed) {
                fm.findFragmentByTag(tag)?.let { f ->
                    fm.beginTransaction().remove(f).commitAllowingStateLoss()
                }
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            FragmentContainerView(ctx).apply { id = containerId }
        },
        update = {
            if (!committed[0] && fm.findFragmentByTag(tag) == null) {
                fm.beginTransaction()
                    .add(containerId, fragment, tag)
                    .apply { if (!selected) hide(fragment) }
                    .commitNow()
                committed[0] = true
            }
        },
        modifier = modifier,
    )
}
