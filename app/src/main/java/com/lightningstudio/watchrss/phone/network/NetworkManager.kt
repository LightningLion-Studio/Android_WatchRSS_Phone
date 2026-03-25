package com.lightningstudio.watchrss.phone.network

import android.util.Log
import com.google.gson.Gson
import com.lightningstudio.watchrss.phone.model.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

object NetworkManager {
    private const val TAG = "WatchRSS_Network"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private var baseUrl: String = ""

    fun setBaseUrl(ip: String, port: Int) {
        baseUrl = "http://$ip:$port"
        Log.i(TAG, "=== Base URL Set ===")
        Log.i(TAG, "Base URL: $baseUrl")
        Log.i(TAG, "IP: $ip, Port: $port")
    }

    fun checkHealth(callback: (Boolean) -> Unit) {
        val url = "$baseUrl/health"
        val startTime = System.currentTimeMillis()

        Log.d(TAG, "=== Health Check Request ===")
        Log.d(TAG, "Method: GET")
        Log.d(TAG, "URL: $url")
        Log.d(TAG, "Timestamp: $startTime")

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val duration = System.currentTimeMillis() - startTime
                Log.e(TAG, "=== Health Check Failed ===")
                Log.e(TAG, "URL: $url")
                Log.e(TAG, "Duration: ${duration}ms")
                Log.e(TAG, "Error: ${e.message}")
                Log.e(TAG, "Exception: ${e.javaClass.simpleName}")
                e.printStackTrace()
                callback(false)
            }

