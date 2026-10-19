package com.cvassilas.cull.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.cvassilas.cull.BinRed
import com.cvassilas.cull.KeepGreen
import com.cvassilas.cull.Muted
import com.cvassilas.cull.Paper
import com.cvassilas.cull.data.Decision
import com.cvassilas.cull.data.Photo
import com.cvassilas.cull.data.Source
import com.cvassilas.cull.data.request

@Composable
fun ReviewScreen(vm: CullViewModel) {
    val context = LocalContext.current
    val activity = context as Activity

    // Snapshot of what was binned when the screen opened, so rescued photos stay visible (greyed) to re-bin.
    val deviceList = remember(vm.units, vm.index) { vm.binned(Source.DEVICE) }
    val googleList = remember(vm.units, vm.index) { vm.binned(Source.GOOGLE) }
    val deviceBin = deviceList.count { vm.decisions[it.id] == Decision.BIN }
    val googleBin = googleList.count { vm.decisions[it.id] == Decision.BIN }
    val remaining = vm.units.size - vm.index

    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        vm.onTrashed(it.resultCode == Activity.RESULT_OK)
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        full {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Review", fontSize = 32.sp, fontWeight = FontWeight.Black)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("✓ ${vm.keptCount} kept", color = KeepGreen, fontWeight = FontWeight.Bold)
                    Text("✕ ${vm.binCount} to bin", color = BinRed, fontWeight = FontWeight.Bold)
                }
                if (remaining > 0) {
                    OutlinedButton(onClick = { vm.backToCull() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Back to swiping · $remaining left")
                    }
                }
                Text("Tap a photo to rescue it.", color = Muted, fontSize = 14.sp)
            }
        }

        // ---- device rejects ----
        if (deviceList.isNotEmpty()) {
            full { SectionTitle("On this phone", "$deviceBin to bin") }
            items(deviceList, key = { it.id }) { p -> Thumb(p, vm.decisions[p.id] == Decision.BIN) { vm.toggle(p.id) } }
            full {
                if (vm.deviceTrashed) {
                    Text(
                        "✓ Moved to the phone's bin. You can restore them from Google Photos' or Files' Bin for 30 days.",
                        color = KeepGreen, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    Button(
                        onClick = {
                            vm.trashRequest()?.let { trashLauncher.launch(IntentSenderRequest.Builder(it).build()) }
                        },
                        enabled = deviceBin > 0,
                        colors = ButtonDefaults.buttonColors(containerColor = BinRed, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth().height(52.dp).padding(top = 4.dp),
                    ) { Text("Move $deviceBin to bin", fontWeight = FontWeight.Bold) }
                }
            }
        }

        // ---- google rejects ----
        if (googleList.isNotEmpty()) {
            full { SectionTitle("Google Photos", "$googleBin to bin") }
            items(googleList, key = { it.id }) { p -> Thumb(p, vm.decisions[p.id] == Decision.BIN) { vm.toggle(p.id) } }
            full {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Text(
                        "Google only lets apps read your library, not delete from it. Open Google Photos and bin these — " +
                            "they're listed by date so they're quick to find.",
                        color = Muted, fontSize = 14.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val launch = context.packageManager.getLaunchIntentForPackage("com.google.android.apps.photos")
                                ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://photos.google.com"))
                            activity.startActivity(launch)
                        }) { Text("Open Google Photos") }
                        OutlinedButton(onClick = {
                            val text = googleList.filter { vm.decisions[it.id] == Decision.BIN }
                                .sortedBy { it.takenAt }
                                .joinToString("\n") { "${it.dateText()}  —  ${it.name}" }
                            activity.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                                    "Share bin list",
                                )
                            )
                        }) { Text("Share list") }
                    }
                }
            }
        }

        if (deviceList.isEmpty() && googleList.isEmpty()) {
            full { Text("Nothing binned — every photo was a keeper.", color = Muted, modifier = Modifier.padding(vertical = 24.dp)) }
        }

        full {
            Button(
                onClick = { vm.finish() },
                modifier = Modifier.fillMaxWidth().height(52.dp).padding(top = 12.dp),
            ) { Text("Done", fontWeight = FontWeight.Bold) }
        }
    }
}

private fun LazyGridScope.full(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun SectionTitle(title: String, sub: String) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Paper, modifier = Modifier.weight(1f))
        Text(sub, color = BinRed, fontSize = 14.sp)
    }
}

@Composable
private fun Thumb(p: Photo, binned: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black)
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = remember(p.id) { p.request(context, 320) },
            contentDescription = p.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(if (binned) 1f else 0.35f),
        )
        if (!binned) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.CheckCircle, null, tint = KeepGreen, modifier = Modifier.size(28.dp))
                Text("Kept", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
