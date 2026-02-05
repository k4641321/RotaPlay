package com.bnao.rotaplay

import android.util.Log
import android.webkit.JavascriptInterface
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * 与局域网内的 RotaenoChartTool 制谱器建立连接，并把状态和最新帧数据暴露给 JS。
 *
 * 暴露给 JS 的接口和现有模块保持一致：
 * - 简单字符串返回值
 * - 通过 WebView.addJavascriptInterface 注册
 *
 * JS 侧可通过 window.Androidcharttool 调用：
 * - discoverAndConnect(): string
 * - getConnectionState(): string
 * - getLatestFrameJson(): string
 * - disconnect(): void
 */
class ChartToolHelper {

    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null

    // 使用 AtomicReference 确保多线程读写安全
    private val connectionState = AtomicReference("disconnected")
    private val lastFrameJson = AtomicReference("")
    private val lastError = AtomicReference("")
    // 发现流程的详细调试日志，供前端页面查看
    private val discoverDebugLog = AtomicReference("")

    /**
     * JS 调用：发现局域网中的制谱器并尝试建立 WebSocket 连接。
     *
     * 返回值（字符串）：
     * - 发现并成功连接：返回 "ok:<ws_url>"
     * - 未发现：返回 "not_found"
     * - 发生错误：返回 "error:<message>"
     *
     * 说明：
     * - 调用会在 WebView 的 Binder 线程中执行，不阻塞主线程。
     */
    @Suppress("unused")
    @JavascriptInterface
    fun discoverAndConnect(): String {
        // 如果已有连接，先断开
        disconnectInternal()

        connectionState.set("discovering")
        lastError.set("")
        discoverDebugLog.set("")
        appendDiscoverLog("discoverAndConnect() called")

        val discoverMessage = "RotaenoChartTool_DISCOVER_V1".toByteArray(Charsets.UTF_8)

        return try {
            val discoveredWsUrl = discoverOnce(discoverMessage, 55555, 1000)
            if (discoveredWsUrl.isNullOrBlank()) {
                appendDiscoverLog("No server discovered (ws_url is blank)")
                connectionState.set("disconnected")
                "not_found"
            } else {
                appendDiscoverLog("Discovered ws_url: $discoveredWsUrl, start WebSocket connect")
                val ok = connectWebSocket(discoveredWsUrl)
                if (ok) {
                    appendDiscoverLog("WebSocket connect() invoked successfully")
                    "ok:$discoveredWsUrl"
                } else {
                    appendDiscoverLog("WebSocket connect() failed")
                    "error:connect_failed"
                }
            }
        } catch (e: Exception) {
            Log.e("ChartToolHelper", "discoverAndConnect error", e)
            lastError.set(e.message ?: "unknown_error")
            connectionState.set("error")
            appendDiscoverLog("Exception in discoverAndConnect: ${e.javaClass.simpleName}: ${e.message}")
            "error:${e.message}"
        }
    }

    /**
     * JS 调用：使用手动指定的 ws_url 直接连接（用于调试或发现失败兜底）。
     *
     * @param url 形如 ws://192.168.x.x:8080/ws
     * @return "ok" / "error:<message>"
     */
    @Suppress("unused")
    @JavascriptInterface
    fun connectWithUrl(url: String): String {
        disconnectInternal()
        lastError.set("")

        return try {
            val ok = connectWebSocket(url)
            if (ok) "ok" else "error:connect_failed"
        } catch (e: Exception) {
            Log.e("ChartToolHelper", "connectWithUrl error", e)
            lastError.set(e.message ?: "connect_exception")
            connectionState.set("error")
            "error:${e.message}"
        }
    }

    /**
     * JS 调用：获取当前连接状态。
     *
     * 可能的值：
     * - "disconnected"
     * - "discovering"
     * - "connecting"
     * - "connected"
     * - "closing"
     * - "error"
     */
    @Suppress("unused")
    @JavascriptInterface
    fun getConnectionState(): String {
        return connectionState.get()
    }

    /**
     * JS 调用：获取最近一次从制谱器收到的原始 JSON 文本。
     *
     * - 若尚未收到任何数据，返回空字符串 ""。
     * - JSON 格式与 ANDROID_WS_CLIENT.md 中的 frame_update 定义保持一致。
     */
    @Suppress("unused")
    @JavascriptInterface
    fun getLatestFrameJson(): String {
        return lastFrameJson.get()
    }

    /**
     * JS 调用：获取最近一次错误信息（若有）。
     *
     * - 若没有错误，返回空字符串 ""。
     */
    @Suppress("unused")
    @JavascriptInterface
    fun getLastError(): String {
        return lastError.get()
    }

    /**
     * JS 调用：获取最近一次“发现制谱器”流程的详细调试日志。
     *
     * - 多行文本，每行以时间戳前缀。
     * - 若尚未执行过发现流程，则可能为空字符串。
     */
    @Suppress("unused")
    @JavascriptInterface
    fun getDiscoverDebugLog(): String {
        return discoverDebugLog.get()
    }

    /**
     * JS 调用：主动断开当前 WebSocket 连接。
     */
    @Suppress("unused")
    @JavascriptInterface
    fun disconnect() {
        disconnectInternal()
    }

