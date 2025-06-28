package com.example.testserver

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
                val value = thing.genericReadProperty<Float>(key)
                results[key] = value
            } catch (e: Exception) {
                results[key] = "Errore: ${e.message}"
            }
        }
        return results
    }

    fun getThingTitle(): String = thing.getThingDescription().title ?: "null"
}