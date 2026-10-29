package com.cvassilas.cull.analysis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Size
import com.cvassilas.cull.data.CullUnit
import com.cvassilas.cull.data.GoogleAuth
import com.cvassilas.cull.data.Photo
import com.cvassilas.cull.data.PickerApi
import com.cvassilas.cull.data.Source
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.ln

/** Cheap per-photo fingerprint used for grouping and ranking. */
class Stats(
    val hash: Long,        // 64-bit difference hash (layout / shapes)
    val hist: FloatArray,  // 64-bin colour histogram, sums to 1 (palette — survives reframing)
    val sharpness: Double, // variance of the Laplacian — higher is sharper
    val exposure: Double,  // 0..1, 1 = well exposed, little clipping
)

/**
 * How eagerly photos are grouped.
 * [looseness] 0 = only near-identical bursts, 1 = anything from the same scene.
 * [windowMin] max minutes between neighbouring shots in a group.
 */
data class GroupSettings(val looseness: Float = 0.6f, val windowMin: Int = 30) {
    /** Minimum similarity score (0..1) for two photos to count as the same shot. */
    val threshold get() = 0.86f - looseness * 0.38f

    val label get() = when {
        looseness < 0.2f -> "Strict — near-identical bursts only"
        looseness < 0.45f -> "Normal — bursts and quick retakes"
        looseness < 0.75f -> "Loose — retakes with small reframing"
        else -> "Very loose — same scene, different angles"
    }

    companion object {
        val windows = listOf(2, 10, 30, 120, 720)
        fun windowLabel(m: Int) = when {
            m < 60 -> "$m min"
            m < 720 -> "${m / 60} h"
            else -> "Same day"
        }
    }
}

object Analyzer {
    /** How many recent group members a new photo is compared against. */
    private const val LOOKBACK = 6