    private fun disconnectInternal() {
        connectionState.set("closing")
        try {
            webSocket?.close(1000, "Client closing")
        } catch (_: Exception) {
        } finally {
            webSocket = null
            if (connectionState.get() != "error") {
                connectionState.set("disconnected")
            }
        }
    }

    /**
     * 发送一次 UDP 广播并等待响应，返回发现到的 ws_url（或 null）。
     */
    private fun discoverOnce(
        discoverMessage: ByteArray,
        port: Int,
        timeoutMillis: Int
    ): String? {
        appendDiscoverLog("Starting UDP discover: port=$port, timeout=${timeoutMillis}ms")

        // 计算所有可能的广播地址：255.255.255.255 + 每个网络接口的广播地址
        val targets = mutableListOf<InetAddress>()
        try {
            targets.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {
        }
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            ifaces?.toList()?.forEach { nif ->
                if (!nif.isUp || nif.isLoopback) return@forEach
                nif.interfaceAddresses.forEach { ia ->
                    val broadcast = ia.broadcast
                    val addr = ia.address
                    if (broadcast != null && addr is Inet4Address) {
                        targets.add(broadcast)
                    }
                }
            }
        } catch (e: Exception) {
            appendDiscoverLog("Failed to enumerate network interfaces: ${e.javaClass.simpleName}: ${e.message}")
        }

        // 去重
        val uniqueTargets = targets.distinctBy { it.hostAddress }
        appendDiscoverLog("Broadcast targets: ${uniqueTargets.joinToString { it.hostAddress }}")

        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = timeoutMillis

            // 向所有候选广播地址发送一份报文
            uniqueTargets.forEach { addr ->
                val target = "$addr:$port"
                Log.d("ChartToolHelper", "Sending discover broadcast to $target")
                appendDiscoverLog("Sending broadcast to $target")
                try {
                    val packet = DatagramPacket(
                        discoverMessage,
                        discoverMessage.size,
                        addr,
                        port
                    )
                    socket.send(packet)
                } catch (e: Exception) {
                    appendDiscoverLog("Send failed to $target: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            appendDiscoverLog("All broadcasts sent, waiting for response...")

            val buf = ByteArray(2048)
            val responsePacket = DatagramPacket(buf, buf.size)
            return try {
                socket.receive(responsePacket)
                val text = String(responsePacket.data, 0, responsePacket.length, Charsets.UTF_8)
                Log.d("ChartToolHelper", "Discover response from ${responsePacket.address}: $text")
                appendDiscoverLog("Received response from ${responsePacket.address}: $text")
                val json = JSONObject(text)
                json.optString("ws_url", "").takeIf { it.isNotBlank() }
            } catch (e: SocketTimeoutException) {
                Log.w("ChartToolHelper", "Discover timeout, no response within $timeoutMillis ms")
                appendDiscoverLog("Timeout: no UDP response within ${timeoutMillis}ms")
                null
            } catch (e: Exception) {
                Log.e("ChartToolHelper", "Discover error", e)
                appendDiscoverLog("Discover exception: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }
    }

    /**
     * 基于 OkHttp 建立 WebSocket 连接。
     */
    private fun connectWebSocket(serverUrl: String): Boolean {
        connectionState.set("connecting")
        lastFrameJson.set("")

        return try {
            appendDiscoverLog("connectWebSocket() with url=$serverUrl")
            val request = Request.Builder()
                .url(serverUrl)
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    Log.d("ChartToolHelper", "WebSocket connected")
                    connectionState.set("connected")
                    appendDiscoverLog("WebSocket onOpen: connected, response code=${response.code}")
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    // 服务端会定期发送 JSON 文本，例如 {"type":"frame_update", ...}
                    lastFrameJson.set(text)
                }

                override fun onMessage(ws: WebSocket, bytes: ByteString) {
                    // 协议目前只预期文本，忽略二进制消息
                    Log.d("ChartToolHelper", "Binary message: ${bytes.size} bytes")
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    Log.d("ChartToolHelper", "Closing: $code / $reason")
                    connectionState.set("closing")
                    ws.close(code, reason)
                    connectionState.set("disconnected")
                    appendDiscoverLog("WebSocket onClosing: code=$code, reason=$reason")
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    Log.e("ChartToolHelper", "WebSocket error", t)
                    val msg = buildString {
                        append(t.javaClass.simpleName)
                        if (!t.message.isNullOrBlank()) {
                            append(": ")
                            append(t.message)
                        }
                        if (response != null) {
                            append(" (code=")
                            append(response.code)
                            append(")")
                        }
                    }
                    lastError.set(if (msg.isNotBlank()) msg else "websocket_error")
                    connectionState.set("error")
                    appendDiscoverLog("WebSocket onFailure: $msg")
                }
            })

            true
        } catch (e: Exception) {
            Log.e("ChartToolHelper", "connectWebSocket error", e)
            val msg = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
            lastError.set(if (msg.isNotBlank()) msg else "connect_exception")
            connectionState.set("error")
            appendDiscoverLog("connectWebSocket() exception: $msg")
            false
        }
    }

    private fun appendDiscoverLog(message: String) {
        val timestamp = System.currentTimeMillis()
        val line = "[$timestamp] $message"
        discoverDebugLog.updateAndGet { old ->
            if (old.isBlank()) line else "$old\n$line"
        }
    }
}


