package com.example.testserver

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SensorDataFragment : Fragment() {
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var sensorClient: MultiValueSensorClient? = null
    private val propertyViews = mutableMapOf<String, TextView>()

    private lateinit var connectionStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var refreshButton: Button
    private lateinit var sensorDataContainer: LinearLayout

    // FIXED: Allinea le chiavi con quelle del Server
    private val mainSensorMap = mapOf(
        Sensor.TYPE_ACCELEROMETER to SensorInfo("accelerometro", "Accelerometro", "X, Y, Z (m/s²)"),
        Sensor.TYPE_GYROSCOPE to SensorInfo("giroscopio", "Giroscopio", "X, Y, Z (rad/s)"),
        Sensor.TYPE_MAGNETIC_FIELD to SensorInfo("campo_magnetico", "Campo Magnetico", "X, Y, Z (μT)"),
        Sensor.TYPE_LIGHT to SensorInfo("luminosità", "Luminosità", "Lux"), // FIXED: con accento
        Sensor.TYPE_PROXIMITY to SensorInfo("prossimità", "Prossimità", "cm"), // FIXED: con accento
        Sensor.TYPE_PRESSURE to SensorInfo("pressione", "Pressione", "hPa"),
        Sensor.TYPE_AMBIENT_TEMPERATURE to SensorInfo("temperatura", "Temperatura", "°C"),
        Sensor.TYPE_RELATIVE_HUMIDITY to SensorInfo("umidità", "Umidità", "%"),
        Sensor.TYPE_LINEAR_ACCELERATION to SensorInfo("accelerazione_lineare", "Accelerazione Lineare", "X, Y, Z (m/s²)"),
        Sensor.TYPE_ROTATION_VECTOR to SensorInfo("vettore_rotazione", "Vettore Rotazione", "X, Y, Z, W"),
        Sensor.TYPE_GRAVITY to SensorInfo("gravità", "Gravità", "X, Y, Z (m/s²)"),
        Sensor.TYPE_STEP_COUNTER to SensorInfo("contapassi", "Contapassi", "steps"),
        Sensor.TYPE_HEART_RATE to SensorInfo("frequenza_cardiaca", "Frequenza Cardiaca", "bpm")
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.activity_sensor_data, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        connectionStatus = view.findViewById(R.id.connectionStatus)
        progressBar = view.findViewById(R.id.progressBar)
        refreshButton = view.findViewById(R.id.refreshButton)
        sensorDataContainer = view.findViewById(R.id.sensorDataContainer)

        coroutineScope.launch {
            withContext(Dispatchers.Main) {
                connectionStatus.text = "In connessione.."
                progressBar.visibility = View.VISIBLE
            }

            val serverReady = waitForServerStart()
            if (!serverReady) {
                withContext(Dispatchers.Main) {
                    connectionStatus.text = "Server non disponibile dopo il timeout!"
                    progressBar.visibility = View.GONE
                }
                Log.e("DEBUG", "Server non disponibile dopo timeout")
                return@launch
            }

            try {
                val wot = WoTClientHolder.wot!!
                val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                val sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager

                val selectedProperties = mutableListOf<String>()

                // FIXED: Genera le proprietà usando la stessa logica del Server
                for ((sensorType, sensorInfo) in mainSensorMap) {
                    val sensor = sensorManager.getDefaultSensor(sensorType)
                    if (sensor == null) continue

                    // FIXED: Usa la stessa logica di generazione delle chiavi del Server
                    val prefKey = "share_sensor_${sensorInfo.displayName.replace(" ", "_").lowercase()}"
                    if (!sharedPrefs.getBoolean(prefKey, false)) {
                        Log.d("SENSOR_DATA", "Sensor ${sensorInfo.displayName} not enabled (key: $prefKey)")
                        continue
                    }

                    Log.d("SENSOR_DATA", "Sensor ${sensorInfo.displayName} is enabled (key: $prefKey)")

                    val sensorValuesCount = getSensorValuesCount(sensorType)
                    if (sensorValuesCount == 1) {
                        // Per sensori con un valore singolo, usa il key come nome proprietà
                        selectedProperties.add(sensorInfo.key)
                    } else {
                        // Per sensori multi-valore, aggiungi suffissi x, y, z, w
                        for (i in 0 until sensorValuesCount) {
                            val suffix = listOf("x", "y", "z", "w", "v").getOrNull(i) ?: "v$i"
                            selectedProperties.add("${sensorInfo.key}_$suffix")
                        }
                    }
                }

                Log.d("SENSOR_DATA", "Selected properties: $selectedProperties")

                val port = sharedPrefs.getString("server_port", "8080")?.toIntOrNull() ?: 8080
                val url = "http://localhost:$port/smartphone"
                val client = MultiValueSensorClient(wot, url)
                client.connect()
                sensorClient = client

                withContext(Dispatchers.Main) {
                    val titleView = TextView(requireContext()).apply {
                        textSize = 18f
                        text = "Smartphone: "
                        setPadding(8, 24, 8, 8)
                    }
                    sensorDataContainer.addView(titleView)

                    for (prop in selectedProperties) {
                        val valueView = TextView(requireContext()).apply {
                            textSize = 16f
                            text = "$prop: ..."
                            setPadding(16, 4, 8, 4)
                        }
                        sensorDataContainer.addView(valueView)
                        propertyViews[prop] = valueView
                    }

                    connectionStatus.text = "Connesso!"
                    progressBar.visibility = View.GONE
                }

                updateSensorValues()

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    connectionStatus.text = "Errore di connessione: ${e.message}"
                    progressBar.visibility = View.GONE
                }
                Log.e("DEBUG", "Errore durante la connessione: ", e)
            }
        }

        refreshButton.setOnClickListener {
            coroutineScope.launch {
                updateSensorValues()
            }
        }
    }

    private suspend fun updateSensorValues() {
        val client = sensorClient ?: return
        try {
            val values = client.getAllSensorValues()
            val timestamp = System.currentTimeMillis()
            withContext(Dispatchers.Main) {
                for ((prop, value) in values) {
                    val floatValue = when (value) {
                        is Float -> value
                        is Double -> value.toFloat()
                        is Number -> value.toFloat()
                        else -> -1f
                    }

                    propertyViews[prop]?.text = if (floatValue == -1f)
                        "$prop: Sensore non presente" else "$prop: $value"

                    if (floatValue != -1f) {
                        SensorDataHolder.addData(prop, timestamp, floatValue)
                    }
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                propertyViews.values.forEach { it.text = "errore" }
            }
        }
    }

    private fun getSensorValuesCount(sensorType: Int): Int = when (sensorType) {
        Sensor.TYPE_ACCELEROMETER,
        Sensor.TYPE_GRAVITY,
        Sensor.TYPE_GYROSCOPE,
        Sensor.TYPE_MAGNETIC_FIELD -> 3

        Sensor.TYPE_ROTATION_VECTOR -> 4
        Sensor.TYPE_GAME_ROTATION_VECTOR -> 4
        Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> 5

        else -> 1
    }

    private suspend fun waitForServerStart(maxRetries: Int = 10, delayMillis: Long = 500): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        repeat(maxRetries) {
            if (prefs.getBoolean("server_started", false)) return true
            delay(delayMillis)
        }
        return false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        coroutineScope.cancel()
    }

    // Data class per le informazioni dei sensori
    private data class SensorInfo(
        val key: String,
        val displayName: String,
        val values: String
    )
}