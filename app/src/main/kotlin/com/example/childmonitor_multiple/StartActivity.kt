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

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class StartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "ChildMonitor launched")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_start)
        val monitorButton = findViewById<Button>(R.id.useChildDevice)
        monitorButton.setOnClickListener { _: View? ->
            Log.i(TAG, "Starting up monitor")
            if (isAudioRecordingPermissionGranted) {
                startActivity(Intent(applicationContext, MonitorActivity::class.java))
            } else {
                requestAudioPermission()
            }
        }
        val connectButton = findViewById<Button>(R.id.useParentDevice)
        connectButton.setOnClickListener { _: View? ->
            Log.i(TAG, "Starting connection activity")
            if (isMulticastPermissionGranted) {
                val i = Intent(applicationContext, DiscoverActivity::class.java)
                startActivity(i)
            } else {
                requestMulticastPermission()
            }
        }
    }

    private val isMulticastPermissionGranted: Boolean
        get() = (ContextCompat.checkSelfPermission(this@StartActivity, Manifest.permission.CHANGE_WIFI_MULTICAST_STATE)
                == PackageManager.PERMISSION_GRANTED)
    private val isAudioRecordingPermissionGranted: Boolean
        get() = (ContextCompat.checkSelfPermission(this@StartActivity, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED)

    private fun requestAudioPermission() {
        ActivityCompat.requestPermissions(this@StartActivity, arrayOf(Manifest.permission.RECORD_AUDIO),
                PERMISSIONS_REQUEST_RECORD_AUDIO)
    }

    private fun requestMulticastPermission() {
        ActivityCompat.requestPermissions(this@StartActivity, arrayOf(Manifest.permission.CHANGE_WIFI_MULTICAST_STATE),
                PERMISSIONS_REQUEST_MULTICAST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == PERMISSIONS_REQUEST_RECORD_AUDIO && grantResults.size > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startActivity(Intent(applicationContext, MonitorActivity::class.java))
        } else if (requestCode == PERMISSIONS_REQUEST_MULTICAST) {
            // its okay if the permission was denied... the user will have to type the address manually
            startActivity(Intent(applicationContext, DiscoverActivity::class.java))
        }
    }

    companion object {
        const val TAG = "ChildMonitor"
        private const val PERMISSIONS_REQUEST_RECORD_AUDIO = 298349824
        private const val PERMISSIONS_REQUEST_MULTICAST = 298349825
    }
}
