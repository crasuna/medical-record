package com.loveluke.medicalrecord.app.navigation

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MedicalListDetailSceneTest {
    @Test
    fun compactWindowAlwaysUsesOnePane() {
        assertFalse(
            shouldRenderListDetail(
                isWide = false,
                listGroup = MedicalPaneGroup.ENCOUNTER,
                detailGroup = MedicalPaneGroup.ENCOUNTER,
            ),
        )
    }

    @Test
    fun wideWindowCombinesMatchingListAndDetailGroups() {
        assertTrue(
            shouldRenderListDetail(
                isWide = true,
                listGroup = MedicalPaneGroup.MEDICATION,
                detailGroup = MedicalPaneGroup.MEDICATION,
            ),
        )
    }

    @Test
    fun wideWindowDoesNotCombineEncounterAndMedicationGroups() {
        assertFalse(
            shouldRenderListDetail(
                isWide = true,
                listGroup = MedicalPaneGroup.ENCOUNTER,
                detailGroup = MedicalPaneGroup.MEDICATION,
            ),
        )
    }

    @Test
    fun ordinaryWideWindowUsesFortySixtySplitAroundDivider() {
        val placement = calculatePanePlacement(
            totalWidth = 1_000,
            rootWindowX = 0,
            dividerWidth = 1,
            hingeBounds = null,
        )

        assertEquals(399, placement.listWidth)
        assertEquals(400, placement.detailX)
        assertEquals(600, placement.detailWidth)
        assertEquals(399, placement.dividerX)
    }

    @Test
    fun separatingHingePartitionsPanesWithoutDrawingAcrossIt() {
        val placement = calculatePanePlacement(
            totalWidth = 1_000,
            rootWindowX = 100,
            dividerWidth = 1,
            hingeBounds = Rect(500, 0, 520, 1_000),
        )

        assertEquals(400, placement.listWidth)
        assertEquals(420, placement.detailX)
        assertEquals(580, placement.detailWidth)
        assertNull(placement.dividerX)
        assertTrue(placement.listWidth <= 400)
        assertTrue(placement.detailX >= 420)
    }
}
