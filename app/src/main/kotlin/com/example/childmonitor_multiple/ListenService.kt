/*
 * This file is part of Child Monitor.
 *
 * Child Monitor is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Child Monitor is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Child Monitor. If not, see <http://www.gnu.org/licenses/>.
 */
package com.example.childmonitor_multiple

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.IOException
import java.net.Socket

class ListenService : Service() {
    private val frequency: Int = AudioCodecDefines.FREQUENCY
    private val channelConfiguration: Int = AudioCodecDefines.CHANNEL_CONFIGURATION_OUT
    private val audioEncoding: Int = AudioCodecDefines.ENCODING
    private val bufferSize = AudioTrack.getMinBufferSize(frequency, channelConfiguration, audioEncoding)
    private val byteBufferSize = bufferSize * 2
    private val binder: IBinder = ListenBinder()
    private lateinit var notificationManager: NotificationManager
    private var listenThread: Thread? = null
    val volumeHistory = VolumeHistory(16384)
    var childDeviceName: String? = null
        private set

    var onError: (() -> Unit)? = null
    var onUpdate: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        this.notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.i(TAG, "Received start id $startId: $intent")
        // Display a notification about us starting.  We put an icon in the status bar.
        createNotificationChannel()
        intent.extras?.let {
            val name = it.getString("name")
            childDeviceName = name
            val n = buildNotification(name)
            val foregroundServiceType =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
            ServiceCompat.startForeground(this, ID, n, foregroundServiceType)

            val address = it.getString("address")
            val port = it.getInt("port")
            doListenWithRetries(address, port)
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        this.listenThread?.interrupt()
        this.listenThread = null

        // Cancel the persistent notification.
        notificationManager.cancel(R.string.listening)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // Tell the user we stopped.
        Toast.makeText(this, R.string.stopped, Toast.LENGTH_SHORT).show()
    }

    override fun onBind(intent: Intent): IBinder = binder

    inner class ListenBinder : Binder() {
        val service: ListenService
            get() = this@ListenService
    }

    private fun buildNotification(name: String?): Notification {
        // In this sample, we'll use the same text for the ticker and the expanded notification
        val text = getText(R.string.listening)
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, ListenActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.listening_notification)
            .setOngoing(true)
            .setTicker(text)
            .setContentTitle(text)
            .setContentText(name)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Foreground Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(serviceChannel)
        }
    }

    /** New logic: automatically retry connection up to 3 times **/
    private fun doListenWithRetries(address: String?, port: Int) {
        val lt = Thread {
            var attempts = 0
            var connected = false

            while (attempts < 3 && !connected && !Thread.currentThread().isInterrupted) {
                try {
                    Log.i(TAG, "Connection attempt ${attempts + 1} to $address:$port ...")
                    val socket = Socket(address, port)
                    socket.soTimeout = 30_000
                    connected = streamAudio(socket)
                    if (!connected) {
                        Log.w(TAG, "Streaming failed, attempt ${attempts + 1}")
                        attempts++
                        Thread.sleep(2000)
                    }
                } catch (e: IOException) {
                    attempts++
                    Log.e(TAG, "Error while connecting (attempt $attempts of 3)", e)
                    if (attempts < 3) Thread.sleep(2000)
                }
            }

            if (!connected) {
                Log.e(TAG, "Failed to connect after 3 attempts.")
                playAlert()
                onError?.invoke()
            } else {
                Log.i(TAG, "Connection established successfully.")
            }
        }

        this.listenThread = lt
        lt.start()
    }

    private fun streamAudio(socket: Socket): Boolean {
        Log.i(TAG, "Starting audio stream")
        val audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            frequency,
            channelConfiguration,
            audioEncoding,
            bufferSize,
            AudioTrack.MODE_STREAM
        )

        try {
            audioTrack.play()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Failed to start AudioTrack", e)
            return false
        }

        val inputStream = try {
            socket.getInputStream()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to open input stream", e)
            return false
        }

        val readBuffer = ByteArray(byteBufferSize)
        val decodedBuffer = ShortArray(byteBufferSize * 2)

        return try {
            while (!Thread.currentThread().isInterrupted) {
                val len = inputStream.read(readBuffer)
                if (len < 0) return false
                val decoded = AudioCodecDefines.CODEC.decode(decodedBuffer, readBuffer, len, 0)
                if (decoded > 0) {
                    audioTrack.write(decodedBuffer, 0, decoded)
                    val decodedBytes = ShortArray(decoded)
                    System.arraycopy(decodedBuffer, 0, decodedBytes, 0, decoded)
                    volumeHistory.onAudioData(decodedBytes)
                    onUpdate?.invoke()
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Connection lost during streaming", e)
            false
        } finally {
            try {
                audioTrack.stop()
                audioTrack.release()
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun playAlert() {
        val mp = MediaPlayer.create(this, R.raw.upward_beep_chromatic_fifths)
        if (mp != null) {
            Log.i(TAG, "Playing alert sound")
            mp.setOnCompletionListener { it.release() }
            mp.start()
        } else {
            Log.e(TAG, "Failed to play alert sound")
        }
    }

    companion object {
        private const val TAG = "ListenService"
        const val CHANNEL_ID = TAG
        const val ID = 902938409
    }
}
