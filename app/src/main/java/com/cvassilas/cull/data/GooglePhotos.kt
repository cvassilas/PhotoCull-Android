package com.cvassilas.cull.data

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * OAuth via Google Play services. No client ID lives in the app: Google matches the
 * package name + signing SHA-1 against the Android OAuth client in your Cloud project.
 */
object GoogleAuth {
    private const val SCOPE = "https://www.googleapis.com/auth/photospicker.mediaitems.readonly"

    @Volatile
    var token: String? = null

    private fun request() = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(SCOPE)))
        .build()

    /** Returns a result that either has a token, or a PendingIntent the UI must launch for consent. */
    suspend fun authorize(context: Context): AuthorizationResult {
        val r = Identity.getAuthorizationClient(context).authorize(request()).await()
        r.accessToken?.let { token = it }
        return r
    }

    /** Silent refresh (tokens last an hour). Returns false if user interaction would be needed. */
    suspend fun refresh(context: Context): Boolean = runCatching {
        authorize(context).accessToken != null
    }.getOrDefault(false)
}

data class PickerSession(
    val id: String,
    val pickerUri: String,
    val itemsSet: Boolean,
    val pollMs: Long,
)

class GoogleApiException(val code: Int, msg: String) : Exception(msg)

/** Google Photos Picker API — the only way third-party apps can read a Photos library since 2025. */
object PickerApi {
    private const val BASE = "https://photospicker.googleapis.com/v1"
    private val json = "application/json".toMediaType()

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun authed(url: String) = Request.Builder().url(url)
        .header("Authorization", "Bearer ${GoogleAuth.token}")

    private suspend fun call(req: Request): JSONObject = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw GoogleApiException(res.code, "Google Photos ${res.code}: ${body.take(300)}")
            if (body.isBlank()) JSONObject() else JSONObject(body)
        }
    }

    private fun parseSession(o: JSONObject) = PickerSession(
        id = o.getString("id"),
        pickerUri = o.optString("pickerUri"),
        itemsSet = o.optBoolean("mediaItemsSet", false),
        pollMs = parseDuration(o.optJSONObject("pollingConfig")?.optString("pollInterval")) ?: 3000L,
    )

    /** "5s" / "3.5s" -> millis */
    private fun parseDuration(s: String?): Long? =
        s?.removeSuffix("s")?.toDoubleOrNull()?.let { (it * 1000).toLong().coerceIn(1000, 10_000) }

    suspend fun createSession(): PickerSession {
        val body = JSONObject().put("pickingConfig", JSONObject().put("maxItemCount", "2000"))
        return parseSession(call(authed("$BASE/sessions").post(body.toString().toRequestBody(json)).build()))
    }

    suspend fun getSession(id: String): PickerSession =
        parseSession(call(authed("$BASE/sessions/$id").get().build()))

    suspend fun deleteSession(id: String) {
        runCatching { call(authed("$BASE/sessions/$id").delete().build()) }
    }

    /** Photos the user picked (videos are skipped). */
    suspend fun listItems(sessionId: String): List<Photo> {
        val out = ArrayList<Photo>()
        var pageToken: String? = null
        do {
            var url = "$BASE/mediaItems?sessionId=$sessionId&pageSize=100"
            if (pageToken != null) url += "&pageToken=$pageToken"
            val o = call(authed(url).get().build())
            val items = o.optJSONArray("mediaItems")
            if (items != null) for (i in 0 until items.length()) {
                val it = items.getJSONObject(i)
                if (it.optString("type") == "VIDEO") continue
                val file = it.optJSONObject("mediaFile") ?: continue
                out += Photo(
                    id = "g:" + it.getString("id"),
                    source = Source.GOOGLE,
                    uri = null,
                    baseUrl = file.getString("baseUrl"),
                    takenAt = runCatching { Instant.parse(it.optString("createTime")).toEpochMilli() }.getOrDefault(0L),
                    name = file.optString("filename"),
                )
            }
            pageToken = o.optString("nextPageToken").ifBlank { null }
        } while (pageToken != null)
        return out.sortedByDescending { it.takenAt }
    }
}
