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

            Log.d("SERVER_DEBUG", "🚀 INIZIO startWoTServerInternal()")
            setServerStarting(true)
            stopWoTServerInternal()

            val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)

            // 🔧 DEBUG: Leggi e logga tutte le preferenze
            val enableHttp = prefs.getBoolean("enable_http", true)
            val enableWebSocket = prefs.getBoolean("enable_websocket", true)
            val enableMqtt = prefs.getBoolean("enable_mqtt", false)

            Log.d("SERVER_DEBUG", "📋 Preferenze lette:")
            Log.d("SERVER_DEBUG", "   enable_http: $enableHttp")
            Log.d("SERVER_DEBUG", "   enable_websocket: $enableWebSocket")
            Log.d("SERVER_DEBUG", "   enable_mqtt: $enableMqtt")

            val port = prefs.getString("server_port", "8080")?.toIntOrNull() ?: 8080
            val webSocketPort = prefs.getString("websocket_port", "8080")?.toIntOrNull() ?: 8080
            val useLocalIp = prefs.getBoolean("use_local_ip", false)
            val customHostname = prefs.getString("server_hostname", "")

            Log.d("SERVER_DEBUG", "🔧 Configurazione rete:")
            Log.d("SERVER_DEBUG", "   HTTP port: $port")
            Log.d("SERVER_DEBUG", "   WebSocket port: $webSocketPort")
            Log.d("SERVER_DEBUG", "   useLocalIp: $useLocalIp")
            Log.d("SERVER_DEBUG", "   customHostname: '$customHostname'")

            val actualHostname = when {
                !useLocalIp -> "localhost"
                !customHostname.isNullOrBlank() -> customHostname
                else -> getLocalIpAddress() ?: "localhost"
            }
            Log.d("SERVER_DEBUG", "   actualHostname: $actualHostname")

            val servers = mutableListOf<ai.anfc.lmos.wot.binding.ProtocolServer>()
            val clientFactories = mutableListOf<ai.anfc.lmos.wot.binding.ProtocolClientFactory>()

            // HTTP Configuration
            if (enableHttp) {
                try {
                    Log.d("SERVER_DEBUG", "🔧 Configurando HTTP server...")
                    val httpServer = if (useLocalIp) {
                        HttpProtocolServer(bindPort = port, bindHost = "0.0.0.0")
                    } else {
                        HttpProtocolServer(bindPort = port, bindHost = "127.0.0.1")
                    }
                    servers.add(httpServer)
                    clientFactories.add(HttpProtocolClientFactory())
                    Log.d("SERVER_DEBUG", "✅ HTTP server configurato")
                } catch (e: Exception) {
                    Log.e("SERVER_DEBUG", "❌ Errore configurazione HTTP server", e)
                    throw e
                }
            } else {
                Log.d("SERVER_DEBUG", "❌ HTTP DISABILITATO")
            }

            // MQTT Configuration
            if (enableMqtt) {
                try {
                    Log.d("SERVER_DEBUG", "🔧 Configurando MQTT server...")
                    val mqttBrokerHost = prefs.getString("mqtt_broker_host", "test.mosquitto.org") ?: "test.mosquitto.org"
                    val mqttBrokerPort = prefs.getString("mqtt_broker_port", "1883")?.toIntOrNull() ?: 1883
                    val mqttClientId = prefs.getString("mqtt_client_id", "wot-client-${System.currentTimeMillis()}")

                    Log.d("SERVER_DEBUG", "   MQTT host: $mqttBrokerHost")
                    Log.d("SERVER_DEBUG", "   MQTT port: $mqttBrokerPort")
                    Log.d("SERVER_DEBUG", "   MQTT clientId: $mqttClientId")

                    val mqttConfig = MqttClientConfig(
                        host = mqttBrokerHost,
                        port = mqttBrokerPort,
                        clientId = mqttClientId ?: "wot-client-${System.currentTimeMillis()}"
                    )
                    val mqttServer = MqttProtocolServer(mqttConfig)
                    val mqttClient = MqttProtocolClientFactory(mqttConfig)
                    servers.add(mqttServer)
                    clientFactories.add(mqttClient)
                    Log.d("SERVER_DEBUG", "✅ MQTT server configurato")
                } catch (e: Exception) {
                    Log.e("SERVER_DEBUG", "❌ Errore configurazione MQTT server", e)
                    throw e
                }
            } else {
                Log.d("SERVER_DEBUG", "❌ MQTT DISABILITATO")
            }

            // WebSocket Configuration
            if (enableWebSocket) {
                try {
                    Log.d("SERVER_DEBUG", "🔧 Configurando WebSocket server...")
                    val wsBindHost = if (useLocalIp) "0.0.0.0" else "127.0.0.1"

                    Log.d("SERVER_DEBUG", "   WS bindHost: $wsBindHost")
                    Log.d("SERVER_DEBUG", "   WS bindPort: $webSocketPort")

                    val webSocketServer = WebSocketProtocolServer(
                        bindHost = wsBindHost,
                        bindPort = webSocketPort,
                    )
                    val webSocketClient = WebSocketProtocolClientFactory()
                    servers.add(webSocketServer)
                    clientFactories.add(webSocketClient)
                    Log.d("SERVER_DEBUG", "✅ WebSocket server configurato")
                } catch (e: Exception) {
                    Log.e("SERVER_DEBUG", "❌ Errore configurazione WebSocket server", e)
                    throw e
                }
            } else {
                Log.d("SERVER_DEBUG", "❌ WebSocket DISABILITATO")
            }

            // Verifica protocolli
            Log.d("SERVER_DEBUG", "📊 Riepilogo servers: ${servers.size} configurati")
            servers.forEachIndexed { index, server ->
                Log.d("SERVER_DEBUG", "   Server $index: ${server::class.simpleName}")
            }

            if (servers.isEmpty()) {
                Log.e("SERVER_DEBUG", "❌ ERRORE CRITICO: Nessun server configurato!")
                setServerStarting(false)
                return
            }

            // Crea Servient
            try {
                Log.d("SERVER_DEBUG", "🔧 Creando Servient...")
                servient = Servient(
                    servers = servers,
                    clientFactories = clientFactories
                )
                Log.d("SERVER_DEBUG", "✅ Servient creato")
            } catch (e: Exception) {
                Log.e("SERVER_DEBUG", "❌ Errore creazione Servient", e)
                throw e
            }

            // Crea WoT
            try {
                Log.d("SERVER_DEBUG", "🔧 Creando WoT...")
                wot = Wot.create(servient!!)
                WoTClientHolder.wot = wot
                Log.d("SERVER_DEBUG", "✅ WoT creato")
            } catch (e: Exception) {
                Log.e("SERVER_DEBUG", "❌ Errore creazione WoT", e)
                throw e
            }

            // Avvia Servient
            try {
                Log.d("SERVER_DEBUG", "🔧 Avviando Servient...")
                servient!!.start()
                Log.d("SERVER_DEBUG", "✅ Servient avviato")
            } catch (e: Exception) {
                Log.e("SERVER_DEBUG", "❌ Errore avvio Servient", e)
                throw e
            }

            // Crea e avvia Server
            try {
                Log.d("SERVER_DEBUG", "🔧 Creando Server...")
                server = Server(wot!!, servient!!, applicationContext)
                Log.d("SERVER_DEBUG", "✅ Server creato")

                Log.d("SERVER_DEBUG", "🔧 Avviando Server...")
                server!!.start()
                Log.d("SERVER_DEBUG", "✅ Server avviato")
            } catch (e: Exception) {
                Log.e("SERVER_DEBUG", "❌ Errore creazione/avvio Server", e)
                throw e
            }

            isServerRunning = true
            setServerStarting(false)
            prefs.edit().putBoolean("server_started", true).commit()
            sendServiceStatusBroadcast()

            Log.d("SERVER_DEBUG", "🎉 SERVER COMPLETAMENTE AVVIATO!")

            // Log finale URLs
            if (enableHttp) {
                Log.d("SERVER", "📡 HTTP: http://$actualHostname:$port")
            }
            if (enableWebSocket) {
                Log.d("SERVER", "🔌 WebSocket: ws://$actualHostname:$webSocketPort")
            }
            if (enableMqtt) {
                val mqttHost = prefs.getString("mqtt_broker_host", "test.mosquitto.org")
                val mqttPort = prefs.getString("mqtt_broker_port", "1883")
                Log.d("SERVER", "📬 MQTT: $mqttHost:$mqttPort")
            }

        } catch (e: Exception) {
            Log.e("SERVER_DEBUG", "💥 ERRORE FATALE durante avvio server", e)
            Log.e("SERVER_DEBUG", "💥 Tipo errore: ${e::class.simpleName}")
            Log.e("SERVER_DEBUG", "💥 Messaggio: ${e.message}")
            e.printStackTrace()
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