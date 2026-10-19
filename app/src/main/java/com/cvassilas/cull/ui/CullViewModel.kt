package com.cvassilas.cull.ui

import android.app.Application
import android.content.IntentSender
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cvassilas.cull.analysis.Analyzer
import com.cvassilas.cull.data.Album
import com.cvassilas.cull.data.CullUnit
import com.cvassilas.cull.data.Decision
import com.cvassilas.cull.data.DeviceRepo
import com.cvassilas.cull.data.GoogleAuth
import com.cvassilas.cull.data.Photo
import com.cvassilas.cull.data.PickerApi
import com.cvassilas.cull.data.ReviewedStore
import com.cvassilas.cull.data.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data class Working(val label: String, val progress: Float? = null) : Screen
    data object WaitingForPicker : Screen
    data object Cull : Screen
    data object Review : Screen
}

class CullViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val reviewed = ReviewedStore(app)

    var screen by mutableStateOf<Screen>(Screen.Home)
        private set
    var message by mutableStateOf<String?>(null)

    // Home settings
    var groupSimilar by mutableStateOf(true)
    var skipReviewed by mutableStateOf(true)
    var albums by mutableStateOf<List<Album>>(emptyList())
        private set

    // Deck
    var units by mutableStateOf<List<CullUnit>>(emptyList())
        private set
    var index by mutableIntStateOf(0)
        private set
    val decisions = mutableStateMapOf<String, Decision>()
    private val history = ArrayList<Pair<Int, List<String>>>()

    // Review
    var deviceTrashed by mutableStateOf(false)
        private set

    private var work: Job? = null
    private var refreshJob: Job? = null
    private var pickerSessionId: String? = null

    val current: CullUnit? get() = units.getOrNull(index)
    val keptCount get() = decisions.values.count { it == Decision.KEEP }
    val binCount get() = decisions.values.count { it == Decision.BIN }

    fun allPhotos(): List<Photo> = units.flatMap {
        when (it) {
            is CullUnit.Single -> listOf(it.photo)
            is CullUnit.Group -> it.photos
        }
    }

    fun binned(source: Source) = allPhotos().filter { it.source == source && decisions[it.id] == Decision.BIN }

    fun reviewedCount() = reviewed.all().size

    fun resetReviewed() {
        reviewed.clear()
        message = "Forgot all previously reviewed photos"
    }

    // ---------- starting a cull ----------

    fun loadAlbums() {
        viewModelScope.launch(Dispatchers.IO) { albums = runCatching { DeviceRepo.albums(ctx) }.getOrDefault(emptyList()) }
    }

    private fun launchWork(block: suspend () -> Unit) {
        work?.cancel()
        work = viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: e.toString()
                screen = Screen.Home
            }
        }
    }

    fun startDevice(sinceMillis: Long?, albumId: Long?) = launchWork {
        endGoogleSession()
        screen = Screen.Working("Finding photos")
        val photos = withContext(Dispatchers.IO) { DeviceRepo.photos(ctx, sinceMillis, albumId) }
        prepare(photos)
    }

    /** [open] launches the picker URL in a Custom Tab (needs an Activity, so the UI supplies it). */
    fun startGooglePicker(open: (String) -> Unit) = launchWork {
        endGoogleSession()
        screen = Screen.Working("Connecting to Google Photos")
        val session = PickerApi.createSession()
        pickerSessionId = session.id
        screen = Screen.WaitingForPicker
        open(session.pickerUri.trimEnd('/') + "/autoclose")
        var s = session
        val deadline = System.currentTimeMillis() + 30 * 60_000L
        while (!s.itemsSet) {
            delay(s.pollMs)
            if (System.currentTimeMillis() > deadline) throw IllegalStateException("Timed out waiting for Google Photos selection")
            s = PickerApi.getSession(session.id)
        }
        screen = Screen.Working("Loading your picks")
        val photos = PickerApi.listItems(session.id)
        keepTokenFresh()
        prepare(photos)
    }

    fun cancelPicker() {
        work?.cancel()
        endGoogleSession()
        screen = Screen.Home
    }

    private suspend fun prepare(all: List<Photo>) {
        val skip = if (skipReviewed) reviewed.all() else emptySet()
        val photos = all.filter { it.id !in skip }
        if (photos.isEmpty()) {
            message = if (all.isEmpty()) "No photos found" else "All ${all.size} photos already reviewed"
            screen = Screen.Home
            return
        }
        units = if (groupSimilar && photos.size > 1) {
            screen = Screen.Working("Comparing photos", 0f)
            Analyzer.buildUnits(ctx, photos) { label, f -> screen = Screen.Working(label, f) }
        } else {
            photos.map { CullUnit.Single(it) }
        }
        index = 0
        decisions.clear()
        history.clear()
        deviceTrashed = false
        screen = Screen.Cull
    }

    private fun keepTokenFresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (true) {
                delay(40 * 60_000L)
                GoogleAuth.refresh(ctx)
            }
        }
    }

    private fun endGoogleSession() {
        refreshJob?.cancel()
        val id = pickerSessionId ?: return
        pickerSessionId = null
        CoroutineScope(Dispatchers.IO).launch { PickerApi.deleteSession(id) }
    }

    // ---------- swiping ----------

    fun decideSingle(keep: Boolean) {
        val u = current as? CullUnit.Single ?: return
        record(listOf(u.photo.id to if (keep) Decision.KEEP else Decision.BIN))
    }

    /** Keeps exactly [keepIds] from the current group and bins the rest. Empty set = bin all. */
    fun decideGroup(keepIds: Set<String>) {
        val u = current as? CullUnit.Group ?: return
        record(u.photos.map { it.id to if (it.id in keepIds) Decision.KEEP else Decision.BIN })
    }

    private fun record(list: List<Pair<String, Decision>>) {
        history += index to list.map { it.first }
        list.forEach { (id, d) -> decisions[id] = d }
        // Kept photos are "done" straight away, so a crash or exit doesn't lose them.
        reviewed.add(list.filter { it.second == Decision.KEEP }.map { it.first })
        index++
        if (index >= units.size) screen = Screen.Review
    }

    fun undo() {
        val (i, ids) = history.removeLastOrNull() ?: return
        ids.forEach { decisions.remove(it) }
        reviewed.remove(ids)
        index = i
        screen = Screen.Cull
    }

    fun toggle(id: String) {
        decisions[id] = if (decisions[id] == Decision.BIN) Decision.KEEP else Decision.BIN
    }

    fun openReview() {
        screen = Screen.Review
    }

    fun backToCull() {
        if (index < units.size) screen = Screen.Cull
    }

    fun goHome() {
        work?.cancel()
        screen = Screen.Home
    }

    // ---------- finishing ----------

    /** System dialog that moves the device rejects to the phone's bin (recoverable for 30 days). */
    fun trashRequest(): IntentSender? {
        val uris = binned(Source.DEVICE).mapNotNull { it.uri }
        if (uris.isEmpty()) return null
        return MediaStore.createTrashRequest(ctx.contentResolver, uris, true).intentSender
    }

    fun onTrashed(ok: Boolean) {
        if (ok) {
            deviceTrashed = true
            message = "Moved to the phone's bin"
        }
    }

    fun finish() {
        // Google rejects can't be deleted from here; mark them reviewed so they don't come round again.
        reviewed.add(decisions.keys.filter { it.startsWith("g:") })
        endGoogleSession()
        units = emptyList()
        decisions.clear()
        history.clear()
        index = 0
        screen = Screen.Home
    }

    override fun onCleared() {
        endGoogleSession()
    }
}
