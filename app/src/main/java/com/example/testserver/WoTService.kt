package com.example.testserver

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.preference.PreferenceManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.eclipse.thingweb.Servient
import org.eclipse.thingweb.Wot
import org.eclipse.thingweb.binding.http.HttpProtocolClientFactory
import org.eclipse.thingweb.binding.http.HttpProtocolServer
import androidx.core.content.edit
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.eclipse.thingweb.binding.mqtt.MqttClientConfig
import org.eclipse.thingweb.binding.mqtt.MqttProtocolClientFactory
import org.eclipse.thingweb.binding.mqtt.MqttProtocolServer
import org.eclipse.thingweb.binding.websocket.WebSocketProtocolClientFactory
import org.eclipse.thingweb.binding.websocket.WebSocketProtocolServer

class WoTService : Service() {
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var servient: Servient? = null
    private var wot: Wot? = null
    private var server: Server? = null

    // Mutex per evitare race condition?
    private val serverMutex = Mutex()
    private var isServerRunning = false

    private val preferenceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "PREFERENCES_UPDATED") {
                val updatedType = intent.getStringExtra("update_type") ?: ""
                coroutineScope.launch {
                    serverMutex.withLock {
                        when (updatedType) {
                            "port", "hostname" -> {
                                // Cambio porta o hostname --> stop e riavvio completo
                                stopWoTServerInternal()
                                Log.d("SERVER", "Riavvio Server per cambio $updatedType")
                                delay(1000)
                                startWoTServerInternal()
                                Log.d("SERVER", "Server riattivo!")
                            }
                            "sensors" -> {
                                // Aggiunta/rimozione sensori --> niente stop
                                server?.updateExposedThings()
                                Log.d("SERVER", "Server aggiornato!")
                            }
                            "sensors_restart" -> {
                                // Restart completo
                                Log.d("SERVER", "Restart completo per cambio sensori")
                                setServerStarting(true)
                                stopWoTServerInternal()
                                delay(1000)
                                startWoTServerInternal()
                                Log.d("SERVER", "Server riavviato per cambio sensori")
                            }
                            else -> {
                                // Default
                                server?.updateExposedThings()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(1, createNotification())
        // Carico le stats
        ServientStatsPrefs.load(applicationContext)
        ServientStats.initialize(applicationContext)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_NOT_EXPORTED else 0
        registerReceiver(preferenceReceiver, IntentFilter("PREFERENCES_UPDATED"), flags)
        coroutineScope.launch {
            startWoTServer()
        }
    }

    private suspend fun startWoTServer() {
        serverMutex.withLock {
            startWoTServerInternal()
        }
    }

    private suspend fun startWoTServerInternal() {
        try {
            if (isServerRunning) {
                Log.d("SERVER", "Server già in esecuzione!")
                return
            }

            setServerStarting(true)

            stopWoTServerInternal()
            val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            val port = prefs.getString("server_port", "8080")?.toIntOrNull() ?: 8080
            val useLocalIp = prefs.getBoolean("use_local_ip", false)
            val customHostname = prefs.getString("server_hostname", "")

            val actualHostname = when {
                !useLocalIp -> "localhost"
                !customHostname.isNullOrBlank() -> customHostname
                else -> getLocalIpAddress() ?: "localhost"
            }

            Log.d("SERVER", "Avvio Server sulla porta $port")
            Log.d("SERVER", "Usa IP locale: $useLocalIp, Hostname: $actualHostname")

            val servers = mutableListOf<ai.anfc.lmos.wot.binding.ProtocolServer>()
            val clientFactories = mutableListOf<ai.anfc.lmos.wot.binding.ProtocolClientFactory>()

            // HTTP
            val httpServer = if (useLocalIp) {
                // When using local IP, bind to all interfaces (0.0.0.0)
                // so the server can accept connections from the local network
                HttpProtocolServer(bindPort = port, bindHost = "0.0.0.0")
            } else {
                // When using localhost, can bind specifically to localhost
                HttpProtocolServer(bindPort = port, bindHost = "127.0.0.1")
            }
            servers.add(httpServer)
            clientFactories.add(HttpProtocolClientFactory())

            // MQTT Configuration
            val enableMqtt = prefs.getBoolean("enable_mqtt", false)
            val mqttBrokerHost = prefs.getString("mqtt_broker_host", "test.mosquitto.org") ?: "test.mosquitto.org"
            val mqttBrokerPort = prefs.getString("mqtt_broker_port", "1883")?.toIntOrNull() ?: 1883
            val mqttClientId = prefs.getString("mqtt_client_id", "wot-client-${System.currentTimeMillis()}")
            Log.e("WOT_MQTT_DEBUG", "🔧 MQTT enabled: $enableMqtt")
            Log.e("WOT_MQTT_DEBUG", "🔧 MQTT host: $mqttBrokerHost")
            Log.e("WOT_MQTT_DEBUG", "🔧 MQTT port: $mqttBrokerPort")

            //MQTT
            if (enableMqtt) {
                Log.e("WOT_MQTT_DEBUG", "✅ Aggiungendo MQTT server...")
                val mqttConfig = MqttClientConfig(
                    host = mqttBrokerHost,
                    port = mqttBrokerPort,
                    clientId = mqttClientId ?: "wot-client-${System.currentTimeMillis()}"
                )
                val mqttServer = MqttProtocolServer(mqttConfig)
                val mqttClient = MqttProtocolClientFactory(mqttConfig)
                servers.add(mqttServer)
                clientFactories.add(mqttClient)
            } else {
                Log.e("WOT_MQTT_DEBUG", "❌ MQTT DISABILITATO!")
            }

            // WebSocket
            val enableWebSocket = prefs.getBoolean("enable_websocket", true) // Default to enabled
            val webSocketPort = prefs.getString("websocket_port", "8081")?.toIntOrNull() ?: 8081
            var webSocketServer: WebSocketProtocolServer? = null
            if (enableWebSocket) {
                val wsBindHost = if (useLocalIp) "0.0.0.0" else "127.0.0.1"
                val wsBaseUrls = if (useLocalIp) {
                    listOf("ws://$actualHostname:$webSocketPort", "ws://localhost:$webSocketPort")
                } else {
                    listOf("ws://localhost:$webSocketPort")
                }

                webSocketServer = WebSocketProtocolServer(
                    bindHost = wsBindHost,
                    bindPort = webSocketPort,
                )
                val webSocketClient = WebSocketProtocolClientFactory()
                servers.add(webSocketServer)
                clientFactories.add(webSocketClient)
            }

            servient = Servient(
                servers = servers,
                clientFactories = clientFactories
            )

            wot = Wot.create(servient!!)
            WoTClientHolder.wot = wot
            servient!!.start()

            server = Server(wot!!, servient!!, applicationContext)
            server!!.start()

            isServerRunning = true

            setServerStarting(false) // ora "started"
            prefs.edit().putBoolean("server_started", true).commit()
            sendServiceStatusBroadcast()

            // Logging
            if (useLocalIp) {
                val localIp = getLocalIpAddress()
                Log.d("SERVER", "Server avviato con successo!")
                Log.d("SERVER", "Accesso locale: http://localhost:$port")
                localIp?.let { ip ->
                    Log.d("SERVER", "Accesso rete locale: http://$ip:$port")
                }
            } else {
                Log.d("SERVER", "Server avviato con successo su http://localhost:$port")
            }

        } catch (e: Exception) {
            Log.e("WOT_SERVICE", "Errore durante avvio server: ", e)
            setServerStarting(false)
            stopWoTServerInternal()
        }
    }

    private fun createNotification(): Notification {
        val channelId = "wot_service_channel"
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "WoT Server",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, WoTService::class.java).apply {
            action = "STOP_SERVICE"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Server WoT attivo")
            .setContentText("Il server è in esecuzione")
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .addAction(R.drawable.ic_launcher_background, "Stop", stopPendingIntent)
            .build()
    }

    override fun onDestroy() {
        Log.d("WOT_SERVICE", "Destroying service..")
        try {
            unregisterReceiver(preferenceReceiver)
        } catch (e: Exception) {
            Log.e("WOT_SERVICE", "Errore durante unregister receiver: ", e)
        }
        coroutineScope.launch {
            try {
                stopWoTServer()
                // Salvo stats
                ServientStatsPrefs.save(applicationContext)
                delay(500)
            } catch (e: Exception) {
                Log.e("WOT_SERVICE", "errore durante cleanup: ", e)
            } finally {
                coroutineScope.cancel()
                Log.d("WOT_SERVICE", "Service destroyed")
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent?.action == "STOP_SERVICE") {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private suspend fun stopWoTServer() {
        serverMutex.withLock {
            stopWoTServerInternal()
        }
    }

    private suspend fun stopWoTServerInternal() {
        try {
            if (!isServerRunning) {
                Log.d("SERVER", "Server già fermo, skip..")
                return
            }
            Log.d("SERVER", "Fermando Server..")
            server?.let {
                try {
                    it.stop()
                    Log.d("SERVER", "Server fermo")
                } catch (e: Exception) {
                    Log.e("SERVER", "Errore durante stop server: ", e)
                }
            }
            server = null

            wot?.let {
                try {
                    WoTClientHolder.wot = null
                    Log.d("SERVER", "WoT pulito")
                } catch (e: Exception) {
                    Log.e("SERVER", "Errore durante pulizia WoT: ", e)
                }
            }
            wot = null

            servient?.let { currentServient ->
                try {
                    delay(200)
                    currentServient.shutdown()
                    Log.d("SERVER", "Servient fermato")
                    delay(300)
                } catch (e: Exception) {
                    Log.e("SERVER", "Errore durante shutdown servient: ", e)
                    try {
                        // Prova a suggerire garbage collection
                        System.gc()
                        delay(100)
                    } catch (gcError: Exception) {
                        Log.w("SERVER", "Errore durante GC: ", gcError)
                    }
                }
            }
            servient = null

            isServerRunning = false
            val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            prefs.edit{
                putBoolean("server_started", false)
                putBoolean("server_starting", false)
                    .commit()
            }
            sendServiceStatusBroadcast()
            Log.d("SERVER", "Stop completo!")
        } catch (e: Exception) {
            Log.e("WOT_SERVICE", "Errore durante stop server: ", e)
            server = null
            wot = null
            servient = null
            isServerRunning = false
        }
    }

    // Aggiungi questa funzione helper per ottenere l'IP locale
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
            Log.e("WOT_SERVICE", "Errore ottenimento IP locale: ", e)
        }
        return null
    }

    private fun setServerStarting(starting: Boolean) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        prefs.edit().putBoolean("server_starting", starting).apply()
        sendServiceStatusBroadcast()
    }

    private fun sendServiceStatusBroadcast() {
        val intent = Intent("SERVICE_STATUS_CHANGED")
        sendBroadcast(intent)
    }
}