            override fun onResponse(call: Call, response: Response) {
                val duration = System.currentTimeMillis() - startTime
                Log.d(TAG, "=== Health Check Response ===")
                Log.d(TAG, "URL: $url")
                Log.d(TAG, "Status Code: ${response.code}")
                Log.d(TAG, "Success: ${response.isSuccessful}")
                Log.d(TAG, "Duration: ${duration}ms")
                Log.d(TAG, "Response Headers: ${response.headers}")
                callback(response.isSuccessful)
            }
        })
    }

    fun getAbility(callback: (AbilityResponse?) -> Unit) {
        val url = "$baseUrl/getCurrentActivationAbility"
        val startTime = System.currentTimeMillis()

        Log.d(TAG, "=== Get Ability Request ===")
        Log.d(TAG, "Method: GET")
        Log.d(TAG, "URL: $url")
        Log.d(TAG, "Timestamp: $startTime")

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val duration = System.currentTimeMillis() - startTime
                Log.e(TAG, "=== Get Ability Failed ===")
                Log.e(TAG, "URL: $url")
                Log.e(TAG, "Duration: ${duration}ms")
                Log.e(TAG, "Error: ${e.message}")
                Log.e(TAG, "Exception: ${e.javaClass.simpleName}")
                e.printStackTrace()
                callback(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val duration = System.currentTimeMillis() - startTime
                Log.d(TAG, "=== Get Ability Response ===")
                Log.d(TAG, "URL: $url")
                Log.d(TAG, "Status Code: ${response.code}")
                Log.d(TAG, "Success: ${response.isSuccessful}")
                Log.d(TAG, "Duration: ${duration}ms")

                if (response.isSuccessful) {
                    val body = response.body?.string()
                    Log.d(TAG, "Response Body: $body")
                    val ability = gson.fromJson(body, AbilityResponse::class.java)
                    Log.d(TAG, "Parsed Ability: code=${ability?.code}, name=${ability?.name}, version=${ability?.version}")
                    callback(ability)
                } else {
                    Log.w(TAG, "Response not successful, returning null")
                    callback(null)
                }
            }
        })
    }

    fun postRSSUrl(url: String, callback: (RSSUrlResponse?) -> Unit) {
        val apiUrl = "$baseUrl/remoteEnterRSSURL"
        val startTime = System.currentTimeMillis()
        val json = gson.toJson(RSSUrlRequest(url))
        val body = json.toRequestBody("application/json".toMediaType())

        Log.d(TAG, "=== Post RSS URL Request ===")
        Log.d(TAG, "Method: POST")
        Log.d(TAG, "URL: $apiUrl")
        Log.d(TAG, "Content-Type: application/json")
        Log.d(TAG, "Request Body: $json")
        Log.d(TAG, "RSS URL Parameter: $url")
        Log.d(TAG, "Timestamp: $startTime")

        val request = Request.Builder()
            .url(apiUrl)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val duration = System.currentTimeMillis() - startTime
                Log.e(TAG, "=== Post RSS URL Failed ===")
                Log.e(TAG, "URL: $apiUrl")
                Log.e(TAG, "Request Body: $json")
                Log.e(TAG, "Duration: ${duration}ms")
                Log.e(TAG, "Error: ${e.message}")
                Log.e(TAG, "Exception: ${e.javaClass.simpleName}")
                e.printStackTrace()
                callback(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val duration = System.currentTimeMillis() - startTime
                Log.d(TAG, "=== Post RSS URL Response ===")
                Log.d(TAG, "URL: $apiUrl")
                Log.d(TAG, "Status Code: ${response.code}")
                Log.d(TAG, "Success: ${response.isSuccessful}")
                Log.d(TAG, "Duration: ${duration}ms")

                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    Log.d(TAG, "Response Body: $responseBody")
                    val result = gson.fromJson(responseBody, RSSUrlResponse::class.java)
                    Log.d(TAG, "Parsed Response: $result")
                    callback(result)
                } else {
                    Log.w(TAG, "Response not successful, returning null")
                    callback(null)
                }
            }
        })
    }

    fun getFavorites(callback: (FavoritesResponse?) -> Unit) {
        val url = "$baseUrl/getFavorites"
        val startTime = System.currentTimeMillis()

        Log.d(TAG, "=== Get Favorites Request ===")
        Log.d(TAG, "Method: GET")
        Log.d(TAG, "URL: $url")
        Log.d(TAG, "Timestamp: $startTime")

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val duration = System.currentTimeMillis() - startTime
                Log.e(TAG, "=== Get Favorites Failed ===")
                Log.e(TAG, "URL: $url")
                Log.e(TAG, "Duration: ${duration}ms")
                Log.e(TAG, "Error: ${e.message}")
                Log.e(TAG, "Exception: ${e.javaClass.simpleName}")
                e.printStackTrace()
                callback(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val duration = System.currentTimeMillis() - startTime
                Log.d(TAG, "=== Get Favorites Response ===")
                Log.d(TAG, "URL: $url")
                Log.d(TAG, "Status Code: ${response.code}")
                Log.d(TAG, "Success: ${response.isSuccessful}")
                Log.d(TAG, "Duration: ${duration}ms")

                if (response.isSuccessful) {
                    val body = response.body?.string()
                    Log.d(TAG, "Response Body: $body")
                    val result = gson.fromJson(body, FavoritesResponse::class.java)
                    Log.d(TAG, "Parsed Favorites: ${result?.data?.size ?: 0} items")
                    callback(result)
                } else {
                    Log.w(TAG, "Response not successful, returning null")
                    callback(null)
                }
            }
        })
    }

    // ── LLM 总结配置 API ─────────────────────────────────────────

    fun getLLMConfig(callback: (LLMConfigGetResponse?) -> Unit) {
        val url = "$baseUrl/getLLMSummaryConfig"
        val startTime = System.currentTimeMillis()

        Log.d(TAG, "=== Get LLM Config Request ===")
        Log.d(TAG, "Method: GET")
        Log.d(TAG, "URL: $url")
        Log.d(TAG, "Timestamp: $startTime")

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val duration = System.currentTimeMillis() - startTime
                Log.e(TAG, "=== Get LLM Config Failed ===")
                Log.e(TAG, "URL: $url")
                Log.e(TAG, "Duration: ${duration}ms")
                Log.e(TAG, "Error: ${e.message}")
                e.printStackTrace()
                callback(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val duration = System.currentTimeMillis() - startTime
                Log.d(TAG, "=== Get LLM Config Response ===")
                Log.d(TAG, "URL: $url")
                Log.d(TAG, "Status Code: ${response.code}")
                Log.d(TAG, "Success: ${response.isSuccessful}")
                Log.d(TAG, "Duration: ${duration}ms")

                if (response.isSuccessful) {
                    val body = response.body?.string()
                    Log.d(TAG, "Response Body: $body")
                    val result = gson.fromJson(body, LLMConfigGetResponse::class.java)
                    callback(result)
                } else {
                    Log.w(TAG, "Response not successful, returning null")
                    callback(null)
                }
            }
        })
    }

    fun postLLMConfig(config: LLMConfigRequest, callback: (LLMConfigResponse?) -> Unit) {
        val apiUrl = "$baseUrl/setLLMSummaryConfig"
        val startTime = System.currentTimeMillis()
        val json = gson.toJson(config)
        val body = json.toRequestBody("application/json".toMediaType())

        Log.d(TAG, "=== Post LLM Config Request ===")
        Log.d(TAG, "Method: POST")
        Log.d(TAG, "URL: $apiUrl")
        Log.d(TAG, "Content-Type: application/json")
        // 不打印完整 JSON，避免 apiKey 泄露
        Log.d(TAG, "Provider: ${config.provider}, Model: ${config.model}, Enabled: ${config.enabled}")
        Log.d(TAG, "Timestamp: $startTime")

        val request = Request.Builder()
            .url(apiUrl)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val duration = System.currentTimeMillis() - startTime
                Log.e(TAG, "=== Post LLM Config Failed ===")
                Log.e(TAG, "URL: $apiUrl")
                Log.e(TAG, "Duration: ${duration}ms")
                Log.e(TAG, "Error: ${e.message}")
                e.printStackTrace()
                callback(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val duration = System.currentTimeMillis() - startTime
                Log.d(TAG, "=== Post LLM Config Response ===")
                Log.d(TAG, "URL: $apiUrl")
                Log.d(TAG, "Status Code: ${response.code}")
                Log.d(TAG, "Success: ${response.isSuccessful}")
                Log.d(TAG, "Duration: ${duration}ms")

                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    Log.d(TAG, "Response Body: $responseBody")
                    val result = gson.fromJson(responseBody, LLMConfigResponse::class.java)
                    callback(result)
                } else {
                    Log.w(TAG, "Response not successful, returning null")
                    callback(null)
                }
            }
        })
    }

    fun getWatchLater(callback: (WatchLaterResponse?) -> Unit) {
        val url = "$baseUrl/getWatchlaterList"
        val startTime = System.currentTimeMillis()

        Log.d(TAG, "=== Get Watch Later Request ===")
        Log.d(TAG, "Method: GET")
        Log.d(TAG, "URL: $url")
        Log.d(TAG, "Timestamp: $startTime")

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val duration = System.currentTimeMillis() - startTime
                Log.e(TAG, "=== Get Watch Later Failed ===")
                Log.e(TAG, "URL: $url")
                Log.e(TAG, "Duration: ${duration}ms")
                Log.e(TAG, "Error: ${e.message}")
                Log.e(TAG, "Exception: ${e.javaClass.simpleName}")
                e.printStackTrace()
                callback(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val duration = System.currentTimeMillis() - startTime
                Log.d(TAG, "=== Get Watch Later Response ===")
                Log.d(TAG, "URL: $url")
                Log.d(TAG, "Status Code: ${response.code}")
                Log.d(TAG, "Success: ${response.isSuccessful}")
                Log.d(TAG, "Duration: ${duration}ms")

                if (response.isSuccessful) {
                    val body = response.body?.string()
                    Log.d(TAG, "Response Body: $body")
                    val result = gson.fromJson(body, WatchLaterResponse::class.java)
                    Log.d(TAG, "Parsed Watch Later: ${result?.data?.size ?: 0} items")
                    callback(result)
                } else {
                    Log.w(TAG, "Response not successful, returning null")
                    callback(null)
                }
            }
        })
    }
}

