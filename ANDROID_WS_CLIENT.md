## 安卓端接入说明：发现制谱器并通过 WebSocket 实时接收物件

> 本文面向 **Android 开发者**，描述如何在同一局域网内自动发现电脑上的 **RotaenoChartTool 制谱器**，并使用 Kotlin 建立 WebSocket 连接，接收当前显示区域的物件数据。

---

### 一、发现制谱器（局域网内自动发现）

制谱器端会在固定的 UDP 端口（例如 `55555`）上监听来自手机的“发现请求”，并通过 UDP 回复一个 JSON，里面包含可连接的 WebSocket 地址。

#### 1.1 UDP 协议约定

- **端口**
  - 手机 -> 制谱器：UDP 广播到 `255.255.255.255:55555` 或当前子网广播地址（例如 `192.168.0.255:55555`）。
  - 制谱器监听：`0.0.0.0:55555`。
- **请求报文（手机发送，UTF-8 文本）**
  - 内容固定字符串：
    - `"RotaenoChartTool_DISCOVER_V1"`
- **响应报文（制谱器发送，UTF-8 文本，JSON）**
  - 示例：
    ```json
    {
      "type": "discover_response",
      "name": "RotaenoChartTool",
      "version": "1.0",
      "ws_url": "ws://192.168.0.10:8080/ws",
      "http_info_url": "http://192.168.0.10:8008/api/info"
    }
    ```
  - 安卓只需关注：
    - `ws_url`: 用于建立 WebSocket 连接
    - 可选 `http_info_url`: 如果需要进一步获取项目信息，可通过 HTTP 请求访问。

#### 1.2 Kotlin 示例：发送发现请求并等待响应

> 下面示例使用标准 `java.net.DatagramSocket`，在 `Dispatchers.IO` 中运行。请根据你项目的协程结构调整。

```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

data class DiscoveredServer(
    val wsUrl: String,
    val infoUrl: String?,
    val name: String?,
    val version: String?
)

suspend fun discoverRotaenoServer(
    port: Int = 55555,
    timeoutMillis: Int = 2000
): DiscoveredServer? = withContext(Dispatchers.IO) {
    val discoverMessage = "RotaenoChartTool_DISCOVER_V1".toByteArray(Charsets.UTF_8)

    DatagramSocket().use { socket ->
        socket.broadcast = true
        socket.soTimeout = timeoutMillis

        // 发送广播
        val broadcastAddress = InetAddress.getByName("255.255.255.255")
        val packet = DatagramPacket(
            discoverMessage,
            discoverMessage.size,
            broadcastAddress,
            port
        )
        socket.send(packet)

        // 等待响应
        val buf = ByteArray(2048)
        val responsePacket = DatagramPacket(buf, buf.size)
        return@withContext try {
            socket.receive(responsePacket)
            val text = String(responsePacket.data, 0, responsePacket.length, Charsets.UTF_8)
            val json = JSONObject(text)
            val wsUrl = json.optString("ws_url", "")
            if (wsUrl.isBlank()) {
                null
            } else {
                DiscoveredServer(
                    wsUrl = wsUrl,
                    infoUrl = json.optString("http_info_url", null),
                    name = json.optString("name", null),
                    version = json.optString("version", null)
                )
            }
        } catch (e: SocketTimeoutException) {
            null // 超时未发现
        }
    }
}
```

> 提示：部分路由器/系统可能对广播有限制，如遇发现失败，可考虑让用户手动输入 IP 作为兜底方案。

---

### 二、建立 WebSocket 连接（Kotlin）

制谱器端 WebSocket 服务示例地址为：`ws://<server-ip>:8080/ws`，由上一步发现流程得到完整的 `ws_url`。  
安卓端可以使用 **OkHttp** 的 WebSocket 客户端来连接。

#### 2.1 Gradle 依赖

在 `build.gradle`（Module 级）中添加：

```gradle
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
```

#### 2.2 建立连接并接收消息

```kotlin
import android.util.Log
import okhttp3.*
import okio.ByteString

class ChartWebSocketClient(
    private val serverUrl: String,
    private val onFrameUpdate: (String) -> Unit,
    private val onConnected: () -> Unit = {},
    private val onDisconnected: () -> Unit = {},
    private val onError: (Throwable) -> Unit = {}
) {
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null

    fun connect() {
        val request = Request.Builder()
            .url(serverUrl) // 例如 ws://192.168.0.10:8080/ws
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d("ChartWebSocket", "Connected")
                onConnected()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                // 服务端会定期发送 JSON 文本，例如 {"type":"frame_update", ...}
                onFrameUpdate(text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                // 当前协议只预期文本消息，这里可以忽略或按需处理
                Log.d("ChartWebSocket", "Binary message: ${bytes.size} bytes")
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.d("ChartWebSocket", "Closing: $code / $reason")
                ws.close(code, reason)
                onDisconnected()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e("ChartWebSocket", "Error", t)
                onError(t)
                onDisconnected()
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "Client closing")
        webSocket = null
    }
}
```

使用示例：

```kotlin
// 假设前一步 discover 得到 wsUrl
val wsClient = ChartWebSocketClient(
    serverUrl = discoveredServer.wsUrl,
    onFrameUpdate = { json ->
        // 在这里解析 JSON 并更新 UI / 渲染层
        handleFrameUpdate(json)
    }
)

// 在合适的生命周期（例如 Activity/Fragment onStart）中：
wsClient.connect()

// 在 onStop/onDestroy 等时机中：
wsClient.disconnect()
```

---

