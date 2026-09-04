package com.example.aicamera.filter

import org.junit.Assert.*
import org.junit.Test

/**
 * 系统预设滤镜纯 JVM 单元测试（不依赖 Android Bitmap/ColorMatrix）。
 */
class PresetFilterTest {

    @Test
    fun `all filters have unique ids`() {
        val ids = PresetFilter.ALL.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `original filter is first and has identity id`() {
        assertEquals(PresetFilter.Original, PresetFilter.ALL.first())
        assertEquals(FilterIds.ORIGINAL, PresetFilter.Original.id)
    }

    @Test
    fun `all filter ids are known by FilterIds`() {
        PresetFilter.ALL.forEach { filter ->
            assertTrue(
                "${filter.id} 应在 FilterIds.ALL 中",
                FilterIds.isKnown(filter.id)
            )
        }
    }

    @Test
    fun `fromId returns matching filter or original`() {
        assertEquals(PresetFilter.PortraitSoft, PresetFilter.fromId(FilterIds.PORTRAIT_SOFT))
        assertEquals(PresetFilter.FoodVivid, PresetFilter.fromId(FilterIds.FOOD_VIVID))
        assertEquals(PresetFilter.Original, PresetFilter.fromId(FilterIds.ORIGINAL))
        assertEquals(PresetFilter.Original, PresetFilter.fromId("unknown"))
    }

    @Test
    fun `non-original filters differ from original`() {
        val nonOriginal = PresetFilter.ALL - PresetFilter.Original
        assertTrue(nonOriginal.isNotEmpty())
        nonOriginal.forEach { filter ->
            assertNotEquals(PresetFilter.Original.id, filter.id)
        }
    }
}
