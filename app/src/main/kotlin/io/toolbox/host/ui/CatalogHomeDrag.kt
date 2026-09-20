package io.toolbox.host.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.catalog.CatalogTool
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/** One pointer owner survives lazy-row recycling; targets are actual two-dimensional cell bounds. */
@Stable
internal class CatalogHomeDragState {
    internal val targets = mutableMapOf<String, HomeDragTarget>()
    var active by mutableStateOf<HomeDragTarget?>(null)
        private set
    var pointer by mutableStateOf(Offset.Zero)
        private set
    var targetKey by mutableStateOf<String?>(null)
        private set
    var listBounds = Rect.Zero
    private var grabOffset = Offset.Zero
    private var startPointer = Offset.Zero
    private var lastTarget: HomeDragTarget? = null
    var touchSlop = 0f
    var hasMoved by mutableStateOf(false)
        private set
    val collapsingGroups: Boolean get() = active?.collection == "groups"
    val floatingTopLeft: Offset get() = pointer - grabOffset

    fun start(position: Offset) {
        val hit = targets.values.firstOrNull { it.bounds.contains(position) } ?: return
        active = hit
        pointer = position
        startPointer = position
        hasMoved = false
        grabOffset = position - hit.bounds.topLeft
        targetKey = hit.key
        lastTarget = hit
    }

    fun move(position: Offset) {
        pointer = position
        if ((position - startPointer).getDistanceSquared() > touchSlop * touchSlop) hasMoved = true
        updateTarget()
    }

    fun updateTarget() {
        val source = active ?: return
        // Folding group bodies may move targets without any user drag.
        if (!hasMoved) return
        targets.values.asSequence()
            .filter { it.collection == source.collection && it.bounds.overlaps(listBounds) }
            .minByOrNull { (it.bounds.center - pointer).getDistanceSquared() }?.let {
                targetKey = it.key
                lastTarget = it
            }
    }

    fun canScroll(direction: Float, top: Float, bottom: Float): Boolean {
        val source = active ?: return false
        val visible = targets.values.filter { it.collection == source.collection }
        if (visible.isEmpty()) return false
        return if (direction < 0f) visible.none { it.index == 0 && it.bounds.top >= top }
        else visible.none { it.index == source.itemCount - 1 && it.bounds.bottom <= bottom }
    }

    fun finish() {
        val source = active
        val target = targets[targetKey] ?: lastTarget
        if (hasMoved && source != null && target != null && source.collection == target.collection) {
            val current = targets[source.key] ?: source
            val offset = target.index - current.index
            if (offset != 0) current.onMove(offset)
        }
        cancel()
    }

    fun cancel() { active = null; targetKey = null; lastTarget = null; hasMoved = false }
}

internal data class HomeDragTarget(
    val key: String,
    val collection: String,
    val index: Int,
    val label: String,
    val tool: CatalogTool?,
    val bounds: Rect,
    val onMove: (Int) -> Unit,
    val itemCount: Int = index + 1,
)

@Composable
internal fun rememberCatalogHomeDragState(
    editing: Boolean,
    listState: LazyListState,
    contentPadding: PaddingValues,
): CatalogHomeDragState {
    val state = remember { CatalogHomeDragState() }
    val density = LocalDensity.current
    state.touchSlop = LocalViewConfiguration.current.touchSlop
    val top = with(density) { contentPadding.calculateTopPadding().toPx() }
    val bottom = with(density) { contentPadding.calculateBottomPadding().toPx() }
    val edge = with(density) { 56.dp.toPx() }
    val speed = with(density) { 640.dp.toPx() }
    LaunchedEffect(editing) { if (!editing) state.cancel() }
    LaunchedEffect(state.active?.key) {
        if (state.active == null) return@LaunchedEffect
        var previousFrame = withFrameNanos { it }
        while (isActive && state.active != null) {
            val frame = withFrameNanos { it }
            val seconds = ((frame - previousFrame) / 1_000_000_000f).coerceAtMost(0.05f)
            previousFrame = frame
            val upper = state.listBounds.top + top
            val lower = state.listBounds.bottom - bottom
            val factor = when {
                state.pointer.y < upper + edge -> -((upper + edge - state.pointer.y) / edge).coerceIn(0f, 1f)
                state.pointer.y > lower - edge -> ((state.pointer.y - lower + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (state.hasMoved && factor != 0f && state.canScroll(factor, upper, lower)) {
                listState.scrollBy(speed * factor * seconds)
            }
            state.updateTarget()
        }
    }
    return state
}

internal fun Modifier.catalogDragSurface(state: CatalogHomeDragState, editing: Boolean): Modifier =
    onGloballyPositioned { state.listBounds = it.boundsInRoot() }
        .pointerInput(state, editing) {
            if (editing) detectDragGesturesAfterLongPress(
                onDragStart = { state.start(it + state.listBounds.topLeft) },
                onDrag = { change, _ ->
                    if (state.active != null) {
                        change.consume()
                        state.move(change.position + state.listBounds.topLeft)
                    }
                },
                onDragEnd = state::finish,
                onDragCancel = state::cancel,
            )
        }

@Composable
internal fun Modifier.catalogDragTarget(
    state: CatalogHomeDragState,
    key: String,
    collection: String,
    index: Int,
    itemCount: Int,
    label: String,
    tool: CatalogTool? = null,
    enabled: Boolean,
    onMove: (Int) -> Unit,
): Modifier {
    // Geometry must update in the placement callback. Reading a State only inside
    // SideEffect does not subscribe composition to later layout/scroll changes.
    val measured = remember(key) { arrayOf(Rect.Zero) }
    fun publish(bounds: Rect) {
        if (enabled && bounds != Rect.Zero) {
            state.targets[key] = HomeDragTarget(key, collection, index, label, tool, bounds, onMove, itemCount)
        } else state.targets.remove(key)
    }
    SideEffect {
        publish(measured[0])
    }
    DisposableEffect(state, key) { onDispose { state.targets.remove(key) } }
    val target = state.targetKey == key && state.active?.key != key
    return this
        .onGloballyPositioned {
            measured[0] = it.boundsInRoot()
            publish(measured[0])
        }
        .graphicsLayer { alpha = if (state.active?.key == key) 0.25f else 1f }
        .then(if (target) Modifier.border(2.dp, ToolBoxThemeTokens.colors.primary, RoundedCornerShape(16.dp)) else Modifier)
}

@Composable
internal fun BoxScope.CatalogDragPreview(state: CatalogHomeDragState) {
    val source = state.active ?: return
    val density = LocalDensity.current
    val offset = state.floatingTopLeft - state.listBounds.topLeft
    Box(
        Modifier.offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .width(with(density) { source.bounds.width.toDp() })
            .graphicsLayer { scaleX = 1.04f; scaleY = 1.04f; shadowElevation = 8.dp.toPx() }
            .background(ToolBoxThemeTokens.colors.surface, RoundedCornerShape(16.dp))
            .clearAndSetSemantics { },
    ) {
        if (source.tool != null) CatalogHomeTile(source.tool, editing = false, onOpen = {}, onOptions = {})
        else AppText(source.label, Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(16.dp),
            textStyle = ToolBoxThemeTokens.textStyles.title)
    }
}
