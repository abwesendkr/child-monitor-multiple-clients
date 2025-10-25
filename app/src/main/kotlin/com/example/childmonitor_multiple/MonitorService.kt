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

    override fun onCreate() {
        super.onCreate()
        this.notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        this.nsdManager = this.getSystemService(NSD_SERVICE) as NsdManager
        this.currentPort = 10000
        this.currentSocket = null
        Log.i(TAG, "ChildMonitor start")
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val n = buildNotification()
        val foregroundServiceType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else 0
        ServiceCompat.startForeground(this, ID, n, foregroundServiceType)
        ensureMonitorThread()
        return START_REDELIVER_INTENT
    }

    private fun ensureMonitorThread() {
        if (monitorThread?.isAlive == true) return

        val currentToken = Any()
        this.connectionToken = currentToken

        monitorThread = Thread {
            while (connectionToken == currentToken) {
                try {
                    ServerSocket(currentPort).use { serverSocket ->
                        currentSocket = serverSocket
                        registerService(serverSocket.localPort)

                        val clients = mutableListOf<Socket>()

                        // Thread: neue Clients akzeptieren
                        Thread {
                            try {
                                while (!Thread.currentThread().isInterrupted) {
                                    val client = serverSocket.accept()
                                    client.tcpNoDelay = true
                                    synchronized(clients) { clients.add(client) }
                                    Log.i(TAG, "Neuer Client: ${client.inetAddress}")

                                    // Statusanzeige auf Streaming
                                    monitorActivity?.runOnUiThread {
                                        monitorActivity?.findViewById<TextView>(R.id.textStatus)
                                            ?.setText(R.string.streaming)
                                    }
                                }
                            } catch (e: IOException) {
                                Log.e(TAG, "Client-Akzeptierungsfehler: ${e.message}")
                            }
                        }.start()

                        // Aufnahme + Multi-Client-Streaming starten
                        handleMultiClientStreaming(serverSocket, clients)
                    }
                } catch (e: Exception) {
                    if (connectionToken == currentToken) {
                        currentPort++
                        Log.e(TAG, "Failed to open server socket. Port increased to $currentPort", e)
                    }
                }
            }
        }.also { it.start() }
    }

    private fun handleMultiClientStreaming(serverSocket: ServerSocket, clients: MutableList<Socket>) {
        val frequency = AudioCodecDefines.FREQUENCY
        val channelConfiguration = AudioCodecDefines.CHANNEL_CONFIGURATION_IN
        val audioEncoding = AudioCodecDefines.ENCODING
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
                    val encoded = AudioCodecDefines.CODEC.encode(pcmBuffer, read, ulawBuffer, 0)
                    synchronized(clients) {
                        val iterator = clients.iterator()
                        while (iterator.hasNext()) {
                            val client = iterator.next()
                            try {
                                client.getOutputStream().write(ulawBuffer, 0, encoded)
                            } catch (e: IOException) {
                                try { client.close() } catch (_: IOException) {}
                                iterator.remove()
                                Log.w(TAG, "Client getrennt: ${client.inetAddress}")
                            }
                        }

                        // Wenn keine Clients mehr da, Status auf "Warte auf Eltern"
                        if (clients.isEmpty()) {
                            monitorActivity?.runOnUiThread {
                                monitorActivity?.findViewById<TextView>(R.id.textStatus)
                                    ?.setText(R.string.waitingForParent)
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
            monitorActivity?.runOnUiThread {
                monitorActivity?.findViewById<TextView>(R.id.textStatus)
                    ?.setText(R.string.waitingForParent)
            }
        }
    }

    private fun registerService(port: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "ChildMonitor on ${Build.MODEL}"
            serviceType = "_childmonitor._tcp."
            this.port = port
        }
        registrationListener = object : RegistrationListener {
            override fun onServiceRegistered(nsdServiceInfo: NsdServiceInfo) {
                monitorActivity?.runOnUiThread {
                    monitorActivity?.findViewById<TextView>(R.id.textStatus)
                        ?.setText(R.string.waitingForParent)
                    monitorActivity?.findViewById<TextView>(R.id.textService)
                        ?.text = nsdServiceInfo.serviceName
                    monitorActivity?.findViewById<TextView>(R.id.port)
                        ?.text = port.toString()
                }
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Registration failed: $errorCode")
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    private fun unregisterService() {
        registrationListener?.let {
            registrationListener = null
            nsdManager.unregisterService(it)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Foreground Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.listening_notification)
            .setOngoing(true)
            .setTicker("Child Device")
            .setContentTitle("Child Device")
            .build()
    }

    override fun onDestroy() {
        monitorThread?.interrupt()
        monitorThread = null
        unregisterService()
        connectionToken = null
        currentSocket?.close()
        currentSocket = null
        notificationManager.cancel(R.string.listening)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        Toast.makeText(this, R.string.stopped, Toast.LENGTH_SHORT).show()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder = binder

    inner class MonitorBinder : Binder() {
        val service: MonitorService get() = this@MonitorService
    }

    companion object {
        const val TAG = "MonitorService"
        const val CHANNEL_ID = TAG
        const val ID = 1338
    }
}
