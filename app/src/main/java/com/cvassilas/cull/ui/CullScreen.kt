package com.cvassilas.cull.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.cvassilas.cull.BinRed
import com.cvassilas.cull.KeepGreen
import com.cvassilas.cull.Muted
import com.cvassilas.cull.Paper
import com.cvassilas.cull.data.CullUnit
import com.cvassilas.cull.data.Photo
import com.cvassilas.cull.data.Source
import com.cvassilas.cull.data.request
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy · HH:mm")

fun Photo.dateText(): String =
    if (takenAt <= 0) name else dateFmt.format(Instant.ofEpochMilli(takenAt).atZone(ZoneId.systemDefault()))

@Composable
fun CullScreen(vm: CullViewModel) {
    val unit = vm.current ?: return
    val next = vm.units.getOrNull(vm.index + 1)
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // Per-card state, reset whenever the top card changes.
    var dx by remember(unit.key) { mutableFloatStateOf(0f) }
    var leaving by remember(unit.key) { mutableStateOf(false) }
    val keepSet = remember(unit.key) {
        mutableStateListOf<String>().apply { if (unit is CullUnit.Group) add(unit.photos[unit.bestIndex].id) }
    }
    var focus by remember(unit.key) { mutableIntStateOf((unit as? CullUnit.Group)?.bestIndex ?: 0) }

    // Warm the cache for the next few cards so swiping never waits on the network.
    LaunchedEffect(vm.index) {
        vm.units.drop(vm.index + 1).take(4).forEach { u ->
            val p = when (u) {
                is CullUnit.Single -> u.photo
                is CullUnit.Group -> u.photos[u.bestIndex]
            }
            coil.Coil.imageLoader(context).enqueue(p.request(context, 1600))
        }
    }

    val widthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val threshold = widthPx * 0.28f

    fun commit(keep: Boolean) {
        if (leaving) return
        leaving = true
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            animate(dx, if (keep) widthPx * 1.6f else -widthPx * 1.6f, animationSpec = tween(170)) { v, _ -> dx = v }
            when (unit) {
                is CullUnit.Single -> vm.decideSingle(keep)
                is CullUnit.Group -> vm.decideGroup(if (keep) keepSet.toSet() else emptySet())
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // ---- top bar ----
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.goHome() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Home") }
            Text("${vm.index + 1} / ${vm.units.size}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            Text("✓ ${vm.keptCount}", color = KeepGreen, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
            Text("✕ ${vm.binCount}", color = BinRed, fontWeight = FontWeight.Bold)
            TextButton(onClick = { vm.openReview() }) { Text("Review") }
        }
        LinearProgressIndicator(
            progress = { vm.index.toFloat() / vm.units.size.coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth().height(3.dp),
            color = Paper,
            trackColor = Color(0xFF2A2D34),
        )

        // ---- deck ----
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // card underneath
            if (next != null) {
                val lift = (abs(dx) / threshold).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .fillMaxSize()
                        .scale(0.93f + 0.07f * lift)
                        .alpha(0.5f + 0.5f * lift)
                ) { CardFace(next, preview = true) }
            }

            // top card
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = dx
                        rotationZ = dx / widthPx * 10f
                    }
                    .pointerInput(unit.key) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (abs(dx) > threshold) commit(dx > 0)
                                else scope.launch { animate(dx, 0f, animationSpec = spring()) { v, _ -> dx = v } }
                            },
                            onDragCancel = { scope.launch { animate(dx, 0f, animationSpec = spring()) { v, _ -> dx = v } } },
                        ) { change, amount ->
                            if (!leaving) {
                                change.consume()
                                dx += amount
                            }
                        }
                    }
            ) {
                when (unit) {
                    is CullUnit.Single -> CardFace(unit, preview = false)
                    is CullUnit.Group -> GroupFace(
                        group = unit,
                        focus = focus,
                        keep = keepSet,
                        onFocus = { focus = it },
                        onToggle = { id -> if (id in keepSet) keepSet.remove(id) else keepSet.add(id) },
                    )
                }
                // swipe stamps
                val p = (dx / threshold).coerceIn(-1f, 1f)
                Stamp("KEEP", KeepGreen, Modifier.align(Alignment.TopStart).alpha(p.coerceAtLeast(0f)))
                Stamp("BIN", BinRed, Modifier.align(Alignment.TopEnd).alpha((-p).coerceAtLeast(0f)))
            }
        }

        // ---- buttons ----
        val group = unit as? CullUnit.Group
        Row(
            Modifier.fillMaxWidth().padding(bottom = 16.dp, top = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BigButton(
                color = BinRed,
                label = if (group != null) "Bin all" else "Bin",
                onClick = { commit(false) },
            ) { Icon(Icons.Filled.Close, null, Modifier.size(34.dp), tint = Color.White) }

            IconButton(onClick = { vm.undo() }, enabled = vm.index > 0, modifier = Modifier.size(52.dp)) {
                Icon(Icons.AutoMirrored.Filled.Undo, "Undo", tint = if (vm.index > 0) Paper else Muted)
            }

            BigButton(
                color = KeepGreen,
                label = if (group != null) "Keep ${keepSet.size}" else "Keep",
                onClick = { if (group != null && keepSet.isEmpty()) commit(false) else commit(true) },
            ) { Icon(Icons.Filled.Check, null, Modifier.size(34.dp), tint = Color.White) }
        }
    }
}

