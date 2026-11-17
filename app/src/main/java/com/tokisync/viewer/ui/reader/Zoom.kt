package com.tokisync.viewer.ui.reader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.IntSize

enum class PanMode { HORIZONTAL_ONLY, ALL }

/** 핀치 줌 상태. graphicsLayer(transformOrigin = 중앙) 기준 좌표계 */
@Stable
class ZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    val isZoomed: Boolean get() = scale > 1.01f

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    /** 더블탭: 확대 ↔ 원래대로 */
    fun toggle(tap: Offset, size: IntSize, target: Float = 2.5f) {
        if (isZoomed) {
            reset()
        } else {
            val center = Offset(size.width / 2f, size.height / 2f)
            scale = target
            offset = clamp((tap - center) * (1f - target), target, size)
        }
    }

    fun applyGesture(centroid: Offset, pan: Offset, zoomChange: Float, size: IntSize, maxScale: Float) {
        val newScale = (scale * zoomChange).coerceIn(1f, maxScale)
        val z = newScale / scale
        val center = Offset(size.width / 2f, size.height / 2f)
        // 손가락 중심점 아래의 내용이 그대로 손가락을 따라오도록
        val raw = centroid + pan - center - (centroid - center - offset) * z
        scale = newScale
        offset = clamp(raw, newScale, size)
        if (newScale <= 1.001f) offset = Offset.Zero
    }

    fun panBy(delta: Offset, size: IntSize) {
        offset = clamp(offset + delta, scale, size)
    }

    private fun clamp(o: Offset, s: Float, size: IntSize): Offset {
        val maxX = (s - 1f) * size.width / 2f
        val maxY = (s - 1f) * size.height / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }
}

/**
 * 두 손가락 핀치/팬을 처리. 한 손가락은 확대 상태에서만 이동에 사용하고,
 * 이동할 여지가 없으면 이벤트를 소비하지 않아 Pager/List 가 받도록 둡니다.
 */
fun Modifier.zoomGestures(state: ZoomState, panMode: PanMode, maxScale: Float = 5f): Modifier =
    pointerInput(state, panMode) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.count { it.pressed }
                if (pressed >= 2) {
                    val zoomChange = event.calculateZoom()
                    val pan = event.calculatePan()
                    val centroid = event.calculateCentroid(useCurrent = true)
                    if (centroid != Offset.Unspecified) {
                        state.applyGesture(centroid, pan, zoomChange, size, maxScale)
                    }
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                } else if (pressed == 1 && state.isZoomed) {
                    val pan = event.calculatePan()
                    val p = if (panMode == PanMode.HORIZONTAL_ONLY) Offset(pan.x, 0f) else pan
                    val before = state.offset
                    state.panBy(p, size)
                    if (panMode == PanMode.ALL && state.offset != before) {
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }

/** ZoomState 를 그리기에 반영 */
fun Modifier.zoomTransform(state: ZoomState): Modifier = graphicsLayer {
    scaleX = state.scale
    scaleY = state.scale
    translationX = state.offset.x
    translationY = state.offset.y
}
