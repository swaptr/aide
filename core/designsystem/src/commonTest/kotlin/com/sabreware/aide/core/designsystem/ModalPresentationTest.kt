package com.sabreware.aide.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the sheet↔dialog decision. The cases are the real windows it has to get right, by their Material
 * size-class lower bounds — never a device type.
 */
class ModalPresentationTest {
    private fun adaptive(widthDp: Int, heightDp: Int) = modalPresentationFor(ModalPolicy.Adaptive, widthDp, heightDp)

    @Test
    fun phonePortraitIsASheet() = assertEquals(ModalPresentation.Sheet, adaptive(0, 480))

    @Test
    fun phoneLandscapeIsASheet() {
        // Wide (medium/expanded width) but compact height: the window that clipped a centered card.
        assertEquals(ModalPresentation.Sheet, adaptive(600, 0))
        assertEquals(ModalPresentation.Sheet, adaptive(840, 0))
    }

    @Test
    fun roomyWindowIsADialog() {
        assertEquals(ModalPresentation.Dialog, adaptive(600, 480))
        assertEquals(ModalPresentation.Dialog, adaptive(840, 900))
    }

    @Test
    fun dialogPolicyIsADialogAtEverySize() {
        for ((w, h) in listOf(0 to 0, 600 to 0, 0 to 480, 840 to 900)) {
            assertEquals(ModalPresentation.Dialog, modalPresentationFor(ModalPolicy.Dialog, w, h))
        }
    }
}
