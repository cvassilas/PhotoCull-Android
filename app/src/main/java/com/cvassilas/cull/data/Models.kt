package com.cvassilas.cull.data

import android.content.Context
import android.net.Uri
import coil.request.ImageRequest

enum class Source { DEVICE, GOOGLE }

enum class Decision { KEEP, BIN }

/** One photo from either the phone (MediaStore) or Google Photos (Picker API). */
data class Photo(
    val id: String,
    val source: Source,
    /** content:// uri for device photos. */
    val uri: Uri?,
    /** Picker API base URL for Google photos (needs a size suffix and the OAuth header). */
    val baseUrl: String?,
    val takenAt: Long,
    val name: String,
)

/** What the swipe deck shows: a lone photo, or a burst of similar ones with a suggested best. */
sealed interface CullUnit {
    val key: String

    data class Single(val photo: Photo) : CullUnit {
        override val key get() = photo.id
    }

    data class Group(val photos: List<Photo>, val bestIndex: Int) : CullUnit {
        override val key get() = "g:" + photos.first().id
    }
}

/** Builds a Coil request for a photo at roughly [size] px on the long edge. */
fun Photo.request(context: Context, size: Int): ImageRequest {
    val b = ImageRequest.Builder(context).crossfade(120)
    return when (source) {
        Source.DEVICE -> b.data(uri).size(size).build()
        Source.GOOGLE -> b.data("$baseUrl=w$size-h$size")
            .addHeader("Authorization", "Bearer ${GoogleAuth.token}")
            .build()
    }
}
