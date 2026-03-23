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

        // 新格式：直接解析 http://ip:port/ 格式的 URL
        if (rawContent.startsWith("http://")) {
            try {
                // 移除 http:// 前缀
                var urlContent = rawContent.removePrefix("http://")

                // 移除路径和查询参数（保留 ip:port 部分）
                val pathIndex = urlContent.indexOf('/')
                if (pathIndex != -1) {
                    urlContent = urlContent.substring(0, pathIndex)
                }

                Log.d(TAG, "URL Content after cleanup: $urlContent")

                // 解析 ip:port
                if (urlContent.contains(":")) {
                    val parts = urlContent.split(":", limit = 2)
                    Log.d(TAG, "Split parts: ${parts.size} parts")
                    if (parts.size == 2) {
                        val ip = parts[0]
                        val portStr = parts[1]
                        val port = portStr.toIntOrNull()
                        Log.d(TAG, "Parsed IP: $ip")
                        Log.d(TAG, "Parsed Port: $port")

                        // 验证 IP 地址格式和端口号
                        if (isValidIPv4(ip) && port != null && port in 1..65535) {
                            Log.i(TAG, "=== QR Code Parsing Success ===")
                            Log.i(TAG, "IP: $ip, Port: $port")
                            return Pair(ip, port.toString())
                        } else {
                            Log.w(TAG, "Invalid IP address format or port number")
                            Log.w(TAG, "IP valid: ${isValidIPv4(ip)}, Port valid: ${port != null && port in 1..65535}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "URL parsing failed: ${e.message}")
                e.printStackTrace()
            }
        }

        Log.e(TAG, "=== QR Code Parsing Failed ===")
        Log.e(TAG, "Content doesn't match http://ip:port/ format")
        return Pair(null, null)
    }
}
