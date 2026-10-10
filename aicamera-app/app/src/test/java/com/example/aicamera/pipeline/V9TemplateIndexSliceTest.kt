package com.example.aicamera.pipeline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * V9TemplateIndex.sliceEmbedding 单元测试。
 *
 * 回归防护：修复前 `(i + 1 * dim)` 运算符优先级 bug 导致
 * i=2 时 copyOfRange 起止倒挂抛异常、索引加载永远失败（P0-1）。
 */
class V9TemplateIndexSliceTest {

    private val dim = 512
    private val count = 728

    /** 构造 count × dim 的扁平数组，值 = 元素下标（便于断言行内容） */
    private fun flatEmbeddings(): FloatArray =
        FloatArray(count * dim) { it.toFloat() }

    @Test
    fun `row 0 slices first dim elements`() {
        val row = V9TemplateIndex.sliceEmbedding(flatEmbeddings(), 0, dim)
        assertEquals(dim, row.size)
        assertEquals(0f, row.first())
        assertEquals((dim - 1).toFloat(), row.last())
    }

    @Test
    fun `row 1 slices correct offset`() {
        val row = V9TemplateIndex.sliceEmbedding(flatEmbeddings(), 1, dim)
        assertEquals(dim, row.size)
        assertEquals(dim.toFloat(), row.first())
        assertEquals((2 * dim - 1).toFloat(), row.last())
    }

    @Test
    fun `middle row slices correct offset`() {
        val row = V9TemplateIndex.sliceEmbedding(flatEmbeddings(), 100, dim)
        assertEquals(dim, row.size)
        assertEquals((100 * dim).toFloat(), row.first())
        assertArrayEquals(
            FloatArray(dim) { (100 * dim + it).toFloat() },
            row,
            0f
        )
    }

    @Test
    fun `last row slices to exact end`() {
        val row = V9TemplateIndex.sliceEmbedding(flatEmbeddings(), count - 1, dim)
        assertEquals(dim, row.size)
        assertEquals(((count - 1) * dim).toFloat(), row.first())
        assertEquals((count * dim - 1).toFloat(), row.last())
    }

    @Test
    fun `all 728 rows slice without exception (historical crash at row 2)`() {
        val flat = flatEmbeddings()
        for (i in 0 until count) {
            val row = V9TemplateIndex.sliceEmbedding(flat, i, dim)
            assertEquals("第 $i 行尺寸错误", dim, row.size)
            assertEquals("第 $i 行起始值错误", (i * dim).toFloat(), row.first())
        }
    }

    @Test
    fun `out of range row throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            V9TemplateIndex.sliceEmbedding(flatEmbeddings(), count, dim)
        }
    }

    @Test
    fun `negative row throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            V9TemplateIndex.sliceEmbedding(flatEmbeddings(), -1, dim)
        }
    }

    @Test
    fun `non positive dimension throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            V9TemplateIndex.sliceEmbedding(floatArrayOf(), 0, 0)
        }
    }

    @Test
    fun `row multiplication overflow is rejected before slicing`() {
        assertThrows(IllegalArgumentException::class.java) {
            V9TemplateIndex.sliceEmbedding(floatArrayOf(1f, 2f), Int.MAX_VALUE, 2)
        }
    }
}
