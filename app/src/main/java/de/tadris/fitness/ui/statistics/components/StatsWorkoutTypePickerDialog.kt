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

package de.tadris.fitness.ui.statistics.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.tadris.fitness.R
import de.tadris.fitness.Instance
import de.tadris.fitness.data.WorkoutType
import de.tadris.fitness.data.WorkoutTypeManager
import de.tadris.fitness.stats.aggregation.WorkoutTypeFilter
import de.tadris.fitness.util.Icon as WorkoutIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun StatsWorkoutTypePickerDialog(
    onDismiss: () -> Unit,
    onSelect: (WorkoutType) -> Unit
) {
    val context = LocalContext.current
    val allLabel = stringResource(R.string.workoutTypeAll)
    var types by remember { mutableStateOf<List<WorkoutType>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(allLabel) {
        withContext(Dispatchers.IO) {
            val db = Instance.getInstance(context).db
            val allTypes = WorkoutTypeManager.getInstance().getAllTypesSorted(context)
            
            // Filter only types that have at least one workout
            val filteredTypes = allTypes.filter { type ->
                db.workoutDao().getLastWorkoutByType(type.id) != null
            }

            withContext(Dispatchers.Main) {
                val allOption = WorkoutType(
                    WorkoutTypeFilter.ID_ALL,
                    allLabel,
                    0,
                    Color.White.hashCode(), // Default color for "All"
                    "list",
                    0,
                    ""
                )
                types = listOf(allOption) + filteredTypes
                isLoading = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.type)) },
        text = {
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn {
                    items(types) { type ->
                        ListItem(
                            leadingContent = {
                                Icon(
                                    painter = painterResource(WorkoutIcon.getIcon(type.icon)),
                                    contentDescription = null,
                                    tint = if (type.id == WorkoutTypeFilter.ID_ALL) 
                                        MaterialTheme.colorScheme.primary 
                                    else Color(type.color),
                                    modifier = Modifier.size(24.dp),
                                )
                            },
                            headlineContent = { Text(type.title) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable {
                                onSelect(type)
                                onDismiss()
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
