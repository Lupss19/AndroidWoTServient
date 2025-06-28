package com.example.testserver

import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import org.eclipse.thingweb.Wot
import org.eclipse.thingweb.thing.schema.WoTConsumedThing
import org.eclipse.thingweb.thing.schema.genericReadProperty
import java.net.URI

class MultiValueSensorClient(
    private val wot: Wot,
    private val url: String
) {
    private lateinit var thing: WoTConsumedThing

    suspend fun connect() {
        val td = wot.requestThingDescription(URI(url))
        thing = wot.consume(td)
    }

    suspend fun getAllSensorValues(): Map<String, Any?> {
        val results = mutableMapOf<String, Any?>()
        val properties = thing.getThingDescription().properties.keys

        for (key in properties) {
            try {
                // Prova prima a leggere come JsonNode per gestire il JSON completo
                val rawValue = thing.genericReadProperty<JsonNode>(key)
                Log.d("SENSOR_CLIENT", "Raw value for $key: $rawValue")

                if (rawValue != null) {
                    // Se è un JSON object con struttura WoT, estrai il campo "data"
                    val actualValue = if (rawValue.isObject && rawValue.has("data")) {
                        val dataField = rawValue.get("data")
                        when {
                            dataField.isNumber -> dataField.asDouble().toFloat()
                            dataField.isTextual -> dataField.asText().toFloatOrNull() ?: -1f
                            else -> -1f
                        }
                    } else if (rawValue.isNumber) {
                        // Se è direttamente un numero
                        rawValue.asDouble().toFloat()
                    } else if (rawValue.isTextual) {
                        // Se è una stringa che rappresenta un numero
                        rawValue.asText().toFloatOrNull() ?: -1f
                    } else {
                        Log.w("SENSOR_CLIENT", "Formato non riconosciuto per $key: $rawValue")
                        -1f
                    }

                    results[key] = actualValue
                    Log.d("SENSOR_CLIENT", "Parsed value for $key: $actualValue")
                } else {
                    Log.w("SENSOR_CLIENT", "Valore null per $key")
                    results[key] = -1f
                }

            } catch (e: Exception) {
                Log.e("SENSOR_CLIENT", "Errore lettura $key: ${e.message}", e)

                // Fallback: prova a leggere come Float direttamente
                try {
                    val fallbackValue = thing.genericReadProperty<Float>(key)
                    results[key] = fallbackValue ?: -1f
                    Log.d("SENSOR_CLIENT", "Fallback value for $key: $fallbackValue")
                } catch (fallbackError: Exception) {
                    Log.e("SENSOR_CLIENT", "Fallback failed for $key: ${fallbackError.message}")
                    results[key] = -1f
                }
            }
        }
        return results
    }

    fun getThingTitle(): String = thing.getThingDescription().title ?: "null"
}