    private val faceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.12f)
                .build()
        )
    }

    // ---------- image loading ----------

    private suspend fun thumb(context: Context, p: Photo, size: Int): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            when (p.source) {
                Source.DEVICE -> context.contentResolver.loadThumbnail(p.uri!!, Size(size, size), null)
                Source.GOOGLE -> {
                    val req = Request.Builder().url("${p.baseUrl}=w$size-h$size")
                        .header("Authorization", "Bearer ${GoogleAuth.token}").build()
                    PickerApi.http.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) null else r.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
                    }
                }
            }
        }.getOrNull()?.let { fit(it, size) }
    }

    private fun fit(src: Bitmap, size: Int): Bitmap {
        val b = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val long = maxOf(b.width, b.height)
        if (long <= size) return b
        val s = size.toFloat() / long
        return Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
    }

    private fun luma(b: Bitmap): IntArray {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        for (i in px.indices) {
            val c = px[i]
            px[i] = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        }
        return px
    }

    // ---------- per-photo stats ----------

    private fun dHash(b: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(b, 9, 8, true)
        val g = luma(small)
        var h = 0L
        var bit = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            if (g[y * 9 + x] > g[y * 9 + x + 1]) h = h or (1L shl bit)
            bit++
        }
        return h
    }

    private fun colourHist(b: Bitmap): FloatArray {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        val bins = FloatArray(64)
        for (c in px) {
            val r = ((c shr 16) and 0xFF) shr 6
            val g = ((c shr 8) and 0xFF) shr 6
            val bl = (c and 0xFF) shr 6
            bins[(r shl 4) or (g shl 2) or bl] += 1f
        }
        val n = px.size.coerceAtLeast(1).toFloat()
        for (i in bins.indices) bins[i] /= n
        return bins
    }

    private fun stats(b: Bitmap): Stats {
        val w = b.width
        val h = b.height
        val g = luma(b)
        // Laplacian variance
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val lap = (4 * g[i] - g[i - 1] - g[i + 1] - g[i - w] - g[i + w]).toDouble()
            sum += lap; sumSq += lap * lap; n++
        }
        val sharp = if (n == 0) 0.0 else sumSq / n - (sum / n) * (sum / n)
        // exposure
        var total = 0L
        var clipped = 0
        for (v in g) {
            total += v
            if (v < 6 || v > 249) clipped++
        }
        val mean = total.toDouble() / g.size / 255.0
        val clipFrac = clipped.toDouble() / g.size
        val exposure = (1.0 - abs(mean - 0.5) * 1.4 - clipFrac * 2.0).coerceIn(0.0, 1.0)
        return Stats(dHash(b), colourHist(b), sharp, exposure)
    }

    /** Fingerprints every photo (in parallel). */
    suspend fun analyze(
        context: Context,
        photos: List<Photo>,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Map<String, Stats> = coroutineScope {
        val gate = Semaphore(if (photos.firstOrNull()?.source == Source.GOOGLE) 6 else 4)
        val done = AtomicInteger()
        photos.map { p ->
            async(Dispatchers.Default) {
                val s = gate.withPermit { thumb(context, p, 256)?.let { stats(it) } }
                onProgress(done.incrementAndGet(), photos.size)
                s?.let { p.id to it }
            }
        }.awaitAll().filterNotNull().toMap()
    }

    // ---------- grouping ----------

    /** 0..1 — how alike two photos look. Mixes layout (hash) with colour palette (histogram). */
    fun similarity(a: Stats, b: Stats): Float {
        val ham = java.lang.Long.bitCount(a.hash xor b.hash)
        val layout = (1f - ham / 32f).coerceIn(0f, 1f)
        var colour = 0f
        for (i in 0 until 64) colour += minOf(a.hist[i], b.hist[i])
        return 0.6f * layout + 0.4f * colour
    }

    private fun similar(a: Photo, b: Photo, stats: Map<String, Stats>, s: GroupSettings): Boolean {
        if (abs(a.takenAt - b.takenAt) > s.windowMin * 60_000L) return false
        val sa = stats[a.id] ?: return false
        val sb = stats[b.id] ?: return false
        return similarity(sa, sb) >= s.threshold
    }

    /** Chains photos (in time order) into runs of look-alikes. Cheap — safe to rerun as settings change. */
    fun group(photos: List<Photo>, stats: Map<String, Stats>, settings: GroupSettings): List<List<Photo>> {
        val sorted = photos.sortedBy { it.takenAt }
        val groups = ArrayList<MutableList<Photo>>()
        for (p in sorted) {
            val cur = groups.lastOrNull()
            if (cur != null && cur.takeLast(LOOKBACK).any { similar(it, p, stats, settings) }) cur += p
            else groups += mutableListOf(p)
        }
        return groups
    }

    // ---------- best pick ----------

    /** Score 0..1 for faces: eyes open matters most, smiles a bit. Null if no faces found. */
    private suspend fun faceScore(context: Context, p: Photo): Double? {
        val b = thumb(context, p, 640) ?: return null
        val faces = runCatching { faceDetector.process(InputImage.fromBitmap(b, 0)).await() }.getOrNull()
        if (faces.isNullOrEmpty()) return null
        return faces.map { f ->
            val eyes = listOfNotNull(f.leftEyeOpenProbability, f.rightEyeOpenProbability)
                .map { it.toDouble() }.ifEmpty { listOf(0.5) }.average()
            val smile = f.smilingProbability?.toDouble() ?: 0.5
            eyes * 0.75 + smile * 0.25
        }.minOrNull() // the worst face sinks the shot — one person blinking ruins it
    }

    suspend fun bestIndex(context: Context, group: List<Photo>, stats: Map<String, Stats>): Int {
        val faces = group.map { faceScore(context, it) }
        val anyFaces = faces.any { it != null }
        val sharpLog = group.map { ln(1.0 + (stats[it.id]?.sharpness ?: 0.0)) }
        val maxSharp = sharpLog.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val scores = group.indices.map { i ->
            val sharp = sharpLog[i] / maxSharp
            val exp = stats[group[i].id]?.exposure ?: 0.5
            if (anyFaces) 0.45 * sharp + 0.15 * exp + 0.40 * (faces[i] ?: 0.0)
            else 0.75 * sharp + 0.25 * exp
        }
        return scores.indices.maxByOrNull { scores[it] } ?: 0
    }

    /** Groups already-fingerprinted photos and picks the best of each group. Returns deck units newest-first. */
    suspend fun buildUnits(
        context: Context,
        photos: List<Photo>,
        stats: Map<String, Stats>,
        settings: GroupSettings,
        onProgress: (label: String, fraction: Float) -> Unit,
    ): List<CullUnit> {
        val runs = group(photos, stats, settings)
        val multi = runs.count { it.size > 1 }
        var ranked = 0
        val units = runs.map { run ->
            if (run.size == 1) CullUnit.Single(run[0])
            else {
                onProgress("Picking the best of $multi groups", ranked.toFloat() / multi.coerceAtLeast(1))
                val best = bestIndex(context, run, stats)
                ranked++
                CullUnit.Group(run, best)
            }
        }
        return units.sortedByDescending { u ->
            when (u) {
                is CullUnit.Single -> u.photo.takenAt
                is CullUnit.Group -> u.photos.first().takenAt
            }
        }
    }
}
