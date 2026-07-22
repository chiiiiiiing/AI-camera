package com.genwow.camera.ui.theme

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTokenTest {
    @Test
    fun `brand primary no longer uses temporary camera cyan`() {
        assertEquals(0xFF4B3A62.toInt(), BrandPurple.toArgb())
        assertEquals(0xFFF4B6C2.toInt(), BrandPink.toArgb())
        assertNotEquals(0xFF63D8C6.toInt(), BrandPurple.toArgb())
    }

    @Test
    fun `logo gradient matches figma node 1 28`() {
        assertEquals(0xFFFFCCF2.toInt(), LogoGradientStart.toArgb())
        assertEquals(0xFF977DFF.toInt(), LogoGradientEnd.toArgb())
    }

    @Test
    fun `motion tokens stay within restrained interaction budget`() {
        assertTrue(GenWowMotion.PressMillis in 70..100)
        assertTrue(GenWowMotion.ModeChangeMillis in 180..220)
        assertTrue(GenWowMotion.PageEnterMillis in 200..280)
        assertTrue(GenWowMotion.AlignmentSettleMillis in 220..360)
    }
}
