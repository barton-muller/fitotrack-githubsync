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

package de.tadris.fitness.health

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import de.tadris.fitness.R
import de.tadris.fitness.util.NotificationHelper

class StepNotificationBuilder(
    private val context: Context,
    private val notificationManager: NotificationManager,
) {

    fun buildInitial(): Notification {
        NotificationHelper.createChannels(context)
        return build(0)
    }

    fun update(steps: Int) {
        notificationManager.notify(StepTrackingService.NOTIFICATION_ID, build(steps))
    }

    private fun build(steps: Int): Notification {
        val text = if (steps > 0) {
            context.resources.getQuantityString(R.plurals.stepTrackingNotificationSteps, steps, steps)
        } else {
            context.getString(R.string.stepTrackingNotificationText)
        }
        return Notification.Builder(context)
            .setContentTitle(context.getString(R.string.stepTrackingNotificationTitle))
            .setContentText(text)
            .setSmallIcon(R.drawable.notification)
            .setChannelId(NotificationHelper.CHANNEL_STEP_TRACKING)
            .build()
    }
}