object QRCodeParser {
    private const val TAG = "WatchRSS_QRParser"

    /**
     * 验证是否是有效的 IPv4 地址
     */
    private fun isValidIPv4(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false

        return parts.all { part ->
            val num = part.toIntOrNull()
            num != null && num in 0..255
        }
    }

    fun parseQRCode(rawContent: String): Pair<String?, String?> {
        Log.d(TAG, "=== QR Code Parsing Started ===")
        Log.d(TAG, "Raw Content Length: ${rawContent.length}")
        Log.d(TAG, "Raw Content: $rawContent")
        Log.d(TAG, "Raw Content (hex): ${rawContent.toByteArray().joinToString(" ") { "%02x".format(it) }}")

        // 新格式：直接解析 http://ip:port/ 格式的 URL
        if (rawContent.startsWith("http://")) {
            Log.d(TAG, "✓ Detected http:// prefix")
            try {
                // 移除 http:// 前缀
                var urlContent = rawContent.removePrefix("http://")
                Log.d(TAG, "Step 1 - After removing 'http://': '$urlContent'")
                Log.d(TAG, "Step 1 - Length: ${urlContent.length}")

                // 移除路径和查询参数（保留 ip:port 部分）
                val pathIndex = urlContent.indexOf('/')
                Log.d(TAG, "Step 2 - First '/' found at index: $pathIndex")

                if (pathIndex != -1) {
                    val beforeCut = urlContent
                    urlContent = urlContent.substring(0, pathIndex)
                    Log.d(TAG, "Step 2 - Before cut: '$beforeCut'")
                    Log.d(TAG, "Step 2 - After cut: '$urlContent'")
                } else {
                    Log.d(TAG, "Step 2 - No '/' found, keeping full content")
                }

                Log.d(TAG, "Step 3 - Final URL content: '$urlContent'")

                // 解析 ip:port
                if (urlContent.contains(":")) {
                    Log.d(TAG, "Step 4 - Contains ':' separator")
                    val parts = urlContent.split(":", limit = 2)
                    Log.d(TAG, "Step 4 - Split result: ${parts.size} parts")

                    if (parts.size == 2) {
                        val ip = parts[0]
                        var portStr = parts[1]
                        Log.d(TAG, "Step 5 - IP string: '$ip'")
                        Log.d(TAG, "Step 5 - Port string (raw): '$portStr'")

                        // 移除 # 及其后面的内容（如 #同一WiFi#提示信息）
                        val hashIndex = portStr.indexOf('#')
                        if (hashIndex != -1) {
                            portStr = portStr.substring(0, hashIndex)
                            Log.d(TAG, "Step 5 - Port string (after removing #): '$portStr'")
                        }

                        val port = portStr.toIntOrNull()
                        Log.d(TAG, "Step 6 - Port parsed as int: $port")

                        // 验证 IP 地址格式和端口号
                        val ipValid = isValidIPv4(ip)
                        val portValid = port != null && port in 1..65535

                        Log.d(TAG, "Step 7 - IP validation: $ipValid")
                        Log.d(TAG, "Step 7 - Port validation: $portValid")

                        if (ipValid && portValid) {
                            Log.i(TAG, "=== QR Code Parsing Success ===")
                            Log.i(TAG, "✓ Final IP: $ip")
                            Log.i(TAG, "✓ Final Port: $port")
                            return Pair(ip, port.toString())
                        } else {
                            Log.w(TAG, "✗ Validation failed")
                            Log.w(TAG, "  - IP valid: $ipValid (value: '$ip')")
                            Log.w(TAG, "  - Port valid: $portValid (value: '$portStr', parsed: $port)")
                        }
                    } else {
                        Log.w(TAG, "✗ Split didn't produce 2 parts")
                    }
                } else {
                    Log.w(TAG, "✗ No ':' separator found in '$urlContent'")
                }
            } catch (e: Exception) {
                Log.e(TAG, "✗ Exception during parsing: ${e.message}")
                Log.e(TAG, "Exception type: ${e.javaClass.simpleName}")
                e.printStackTrace()
            }
        } else {
            Log.w(TAG, "✗ Content doesn't start with 'http://'")
            Log.w(TAG, "  Actual prefix: '${rawContent.take(10)}'")
        }

        Log.e(TAG, "=== QR Code Parsing Failed ===")
        Log.e(TAG, "Content doesn't match http://ip:port/ format")
        return Pair(null, null)
    }
}
