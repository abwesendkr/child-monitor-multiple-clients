package com.example.childmonitor_multiple

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.childmonitor_multiple.ListenService.ListenBinder

class ListenActivity : Activity() {
    // Don't attempt to unbind from the service unless the client has received some
    // information about the service's state.
    private var shouldUnbind = false
    private lateinit var statusText: TextView

    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            // This is called when the connection with the service has been
            // established, giving us the service object we can use to
            // interact with the service.  Because we have bound to a explicit
            // service that we know is running in our own process, we can
            // cast its IBinder to a concrete class and directly access it.
            val bs = (service as ListenBinder).service
            Toast.makeText(this@ListenActivity, R.string.connect, Toast.LENGTH_SHORT).show()

            val connectedText = findViewById<TextView>(R.id.connectedTo)
            connectedText.text = bs.childDeviceName

            val volumeView = findViewById<VolumeView>(R.id.volume)
            volumeView.volumeHistory = bs.volumeHistory
            bs.onUpdate = { volumeView.postInvalidate() }
            bs.onError = { postErrorMessage() }

            statusText.text = "Connected and listening..."
        }

        override fun onServiceDisconnected(className: ComponentName) {
            // This is called when the connection with the service has been
            // unexpectedly disconnected -- that is, its process crashed.
            // Because it is running in our same process, we should never
            // see this happen.
            Toast.makeText(this@ListenActivity, R.string.disconnected, Toast.LENGTH_SHORT).show()
            statusText.text = "Disconnected"
        }
    }

    private fun ensureServiceRunningAndBind(bundle: Bundle?) {
        val context: Context = this
        val intent = Intent(context, ListenService::class.java)
        bundle?.let {
            intent.putExtra("name", it.getString("name"))
            intent.putExtra("address", it.getString("address"))
            intent.putExtra("port", it.getInt("port"))
            ContextCompat.startForegroundService(context, intent)
        }
        // Attempts to establish a connection with the service.  We use an
        // explicit class name because we want a specific service
        // implementation that we know will be running in our own process
        // (and thus won't be supporting component replacement by other
        // applications).
        if (bindService(intent, connection, BIND_AUTO_CREATE)) {
            shouldUnbind = true
            Log.i(TAG, "Bound to ListenService")
        } else {
            Log.e(TAG, "Error: Could not bind to ListenService.")
            statusText.text = "Failed to bind service."
        }
    }

    private fun doUnbindAndStopService() {
        if (shouldUnbind) {
            unbindService(connection)
            shouldUnbind = false
        }
        stopService(Intent(this, ListenService::class.java))
    }

    fun postErrorMessage() {
        statusText.post {
            statusText.text = "Connection failed after 3 attempts."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_listen)
        statusText = findViewById(R.id.textStatus)
        statusText.text = "Attempting to connect..."
        volumeControlStream = AudioManager.STREAM_MUSIC
        ensureServiceRunningAndBind(intent.extras)
    }

    override fun onDestroy() {
        doUnbindAndStopService()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ListenActivity"
    }
}