@Composable
private fun BigButton(color: Color, label: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(68.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = color),
        ) { icon() }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 13.sp, color = Muted)
    }
}

@Composable
private fun Stamp(text: String, color: Color, modifier: Modifier) {
    Text(
        text,
        modifier
            .padding(20.dp)
            .border(BorderStroke(3.dp, color), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        color = color,
        fontSize = 30.sp,
        fontWeight = FontWeight.Black,
    )
}

@Composable
private fun PhotoBox(photo: Photo, modifier: Modifier = Modifier, size: Int = 1600) {
    val context = LocalContext.current
    Box(modifier.clip(RoundedCornerShape(18.dp)).background(Color.Black)) {
        AsyncImage(
            model = remember(photo.id, size) { photo.request(context, size) },
            contentDescription = photo.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun CardFace(unit: CullUnit, preview: Boolean) {
    val photo = when (unit) {
        is CullUnit.Single -> unit.photo
        is CullUnit.Group -> unit.photos[unit.bestIndex]
    }
    Box(Modifier.fillMaxSize()) {
        PhotoBox(photo, Modifier.fillMaxSize())
        if (!preview) Caption(photo, Modifier.align(Alignment.BottomStart))
        if (unit is CullUnit.Group && preview) {
            Text(
                "${unit.photos.size} similar",
                Modifier.align(Alignment.TopCenter).padding(12.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 4.dp),
                color = Color.White, fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun Caption(photo: Photo, modifier: Modifier) {
    Row(
        modifier
            .padding(12.dp)
            .background(Color(0xAA000000), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (photo.source == Source.GOOGLE) {
            Icon(Icons.Filled.Cloud, null, Modifier.size(14.dp), tint = Color.White)
            Spacer(Modifier.width(6.dp))
        }
        Text(photo.dateText(), color = Color.White, fontSize = 13.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupFace(
    group: CullUnit.Group,
    focus: Int,
    keep: List<String>,
    onFocus: (Int) -> Unit,
    onToggle: (String) -> Unit,
) {
    val photo = group.photos[focus]
    val kept = photo.id in keep
    val strip = rememberLazyListState(initialFirstVisibleItemIndex = (group.bestIndex - 1).coerceAtLeast(0))

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "${group.photos.size} similar · keeping ${keep.size} · tap photo to toggle",
            color = Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp),
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(photo.id) { detectTapGestures(onTap = { onToggle(photo.id) }) }
        ) {
            PhotoBox(photo, Modifier.fillMaxSize().alpha(if (kept) 1f else 0.55f))
            Icon(
                if (kept) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                if (kept) "Keeping" else "Binning",
                Modifier.align(Alignment.TopEnd).padding(14.dp).size(36.dp)
                    .background(Color(0x66000000), CircleShape),
                tint = if (kept) KeepGreen else Color.White,
            )
            if (focus == group.bestIndex) {
                Row(
                    Modifier.align(Alignment.TopStart).padding(14.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Star, null, Modifier.size(16.dp), tint = Color(0xFFFFC53D))
                    Spacer(Modifier.width(4.dp))
                    Text("Best", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
            Caption(photo, Modifier.align(Alignment.BottomStart))
        }
        LazyRow(
            state = strip,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(horizontal = 2.dp),
        ) {
            itemsIndexed(group.photos, key = { _, p -> p.id }) { i, p ->
                val isKept = p.id in keep
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(
                            BorderStroke(if (i == focus) 3.dp else 0.dp, if (i == focus) Paper else Color.Transparent),
                            RoundedCornerShape(10.dp),
                        )
                        .combinedClickable(onClick = { onFocus(i) }, onLongClick = { onToggle(p.id) })
                ) {
                    PhotoBox(p, Modifier.fillMaxSize().alpha(if (isKept) 1f else 0.45f), size = 256)
                    if (isKept) Icon(
                        Icons.Filled.CheckCircle, null,
                        Modifier.align(Alignment.BottomEnd).padding(3.dp).size(18.dp),
                        tint = KeepGreen,
                    )
                    if (i == group.bestIndex) Icon(
                        Icons.Filled.Star, null,
                        Modifier.align(Alignment.TopStart).padding(3.dp).size(16.dp),
                        tint = Color(0xFFFFC53D),
                    )
                }
            }
        }
    }
}
