package io.mo.xiaoaiplug.config

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

data class McpTool(
    val serverId: String,
    val serverName: String,
    val name: String,
    val description: String,
    val inputSchema: JSONObject
)

data class HttpResponse(
    val body: JSONObject,
    val headers: Map<String, List<String>>
)

/**
 * MCP (Model Context Protocol) 远程客户端。
 * 支持 streamable HTTP 和 SSE 两种传输协议模式。
 */
object McpClient {
    private const val TAG = "McpClient"
    private val toolCache = ConcurrentHashMap<String, Pair<Long, List<McpTool>>>()
    private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 分钟缓存

    // 缓存已初始化的 Session ID: key 为 serverId, value 为 sessionId
    private val sessionMap = ConcurrentHashMap<String, String>()

    fun parseHeaders(raw: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        if (raw.isBlank()) return map
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val json = JSONObject(trimmed)
                for (key in json.keys()) {
                    map[key] = json.optString(key)
                }
                return map
            } catch (_: Throwable) {}
        }
        trimmed.lineSequence().forEach { line ->
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val k = line.substring(0, colonIdx).trim()
                val v = line.substring(colonIdx + 1).trim()
                if (k.isNotEmpty()) map[k] = v
            }
        }
        return map
    }

    fun clearCache(serverId: String? = null) {
        if (serverId != null) {
            toolCache.remove(serverId)
            sessionMap.remove(serverId)
        } else {
            toolCache.clear()
            sessionMap.clear()
        }
    }

    fun clearSession(serverId: String) {
        sessionMap.remove(serverId)
    }

    /**
     * 获取缓存的 MCP 工具列表。缓存超时或不存在时同步拉取。
     */
    fun getToolsCached(server: McpServerConfig): List<McpTool> {
        if (!server.enabled) return emptyList()
        val cached = toolCache[server.id]
        if (cached != null && (System.currentTimeMillis() - cached.first) < CACHE_TTL_MS) {
            return cached.second
        }
        return try {
            val fresh = listTools(server)
            toolCache[server.id] = System.currentTimeMillis() to fresh
            fresh
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to fetch tools for ${server.name}: ${t.message}")
            cached?.second ?: emptyList()
        }
    }

    /**
     * 向 MCP 服务端发起 tools/list 请求，获取可用工具列表。
     */
    fun listTools(server: McpServerConfig, timeoutMs: Int = 10000): List<McpTool> {
        val serverId = server.id
        val postUrl = if (server.transportType == McpServerConfig.TRANSPORT_SSE) {
            resolveSsePostUrl(server, timeoutMs)
        } else {
            server.url
        }

        val requestHeaders = parseHeaders(server.headers).toMutableMap()
        var sessionId = sessionMap[serverId]

        if (sessionId.isNullOrEmpty()) {
            // 1. initialize 握手
            val initReq = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "initialize")
                put("params", JSONObject().apply {
                    put("protocolVersion", "2024-11-05")
                    put("capabilities", JSONObject())
                    put("clientInfo", JSONObject().apply {
                        put("name", "XiaoAiPlug")
                        put("version", "1.0")
                    })
                })
            }
            val initResp = sendJsonRpcPost(postUrl, requestHeaders, initReq, timeoutMs)

            // 从响应头中提取 Mcp-Session-Id (大小写兼容)
            val headerEntry = initResp.headers.entries.firstOrNull {
                it.key?.equals("Mcp-Session-Id", ignoreCase = true) == true
            }
            val extractedSessionId = headerEntry?.value?.firstOrNull()

            if (!extractedSessionId.isNullOrEmpty()) {
                sessionId = extractedSessionId
                sessionMap[serverId] = extractedSessionId
            }

            // 2. notifications/initialized 通知
            val notifyReq = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("method", "notifications/initialized")
                put("params", JSONObject())
            }
            val notifyHeaders = requestHeaders.toMutableMap()
            if (!sessionId.isNullOrEmpty()) {
                notifyHeaders["Mcp-Session-Id"] = sessionId
            }
            try {
                sendJsonRpcPost(postUrl, notifyHeaders, notifyReq, timeoutMs)
            } catch (_: Throwable) {}
        }

        // 3. tools/list 请求
        val listHeaders = requestHeaders.toMutableMap()
        if (!sessionId.isNullOrEmpty()) {
            listHeaders["Mcp-Session-Id"] = sessionId
        }

        val listReq = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 2)
            put("method", "tools/list")
            put("params", JSONObject())
        }
        val listResp = sendJsonRpcPost(postUrl, listHeaders, listReq, timeoutMs)
        val listBody = listResp.body

        if (listBody.has("error")) {
            val errObj = listBody.optJSONObject("error")
            val errMsg = errObj?.optString("message") ?: listBody.optString("error")
            sessionMap.remove(serverId)
            throw IOException("tools/list error: $errMsg")
        }

        val result = listBody.optJSONObject("result")
            ?: throw IOException("Invalid tools/list response: missing result")
        val toolsArr = result.optJSONArray("tools") ?: JSONArray()

        val list = mutableListOf<McpTool>()
        for (i in 0 until toolsArr.length()) {
            val item = toolsArr.getJSONObject(i)
            val name = item.optString("name")
            val desc = item.optString("description", "")
            val schema = item.optJSONObject("inputSchema") ?: JSONObject().put("type", "object")
            if (name.isNotEmpty()) {
                list.add(McpTool(server.id, server.name, name, desc, schema))
            }
        }

        toolCache[server.id] = System.currentTimeMillis() to list
        return list
    }

    /**
     * 执行 MCP 工具 tools/call。
     */
    fun callTool(server: McpServerConfig, toolName: String, args: JSONObject, timeoutMs: Int = 30000): String {
        val serverId = server.id
        val postUrl = if (server.transportType == McpServerConfig.TRANSPORT_SSE) {
            resolveSsePostUrl(server, timeoutMs)
        } else {
            server.url
        }

        val requestHeaders = parseHeaders(server.headers).toMutableMap()
        var sessionId = sessionMap[serverId]

        if (sessionId.isNullOrEmpty()) {
            try {
                listTools(server, timeoutMs.coerceAtMost(10000))
                sessionId = sessionMap[serverId]
            } catch (_: Throwable) {}
        }

        if (!sessionId.isNullOrEmpty()) {
            requestHeaders["Mcp-Session-Id"] = sessionId
        }

        val callReq = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 3)
            put("method", "tools/call")
            put("params", JSONObject().apply {
                put("name", toolName)
                put("arguments", args)
            })
        }

        val callResp = sendJsonRpcPost(postUrl, requestHeaders, callReq, timeoutMs)
        val respBody = callResp.body

        if (respBody.has("error")) {
            val errObj = respBody.optJSONObject("error")
            val errMsg = errObj?.optString("message") ?: respBody.optString("error")
            sessionMap.remove(serverId)
            return "error: MCP tool call failed: $errMsg"
        }

        val result = respBody.optJSONObject("result") ?: return respBody.toString()
        val contentArr = result.optJSONArray("content")
        if (contentArr != null && contentArr.length() > 0) {
            val sb = StringBuilder()
            for (i in 0 until contentArr.length()) {
                val item = contentArr.getJSONObject(i)
                val type = item.optString("type", "text")
                if (type == "text") {
                    if (sb.isNotEmpty()) sb.append("\n")
                    sb.append(item.optString("text"))
                } else if (item.has("text")) {
                    if (sb.isNotEmpty()) sb.append("\n")
                    sb.append(item.optString("text"))
                }
            }
            if (sb.isNotEmpty()) return sb.toString()
        }

        return result.toString()
    }

    /**
     * SSE 协议处理: 发起 GET 请求连接 SSE，读取 endpoint 事件获取 POST 消息通道 URI。
     */
    private fun resolveSsePostUrl(server: McpServerConfig, timeoutMs: Int): String {
        val conn = URL(server.url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("Accept", "text/event-stream")
        val headers = parseHeaders(server.headers)
        for ((k, v) in headers) {
            conn.setRequestProperty(k, v)
        }

        val code = conn.responseCode
        if (code >= 400) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw IOException("SSE GET HTTP $code: $err")
        }

        var currentEvent = ""
        var endpointPath = ""

        conn.inputStream.bufferedReader().use { reader ->
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                val line = reader.readLine() ?: break
                val trimmed = line.trim()
                if (trimmed.startsWith("event:")) {
                    currentEvent = trimmed.substring(6).trim()
                } else if (trimmed.startsWith("data:")) {
                    val dataStr = trimmed.substring(5).trim()
                    if (currentEvent == "endpoint" || endpointPath.isEmpty()) {
                        endpointPath = dataStr
                        if (currentEvent == "endpoint") break
                    }
                } else if (trimmed.isEmpty()) {
                    if (endpointPath.isNotEmpty()) break
                    currentEvent = ""
                }
            }
        }

        if (endpointPath.isEmpty()) {
            throw IOException("SSE 未返回 endpoint 事件 (${server.url})")
        }

        return URI(server.url).resolve(endpointPath).toString()
    }

    private fun sendJsonRpcPost(
        targetUrl: String,
        headers: Map<String, String>,
        jsonPayload: JSONObject,
        timeoutMs: Int
    ): HttpResponse {
        val conn = URL(targetUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("Accept", "application/json, text/event-stream")
        if (!headers.keys.any { it.equals("Mcp-Protocol-Version", ignoreCase = true) }) {
            conn.setRequestProperty("Mcp-Protocol-Version", "2024-11-05")
        }
        for ((k, v) in headers) {
            conn.setRequestProperty(k, v)
        }

        val bodyBytes = jsonPayload.toString().toByteArray(Charsets.UTF_8)
        conn.outputStream.use { it.write(bodyBytes) }

        val code = conn.responseCode
        val isErr = code >= 400
        val stream = if (isErr) conn.errorStream else conn.inputStream
        val respStr = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

        if (isErr) {
            throw IOException("HTTP $code: $respStr")
        }

        val responseHeaders = conn.headerFields ?: emptyMap()

        val parsedJson = if (respStr.isBlank()) {
            JSONObject()
        } else if (respStr.startsWith("event:") || respStr.startsWith("data:")) {
            var jsonStr = "{}"
            for (line in respStr.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("data:")) {
                    val dataJson = trimmed.substring(5).trim()
                    if (dataJson.startsWith("{")) {
                        jsonStr = dataJson
                    }
                }
            }
            JSONObject(jsonStr)
        } else {
            JSONObject(respStr)
        }

        return HttpResponse(parsedJson, responseHeaders)
    }
}
