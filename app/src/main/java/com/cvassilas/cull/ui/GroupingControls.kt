package com.cvassilas.cull.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cvassilas.cull.Ink
import com.cvassilas.cull.Muted
import com.cvassilas.cull.Panel
import com.cvassilas.cull.Paper
import com.cvassilas.cull.analysis.GroupSettings

/** Slider for how alike photos must be, plus the time window between shots. */
@Composable
fun GroupingControls(settings: GroupSettings, onChange: (GroupSettings) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Text("How similar", fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("Strict ← → Loose", fontSize = 12.sp, color = Muted)
        }
        Slider(
            value = settings.looseness,
            onValueChange = { onChange(settings.copy(looseness = it)) },
            colors = SliderDefaults.colors(thumbColor = Paper, activeTrackColor = Paper),
        )
        Text(settings.label, fontSize = 13.sp, color = Muted)

        Text("Max time between shots", fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GroupSettings.windows.forEach { m ->
                FilterChip(
                    selected = settings.windowMin == m,
                    onClick = { onChange(settings.copy(windowMin = m)) },
                    label = { Text(GroupSettings.windowLabel(m)) },
                )
            }
        }
    }
}

/** Bottom sheet on the swipe screen: tweak grouping and apply it to the photos not yet swiped. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupingSheet(vm: CullViewModel, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf(vm.groupSettings) }
    val preview = remember(draft) { vm.previewGrouping(draft) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Panel,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Grouping", fontSize = 22.sp, fontWeight = FontWeight.Black)
            GroupingControls(draft) { draft = it }
            Text(
                preview?.let { (groups, photos) ->
                    if (groups == 0) "No groups among the remaining photos at this setting"
                    else "$photos remaining photos would form $groups groups"
                } ?: "Photos will be compared when you apply this",
                fontSize = 14.sp,
                color = Paper,
            )
            Button(
                onClick = {
                    vm.regroup(draft)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Regroup remaining photos", fontWeight = FontWeight.Bold, color = Ink) }
            Text(
                "Photos you've already swiped keep their decisions.",
                fontSize = 12.sp, color = Muted,
            )
        }
    }
}
