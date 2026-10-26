package com.ferousco.hydro.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ferousco.hydro.ui.theme.HydroColors
import com.ferousco.hydro.ui.theme.HydroDimens
import com.ferousco.hydro.ui.theme.HydroShapes
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class SheetDetent { Peek, Half, Full }

/** Tracks the sheet's visible height so map controls can float just above it. */
@Stable
class MapSheetState {
    var detent by mutableStateOf(SheetDetent.Peek)
        internal set

    /** Current visible height of the sheet in dp, updated while it is dragged. */
    var visibleHeight by mutableStateOf(HydroDimens.SheetPeekHeight)
        internal set

    fun expand() { detent = SheetDetent.Half }
    fun collapse() { detent = SheetDetent.Peek }
}

@Composable
fun rememberMapSheetState(): MapSheetState = remember { MapSheetState() }

/**
 * Rounded bottom sheet with three detents (peek, half, full). It is drawn over the map, never
 * replaces it. Only the handle area drags the sheet; the content scrolls independently once the
 * sheet is fully open.
 *
 */
@Composable
fun MapBottomSheet(
    state: MapSheetState,
    modifier: Modifier = Modifier,
    topInset: Dp = 72.dp,
    header: @Composable () -> Unit = {},
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    BoxWithConstraints(modifier = modifier) {
        val containerHeightPx = with(density) { maxHeight.toPx() }
        val topInsetPx = with(density) { topInset.toPx() }
        val peekPx = with(density) { HydroDimens.SheetPeekHeight.toPx() }
        val halfPx = containerHeightPx * 0.5f
        val fullPx = (containerHeightPx - topInsetPx).coerceAtLeast(halfPx)

        fun heightFor(detent: SheetDetent) = when (detent) {
            SheetDetent.Peek -> peekPx
            SheetDetent.Half -> halfPx
            SheetDetent.Full -> fullPx
        }

        val height = remember { Animatable(peekPx) }

        // Follow programmatic detent changes (e.g. expand after a search).
        androidx.compose.runtime.LaunchedEffect(state.detent, containerHeightPx, topInsetPx) {
            height.animateTo(heightFor(state.detent), tween(260, easing = FastOutSlowInEasing))
        }
        androidx.compose.runtime.LaunchedEffect(height.value) {
            state.visibleHeight = with(density) { height.value.toDp() }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(with(density) { height.value.toDp() })
                .shadow(8.dp, HydroShapes.Sheet, clip = false)
                .background(HydroColors.SnowWhite, HydroShapes.Sheet)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Drag handle: the only draggable region.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(HydroDimens.SheetHandleAreaHeight)
                        .semantics { contentDescription = "Drag to resize panel" }
                        .clickable(role = androidx.compose.ui.semantics.Role.Button) {
                            state.detent = when (state.detent) {
                                SheetDetent.Peek -> SheetDetent.Half
                                SheetDetent.Half -> SheetDetent.Full
                                SheetDetent.Full -> SheetDetent.Peek
                            }
                        }
                        .pointerInput(containerHeightPx) {
                            detectVerticalDragGestures(
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    scope.launch {
                                        height.snapTo((height.value - dragAmount).coerceIn(peekPx, fullPx))
                                    }
                                },
                                onDragEnd = {
                                    val target = SheetDetent.entries.minBy { abs(heightFor(it) - height.value) }
                                    state.detent = target
                                    scope.launch {
                                        height.animateTo(heightFor(target), tween(220, easing = FastOutSlowInEasing))
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(HydroDimens.SheetHandleWidth, HydroDimens.SheetHandleHeight)
                            .background(HydroColors.Hairline, RoundedCornerShape(2.dp))
                    )
                }
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = HydroDimens.ScreenEdge)) { header() }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = HydroDimens.ScreenEdge)
                ) {
                    content()
                }
            }
        }
    }
}
