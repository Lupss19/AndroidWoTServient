package com.example.testserver

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

class SensorListActivity : Activity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var textView: TextView
    private val sensorValues = mutableMapOf<Int, FloatArray>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        textView = TextView(this)
        val scrollView = ScrollView(this).apply {
            addView(textView)
        }
        setContentView(scrollView)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensorList = sensorManager.getSensorList(Sensor.TYPE_ALL)

        // Registrazione listener per ciascun sensore
        for (sensor in sensorList) {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }

        updateSensorDisplay(sensorList)
    }

    override fun onSensorChanged(event: SensorEvent) {
        sensorValues[event.sensor.type] = event.values.clone()
        updateSensorDisplay(sensorManager.getSensorList(Sensor.TYPE_ALL))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Non necessario per questa visualizzazione
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
    }

    private fun updateSensorDisplay(sensorList: List<Sensor>) {
        val info = StringBuilder()
        info.append("Sensori disponibili (${sensorList.size}):\n\n")
        for (sensor in sensorList) {
            val values = sensorValues[sensor.type]
            val valuesString = values?.joinToString(prefix = "[", postfix = "]") { "%.2f".format(it) } ?: "[N/A]"
            info.append("- ${sensor.name} (${sensor.type}) → $valuesString\n")
        }
        runOnUiThread {
            textView.text = info.toString()
        }
    }
}
