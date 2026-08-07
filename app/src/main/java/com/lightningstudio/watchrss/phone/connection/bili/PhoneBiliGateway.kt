package com.lightningstudio.watchrss.phone.connection.bili

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Stateless Bilibili gateway for the watch base station.
 *
 * Account material is supplied by the watch for each request and is never written to disk.
 * Responses deliberately contain only the fields needed by the RTOS UI so the transport and
 * Bilibili SDK can evolve independently.
 */
internal class PhoneBiliGateway(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()
) {
    fun execute(method: String, params: JSONObject, cookieHeader: String): JSONObject = when (method) {
        "auth.poll" -> WatchBiliLoginSession.watchResponse()
        "account.status" -> accountStatus(cookieHeader)
        "feed" -> feed(cookieHeader)
        "search" -> search(
            keyword = params.getString("keyword"),
            page = params.optInt("page", 1),
            cookieHeader = cookieHeader
        )
        "detail" -> detail(
            bvid = params.optString("bvid"),
            aid = params.optLong("aid", 0L),
            cookieHeader = cookieHeader
        )
        "comments" -> comments(
            aid = params.getLong("aid"),
            next = params.optLong("next", 0L),
            cookieHeader = cookieHeader
        )
        "interaction" -> interaction(
            aid = params.getLong("aid"),
            cookieHeader = cookieHeader
        )
        "shelf" -> shelf(
            kind = params.getString("kind"),
            page = params.optInt("page", 1),
            cookieHeader = cookieHeader
        )
        "action.like" -> like(
            aid = params.getLong("aid"),
            like = params.optBoolean("like", true),
            cookieHeader = cookieHeader
        )
        "action.coin" -> coin(
            aid = params.getLong("aid"),
            cookieHeader = cookieHeader
        )
        "action.favorite" -> favorite(
            aid = params.getLong("aid"),
            add = params.optBoolean("add", true),
            cookieHeader = cookieHeader
        )
        "action.watchLater" -> addToWatchLater(
            aid = params.optLong("aid", 0L),
            bvid = params.optString("bvid"),
            cookieHeader = cookieHeader
        )
        "play.resolve" -> resolvePlay(
            bvid = params.optString("bvid"),
            aid = params.optLong("aid", 0L),
            cid = params.getLong("cid"),
            cookieHeader = cookieHeader
        )
        else -> error("unsupported_method:$method")
    }

    internal fun requestQrCode(): JSONObject {
        val response = get("https://passport.bilibili.com/x/passport-login/web/qrcode/generate")
        val root = checkedJson(response.body)
        val data = root.getJSONObject("data")
        return JSONObject()
            .put("key", data.getString("qrcode_key"))
            .put("url", data.getString("url"))
    }

    internal fun pollQrCode(key: String): JSONObject {
        val url = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll"
            .toHttpUrl().newBuilder()
            .addQueryParameter("qrcode_key", key)
            .build().toString()
        val response = get(url)
        val root = checkedJson(response.body)
        val data = root.getJSONObject("data")
        val statusCode = data.optInt("code", -1)
        val status = when (statusCode) {
            0 -> "success"
            86038 -> "expired"
            86090 -> "scanned"
            86101 -> "pending"
            else -> "error"
        }
        val result = JSONObject()
            .put("status", status)
            .put("code", statusCode)
            .put("message", data.optString("message"))
        if (status == "success") {
            result.put("cookie", mergeSetCookies(response.setCookies))
            result.put("refreshToken", data.optString("refresh_token"))
        }
        return result
    }

    private fun accountStatus(cookieHeader: String): JSONObject {
        if (cookieHeader.isBlank()) return JSONObject().put("loggedIn", false)
        val response = get("https://api.bilibili.com/x/web-interface/nav", cookieHeader)
        val root = checkedJson(response.body)
        val data = root.optJSONObject("data") ?: JSONObject()
        return JSONObject()
            .put("loggedIn", data.optBoolean("isLogin", false))
            .put("name", data.optString("uname"))
            .put("face", data.optString("face"))
            .put("mid", data.optLong("mid", 0L))
    }

    private fun feed(cookieHeader: String): JSONObject {
        val url = "https://api.bilibili.com/x/web-interface/index/top/feed/rcmd"
            .toHttpUrl().newBuilder()
            .addQueryParameter("ps", "12")
            .addQueryParameter("fresh_type", "3")
            .build().toString()
        val root = checkedJson(get(url, cookieHeader).body)
        val items = root.optJSONObject("data")?.optJSONArray("item") ?: JSONArray()
        return JSONObject().put("items", compactItems(items))
    }

    private fun search(keyword: String, page: Int, cookieHeader: String): JSONObject {
        val url = "https://api.bilibili.com/x/web-interface/search/type"
            .toHttpUrl().newBuilder()
            .addQueryParameter("search_type", "video")
            .addQueryParameter("keyword", keyword)
            .addQueryParameter("page", page.coerceAtLeast(1).toString())
            .build().toString()
        val root = checkedJson(get(url, cookieHeader).body)
        val data = root.optJSONObject("data") ?: JSONObject()
        return JSONObject()
            .put("items", compactItems(data.optJSONArray("result") ?: JSONArray()))
            .put("page", data.optInt("page", page))
            .put("pages", data.optInt("numPages", page))
    }

    private fun detail(bvid: String, aid: Long, cookieHeader: String): JSONObject {
        val builder = "https://api.bilibili.com/x/web-interface/view".toHttpUrl().newBuilder()
        if (bvid.isNotBlank()) builder.addQueryParameter("bvid", bvid)
        else builder.addQueryParameter("aid", aid.toString())
        val data = checkedJson(get(builder.build().toString(), cookieHeader).body)
            .getJSONObject("data")
        val item = compactItem(data)
        val pages = JSONArray()
        val sourcePages = data.optJSONArray("pages") ?: JSONArray()
        for (index in 0 until sourcePages.length()) {
            val page = sourcePages.getJSONObject(index)
            pages.put(
                JSONObject()
                    .put("cid", page.optLong("cid"))
                    .put("page", page.optInt("page", index + 1))
                    .put("part", page.optString("part", "P${index + 1}"))
                    .put("duration", page.optInt("duration"))
            )
        }
        item.put("desc", data.optString("desc"))
        item.put("pages", pages)
        return item
    }

    private fun resolvePlay(
        bvid: String,
        aid: Long,
        cid: Long,
        cookieHeader: String
    ): JSONObject {
        val builder = "https://api.bilibili.com/x/player/playurl".toHttpUrl().newBuilder()
            .addQueryParameter("cid", cid.toString())
            .addQueryParameter("qn", "32")
            .addQueryParameter("fnval", "1")
            .addQueryParameter("platform", "html5")
        if (bvid.isNotBlank()) builder.addQueryParameter("bvid", bvid)
        else builder.addQueryParameter("avid", aid.toString())
        val data = checkedJson(get(builder.build().toString(), cookieHeader).body)
            .getJSONObject("data")
        val durl = data.optJSONArray("durl") ?: error("missing_durl")
        val first = durl.getJSONObject(0)
        return JSONObject()
            .put("url", first.getString("url"))
            .put("durationMs", first.optLong("length"))
            .put("size", first.optLong("size"))
            .put("quality", data.optInt("quality", 32))
            .put("referer", "https://www.bilibili.com/video/${bvid.ifBlank { "av$aid" }}")
    }

    private fun comments(aid: Long, next: Long, cookieHeader: String): JSONObject {
        val url = "https://api.bilibili.com/x/v2/reply/main"
            .toHttpUrl().newBuilder()
            .addQueryParameter("type", "1")
            .addQueryParameter("oid", aid.toString())
            .addQueryParameter("next", next.coerceAtLeast(0L).toString())
            .build().toString()
        val data = checkedJson(get(url, cookieHeader).body).getJSONObject("data")
        val output = JSONArray()
        val top = data.optJSONArray("top_replies") ?: JSONArray()
        val replies = data.optJSONArray("replies") ?: JSONArray()
        for (source in listOf(top, replies)) {
            for (index in 0 until source.length()) {
                val reply = source.optJSONObject(index) ?: continue
                val member = reply.optJSONObject("member") ?: JSONObject()
                val content = reply.optJSONObject("content") ?: JSONObject()
                output.put(
                    JSONObject()
                        .put("rpid", reply.optLong("rpid"))
                        .put("name", member.optString("uname"))
                        .put("avatar", member.optString("avatar"))
                        .put("message", content.optString("message").take(400))
                        .put("like", reply.optLong("like"))
                        .put("replies", reply.optInt("rcount"))
                        .put("time", reply.optLong("ctime"))
                )
                if (output.length() >= 12) break
            }
            if (output.length() >= 12) break
        }
        val cursor = data.optJSONObject("cursor") ?: JSONObject()
        return JSONObject()
            .put("items", output)
            .put("next", cursor.optLong("next", 0L))
            .put("isEnd", cursor.optBoolean("is_end", true))
    }

    private fun interaction(aid: Long, cookieHeader: String): JSONObject {
        if (cookieHeader.isBlank()) {
            return JSONObject().put("liked", false).put("coined", false).put("favorited", false)
        }
        fun endpoint(path: String): JSONObject {
            val url = "https://api.bilibili.com$path"
                .toHttpUrl().newBuilder()
                .addQueryParameter("aid", aid.toString())
                .build().toString()
            return checkedJson(get(url, cookieHeader).body)
        }
        val liked = endpoint("/x/web-interface/archive/has/like").optInt("data", 0) == 1
        val coined = endpoint("/x/web-interface/archive/coins")
            .optJSONObject("data")?.optInt("multiply", 0)?.let { it > 0 } ?: false
        val favorited = endpoint("/x/v2/fav/video/favoured")
            .optJSONObject("data")?.optBoolean("favoured", false) ?: false
        return JSONObject().put("liked", liked).put("coined", coined).put("favorited", favorited)
    }

    private fun shelf(kind: String, page: Int, cookieHeader: String): JSONObject {
        requireAccount(cookieHeader)
        return when (kind) {
            "history" -> historyShelf(cookieHeader)
            "later" -> laterShelf(cookieHeader)
            "favorite" -> favoriteShelf(page, cookieHeader)
            else -> error("unsupported_shelf:$kind")
        }
    }

    private fun historyShelf(cookieHeader: String): JSONObject {
        val url = "https://api.bilibili.com/x/web-interface/history/cursor"
            .toHttpUrl().newBuilder()
            .addQueryParameter("ps", "20")
            .build().toString()
        val data = checkedJson(get(url, cookieHeader).body).getJSONObject("data")
        val source = data.optJSONArray("list") ?: JSONArray()
        val output = JSONArray()
        for (index in 0 until source.length()) {
            val item = source.optJSONObject(index) ?: continue
            val history = item.optJSONObject("history") ?: JSONObject()
            output.put(
                JSONObject()
                    .put("aid", history.optLong("oid"))
                    .put("bvid", history.optString("bvid"))
                    .put("cid", history.optLong("cid"))
                    .put("title", item.optString("title"))
                    .put("cover", item.optString("cover"))
                    .put("owner", item.optString("author_name"))
                    .put("duration", item.optInt("duration"))
            )
        }
        return JSONObject().put("items", output).put("hasMore", false).put("page", 1)
    }

    private fun laterShelf(cookieHeader: String): JSONObject {
        val data = checkedJson(
            get("https://api.bilibili.com/x/v2/history/toview", cookieHeader).body
        ).getJSONObject("data")
        return JSONObject()
            .put("items", compactItems(data.optJSONArray("list") ?: JSONArray()))
            .put("hasMore", false)
            .put("page", 1)
    }

    private fun favoriteShelf(page: Int, cookieHeader: String): JSONObject {
        val mid = cookieValue(cookieHeader, "DedeUserID").toLongOrNull()
            ?: accountStatus(cookieHeader).optLong("mid").takeIf { it > 0 }
            ?: error("missing_mid")
        val foldersUrl = "https://api.bilibili.com/x/v3/fav/folder/created/list-all"
            .toHttpUrl().newBuilder()
            .addQueryParameter("up_mid", mid.toString())
            .build().toString()
        val folders = checkedJson(get(foldersUrl, cookieHeader).body)
            .optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        val folder = folders.optJSONObject(0) ?: error("missing_favorite_folder")
        val mediaId = folder.optLong("id", folder.optLong("fid"))
        val resourcesUrl = "https://api.bilibili.com/x/v3/fav/resource/list"
            .toHttpUrl().newBuilder()
            .addQueryParameter("media_id", mediaId.toString())
            .addQueryParameter("pn", page.coerceAtLeast(1).toString())
            .addQueryParameter("ps", "20")
            .build().toString()
        val data = checkedJson(get(resourcesUrl, cookieHeader).body).getJSONObject("data")
        val medias = data.optJSONArray("medias") ?: JSONArray()
        val output = JSONArray()
        for (index in 0 until medias.length()) {
            val item = medias.optJSONObject(index) ?: continue
            val upper = item.optJSONObject("upper") ?: JSONObject()
            output.put(
                JSONObject()
                    .put("aid", item.optLong("id"))
                    .put("bvid", item.optString("bvid", item.optString("bv_id")))
                    .put("cid", item.optLong("cid"))
                    .put("title", item.optString("title"))
                    .put("cover", item.optString("cover"))
                    .put("owner", upper.optString("name"))
                    .put("duration", item.optInt("duration"))
            )
        }
        return JSONObject()
            .put("items", output)
            .put("hasMore", data.optBoolean("has_more", false))
            .put("page", page.coerceAtLeast(1))
    }

    private fun like(aid: Long, like: Boolean, cookieHeader: String): JSONObject {
        action(
            url = "https://api.bilibili.com/x/web-interface/archive/like",
            fields = mapOf("aid" to aid.toString(), "like" to if (like) "1" else "2"),
            cookieHeader = cookieHeader
        )
        return JSONObject().put("liked", like)
    }

    private fun coin(aid: Long, cookieHeader: String): JSONObject {
        val result = action(
            url = "https://api.bilibili.com/x/web-interface/coin/add",
            fields = mapOf("aid" to aid.toString(), "multiply" to "1", "select_like" to "0"),
            cookieHeader = cookieHeader
        )
        return JSONObject().put("coined", true).put("liked", result.optJSONObject("data")?.optBoolean("like"))
    }

    private fun favorite(aid: Long, add: Boolean, cookieHeader: String): JSONObject {
        val mid = cookieValue(cookieHeader, "DedeUserID").toLongOrNull()
            ?: accountStatus(cookieHeader).optLong("mid").takeIf { it > 0 }
            ?: error("missing_mid")
        val foldersUrl = "https://api.bilibili.com/x/v3/fav/folder/created/list-all"
            .toHttpUrl().newBuilder()
            .addQueryParameter("up_mid", mid.toString())
            .build().toString()
        val folders = checkedJson(get(foldersUrl, cookieHeader).body)
            .optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        val folder = folders.optJSONObject(0) ?: error("missing_favorite_folder")
        val folderId = folder.optLong("id", folder.optLong("fid"))
        action(
            url = "https://api.bilibili.com/medialist/gateway/coll/resource/deal",
            fields = mapOf(
                "rid" to aid.toString(),
                "type" to "2",
                "add_media_ids" to if (add) folderId.toString() else "",
                "del_media_ids" to if (add) "" else folderId.toString()
            ),
            cookieHeader = cookieHeader
        )
        return JSONObject().put("favorited", add)
    }

    private fun addToWatchLater(aid: Long, bvid: String, cookieHeader: String): JSONObject {
        val fields = linkedMapOf<String, String>()
        if (aid > 0) fields["aid"] = aid.toString() else fields["bvid"] = bvid
        action(
            url = "https://api.bilibili.com/x/v2/history/toview/add",
            fields = fields,
            cookieHeader = cookieHeader
        )
        return JSONObject().put("added", true)
    }

    private fun compactItems(source: JSONArray): JSONArray = JSONArray().also { output ->
        for (index in 0 until minOf(source.length(), 20)) {
            output.put(compactItem(source.getJSONObject(index)))
        }
    }

    private fun compactItem(source: JSONObject): JSONObject {
        val owner = source.optJSONObject("owner")
        val stat = source.optJSONObject("stat")
        val id = source.optLong("id", source.optLong("aid"))
        val cid = source.optLong("cid")
        return JSONObject()
            .put("aid", id)
            .put("bvid", source.optString("bvid"))
            .put("cid", cid)
            .put("title", stripHtml(source.optString("title")))
            .put("cover", source.optString("pic", source.optString("cover")))
            .put("owner", owner?.optString("name") ?: source.optString("author"))
            .put("view", stat?.optLong("view") ?: source.optLong("play"))
            .put("like", stat?.optLong("like") ?: 0L)
            .put("danmaku", stat?.optLong("danmaku") ?: source.optLong("video_review"))
            .put("duration", source.optInt("duration"))
    }

    private fun get(url: String, cookieHeader: String = ""): HttpResult {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://www.bilibili.com/")
            .header("Accept", "application/json, text/plain, */*")
            .apply { if (cookieHeader.isNotBlank()) header("Cookie", cookieHeader) }
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("http_${response.code}")
            return HttpResult(body, response.headers.values("Set-Cookie"))
        }
    }

    private fun action(
        url: String,
        fields: Map<String, String>,
        cookieHeader: String
    ): JSONObject {
        requireAccount(cookieHeader)
        val csrf = cookieValue(cookieHeader, "bili_jct")
        if (csrf.isBlank()) error("missing_csrf")
        val body = FormBody.Builder().apply {
            fields.forEach { (name, value) -> add(name, value) }
            add("csrf", csrf)
        }.build()
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://www.bilibili.com/")
            .header("Origin", "https://www.bilibili.com")
            .header("Cookie", cookieHeader)
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("http_${response.code}")
            return checkedJson(text)
        }
    }

    private fun requireAccount(cookieHeader: String) {
        if (cookieHeader.isBlank()) error("login_required")
    }

    private fun cookieValue(cookieHeader: String, name: String): String = cookieHeader
        .split(';')
        .asSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')
        .orEmpty()

    private fun checkedJson(body: String): JSONObject {
        val root = JSONObject(body)
        val code = root.optInt("code", -1)
        if (code != 0) error("bili_${code}:${root.optString("message")}")
        return root
    }

    private fun mergeSetCookies(values: List<String>): String = values.mapNotNull { raw ->
        raw.substringBefore(';').takeIf { it.contains('=') }
    }.joinToString("; ")

    private fun stripHtml(value: String): String = value
        .replace(Regex("<[^>]+>"), "")
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
        .replace("&#39;", "'")

    internal data class HttpResult(val body: String, val setCookies: List<String>)

    private companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }
}
