package com.example.testserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment

class DataFragment : Fragment() {

    private var mediaButton: Button? = null
    private var sensorButton: Button? = null

    private val serviceStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "SERVICE_STATUS_CHANGED") {
                updateButtonStates()
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
        requireContext().registerReceiver(
            serviceStatusReceiver,
            IntentFilter("SERVICE_STATUS_CHANGED"),
            flags
        )
        updateButtonStates()
    }

    override fun onPause() {
        super.onPause()
        try {
            requireContext().unregisterReceiver(serviceStatusReceiver)
        } catch (e: Exception) {
            // Non fare nulla
        }
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
    }

    private fun getServerStatus(): ServerStatus {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val isStarted = prefs.getBoolean("server_started", false)
        val isStarting = prefs.getBoolean("server_starting", false)

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