package com.example.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class EmbeddedWebserver(
    private val port: Int = 8080,
    private val getSensorJson: () -> String
) {
    private var server: HttpServer? = null
    private val executor = Executors.newSingleThreadExecutor()

    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning = _isServerRunning.asStateFlow()

    private val _serverLogs = MutableStateFlow<List<String>>(emptyList())
    val serverLogs = _serverLogs.asStateFlow()

    private val _lastServedJson = MutableStateFlow("")
    val lastServedJson = _lastServedJson.asStateFlow()

    private val _lastReceivedJson = MutableStateFlow("")
    val lastReceivedJson = _lastReceivedJson.asStateFlow()

    fun start(): String {
        if (_isServerRunning.value) {
            return "O servidor já está em execução."
        }
        return try {
            val addr = InetSocketAddress(port)
            server = HttpServer.create(addr, 0).apply {
                // Route 1: Serve landing info page
                createContext("/", RootHandler())
                // Route 2: Serve active JSON telemetry
                createContext("/sensor-data", SensorDataHandler())
                // Route 3: Listen for incoming JSON POSTs to test receiving custom JSON on screen
                createContext("/post-data", PostDataHandler())
                
                executor = this@EmbeddedWebserver.executor
                start()
            }
            _isServerRunning.value = true
            addLog("Servidor local iniciado com sucesso na porta $port")
            "Success"
        } catch (e: Exception) {
            val errMsg = "Falha ao iniciar o servidor: ${e.message}"
            addLog(errMsg)
            errMsg
        }
    }

    fun stop() {
        try {
            server?.stop(0)
            server = null
            _isServerRunning.value = false
            addLog("Servidor local parado.")
        } catch (e: Exception) {
            addLog("Erro ao parar o servidor: ${e.message}")
        }
    }

    private fun addLog(message: String) {
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        val time = sdf.format(Date())
        val formattedLog = "[$time] $message"
        val current = _serverLogs.value.toMutableList()
        current.add(0, formattedLog) // Insert at top
        if (current.size > 50) {
            current.removeAt(current.size - 1)
        }
        _serverLogs.value = current
    }

    // Helper to get local IP addresses
    fun getLocalIpAddresses(): List<String> {
        val ips = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val element = interfaces.nextElement()
                val addresses = element.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        addr.hostAddress?.let { ips.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            addLog("Erro ao ler endereço IP: ${e.message}")
        }
        if (ips.isEmpty()) {
            ips.add("127.0.0.1")
        }
        return ips
    }

    // Handlers
    inner class RootHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            val method = exchange.requestMethod
            addLog("GET / de ${exchange.remoteAddress}")

            val html = """
                <!DOCTYPE html>
                <html>
                <head>
                    <title>Android IMU Webserver</title>
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <style>
                        body { font-family: -apple-system, sans-serif; background: #0f172a; color: #f8fafc; padding: 2rem; max-width: 600px; margin: 0 auto; }
                        h1 { color: #10b981; border-bottom: 2px solid #334155; padding-bottom: 0.5rem; }
                        .route-card { background: #1e293b; padding: 1rem; border-radius: 8px; margin: 1rem 0; box-shadow: 0 4px 6px -1px rgb(0 0 0 / 0.1); }
                        code { background: #0f172a; color: #38bdf8; padding: 0.2rem 0.4rem; border-radius: 4px; font-family: monospace; }
                        a { color: #10b981; }
                    </style>
                </head>
                <body>
                    <h1>IMU Embedded Hub</h1>
                    <p>The Android IMU Sensor server is running live on this device.</p>
                    <div class="route-card">
                        <h3>Fetch Sensor State:</h3>
                        <p>Perform a GET request to <a href="/sensor-data"><code>/sensor-data</code></a>.</p>
                    </div>
                    <div class="route-card">
                        <h3>Post Data to Screen:</h3>
                        <p>Perform a POST request containing JSON to <code>/post-data</code> to display raw text on screen.</p>
                    </div>
                </body>
                </html>
            """.trimIndent()

            val bytes = html.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.responseBody.close()
        }
    }

    inner class SensorDataHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            val method = exchange.requestMethod
            addLog("$method /sensor-data de ${exchange.remoteAddress}")

            val json = getSensorJson()
            _lastServedJson.value = json // Save raw JSON to flow

            val bytes = json.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            // Enable CORS so external network web interfaces can fetch this dynamically
            exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.responseBody.close()
        }
    }

    inner class PostDataHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            val method = exchange.requestMethod
            addLog("$method /post-data de ${exchange.remoteAddress}")

            if (method.equals("OPTIONS", ignoreCase = true)) {
                exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
                exchange.responseHeaders.set("Access-Control-Allow-Methods", "POST, GET, OPTIONS")
                exchange.responseHeaders.set("Access-Control-Allow-Headers", "Content-Type")
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
                return
            }

            if (method.equals("POST", ignoreCase = true)) {
                try {
                    val reader = BufferedReader(InputStreamReader(exchange.requestBody, Charsets.UTF_8))
                    val received = reader.use { it.readText() }
                    
                    _lastReceivedJson.value = received
                    addLog("POST ativo recebido. Bytes: ${received.length}")

                    val response = """{"status": "success", "received_bytes": ${received.length}}"""
                    val bytes = response.toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.set("Content-Type", "application/json")
                    exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                } catch (e: Exception) {
                    val errorResp = """{"status": "error", "message": "${e.message}"}"""
                    val bytes = errorResp.toByteArray(Charsets.UTF_8)
                    exchange.sendResponseHeaders(500, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                } finally {
                    exchange.responseBody.close()
                }
            } else {
                val response = """{"error": "Method not allowed. Use POST."}"""
                val bytes = response.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(405, bytes.size.toLong())
                exchange.responseBody.write(bytes)
                exchange.responseBody.close()
            }
        }
    }
}
