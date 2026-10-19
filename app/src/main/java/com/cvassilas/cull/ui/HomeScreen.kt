package com.cvassilas.cull.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.cvassilas.cull.BinRed
import com.cvassilas.cull.Muted
import com.cvassilas.cull.Panel
import com.cvassilas.cull.Paper
import com.cvassilas.cull.data.GoogleAuth
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.launch

private data class Range(val label: String, val days: Int?)

private val ranges = listOf(
    Range("7 days", 7), Range("30 days", 30), Range("90 days", 90), Range("1 year", 365), Range("All", null),
)

private fun mediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun authErrorText(e: Throwable): String {
    val code = (e as? ApiException)?.statusCode
    return if (code == 10) {
        "Google sign-in isn't set up for this app yet (developer error 10). Check the Android OAuth client's package name and SHA-1 — see the README."
    } else {
        "Couldn't sign in to Google: ${e.message ?: e}"
    }
}

@Composable
fun HomeScreen(vm: CullViewModel) {
    val context = LocalContext.current
    val activity = context as Activity
    val scope = rememberCoroutineScope()

    var rangeIndex by rememberSaveable { mutableIntStateOf(1) }
    var albumId by rememberSaveable { mutableStateOf<Long?>(null) }
    var albumMenu by remember { mutableStateOf(false) }

    fun hasMedia() = mediaPermissions().any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(Unit) { if (hasMedia()) vm.loadAlbums() }

    fun startDevice() {
        val days = ranges[rangeIndex].days
        vm.startDevice(days?.let { System.currentTimeMillis() - it * 86_400_000L }, albumId)
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { it }) {
            vm.loadAlbums()
            startDevice()
        } else {
            vm.message = "Cull needs photo access to sort the photos on your phone"
        }
    }

    val openPicker: (String) -> Unit = { url ->
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(activity, Uri.parse(url))
    }

    val authLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        try {
            val r = Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(res.data)
            GoogleAuth.token = r.accessToken
            if (r.accessToken != null) vm.startGooglePicker(openPicker)
        } catch (e: Exception) {
            vm.message = authErrorText(e)
        }
    }

    fun startGoogle() {
        scope.launch {
            try {
                val r = GoogleAuth.authorize(activity)
                val pending = r.pendingIntent
                if (r.hasResolution() && pending != null) {
                    authLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                } else {
                    vm.startGooglePicker(openPicker)
                }
            } catch (e: Exception) {
                vm.message = authErrorText(e)
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Cull", fontSize = 40.sp, fontWeight = FontWeight.Black, color = Paper)
        Text(
            "Swipe right to keep, left to bin. Bursts of similar shots are grouped with the best one picked for you.",
            color = Muted, fontSize = 15.sp,
        )

        vm.message?.let {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Panel)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(it, Modifier.weight(1f), fontSize = 14.sp)
                TextButton(onClick = { vm.message = null }) { Text("OK") }
            }
        }

        // ---- device ----
        SourceCard(icon = { Icon(Icons.Filled.PhoneAndroid, null, tint = Paper) }, title = "On this phone") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ranges.forEachIndexed { i, r ->
                    FilterChip(selected = i == rangeIndex, onClick = { rangeIndex = i }, label = { Text(r.label) })
                }
            }
            Box {
                val albumName = vm.albums.firstOrNull { it.id == albumId }?.name ?: "All albums"
                OutlinedButton(onClick = { if (hasMedia()) albumMenu = true else permLauncher.launch(mediaPermissions()) }) {
                    Text("Album: $albumName")
                }
                DropdownMenu(expanded = albumMenu, onDismissRequest = { albumMenu = false }) {
                    DropdownMenuItem(text = { Text("All albums") }, onClick = { albumId = null; albumMenu = false })
                    vm.albums.forEach { a ->
                        DropdownMenuItem(
                            text = { Text("${a.name}  ·  ${a.count}") },
                            onClick = { albumId = a.id; albumMenu = false },
                        )
                    }
                }
            }
            Button(
                onClick = { if (hasMedia()) startDevice() else permLauncher.launch(mediaPermissions()) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Start culling", fontWeight = FontWeight.Bold) }
        }

        // ---- google ----
        SourceCard(icon = { Icon(Icons.Filled.Cloud, null, tint = Paper) }, title = "Google Photos") {
            Text(
                "Pick up to 2,000 photos in Google's picker (tap a date to select a whole day), then swipe through them here. " +
                    "Google doesn't let apps delete from your library, so you'll finish binning the rejects in Google Photos.",
                color = Muted, fontSize = 14.sp,
            )
            Button(
                onClick = { startGoogle() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Pick from Google Photos", fontWeight = FontWeight.Bold) }
        }

        // ---- options ----
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Panel)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ToggleRow("Group similar photos & pick the best", vm.groupSimilar) { vm.groupSimilar = it }
            ToggleRow("Skip photos I've already reviewed", vm.skipReviewed) { vm.skipReviewed = it }
            val n = remember(vm.screen) { vm.reviewedCount() }
            if (n > 0) {
                TextButton(onClick = { vm.resetReviewed() }, contentPadding = ButtonDefaults.TextButtonContentPadding) {
                    Text("Forget $n reviewed photos", color = BinRed)
                }
            }
        }
    }
}

@Composable
private fun SourceCard(icon: @Composable () -> Unit, title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Panel)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(10.dp))
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        content()
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 15.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun WorkingScreen(label: String, progress: Float?) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (progress == null) {
            CircularProgressIndicator(color = Paper)
        } else {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(6.dp), color = Paper)
        }
        Spacer(Modifier.height(20.dp))
        Text(label, fontSize = 16.sp, textAlign = TextAlign.Center)
        if (progress != null) Text("${(progress * 100).toInt()}%", color = Muted, fontSize = 14.sp)
    }
}

@Composable
fun WaitingScreen(onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Paper, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(20.dp))
        Text("Choose photos in Google Photos, then tap Done", fontSize = 17.sp, textAlign = TextAlign.Center)
        Text("This screen picks up automatically when you're back.", color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
}
