package com.cvassilas.cull

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cvassilas.cull.ui.CullScreen
import com.cvassilas.cull.ui.CullViewModel
import com.cvassilas.cull.ui.HomeScreen
import com.cvassilas.cull.ui.ReviewScreen
import com.cvassilas.cull.ui.Screen
import com.cvassilas.cull.ui.WaitingScreen
import com.cvassilas.cull.ui.WorkingScreen

val KeepGreen = Color(0xFF30A46C)
val BinRed = Color(0xFFE5484D)
val Ink = Color(0xFF101114)
val Panel = Color(0xFF1A1C21)
val Paper = Color(0xFFF4F1EA)
val Muted = Color(0xFF9BA1AC)

private val colors = darkColorScheme(
    primary = Paper,
    onPrimary = Ink,
    secondary = KeepGreen,
    error = BinRed,
    background = Ink,
    onBackground = Paper,
    surface = Ink,
    onSurface = Paper,
    surfaceVariant = Panel,
    onSurfaceVariant = Muted,
    surfaceContainer = Panel,
    surfaceContainerHigh = Panel,
    outline = Color(0xFF353943),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = colors) {
                App(viewModel())
            }
        }
    }
}

@Composable
private fun App(vm: CullViewModel) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .safeDrawingPadding()
    ) {
        when (val s = vm.screen) {
            Screen.Home -> HomeScreen(vm)
            is Screen.Working -> {
                BackHandler { vm.goHome() }
                WorkingScreen(s.label, s.progress)
            }
            Screen.WaitingForPicker -> {
                BackHandler { vm.cancelPicker() }
                WaitingScreen(onCancel = vm::cancelPicker)
            }
            Screen.Cull -> {
                BackHandler { vm.openReview() }
                CullScreen(vm)
            }
            Screen.Review -> {
                BackHandler { if (vm.index < vm.units.size) vm.backToCull() else vm.finish() }
                ReviewScreen(vm)
            }
        }
    }
}
