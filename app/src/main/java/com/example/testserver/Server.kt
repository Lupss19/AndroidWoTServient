package com.example.testserver

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.hardware.SensorEventListener
import android.preference.PreferenceManager
import android.util.Log
import android.util.Base64
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import org.eclipse.thingweb.Servient
import org.eclipse.thingweb.Wot
import org.eclipse.thingweb.reflection.ExposedThingBuilder
import org.eclipse.thingweb.thing.schema.InteractionInput
import org.eclipse.thingweb.thing.schema.InteractionOptions
import org.eclipse.thingweb.thing.schema.WoTExposedThing
import org.eclipse.thingweb.thing.schema.WoTInteractionOutput
import org.eclipse.thingweb.thing.schema.stringSchema
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException

class Server(
    private val wot: Wot,
    private val servient: Servient,
    private val context: Context
) {
    var photoThing: PhotoThing? = null
    var audioThing: AudioThing? = null
    private val jsonNodeFactory = JsonNodeFactory.instance
    private val activeThings = mutableMapOf<String, WoTExposedThing>()

    private var photoThingId: String? = null
    private var audioThingId: String? = null

    private var currentPhotoBase64: String = ""
    private var currentAudioBase64: String = ""

    // FIXED: Allinea le chiavi con quelle generate in DynamicSensorSettingsFragment
    private val mainSensorMap = mapOf(
        Sensor.TYPE_ACCELEROMETER to SensorInfo("accelerometro", "Accelerometro", "X, Y, Z (m/s²)"),
        Sensor.TYPE_GYROSCOPE to SensorInfo("giroscopio", "Giroscopio", "X, Y, Z (rad/s)"),
        Sensor.TYPE_MAGNETIC_FIELD to SensorInfo("campo_magnetico", "Campo Magnetico", "X, Y, Z (μT)"),
        Sensor.TYPE_LIGHT to SensorInfo("luminosità", "Luminosità", "Lux"),
        Sensor.TYPE_PROXIMITY to SensorInfo("prossimità", "Prossimità", "cm"),
        Sensor.TYPE_PRESSURE to SensorInfo("pressione", "Pressione", "hPa"),
        Sensor.TYPE_AMBIENT_TEMPERATURE to SensorInfo("temperatura", "Temperatura", "°C"),
        Sensor.TYPE_RELATIVE_HUMIDITY to SensorInfo("umidità", "Umidità", "%"),
        Sensor.TYPE_LINEAR_ACCELERATION to SensorInfo("accelerazione_lineare", "Accelerazione Lineare", "X, Y, Z (m/s²)"),
        Sensor.TYPE_ROTATION_VECTOR to SensorInfo("vettore_rotazione", "Vettore Rotazione", "X, Y, Z, W"),
        Sensor.TYPE_GRAVITY to SensorInfo("gravità", "Gravità", "X, Y, Z (m/s²)"),
        Sensor.TYPE_STEP_COUNTER to SensorInfo("contapassi", "Contapassi", "steps"),
        Sensor.TYPE_HEART_RATE to SensorInfo("frequenza_cardiaca", "Frequenza Cardiaca", "bpm")
    )

    suspend fun start(): List<WoTExposedThing> {
        // Stop eventuali Thing attivi
        stop()

        val exposedThings = mutableListOf<WoTExposedThing>()

        // Creo Thing smartphone con tutte le affordances dei sensori abilitati
        val smartphoneThing = createSmartphoneThing()
        if(smartphoneThing != null) {
            servient.addThing(smartphoneThing)
            servient.expose(smartphoneThing.getThingDescription().id)
            exposedThings.add(smartphoneThing)
            Log.d("SERVER", "SmartphoneThing esposto con ID: ${smartphoneThing.getThingDescription().id}")
        }

        return exposedThings
    }

    private fun createSmartphoneThing() : WoTExposedThing? {
        val sharedPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val availableSensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
        val jsonNodeFactory = JsonNodeFactory.instance
        val thingId = "smartphone"
        val thingTitle = "Smartphone sensors"
        val thingDescription = "Thing representing selected sensors of the smartphone"

        // FIXED: Controlla se ci sono sensori abilitati usando le chiavi corrette
        val enabledSensors = mainSensorMap.filter { (sensorType, sensorInfo) ->
            val sensor = sensorManager.getDefaultSensor(sensorType)
            // FIXED: Genera la chiave nello stesso modo del DynamicSensorSettingsFragment
            val prefKey = "share_sensor_${sensorInfo.displayName.replace(" ", "_").lowercase()}"
            val isEnabled = sensor != null && sharedPrefs.getBoolean(prefKey, false)
            Log.d("SERVER", "Checking sensor ${sensorInfo.displayName} with key $prefKey: $isEnabled")
            isEnabled
        }

        if (enabledSensors.isEmpty()) {
            Log.d("SERVER", "Nessun sensore abilitato!")
        } else {
            Log.d("SERVER", "Sensori abilitati: ${enabledSensors.values.map { it.displayName }}")
        }

        val thing = wot.produce {
            id = thingId
            title = thingTitle
            description = thingDescription

            // Aggiungi proprietà per ogni sensore abilitato
            for ((sensorType, sensorInfo) in enabledSensors) {
                val sensorValuesCount = getSensorValuesCount(sensorType)
                val units = getSensorUnits(sensorType)
                if (sensorValuesCount == 1) {
                    val propName = sensorInfo.key
                    numberProperty(propName) {
                        title = sensorInfo.displayName
                        readOnly = true
                        observable = true
                        unit = units.getOrNull(0)
                    }
                } else {
                    for (i in 0 until sensorValuesCount) {
                        val suffix = listOf("x", "y", "z", "w", "v").getOrNull(i) ?: "v$i"
                        val propName = "${sensorInfo.key}_$suffix"
                        numberProperty(propName) {
                            title = "${sensorInfo.displayName} $suffix"
                            readOnly = true
                            observable = true
                            unit = units.getOrNull(i)
                        }
                    }
                }
            }
            stringProperty("photo") {
                title = "Last captured photo"
                description = "Base64 encoded image"
                readOnly = true
                observable = false
            }
            action<Unit, Unit>("takePhoto") {
                title = "Capture a new photo"
                description = "Takes a new photo and updates photo property"
            }
            action<String, Unit>("updatePhoto") {
                title = "Update photo property"
                description = "Updates the 'photo' property with a new Base64 encoded image"
                input = stringSchema{}
            }
            stringProperty("audio") {
                title = "Last recorded audio"
                description = "Base64 encoded audio"
                readOnly = true
                observable = false
            }
            action<Unit, Unit>("startRecording") {
                title = "Start recording audio"
                description = "Start recording and updates audio property"
            }
            action<String, Unit>("stopRecording") {
                title = "Stop recording"
                description = "Stop recording and updates 'audio' property"
            }
        }.apply {
            // Aggiungi i property read handlers
            for ((sensorType, sensorInfo) in enabledSensors) {
                val sensorValuesCount = getSensorValuesCount(sensorType)
                if (sensorValuesCount == 1) {
                    val propName = sensorInfo.key
                    setPropertyReadHandler(propName) {
                        ServientStats.logRequest(thingId, "readProperty", propName)
                        val v = readSensorValues(context, sensorType)
                        InteractionInput.Value(jsonNodeFactory.numberNode(v.getOrNull(0) ?: -1f))
                    }
                } else {
                    for (i in 0 until sensorValuesCount) {
                        val suffix = listOf("x", "y", "z", "w", "v").getOrNull(i) ?: "v$i"
                        val propName = "${sensorInfo.key}_$suffix"
                        setPropertyReadHandler(propName) {
                            ServientStats.logRequest(thingId, "readProperty", propName)
                            val v = readSensorValues(context, sensorType)
                            InteractionInput.Value(jsonNodeFactory.numberNode(v.getOrNull(i) ?: -1f))
                        }
                    }
                }
            }
            setPropertyReadHandler("photo") {
                ServientStats.logRequest(thingId, "readProperty", "photo")
                InteractionInput.Value(jsonNodeFactory.textNode(currentPhotoBase64))
            }
            setActionHandler("takePhoto"){ _: WoTInteractionOutput, _: InteractionOptions? ->
                ServientStats.logRequest(thingId, "invokeAction", "takePhoto")
                MediaUtils.takePhoto(context)
                InteractionInput.Value(jsonNodeFactory.nullNode())
            }
            setActionHandler("updatePhoto") { input: WoTInteractionOutput, _: InteractionOptions? ->
                ServientStats.logRequest(thingId, "invokeAction", "updatePhoto")
                val newPhotoBase64 = input.value()?.asText()
                if (newPhotoBase64 != null) {
                    currentPhotoBase64 = newPhotoBase64
                    Log.d("SERVER", "'photo' aggiornata!")
                    val photoFile = File(context.externalCacheDir, "photo.jpg")
                    try {
                        val bytes = Base64.decode(newPhotoBase64, Base64.DEFAULT)
                        photoFile.writeBytes(bytes)
                        Log.d("SERVER", "Foto salvata su disco")
                    } catch (e: Exception) {
                        Log.e("SERVER", "Errore salvando foto su disco: ", e)
                    }
                    InteractionInput.Value(jsonNodeFactory.nullNode())
                } else {
                    Log.e("SERVER", "Errore input per updatePhoto è nullo")
                    InteractionInput.Value(jsonNodeFactory.nullNode())
                }
            }
            setPropertyReadHandler("audio") {
                ServientStats.logRequest(thingId, "readProperty", "audio")
                InteractionInput.Value(jsonNodeFactory.textNode(currentAudioBase64))
            }
            setActionHandler("startRecording"){ _: WoTInteractionOutput, _: InteractionOptions? ->
                ServientStats.logRequest(thingId, "invokeAction", "startRecording")
                MediaUtils.startAudioRecording(context)
                InteractionInput.Value(jsonNodeFactory.nullNode())
            }
            setActionHandler("stopRecording") { _: WoTInteractionOutput, _: InteractionOptions? ->
                ServientStats.logRequest(thingId, "invokeAction", "stopRecording")
                val recordedAudioBase64 = MediaUtils.stopAudioRecording()
                if (recordedAudioBase64 != null) {
                    currentAudioBase64 = recordedAudioBase64
                    Log.d("SERVER", "Proprietà 'audio' aggiornata con nuovo audio Base64. Lunghezza: ${recordedAudioBase64.length}")
                    val audioFile = File(context.externalCacheDir, "recorded_audio.3gp")
                    try {
                        val bytes = Base64.decode(recordedAudioBase64, Base64.DEFAULT)
                        audioFile.writeBytes(bytes)
                        Log.d("SERVER", "Audio Base64 salvato su disco.")
                    } catch (e: Exception) {
                        Log.e("SERVER", "Errore salvando l'audio Base64 su disco", e)
                    }
                } else {
                    Log.e("SERVER", "Errore: Input Base64 per updateAudio è nullo.")
                }
                InteractionInput.Value(jsonNodeFactory.nullNode())
            }
        }

        try {
            return thing
        } catch (e: Exception) {
            Log.e("SERVER", "Errore creazione SmartphoneThing", e)
            return null
        }
    }

    suspend fun updateExposedThings(): List<WoTExposedThing> {
        val sharedPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val wantedThingIds = mutableSetOf<String>()
        val newlyAdded = mutableListOf<WoTExposedThing>()

        for ((sensorType, sensorInfo) in mainSensorMap) {
            val sensor = sensorManager.getDefaultSensor(sensorType)
            if (sensor == null) continue

            // FIXED: Usa la stessa logica di generazione delle chiavi
            val prefKey = "share_sensor_${sensorInfo.displayName.replace(" ", "_").lowercase()}"
            val shouldShare = sharedPrefs.getBoolean(prefKey, false)
            if (!shouldShare) continue

            Log.d("SERVER_PREF", "Checking $prefKey = $shouldShare")
            val thingId = sensorInfo.key
            wantedThingIds.add(thingId)

            if (thingId in activeThings) continue

            val sensorValuesCount = getSensorValuesCount(sensorType)
            val units = getSensorUnits(sensorType)
            val thing = wot.produce {
                id = thingId
                title = sensorInfo.displayName
                description = "Thing for sensor ${sensorInfo.displayName}, type: $sensorType"

                if (sensorValuesCount == 1) {
                    numberProperty("value") {
                        title = "Sensor value"
                        readOnly = true
                        observable = true
                        unit = units.getOrNull(0)
                    }
                } else {
                    for (i in 0 until sensorValuesCount) {
                        val propName = listOf("x", "y", "z").getOrNull(i) ?: "v$i"
                        numberProperty(propName) {
                            title = "Component $propName"
                            readOnly = true
                            observable = true
                            unit = units.getOrNull(i)
                        }
                    }
                }
            }.apply {
                val currentSensorType = sensorType
                if (sensorValuesCount == 1) {
                    setPropertyReadHandler("value") {
                        ServientStats.logRequest(thingId, "readProperty", "value")
                        val v = readSensorValues(context, currentSensorType)
                        InteractionInput.Value(jsonNodeFactory.numberNode(v.getOrNull(0) ?: -1f))
                    }
                } else {
                    for (i in 0 until sensorValuesCount) {
                        val propName = listOf("x", "y", "z").getOrNull(i) ?: "v$i"
                        setPropertyReadHandler(propName) {
                            ServientStats.logRequest(thingId, "readProperty", propName)
                            val v = readSensorValues(context, currentSensorType)
                            InteractionInput.Value(jsonNodeFactory.numberNode(v.getOrNull(i) ?: -1f))
                        }
                    }
                }
            }

            try {
                servient.addThing(thing)
                servient.expose(thingId)
                activeThings[thingId] = thing
                newlyAdded.add(thing)
                Log.d("SERVER_UPDATE", "Added Thing: $thingId")
            } catch (e: Exception) {
                Log.e("SERVER_UPDATE", "Errore aggiunta Thing $thingId :", e)
            }
        }

        val toRemove = activeThings.keys - wantedThingIds
        for (thingId in toRemove) {
            try {
                servient.destroy(thingId)
                activeThings.remove(thingId)
                Log.d("SERVER_UPDATE", "Removed Thing: $thingId")
            } catch (e: Exception) {
                Log.e("SERVER_UPDATE", "Errore rimozione $thingId", e)
            }
        }

        return newlyAdded
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

    private fun sanitizeSensorName(name: String, type: Int): String {
        val sanitizedName = name.lowercase()
            .replace("\\s+".toRegex(), "-")
            .replace("[^a-z0-9\\-]".toRegex(), "")
        return "$sanitizedName-$type"
    }

    fun readSensorValues(context: Context, sensorType: Int): FloatArray {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensorManager.getDefaultSensor(sensorType) ?: return floatArrayOf()

        val latch = CountDownLatch(1)
        var values: FloatArray = floatArrayOf()

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event != null) {
                    values = event.values.copyOf()
                    latch.countDown()
                    sensorManager.unregisterListener(this)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)

        try {
            latch.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

        sensorManager.unregisterListener(listener)
        return values
    }

    fun getSensorUnits(type: Int): List<String?> = when (type) {
        Sensor.TYPE_LIGHT -> listOf("lux")
        Sensor.TYPE_PRESSURE -> listOf("hPa")
        Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_GRAVITY -> listOf("m/s²", "m/s²", "m/s²")
        Sensor.TYPE_GYROSCOPE -> listOf("rad/s", "rad/s", "rad/s")
        Sensor.TYPE_MAGNETIC_FIELD -> listOf("μT", "μT", "μT")
        Sensor.TYPE_PROXIMITY -> listOf("cm")
        Sensor.TYPE_AMBIENT_TEMPERATURE -> listOf("°C")
        Sensor.TYPE_RELATIVE_HUMIDITY -> listOf("%")
        Sensor.TYPE_STEP_COUNTER -> listOf("steps")
        Sensor.TYPE_HEART_RATE -> listOf("bpm")
        Sensor.TYPE_ROTATION_VECTOR -> listOf(null, null, null, null)
        else -> listOf(null)
    }

    suspend fun stop() {
        Log.d("SERVER_STOP", "Inizio stop server..")
        try {
            val thingsToRemove = activeThings.keys.toList()
            // Cicla su tutti i Thing esposti e li distrugge nel Servient
            for (thingId in thingsToRemove) {
                try {
                    servient.destroy(thingId)
                    Log.d("SERVER_STOP", "Destroyed Thing: $thingId")
                } catch (e: Exception) {
                    Log.e("SERVER_STOP", "Errore distruggendo $thingId", e)
                }
            }
            activeThings.clear()

            // Se hai Thing media esposti, rimuovili
            photoThingId?.let { id ->
                try {
                    servient.destroy(id)
                    Log.d("SERVER_STOP", "Destroyed PhotoThing")
                } catch (e: Exception) {
                    Log.e("SERVER_STOP", "Errore distruggendo PhotoThing", e)
                }
            }

            audioThingId?.let { id ->
                try {
                    servient.destroy(id)
                    Log.d("SERVER_STOP", "Destroyed AudioThing")
                } catch (e: Exception) {
                    Log.e("SERVER_STOP", "Errore distruggendo AudioThing", e)
                }
            }

            photoThing = null
            audioThing = null
            photoThingId = null
            audioThingId = null
            MediaThings.photoThing = null
            MediaThings.audioThing = null

            Log.d("SERVER_STOP", "Stop server completo!")

        } catch (e: Exception) {
            Log.e("SERVER_STOP", "Errore durante stop Server", e)
        }
    }

    // Data class per le informazioni dei sensori
    private data class SensorInfo(
        val key: String,
        val displayName: String,
        val values: String
    )
}