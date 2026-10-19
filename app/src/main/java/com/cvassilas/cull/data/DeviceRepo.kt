package com.cvassilas.cull.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore

data class Album(val id: Long, val name: String, val count: Int)

object DeviceRepo {
    private val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    fun albums(context: Context): List<Album> {
        val counts = LinkedHashMap<Long, Pair<String, Int>>()
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
            null, null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val name = c.getString(nameCol) ?: "Unnamed"
                val prev = counts[id]
                counts[id] = name to ((prev?.second ?: 0) + 1)
            }
        }
        return counts.map { (id, v) -> Album(id, v.first, v.second) }.sortedByDescending { it.count }
    }

    /** Photos newest first. [sinceMillis] null = all time; [albumId] null = every album. */
    fun photos(context: Context, sinceMillis: Long?, albumId: Long?): List<Photo> {
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (sinceMillis != null) {
            where += "${MediaStore.Images.Media.DATE_ADDED} >= ?"
            args += (sinceMillis / 1000).toString()
        }
        if (albumId != null) {
            where += "${MediaStore.Images.Media.BUCKET_ID} = ?"
            args += albumId.toString()
        }
        val out = ArrayList<Photo>()
        context.contentResolver.query(
            collection,
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DISPLAY_NAME,
            ),
            where.joinToString(" AND ").ifEmpty { null },
            args.toTypedArray().ifEmpty { null },
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val takenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val taken = c.getLong(takenCol).takeIf { it > 0 } ?: (c.getLong(addedCol) * 1000)
                out += Photo(
                    id = "d:$id",
                    source = Source.DEVICE,
                    uri = ContentUris.withAppendedId(collection, id),
                    baseUrl = null,
                    takenAt = taken,
                    name = c.getString(nameCol) ?: "",
                )
            }
        }
        return out
    }
}

/** Remembers which photos have already been through a cull, so they can be skipped next time. */
class ReviewedStore(context: Context) {
    private val prefs = context.getSharedPreferences("reviewed", Context.MODE_PRIVATE)

    fun all(): Set<String> = prefs.getStringSet("ids", emptySet()) ?: emptySet()

    fun add(ids: Collection<String>) {
        if (ids.isEmpty()) return
        prefs.edit().putStringSet("ids", all() + ids).apply()
    }

    fun remove(ids: Collection<String>) {
        if (ids.isEmpty()) return
        prefs.edit().putStringSet("ids", all() - ids.toSet()).apply()
    }

    fun clear() = prefs.edit().remove("ids").apply()
}
