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
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.nsd.NsdManager
import android.net.nsd.NsdManager.RegistrationListener
import android.net.nsd.NsdServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

class MonitorService : Service() {
    private val binder: IBinder = MonitorBinder()
    private lateinit var nsdManager: NsdManager
    private var registrationListener: RegistrationListener? = null
    private var currentSocket: ServerSocket? = null
    private var connectionToken: Any? = null
    private var currentPort = 0
    private lateinit var notificationManager: NotificationManager
    private var monitorThread: Thread? = null
    var monitorActivity: MonitorActivity? = null

    private fun serviceConnection(socket: Socket) {
        val ma = this.monitorActivity
        ma?.runOnUiThread {
            val statusText = ma.findViewById<TextView>(R.id.textStatus)
            statusText.setText(R.string.streaming)
        }
        val frequency: Int = AudioCodecDefines.FREQUENCY
        val channelConfiguration: Int = AudioCodecDefines.CHANNEL_CONFIGURATION_IN
        val audioEncoding: Int = AudioCodecDefines.ENCODING
        val bufferSize = AudioRecord.getMinBufferSize(frequency, channelConfiguration, audioEncoding)
        val audioRecord: AudioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                frequency,
                channelConfiguration,
                audioEncoding,
                bufferSize
            )
        } catch (e: SecurityException) {
            // This should never happen, we asked for permission before
            throw RuntimeException(e)
        }
        val pcmBufferSize = bufferSize * 2
        val pcmBuffer = ShortArray(pcmBufferSize)
        val ulawBuffer = ByteArray(pcmBufferSize)
        try {
            audioRecord.startRecording()
            val out = socket.getOutputStream()
            socket.sendBufferSize = pcmBufferSize
            Log.d(TAG, "Socket send buffer size: " + socket.sendBufferSize)
            while (socket.isConnected && (this.currentSocket != null) && !Thread.currentThread().isInterrupted) {
                val read = audioRecord.read(pcmBuffer, 0, bufferSize)
                val encoded: Int = AudioCodecDefines.CODEC.encode(pcmBuffer, read, ulawBuffer, 0)
                out.write(ulawBuffer, 0, encoded)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
        } finally {
            audioRecord.stop()
        }
    }
    private fun handleMultiClientStreaming(serverSocket: ServerSocket, clients: MutableList<Socket>) {
        val frequency: Int = AudioCodecDefines.FREQUENCY
        val channelConfiguration: Int = AudioCodecDefines.CHANNEL_CONFIGURATION_IN
        val audioEncoding: Int = AudioCodecDefines.ENCODING
        val bufferSize = AudioRecord.getMinBufferSize(frequency, channelConfiguration, audioEncoding)

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            frequency,
            channelConfiguration,
            audioEncoding,
            bufferSize
        )

        val pcmBufferSize = bufferSize * 2
        val pcmBuffer = ShortArray(pcmBufferSize)
        val ulawBuffer = ByteArray(pcmBufferSize)

        try {
            audioRecord.startRecording()
            Log.i(TAG, "Mehrere Clients aktiv – Aufnahme gestartet.")

            while (!Thread.currentThread().isInterrupted && !serverSocket.isClosed) {
                val read = audioRecord.read(pcmBuffer, 0, bufferSize)
                if (read > 0) {
                    val encoded: Int = AudioCodecDefines.CODEC.encode(pcmBuffer, read, ulawBuffer, 0)

                    synchronized(clients) {
                        val iterator = clients.iterator()
                        while (iterator.hasNext()) {
                            val client = iterator.next()
                            try {
                                val out = client.getOutputStream()
                                out.write(ulawBuffer, 0, encoded)
                            } catch (e: IOException) {
                                try { client.close() } catch (ignored: IOException) {}
                                iterator.remove()
                                Log.w(TAG, "Client getrennt: ${client.inetAddress}")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Streaming-Fehler", e)
        } finally {
            audioRecord.stop()
            audioRecord.release()
            synchronized(clients) {
                clients.forEach {
                    try { it.close() } catch (_: IOException) {}
                }
                clients.clear()
            }
            Log.i(TAG, "Audioaufnahme beendet, alle Clients getrennt.")
        }
    }


    override fun onCreate() {
        Log.i(TAG, "ChildMonitor start")
        super.onCreate()
        this.notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        this.nsdManager = this.getSystemService(NSD_SERVICE) as NsdManager
        this.currentPort = 10000
        this.currentSocket = null
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.i(TAG, "Received start id $startId: $intent")
        // Display a notification about us starting.  We put an icon in the status bar.
        createNotificationChannel()
        val n = buildNotification()
        val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0 // Keep the linter happy
        ServiceCompat.startForeground(this, ID, n, foregroundServiceType)
        ensureMonitorThread()
        return START_REDELIVER_INTENT
    }

    private fun ensureMonitorThread() {
        var mt = this.monitorThread
        if (mt != null && mt.isAlive) {
            return
        }
        val currentToken = Any()
        this.connectionToken = currentToken
        mt = Thread {
            while (this.connectionToken == currentToken) {
                try {
                    ServerSocket(this.currentPort).use { serverSocket ->
                        this.currentSocket = serverSocket
                        // Store the chosen port.
                        val localPort = serverSocket.localPort

                        // Register the service so that parent devices can
                        // locate the child device
                        registerService(localPort)
                        val clients = mutableListOf<Socket>()

// Thread zum Akzeptieren neuer Clients
                        Thread {
                            try {
                                while (!Thread.currentThread().isInterrupted) {
                                    val client = serverSocket.accept()
                                    client.tcpNoDelay = true
                                    synchronized(clients) { clients.add(client) }
                                    Log.i(TAG, "Neuer Client verbunden: ${client.inetAddress}")
                                }
                            } catch (e: IOException) {
                                Log.e(TAG, "Client-Akzeptierungsfehler: ${e.message}")
                            }
                        }.start()

// Aufnahme und Verteilung starten
                        handleMultiClientStreaming(serverSocket, clients)

                    }
                } catch (e: Exception) {
                    if (this.connectionToken == currentToken) {
                        // Just in case
                        this.currentPort++
                        Log.e(TAG, "Failed to open server socket. Port increased to $currentPort", e)
                    }
                }
            }
        }
        this.monitorThread = mt
        mt.start()
    }

    private fun registerService(port: Int) {
        val serviceInfo = NsdServiceInfo()
        serviceInfo.serviceName = "ChildMonitor on " + Build.MODEL
        serviceInfo.serviceType = "_childmonitor._tcp."
        serviceInfo.port = port
        this.registrationListener = object : RegistrationListener {
            override fun onServiceRegistered(nsdServiceInfo: NsdServiceInfo) {
                // Save the service name.  Android may have changed it in order to
                // resolve a conflict, so update the name you initially requested
                // with the name Android actually used.
                nsdServiceInfo.serviceName.let { serviceName ->
                    Log.i(TAG, "Service name: $serviceName")
                    monitorActivity?.let { ma ->
                        ma.runOnUiThread {
                            val statusText = ma.findViewById<TextView>(R.id.textStatus)
                            statusText.setText(R.string.waitingForParent)
                            val serviceText = ma.findViewById<TextView>(R.id.textService)
                            serviceText.text = serviceName
                            val portText = ma.findViewById<TextView>(R.id.port)
                            portText.text = port.toString()
                        }
                    }
                }
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // Registration failed!  Put debugging code here to determine why.
                Log.e(TAG, "Registration failed: $errorCode")
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                // Service has been unregistered.  This only happens when you call
                // NsdManager.unregisterService() and pass in this listener.
                Log.i(TAG, "Unregistering service")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // Unregistration failed.  Put debugging code here to determine why.
                Log.e(TAG, "Unregistration failed: $errorCode")
            }
        }
        nsdManager.registerService(
            serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    private fun unregisterService() {
        this.registrationListener?.let {
            this.registrationListener = null
            Log.i(TAG, "Unregistering monitoring service")
            this.nsdManager.unregisterService(it)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Foreground Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            this.notificationManager.createNotificationChannel(serviceChannel)
        }
    }

    private fun buildNotification(): Notification {
        val text: CharSequence = "Child Device"
        // Set the info for the views that show in the notification panel.
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
        b.setSmallIcon(R.drawable.listening_notification) // the status icon
            .setOngoing(true)
            .setTicker(text) // the status text
            .setContentTitle(text) // the label of the entry
        return b.build()
    }

    override fun onDestroy() {
        this.monitorThread?.let {
            this.monitorThread = null
            it.interrupt()
        }
        unregisterService()
        this.connectionToken = null
        this.currentSocket?.let {
            try {
                it.close()
            } catch (e: IOException) {
                Log.e(TAG, "Failed to close active socket on port $currentPort")
            }
        }
        this.currentSocket = null

        // Cancel the persistent notification.
        this.notificationManager.cancel(R.string.listening)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // Tell the user we stopped.
        Toast.makeText(this, R.string.stopped, Toast.LENGTH_SHORT).show()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    inner class MonitorBinder : Binder() {
        val service: MonitorService
            get() = this@MonitorService
    }

    companion object {
        const val TAG = "MonitorService"
        const val CHANNEL_ID = TAG
        const val ID = 1338
    }
}
