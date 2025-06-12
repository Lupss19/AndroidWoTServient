package com.example.testserver

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat

class DynamicSensorSettingsFragment : PreferenceFragmentCompat() {

    // Lista dei sensori principali con valori numerici misurabili
    private val mainSensors = listOf(
        SensorInfo(Sensor.TYPE_ACCELEROMETER, "Accelerometro", "X, Y, Z (m/s²)", "Misura accelerazione sui 3 assi"),
        SensorInfo(Sensor.TYPE_GYROSCOPE, "Giroscopio", "X, Y, Z (rad/s)", "Misura velocità angolare sui 3 assi"),
        SensorInfo(Sensor.TYPE_MAGNETIC_FIELD, "Campo Magnetico", "X, Y, Z (μT)", "Misura campo magnetico sui 3 assi"),
        SensorInfo(Sensor.TYPE_LIGHT, "Luminosità", "Lux", "Misura intensità luminosa ambientale"),
        SensorInfo(Sensor.TYPE_PROXIMITY, "Prossimità", "cm", "Misura distanza di oggetti vicini"),
        SensorInfo(Sensor.TYPE_PRESSURE, "Pressione", "hPa", "Misura pressione atmosferica"),
        SensorInfo(Sensor.TYPE_AMBIENT_TEMPERATURE, "Temperatura", "°C", "Misura temperatura ambientale"),
        SensorInfo(Sensor.TYPE_RELATIVE_HUMIDITY, "Umidità", "%", "Misura umidità relativa"),
        SensorInfo(Sensor.TYPE_LINEAR_ACCELERATION, "Accelerazione Lineare", "X, Y, Z (m/s²)", "Accelerazione senza gravità"),
        SensorInfo(Sensor.TYPE_ROTATION_VECTOR, "Vettore Rotazione", "X, Y, Z, W", "Orientamento del dispositivo"),
        SensorInfo(Sensor.TYPE_GRAVITY, "Gravità", "X, Y, Z (m/s²)", "Forza di gravità sui 3 assi"),
        SensorInfo(Sensor.TYPE_STEP_COUNTER, "Contapassi", "steps", "Numero totale di passi"),
        SensorInfo(Sensor.TYPE_HEART_RATE, "Frequenza Cardiaca", "bpm", "Battiti cardiaci per minuto")
    )

    private val preferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null && key.startsWith("share_sensor_")) {
                requireContext().sendBroadcast(
                    Intent("PREFERENCES_UPDATED").putExtra("update_type", "sensors")
                )
                Log.d("DYNAMIC_PREF", "Broadcast inviato per chiave: $key")
            }
        }

    private var initialSensorPrefs: Map<String, Any?> = emptyMap()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        val screen: PreferenceScreen = preferenceManager.createPreferenceScreen(context)
        preferenceScreen = screen

        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context)

        // Crea preferenze solo per i sensori principali che sono disponibili
        for (sensorInfo in mainSensors) {
            val sensor = sensorManager.getDefaultSensor(sensorInfo.type)
            if (sensor != null) {
                val key = "share_sensor_${sensorInfo.name.replace(" ", "_").lowercase()}"
                val pref = SwitchPreferenceCompat(context).apply {
                    title = sensorInfo.name
                    summary = "${sensorInfo.description}\nValori: ${sensorInfo.values}"
                    this.key = key
                    setDefaultValue(false)
                }
                screen.addPreference(pref)
                Log.d("SENSOR_PREFS", "Added preference for: ${sensorInfo.name}")
            } else {
                Log.d("SENSOR_PREFS", "Sensor not available: ${sensorInfo.name}")
            }
        }

        // Se nessun sensore è disponibile, mostra un messaggio
        if (screen.preferenceCount == 0) {
            val pref = SwitchPreferenceCompat(context).apply {
                title = "Nessun sensore disponibile"
                summary = "Il dispositivo non ha sensori compatibili"
                isEnabled = false
            }
            screen.addPreference(pref)
        }

        initialSensorPrefs = sharedPrefs.all.filterKeys { it.startsWith("share_sensor_") }
    }

    override fun onResume() {
        super.onResume()
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .registerOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    override fun onPause() {
        super.onPause()
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    override fun onStop() {
        super.onStop()

        val context = context ?: return
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val currentSensorPrefs = sharedPrefs.all.filterKeys { it.startsWith("share_sensor_") }

        if (currentSensorPrefs != initialSensorPrefs) {
            Log.d("DYNAMIC_PREF", "Preferenze dei sensori cambiate, riavvio servizio..")
            context.sendBroadcast(
                Intent("PREFERENCES_UPDATED").putExtra("update_type", "sensors_restart")
            )
            Log.d("DYNAMIC_PREF", "Broadcast per restart Service inviato!")
        }
    }

    // Data class per rappresentare le informazioni di un sensore
    private data class SensorInfo(
        val type: Int,
        val name: String,
        val values: String,
        val description: String
    )
}