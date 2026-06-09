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
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import de.tadris.fitness.Instance
import de.tadris.fitness.R
import de.tadris.fitness.data.StepSample
import de.tadris.fitness.util.NotificationHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class StepTrackingService : Service(), SensorEventListener {

    companion object {
        const val NOTIFICATION_ID = 20
        private const val WINDOW_SIZE_MS = 15 * 60 * 1000L
        private const val PERSIST_INTERVAL_MS = 3 * 60 * 1000L
        private const val PREF_NAME = "step_tracking"
        private const val PREF_LAST_COUNTER = "last_counter"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        private val _pendingSteps = MutableStateFlow(0)
        val pendingSteps: StateFlow<Int> = _pendingSteps
    }

    private lateinit var sensorManager: SensorManager
    private var stepSensor: Sensor? = null

    private var lastCounterValue = -1f
    private var pendingStepsCount = 0
    private val lock = Any()

    private var persisterThread: Thread? = null
    @Volatile
    private var persisterRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        val prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        lastCounterValue = prefs.getFloat(PREF_LAST_COUNTER, -1f)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        stepSensor?.let {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }

        Instance.getInstance(this).userPreferences.stepTrackingEnabled = true
        _isRunning.value = true
        startPersister()
        return START_STICKY
    }

    override fun onDestroy() {
        _isRunning.value = false
        _pendingSteps.value = 0
        sensorManager.unregisterListener(this)
        persisterRunning = false
        persisterThread?.interrupt()
        persistPending()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
        val counter = event.values[0]
        synchronized(lock) {
            if (lastCounterValue >= 0f) {
                val delta = if (counter >= lastCounterValue) {
                    (counter - lastCounterValue).toInt()
                } else {
                    // counter reset (reboot) — treat new value as steps since reboot
                    counter.toInt()
                }
                if (delta > 0) {
                    pendingStepsCount += delta
                    _pendingSteps.value = pendingStepsCount
                }
            }
            lastCounterValue = counter
        }
        getSharedPreferences(PREF_NAME, MODE_PRIVATE)
            .edit()
            .putFloat(PREF_LAST_COUNTER, counter)
            .apply()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun startPersister() {
        persisterRunning = true
        persisterThread = Thread({
            while (persisterRunning) {
                try {
                    Thread.sleep(PERSIST_INTERVAL_MS)
                    persistPending()
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "StepPersister")
        persisterThread!!.start()
    }

    private fun persistPending() {
        val steps: Int
        synchronized(lock) {
            steps = pendingStepsCount
            if (steps == 0) return
            pendingStepsCount = 0
            _pendingSteps.value = 0
        }
        val windowStart = currentWindowStart()
        val dao = Instance.getInstance(this).db.stepDao()
        val existing = dao.getStepSample(windowStart)
        dao.insertStepSample(StepSample(windowStart, (existing?.steps ?: 0) + steps))
    }

    private fun currentWindowStart(): Long {
        val now = System.currentTimeMillis()
        return now - (now % WINDOW_SIZE_MS)
    }

    private fun buildNotification(): Notification {
        NotificationHelper.createChannels(this)
        val builder = Notification.Builder(this)
            .setContentTitle(getString(R.string.stepTrackingNotificationTitle))
            .setContentText(getString(R.string.stepTrackingNotificationText))
            .setSmallIcon(R.drawable.notification)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(NotificationHelper.CHANNEL_STEP_TRACKING)
        }
        return builder.build()
    }
}
