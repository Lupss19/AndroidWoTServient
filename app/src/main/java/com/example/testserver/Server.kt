package com.example.testserver

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.hardware.SensorEventListener
import android.preference.PreferenceManager
import android.util.Log
import android.util.Base64
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.BooleanNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import kotlin.math.acos
import kotlin.math.sqrt

class Server(
    private val wot: Wot,
    private val servient: Servient,
    private val context: Context
) {
    private val jsonNodeFactory = JsonNodeFactory.instance
    private val activeThings = mutableMapOf<String, WoTExposedThing>()

    private var photoThingId: String? = null
    private var audioThingId: String? = null

    private var currentPhotoBase64: String = ""
    private var currentAudioBase64: String = ""

    private var sensorPublisher: SensorPublisher? = null

    suspend fun start(): List<WoTExposedThing> {
        // Stop eventuali Thing attivi
        stop()

        val exposedThings = mutableListOf<WoTExposedThing>()

        // Creo Thing smartphone con tutte le affordances dei sensori abilitati
        val smartphoneThing = createSmartphoneThing()
        if (smartphoneThing != null) {
            servient.addThing(smartphoneThing)
            servient.expose(smartphoneThing.getThingDescription().id)
            exposedThings.add(smartphoneThing)
            Log.d(
                "SERVER",
                "SmartphoneThing esposto con ID: ${smartphoneThing.getThingDescription().id}"
            )
        }

        return exposedThings
    }

    private fun createSmartphoneThing(): WoTExposedThing? {
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

        val useLocalIp = sharedPrefs.getBoolean("use_local_ip", false)
        val customHostname = sharedPrefs.getString("server_hostname", "")

        val actualHostname = when {
            !useLocalIp -> "localhost"
            !customHostname.isNullOrBlank() -> customHostname
            else -> getLocalIpAddress() ?: "localhost"
        }
        Log.d("SERVER", "Utilizzando hostname: $actualHostname (configurato: $customHostname)")

        val allowedSensorTypes = listOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_LIGHT,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_PROXIMITY,
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_PRESSURE,
            Sensor.TYPE_AMBIENT_TEMPERATURE,
            Sensor.TYPE_RELATIVE_HUMIDITY,
            Sensor.TYPE_GRAVITY
        )
        val availableSensors = getFilteredSensors(sensorManager, allowedSensorTypes)

        val jsonNodeFactory = JsonNodeFactory.instance
        val thingId = "smartphone"
        val thingTitle = "Smartphone sensors"
        val thingDescription = "Thing representing selected sensors of the smartphone"

        // Controlla se ci sono sensori abilitati
        val enabledSensors = availableSensors.filter { sensor ->
            val prefKey = "share_sensor_${sensor.name}"
            sharedPrefs.getBoolean(prefKey, false)
        }
        if (enabledSensors.isEmpty()) {
            Log.d("SERVER", "Nessun sensore abilitato!")
        }

        val thing = wot.produce {
            id = thingId
            title = thingTitle
            description = thingDescription

            if (useLocalIp) {
                val port = sharedPrefs.getString("server_port", "8080")?.toIntOrNull() ?: 8080
                base = "http://$actualHostname:$port/"
            }

            // Aggiungi proprietà per ogni sensore abilitato
            for (sensor in enabledSensors) {
                val type = sensor.type
                val name = sensor.name
                val sensorValuesCount = getSensorValuesCount(type)
                val units = getSensorUnits(type)
                if (sensorValuesCount == 1) {
                    val propName = sanitizeSensorName(name, type)
                    numberProperty(propName) {
                        title = name
                        readOnly = true
                        observable = true
                        unit = units.getOrNull(0)
                    }
                } else {
                    for (i in 0 until sensorValuesCount) {
                        val suffix = listOf("x", "y", "z", "w", "v").getOrNull(i) ?: "v$i"
                        val propName = "${sanitizeSensorName(name, type)}_$suffix"
                        numberProperty(propName) {
                            title = "$name $suffix"
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
                input = stringSchema {}
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
            action<Unit, Float>("getMagnitude") {
                title = "Get accelerometer magnitude"
                description = "Calculates the magnitude of acceleration from accelerometer data"
            }
            action<Unit, Float>("getNorthDirection") {
                title = "Get compass direction"
                description = "Returns the direction (azimuth) in degrees relative to magnetic north"
            }
            action<Unit, JsonNode>("getOrientation") {
                title = "Get orientation"
                description = "Returns the orientation angles: azimuth, pitch and roll"
            }
            action<Unit, Float>("getInclination") {
                title = "Get inclination"
                description = "Estimates inclination angle (0° = verticale, 90° = orizzontale)"
            }
            action<Unit, BooleanNode>("checkInPocket") {
                title = "Check if in pocket"
                description = "Returns true if the phone is likely in the user's pocket"
            }
            objectProperty("allSensors") {
                title = "All sensor readings"
                description = "Returns all sensor data in one request"
                readOnly = true
                observable = false
            }
        }.apply {
            // Aggiungi i property read handlers
            for (sensor in enabledSensors) {
                val type = sensor.type
                val name = sensor.name
                val sensorValuesCount = getSensorValuesCount(type)
                if (sensorValuesCount == 1) {
                    val propName = sanitizeSensorName(name, type)
                    setPropertyReadHandler(propName) {
                        ServientStats.logRequest(thingId, "readProperty", propName)
                        val v = readSensorValues(context, type)
                        InteractionInput.Value(jsonNodeFactory.numberNode(v.getOrNull(0) ?: -1f))
                    }
                } else {
                    for (i in 0 until sensorValuesCount) {
                        val suffix = listOf("x", "y", "z", "w", "v").getOrNull(i) ?: "v$i"
                        val propName = "${sanitizeSensorName(name, type)}_$suffix"
                        setPropertyReadHandler(propName) {
                            ServientStats.logRequest(thingId, "readProperty", propName)
                            val v = readSensorValues(context, type)
                            InteractionInput.Value(
                                jsonNodeFactory.numberNode(
                                    v.getOrNull(i) ?: -1f
                                )
                            )
                        }
                    }
                }
            }
            setPropertyReadHandler("photo") {
                ServientStats.logRequest(thingId, "readProperty", "photo")
                InteractionInput.Value(jsonNodeFactory.textNode(currentPhotoBase64))
            }
            setActionHandler("takePhoto") { _: WoTInteractionOutput, _: InteractionOptions? ->
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
            setActionHandler("startRecording") { _: WoTInteractionOutput, _: InteractionOptions? ->
                ServientStats.logRequest(thingId, "invokeAction", "startRecording")
                MediaUtils.startAudioRecording(context)
                InteractionInput.Value(jsonNodeFactory.nullNode())
            }
            setActionHandler("stopRecording") { _: WoTInteractionOutput, _: InteractionOptions? ->
                ServientStats.logRequest(thingId, "invokeAction", "stopRecording")
                val recordedAudioBase64 = MediaUtils.stopAudioRecording()
                if (recordedAudioBase64 != null) {
                    currentAudioBase64 = recordedAudioBase64
                    Log.d(
                        "SERVER",
                        "Proprietà 'audio' aggiornata con nuovo audio Base64. Lunghezza: ${recordedAudioBase64.length}"
                    )
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
            setActionHandler("getMagnitude") { _: WoTInteractionOutput, _: InteractionOptions? ->
                ServientStats.logRequest(thingId, "invokeAction", "getMagnitude")

                if (!isSensorEnabled(Sensor.TYPE_ACCELEROMETER)) {
                    Log.w("SERVER", "Accelerometro non abilitato - getMagnitude non disponibile")
                    InteractionInput.Value(jsonNodeFactory.numberNode(-999.0f)) // Valore di errore
                } else {
                    // Leggi i valori dell'accelerometro
                    val accelerometerValues = readSensorValues(context, Sensor.TYPE_ACCELEROMETER)

                    if (accelerometerValues.size >= 3) {
                        // Calcola la magnitudine: sqrt(x² + y² + z²)
                        val x = accelerometerValues[0]
                        val y = accelerometerValues[1]
                        val z = accelerometerValues[2]
                        val magnitude = kotlin.math.sqrt(x*x + y*y + z*z)

                        Log.d("SERVER", "Magnitudine accelerometro calcolata: $magnitude (x=$x, y=$y, z=$z)")
                        InteractionInput.Value(jsonNodeFactory.numberNode(magnitude))
                    } else {
                        Log.w("SERVER", "Dati accelerometro insufficienti: ${accelerometerValues.size} valori")
                        InteractionInput.Value(jsonNodeFactory.numberNode(-1.0f))
                    }
                }
            }
            setActionHandler("getNorthDirection") { _: WoTInteractionOutput, _: InteractionOptions? ->
                ServientStats.logRequest(thingId, "invokeAction", "getNorthDirection")

                if (!isSensorEnabled(Sensor.TYPE_ACCELEROMETER) || !isSensorEnabled(Sensor.TYPE_MAGNETIC_FIELD)) {
                    Log.w("SERVER", "Accelerometro o magnetometro non abilitati - getNorthDirection non disponibile")
                    InteractionInput.Value(jsonNodeFactory.numberNode(-999.0f)) // Valore di errore
                } else {
                    val accel = readSensorValues(context, Sensor.TYPE_ACCELEROMETER)
                    val magnet = readSensorValues(context, Sensor.TYPE_MAGNETIC_FIELD)

                    if (accel.size >= 3 && magnet.size >= 3) {
                        val gravity = accel.map { it.toFloat() }.toFloatArray()
                        val geomagnetic = magnet.map { it.toFloat() }.toFloatArray()

                        val R = FloatArray(9)
                        val orientation = FloatArray(3)

                        if (SensorManager.getRotationMatrix(R, null, gravity, geomagnetic)) {
                            SensorManager.getOrientation(R, orientation)
                            val azimuthRad = orientation[0]
                            val azimuthDeg = Math.toDegrees(azimuthRad.toDouble()).toFloat()
                            val direction = (azimuthDeg + 360f) % 360f

                            Log.d("SERVER", "Direzione nord: $direction°")
                            InteractionInput.Value(jsonNodeFactory.numberNode(direction))
                        } else {
                            Log.w("SERVER", "Impossibile calcolare la matrice di rotazione")
                            InteractionInput.Value(jsonNodeFactory.numberNode(-1.0f))
                        }
                    } else {
                        Log.w("SERVER", "Dati accelerometro o magnetometro insufficienti")
                        InteractionInput.Value(jsonNodeFactory.numberNode(-1.0f))
                    }
                }
            }
            setActionHandler("getOrientation") { _: WoTInteractionOutput, _: InteractionOptions? ->
                ServientStats.logRequest(thingId, "invokeAction", "getOrientation")

                if (!isSensorEnabled(Sensor.TYPE_ACCELEROMETER) || !isSensorEnabled(Sensor.TYPE_MAGNETIC_FIELD)) {
                    Log.w("SERVER", "Accelerometro o magnetometro non abilitati - getOrientation non disponibile")
                    val errorArray = jsonNodeFactory.arrayNode()
                    errorArray.add(-999.0)
                    errorArray.add(-999.0)
                    errorArray.add(-999.0)
                    InteractionInput.Value(errorArray)
                } else {
                    val accel = readSensorValues(context, Sensor.TYPE_ACCELEROMETER)
                    val magnet = readSensorValues(context, Sensor.TYPE_MAGNETIC_FIELD)

                    if (accel.size >= 3 && magnet.size >= 3) {
                        val gravity = accel.map { it.toFloat() }.toFloatArray()
                        val geomagnetic = magnet.map { it.toFloat() }.toFloatArray()

                        val R = FloatArray(9)
                        val orientation = FloatArray(3)

                        if (SensorManager.getRotationMatrix(R, null, gravity, geomagnetic)) {
                            SensorManager.getOrientation(R, orientation)

                            val result = jsonNodeFactory.arrayNode()
                            result.add(Math.toDegrees(orientation[0].toDouble()))
                            result.add(Math.toDegrees(orientation[1].toDouble()))
                            result.add(Math.toDegrees(orientation[2].toDouble()))

                            Log.d("SERVER", "Orientamento: $result")
                            InteractionInput.Value(result)
                        } else {
                            Log.w("SERVER", "Impossibile calcolare orientamento")
                            val emptyArray = jsonNodeFactory.arrayNode()
                            emptyArray.add(-1.0)
                            emptyArray.add(-1.0)
                            emptyArray.add(-1.0)
                            InteractionInput.Value(emptyArray)
                        }
                    } else {
                        Log.w("SERVER", "Dati sensori insufficienti per orientamento")
                        val emptyArray = jsonNodeFactory.arrayNode()
                        emptyArray.add(-1.0)
                        emptyArray.add(-1.0)
                        emptyArray.add(-1.0)
                        InteractionInput.Value(emptyArray)
                    }
                }
            }
            setActionHandler("getInclination") { _: WoTInteractionOutput, _: InteractionOptions? ->
                ServientStats.logRequest(thingId, "invokeAction", "getInclination")

                if (!isSensorEnabled(Sensor.TYPE_GRAVITY)) {
                    Log.w("SERVER", "Sensore gravità non abilitato - getInclination non disponibile")
                    InteractionInput.Value(jsonNodeFactory.numberNode(-999.0f)) // Valore di errore
                } else {
                    val values = readSensorValues(context, Sensor.TYPE_GRAVITY)

                    if (values.size >= 3) {
                        val x = values[0]
                        val y = values[1]
                        val z = values[2]
                        val norm = sqrt((x * x + y * y + z * z).toDouble())

                        val inclination = Math.toDegrees(acos(z / norm)).toFloat()
                        Log.d("SERVER", "Inclinazione stimata: $inclination°")
                        InteractionInput.Value(jsonNodeFactory.numberNode(inclination))
                    } else {
                        Log.w("SERVER", "Dati gravità insufficienti")
                        InteractionInput.Value(jsonNodeFactory.numberNode(-1.0f))
                    }
                }
            }
            setActionHandler("checkInPocket") { _: WoTInteractionOutput, _: InteractionOptions? ->
                ServientStats.logRequest(thingId, "invokeAction", "checkInPocket")

                if (!isSensorEnabled(Sensor.TYPE_PROXIMITY) || !isSensorEnabled(Sensor.TYPE_LIGHT)) {
                    Log.w("SERVER", "Sensore prossimità o luce non abilitati - checkInPocket non disponibile")
                    InteractionInput.Value(jsonNodeFactory.booleanNode(false))
                } else {

                    val prox = readSensorValues(context, Sensor.TYPE_PROXIMITY)
                    val light = readSensorValues(context, Sensor.TYPE_LIGHT)

                    if (prox.isNotEmpty() && light.isNotEmpty()) {
                        val proximity = prox[0]
                        val luminosity = light[0]

                        val proximitySensor =
                            enabledSensors.find { it.type == Sensor.TYPE_PROXIMITY }
                        val isNear =
                            proximitySensor != null && proximity < proximitySensor.maximumRange
                        val isDark = luminosity < 10

                        val inPocket = isNear && isDark
                        Log.d("SERVER", "Telefono in tasca? $inPocket")
                        InteractionInput.Value(jsonNodeFactory.booleanNode(inPocket))
                    } else {
                        Log.w("SERVER", "Dati luce o prossimità mancanti")
                        InteractionInput.Value(jsonNodeFactory.booleanNode(false))
                    }
                }
            }
            setPropertyReadHandler("allSensors") {
                ServientStats.logRequest(thingId, "readProperty", "allSensors")

                val allProperties = jsonNodeFactory.objectNode()

                // Leggi ogni proprietà e mettila nell'oggetto
                for (sensor in enabledSensors) {
                    val type = sensor.type
                    val name = sensor.name
                    val sensorValuesCount = getSensorValuesCount(type)
                    val values = readSensorValues(context, type)

                    if (sensorValuesCount == 1) {
                        val propName = sanitizeSensorName(name, type)
                        allProperties.put(propName, values.getOrNull(0) ?: -1f)
                    } else {
                        for (i in 0 until sensorValuesCount) {
                            val suffix = listOf("x", "y", "z", "w", "v").getOrNull(i) ?: "v$i"
                            val propName = "${sanitizeSensorName(name, type)}_$suffix"
                            allProperties.put(propName, values.getOrNull(i) ?: -1f)
                        }
                    }
                }

                InteractionInput.Value(allProperties)
            }
        }

        sensorPublisher = SensorPublisher(context, thing, enabledSensors)
        sensorPublisher?.startPublishing()

        try {
            return thing
        } catch (e: Exception) {
            Log.e("SERVER", "Errore creazione SmartphoneThing", e)
            return null
        }


    }

    private fun isSensorEnabled(sensorType: Int): Boolean {
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensorManager.getDefaultSensor(sensorType)

        return if (sensor != null) {
            val prefKey = "share_sensor_${sensor.name}"
            sharedPrefs.getBoolean(prefKey, false)
        } else {
            false
        }
    }

    suspend fun updateExposedThings(): List<WoTExposedThing> {
        val sharedPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

        val allowedSensorTypes = listOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_LIGHT
        )
        val availableSensors = getFilteredSensors(sensorManager, allowedSensorTypes)

        val wantedThingIds = mutableSetOf<String>()
        val newlyAdded = mutableListOf<WoTExposedThing>()

        for (sensor in availableSensors) {
            val prefKey = "share_sensor_${sensor.name}"
            val shouldShare = sharedPrefs.getBoolean(prefKey, false)
            if (!shouldShare) continue

            Log.d("SERVER_PREF", "Checking $prefKey = $shouldShare")
            val type = sensor.type
            val name = sensor.name
            val thingId = sanitizeSensorName(name, type)
            wantedThingIds.add(thingId)

            if (thingId in activeThings) continue

            val sensorValuesCount = getSensorValuesCount(type)
            val units = getSensorUnits(type)
            // TODO: PROBLEMA QUA -- NON PRODUCO A LOCALHOST:$PORT MA SEMPRE LOCALHOST:8080
            val thing = wot.produce {
                id = thingId
                title = name
                description = "Thing for sensor $name, type: $type"

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
                val sensorType = type
                if (sensorValuesCount == 1) {
                    setPropertyReadHandler("value") {
                        ServientStats.logRequest(thingId, "readProperty", "value")
                        val v = readSensorValues(context, sensorType)
                        InteractionInput.Value(jsonNodeFactory.numberNode(v.getOrNull(0) ?: -1f))
                    }
                } else {
                    for (i in 0 until sensorValuesCount) {
                        val propName = listOf("x", "y", "z").getOrNull(i) ?: "v$i"
                        setPropertyReadHandler(propName) {
                            ServientStats.logRequest(thingId, "readProperty", propName)
                            val v = readSensorValues(context, sensorType)
                            InteractionInput.Value(
                                jsonNodeFactory.numberNode(
                                    v.getOrNull(i) ?: -1f
                                )
                            )
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

    private fun readSensorValues(context: Context, sensorType: Int): FloatArray {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensorManager.getDefaultSensor(sensorType) ?: return floatArrayOf()

        if (sensor == null) {
            Log.w("SENSOR_READ", "Sensore tipo $sensorType non disponibile")
            return floatArrayOf()
        }

        val latch = CountDownLatch(1)
        var values: FloatArray = floatArrayOf()
        var hasReceivedData = false

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event != null && event.sensor.type == sensorType && !hasReceivedData) {
                    hasReceivedData = true
                    values = event.values.copyOf()
                    // Log.d("SENSOR_READ", "Ricevuti dati per sensore $sensorType: ${values.contentToString()}")
                    latch.countDown()
                    // sensorManager.unregisterListener(this)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        try {
            // Registra il listener con delay più veloce per lettura immediata
            val registered = sensorManager.registerListener(
                listener,
                sensor,
                SensorManager.SENSOR_DELAY_FASTEST
            )

            if (!registered) {
                // Log.e("SENSOR_READ", "Impossibile registrare listener per sensore $sensorType")
                return floatArrayOf()
            }

            // Log.d("SENSOR_READ", "Listener registrato per sensore $sensorType, attendo dati...")

            // Aspetta più a lungo per i dati del sensore
            val received = latch.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS)

            if (!received) {
                Log.w("SENSOR_READ", "Timeout nella lettura del sensore $sensorType")
            }

        } catch (e: InterruptedException) {
            Log.e("SENSOR_READ", "Interruzione durante lettura sensore $sensorType", e)
            Thread.currentThread().interrupt()
        } finally {
            // Assicurati sempre di de-registrare il listener
            sensorManager.unregisterListener(listener)
            Log.d("SENSOR_READ", "Listener de-registrato per sensore $sensorType")
        }

        return values
    }

    fun getSensorUnits(type: Int): List<String?> = when (type) {
        Sensor.TYPE_LIGHT -> listOf("lux")
        Sensor.TYPE_PRESSURE -> listOf("hPa")
        Sensor.TYPE_ACCELEROMETER -> listOf("m/s^2")
        Sensor.TYPE_MAGNETIC_FIELD -> listOf("μT")
        Sensor.TYPE_PROXIMITY -> listOf("cm")
        Sensor.TYPE_AMBIENT_TEMPERATURE -> listOf("°C")
        Sensor.TYPE_RELATIVE_HUMIDITY -> listOf("%")
        else -> listOf(null)
    }

    suspend fun stop() {
        Log.d("SERVER_STOP", "Inizio stop server..")
        try {
            sensorPublisher?.stopPublishing()
            sensorPublisher = null

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

            photoThingId = null
            audioThingId = null

            Log.d("SERVER_STOP", "Stop server completo!")

        } catch (e: Exception) {
            Log.e("SERVER_STOP", "Errore durante stop Server", e)
        }
    }

    private fun filterByNonWakeupSensors(sensors: List<Sensor>): List<Sensor> {
        val sensorsByType = sensors.groupBy { it.type }

        return sensorsByType.values.mapNotNull { sensorGroup ->
            when {
                sensorGroup.size == 1 -> sensorGroup.first() // Solo una versione disponibile
                sensorGroup.size > 1 -> {
                    // Cerca prima la versione non-wakeup
                    val nonWakeup = sensorGroup.find { !it.isWakeUpSensor }
                    nonWakeup ?: sensorGroup.first() // Se non trova non-wakeup, prende il primo
                }

                else -> null
            }
        }
    }

    private fun getFilteredSensors(
        sensorManager: SensorManager,
        allowedSensorTypes: List<Int>
    ): List<Sensor> {
        val allSensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
            .filter { sensor -> sensor.type in allowedSensorTypes }
        return filterByNonWakeupSensors(allSensors)
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SERVER", "Errore ottenimento IP locale: ", e)
        }
        return null
    }
}