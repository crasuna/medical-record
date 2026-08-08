package com.loveluke.medicalrecord.app.navigation

import android.graphics.Rect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Constraints
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavMetadataKey
import androidx.navigation3.runtime.get
import androidx.navigation3.runtime.metadata
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope

enum class MedicalPaneGroup {
    ENCOUNTER,
    MEDICATION,
}

private object ListPaneGroupKey : NavMetadataKey<MedicalPaneGroup>
private object DetailPaneGroupKey : NavMetadataKey<MedicalPaneGroup>

internal fun shouldRenderListDetail(
    isWide: Boolean,
    listGroup: MedicalPaneGroup?,
    detailGroup: MedicalPaneGroup?,
): Boolean = isWide && listGroup != null && listGroup == detailGroup

/** True for a compact, single-pane detail entry and false inside a wide list-detail scene. */
val LocalDetailBackNavigationVisible = compositionLocalOf { true }

data class MedicalListDetailScene<T : Any>(
    override val key: Any,
    override val previousEntries: List<NavEntry<T>>,
    val listEntry: NavEntry<T>,
    val detailEntry: NavEntry<T>,
    val separatingVerticalHingeBounds: Rect?,
) : Scene<T> {
    override val entries: List<NavEntry<T>> = listOf(listEntry, detailEntry)

    override val content: @Composable () -> Unit = {
        HingeAwareListDetailLayout(
            separatingVerticalHingeBounds = separatingVerticalHingeBounds,
            listPane = { listEntry.Content() },
            detailPane = {
                CompositionLocalProvider(LocalDetailBackNavigationVisible provides false) {
                    detailEntry.Content()
                }
            },
        )
    }

    companion object {
        fun listPane(group: MedicalPaneGroup) = metadata {
            put(com.loveluke.medicalrecord.app.navigation.ListPaneGroupKey, group)
        }

        fun detailPane(group: MedicalPaneGroup) = metadata {
            put(com.loveluke.medicalrecord.app.navigation.DetailPaneGroupKey, group)
        }
    }
}

/** Stable custom Navigation 3 strategy; no Material adaptive-navigation3 dependency is used. */
class MedicalListDetailSceneStrategy<T : Any>(
    private val isWide: Boolean,
    private val separatingVerticalHingeBounds: Rect?,
) : SceneStrategy<T> {
    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T>? {
        if (!isWide) return null

        val detailEntry = entries.lastOrNull() ?: return null
        val detailGroup = detailEntry.metadata[DetailPaneGroupKey]
            ?: return null
        val listEntry = entries.findLast { entry ->
            entry.metadata[ListPaneGroupKey] == detailGroup
        } ?: return null

        if (!shouldRenderListDetail(
                isWide = isWide,
                listGroup = listEntry.metadata[ListPaneGroupKey],
                detailGroup = detailGroup,
            )
        ) {
            return null
        }

        return MedicalListDetailScene(
            key = listEntry.contentKey,
            previousEntries = entries.dropLast(1),
            listEntry = listEntry,
            detailEntry = detailEntry,
            separatingVerticalHingeBounds = separatingVerticalHingeBounds,
        )
    }
}

@Composable
fun <T : Any> rememberMedicalListDetailSceneStrategy(
    isWide: Boolean,
    separatingVerticalHingeBounds: Rect?,
): MedicalListDetailSceneStrategy<T> = remember(
    isWide,
    separatingVerticalHingeBounds?.left,
    separatingVerticalHingeBounds?.right,
    separatingVerticalHingeBounds?.top,
    separatingVerticalHingeBounds?.bottom,
) {
    MedicalListDetailSceneStrategy(isWide, separatingVerticalHingeBounds?.let(::Rect))
}

internal data class PanePlacement(
    val listWidth: Int,
    val detailX: Int,
    val detailWidth: Int,
    val dividerX: Int?,
)

internal fun calculatePanePlacement(
    totalWidth: Int,
    rootWindowX: Int,
    dividerWidth: Int,
    hingeBounds: Rect?,
): PanePlacement {
    val localHingeLeft = hingeBounds?.left?.minus(rootWindowX)
    val localHingeRight = hingeBounds?.right?.minus(rootWindowX)
    val hingeIntersects = localHingeLeft != null && localHingeRight != null &&
        localHingeLeft in 1 until totalWidth && localHingeRight in 1 until totalWidth &&
        localHingeRight >= localHingeLeft
    if (hingeIntersects) {
        return PanePlacement(
            listWidth = localHingeLeft,
            detailX = localHingeRight,
            detailWidth = totalWidth - localHingeRight,
            dividerX = null,
        )
    }

    val safeDividerWidth = dividerWidth.coerceIn(0, totalWidth)
    val listWidth = ((totalWidth - safeDividerWidth) * 0.40f).toInt()
    return PanePlacement(
        listWidth = listWidth,
        detailX = listWidth + safeDividerWidth,
        detailWidth = totalWidth - listWidth - safeDividerWidth,
        dividerX = listWidth,
    )
}

@Composable
private fun HingeAwareListDetailLayout(
    separatingVerticalHingeBounds: Rect?,
    listPane: @Composable () -> Unit,
    detailPane: @Composable () -> Unit,
) {
    var positionInWindow by remember { mutableStateOf(Offset.Zero) }
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    Layout(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                positionInWindow = coordinates.positionInWindow()
            },
        content = {
            Box(Modifier.fillMaxSize()) { listPane() }
            Box(Modifier.fillMaxSize()) { detailPane() }
            Box(Modifier.fillMaxSize().background(dividerColor))
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val dividerWidth = 1.coerceAtMost(width)
        val placement = calculatePanePlacement(
            totalWidth = width,
            rootWindowX = positionInWindow.x.toInt(),
            dividerWidth = dividerWidth,
            hingeBounds = separatingVerticalHingeBounds,
        )
        val listPlaceable = measurables[0].measure(Constraints.fixed(placement.listWidth, height))
        val detailPlaceable = measurables[1].measure(Constraints.fixed(placement.detailWidth, height))
        val dividerPlaceable = placement.dividerX?.let {
            measurables[2].measure(Constraints.fixed(dividerWidth, height))
        }
        layout(width, height) {
            listPlaceable.placeRelative(0, 0)
            detailPlaceable.placeRelative(placement.detailX, 0)
            placement.dividerX?.let { dividerX -> dividerPlaceable?.placeRelative(dividerX, 0) }
        }
    }
}