### 三、解析服务端推送的数据

制谱器端在每帧（或最高 60 FPS，可配置）向所有已连接的客户端广播当前**实际在 3D 圆盘上会被渲染的物件**，并附带一份完整的谱面参数。  
当前服务端发送的 `frame_update` 结构示例如下（**字段名已与实现对齐**）：

```json
{
  "type": "frame_update",
  "timestamp": 12.345,          // 当前音乐时间（秒）
  "start_chart_time": 12345.0,  // 当前视图起始谱面时间（毫秒）
  "start_distance": 45678.9,    // 当前视图起始积分距离
  "cur_degree": 90.0,           // 当前 trail 角度
  "speed": 4.0,                 // 当前流速 slider 的值
  "notes": [
    {
      "id": 1,                  // 对应 Chart.note 中的原始下标
      "note_type": 0,           // 渲染用类型：0 Tap,1 Flick,2 小slide,3 大slide,4 Rotate,5 Catch,6 Bomb,11 Trail
      "time": 10000.0,          // 这个“渲染点”的谱面时间（毫秒）
      "distance": 1234.5,       // 对应的积分距离（由速度事件计算）
      "degree": 180.0,          // 在圆盘上的角度
      "delta": 0.0,             // 主要给 Rotate/Trail 用
      "radius_multiplier": 0.8, // 半径倍数，相对于 max_radius
      "kind": "tap",            // 语义类型：tap / flick / slide / rotate / catch / trail / bomb
      "base": {                 // 对应原始谱面 note 的完整参数（按类型不同而不同）
        "time": 10000.0,
        "degree": 180.0
      }
    }
  ]
}
```

> 说明：
> - `notes` 数组中的每一项都对应当前帧在 3D 圆盘中会被绘制的一个“渲染点”（包括 slide 拆分出来的中间点）。  
> - 如果需要在手机端做与 PC 端完全一致的轨迹/曲率/角度计算，可结合顶层的 `start_chart_time/start_distance/cur_degree/speed` 与 `base` 中的完整参数，直接复用同一套数学逻辑。

在安卓端可以定义对应的数据类并用 `kotlinx.serialization` 解析。

#### 3.1 数据类示例（kotlinx.serialization）

```kotlin
import kotlinx.serialization.Serializable

@Serializable
data class BaseNoteDto(
    val time: Float,
    val degree: Float,
    // Slide 专用
    val slidetype: Int? = null,
    val end_degree: Float? = null,
    val snap: Int? = null,
    val amount: Int? = null,
    val prev_curv: Float? = null,
    val next_curv: Float? = null,
    // Rotate / Trail 专用（delta/curv 在上面）
    val delta: Float? = null
)

@Serializable
data class NoteDto(
    val id: Int,              // Chart.note 下标
    val note_type: Int,       // 渲染用类型（0/1/2/3/4/5/6/11）
    val time: Float,          // 渲染点的谱面时间（ms）
    val distance: Float,      // 积分距离
    val degree: Float,        // 在圆盘上的角度
    val delta: Float,         // Rotate/Trail 用的 delta，其他类型通常为 0
    val radius_multiplier: Float, // 半径倍率
    val kind: String,         // "tap" / "flick" / "slide" / "rotate" / "catch" / "trail" / "bomb"
    val base: BaseNoteDto     // 原始谱面 note 的完整参数
)

@Serializable
data class FrameUpdateDto(
    val type: String,         // 固定为 "frame_update"
    val timestamp: Float,     // 当前音乐时间（秒）
    val start_chart_time: Float,
    val start_distance: Float,
    val cur_degree: Float,
    val speed: Float,
    val notes: List<NoteDto>
)
```

Gradle 依赖示例：

```gradle
plugins {
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.0"
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}
```

解析回调示例：

```kotlin
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

fun handleFrameUpdate(jsonString: String) {
    try {
        val frame = json.decodeFromString<FrameUpdateDto>(jsonString)
        if (frame.type == "frame_update") {
            // 这里你可以直接使用 frame 中的信息重建与 PC 一致的画面：
            // - 使用 frame.start_chart_time / start_distance / cur_degree / speed 作为全局视图状态
            // - 遍历 frame.notes，根据 note_type/distance/degree/radius_multiplier 还原圆盘上每个点的位置
            // - 如需进一步计算 slide / rotate / trail 的轨迹，可结合 base 中的完整参数自己做插值
        }
    } catch (e: Exception) {
        // 解析失败可忽略单帧，避免崩溃
        e.printStackTrace()
    }
}
```

---

### 四、生命周期与错误处理建议

- **网络权限**：
  - 在 `AndroidManifest.xml` 中添加：
    ```xml
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
    ```
  - 部分设备上，为了接收广播，可能需要开启多播（Multicast），可按需处理。
- **发现超时处理**：
  - `discoverRotaenoServer` 返回 `null` 时：
    - 可提示用户检查电脑和手机是否在同一 WiFi；
    - 提供“手动输入 IP 地址”的兜底方案。
- **自动重连策略**：
  - WebSocket 断线时，可在 `onError` / `onDisconnected` 中发起延迟重连；
  - 注意遵守合理的重试间隔，避免频繁请求。

---

### 五、与服务端的对齐

- WebSocket 地址（`ws_url`）、消息字段等，请与 PC 端实际实现保持同步。
- 如果后续服务端增加了“控制类消息”（例如从手机往制谱器发命令），可以在 `ChartWebSocketClient` 中增加 `sendCommand(jsonString: String)` 等接口，调用 `webSocket?.send(...)` 即可。


