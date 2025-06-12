package com.example.testserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.Handler
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager

class DataFragment : Fragment() {

    private var mediaButton: Button? = null
    private var sensorButton: Button? = null
    private val updateHandler = Handler(Looper.getMainLooper())

    private val serviceStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "SERVICE_STATUS_CHANGED") {
                // Add a small delay to ensure preferences are written
                updateHandler.postDelayed({
                    if (isAdded) {
                        updateButtonStates()
                    }
                }, 100)
            }
        }
    }

    // Also listen for preference changes directly
    private val preferenceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "PREFERENCES_UPDATED") {
                // When preferences are updated, check server status after a delay
                updateHandler.postDelayed({
                    if (isAdded) {
                        updateButtonStates()
                    }
                }, 200)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_data, container, false)
        mediaButton = view.findViewById<Button>(R.id.mediaButton)
        sensorButton = view.findViewById<Button>(R.id.sensorButton)

        mediaButton?.setOnClickListener {
            handleMediaButtonClick()
        }
        sensorButton?.setOnClickListener {
            handleSensorButtonClick()
        }

        updateButtonStates()
        return view
    }

    override fun onResume() {
        super.onResume()
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Context.RECEIVER_NOT_EXPORTED else 0

        // Register for both service status and preference updates
        requireContext().registerReceiver(
            serviceStatusReceiver,
            IntentFilter("SERVICE_STATUS_CHANGED"),
            flags
        )

        requireContext().registerReceiver(
            preferenceReceiver,
            IntentFilter("PREFERENCES_UPDATED"),
            flags
        )

        // Update immediately and after a delay to catch any missed updates
        updateHandler.post {
            if (isAdded) {
                updateButtonStates()
            }
        }
        updateHandler.postDelayed({
            if (isAdded) {
                updateButtonStates()
            }
        }, 300)
    }

    override fun onPause() {
        super.onPause()
        try {
            requireContext().unregisterReceiver(serviceStatusReceiver)
        } catch (e: Exception) {
            // Ignore
        }
        try {
            requireContext().unregisterReceiver(preferenceReceiver)
        } catch (e: Exception) {
            // Ignore
        }
        updateHandler.removeCallbacksAndMessages(null)
    }

    private fun handleMediaButtonClick() {
        val serverStatus = getServerStatus()
        when (serverStatus) {
            ServerStatus.RUNNING -> {
                val intent = Intent(requireContext(), PicAudioActivity::class.java)
                startActivity(intent)
            }
            ServerStatus.STARTING -> {
                Toast.makeText(requireContext(), "Server in avvio, attendere..", Toast.LENGTH_SHORT).show()
            }
            ServerStatus.STOPPED -> {
                Toast.makeText(requireContext(), "Avvia il Server!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleSensorButtonClick() {
        val serverStatus = getServerStatus()
        when (serverStatus) {
            ServerStatus.RUNNING -> {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, SensorDataFragment())
                    .addToBackStack(null)
                    .commit()
            }
            ServerStatus.STARTING -> {
                Toast.makeText(requireContext(), "Server in avvio, attendere..", Toast.LENGTH_SHORT).show()
            }
            ServerStatus.STOPPED -> {
                Toast.makeText(requireContext(), "Avvia il Server!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateButtonStates() {
        val serverStatus = getServerStatus()
        val isEnabled = serverStatus == ServerStatus.RUNNING

        mediaButton?.isEnabled = isEnabled
        sensorButton?.isEnabled = isEnabled

        when (serverStatus) {
            ServerStatus.STARTING -> {
                mediaButton?.text = "Media (Avvio...)"
                sensorButton?.text = "Sensori (Avvio...)"
            }
            else -> {
                mediaButton?.text = "Media"
                sensorButton?.text = "Sensori"
            }
        }

        // Debug logging
        android.util.Log.d("DATAFRAGMENT", "Updated button states - Status: $serverStatus, Enabled: $isEnabled")
    }

    private fun getServerStatus(): ServerStatus {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val isStarted = prefs.getBoolean("server_started", false)
        val isStarting = prefs.getBoolean("server_starting", false)

        android.util.Log.d("DATAFRAGMENT", "Server status check - started: $isStarted, starting: $isStarting")

        return when {
            isStarted -> ServerStatus.RUNNING
            isStarting -> ServerStatus.STARTING
            else -> ServerStatus.STOPPED
        }
    }

    private enum class ServerStatus {
        RUNNING, STARTING, STOPPED
    }
}