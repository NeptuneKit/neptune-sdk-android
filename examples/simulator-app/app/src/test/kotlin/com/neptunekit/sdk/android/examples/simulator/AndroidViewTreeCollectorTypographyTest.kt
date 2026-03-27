package com.neptunekit.sdk.android.examples.simulator

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidViewTreeCollectorTypographyTest {
    @Test
    fun convertsTypographyFromPxAndEmToDpWithExplicitUnitAndSourceMetadata() {
        val style = resolveTextTypographyStyle(
            textSizePx = 48f,
            lineHeightPx = 60,
            letterSpacingEm = 0.125f,
            density = 3f,
            platformFontScale = 1.25,
            fontWeightRaw = "style=1,fakeBold=false",
        )

        assertEquals("dp", style.typographyUnit)
        assertEquals("sp", style.sourceTypographyUnit)
        assertEquals(1.25, style.platformFontScale)
        assertEquals(16.0, style.fontSize)
        assertEquals(20.0, style.lineHeight)
        assertEquals(2.0, style.letterSpacing)
        assertEquals("style=1,fakeBold=false", style.fontWeightRaw)
    }

    @Test
    fun formatsRawFontWeightStringFromTypefaceFlags() {
        assertEquals("style=1,fakeBold=true", buildFontWeightRawString(typefaceStyle = 1, isFakeBoldText = true))
        assertEquals(null, buildFontWeightRawString(typefaceStyle = null, isFakeBoldText = false))
    }
}
