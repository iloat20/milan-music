package com.milan.music.data.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 倒影的行序翻转。
 *
 * 这几条断言守的是"镜面线落在框底"这个几何前提：倒影是**整幅**封面的上下镜像，
 * 所以第一行必须改成本来最后一行，最后一行改成本来第一行；每一行**内部**的顺序不能动
 * （动了就成了"转 180 度"，不是镜像）；再翻一次必须回到原样。
 *
 * 红态探针：把 [reflectRows] 改成 `return pixels.copyOf(width * height)`，前四条全红。
 */
class ReflectionFlipTest {

    @Test
    fun firstRowBecomesLastRow() {
        // 2x2，每行一个可辨认的值：
        //   1 2
        //   3 4
        val flipped = reflectRows(intArrayOf(1, 2, 3, 4), width = 2, height = 2)

        assertArrayEquals(
            "2x2 的行序没有翻转（期望 3 4 / 1 2）",
            intArrayOf(3, 4, 1, 2),
            flipped,
        )
    }

    @Test
    fun columnsKeepTheirOrderInsideARow() {
        // 一行的宽度是 3，翻转后这一行内部必须还是 a b c，而不是 c b a ——
        // 后者是"旋转 180 度"，和"上下镜像"是两回事。
        val flipped = reflectRows(
            intArrayOf(
                10, 11, 12,
                20, 21, 22,
            ),
            width = 3,
            height = 2,
        )

        assertArrayEquals(
            "行内元素被倒序了（那是旋转 180 度，不是镜像）",
            intArrayOf(20, 21, 22, 10, 11, 12),
            flipped,
        )
    }

    @Test
    fun oddHeightMapsEachRowToItsMirror() {
        // 3 行、5 列：中间那行不动，首尾互换。宽度和高度不相等，索引算错就会串行。
        val source = intArrayOf(
            0, 0, 0, 0, 9,
            1, 1, 1, 1, 8,
            2, 2, 2, 2, 7,
        )
        val flipped = reflectRows(source, width = 5, height = 3)

        assertArrayEquals(
            "3 行的首尾没有互换，或中间行被挪动",
            intArrayOf(
                2, 2, 2, 2, 7,
                1, 1, 1, 1, 8,
                0, 0, 0, 0, 9,
            ),
            flipped,
        )
    }

    @Test
    fun flippingTwiceRestoresTheSource() {
        val source = IntArray(7 * 3) { it * 3 + 1 }

        assertArrayEquals(
            "翻转两次没有回到原图：行序公式不是对合的",
            source,
            reflectRows(reflectRows(source, 7, 3), 7, 3),
        )
    }

    @Test
    fun keepsThePixelCount() {
        assertEquals(7 * 3, reflectRows(IntArray(7 * 3), width = 7, height = 3).size)
    }

    @Test
    fun rejectsAnEmptyImage() {
        val failures = listOf(
            runCatching { reflectRows(IntArray(0), width = 0, height = 0) },
            runCatching { reflectRows(IntArray(4), width = 2, height = 0) },
        )

        failures.forEach { result ->
            assertEquals(
                "空图或零宽高必须直接失败，不能返回一张空倒影",
                IllegalArgumentException::class.java,
                result.exceptionOrNull()?.javaClass,
            )
        }
    }

    @Test
    fun rejectsABufferSmallerThanTheImage() {
        // 缩略图裁剪算错时会出现这种输入：宽高说 4x4，像素只有 4 个。
        // 静默补零会得到一张右下角全黑的倒影，必须在入口就炸掉。
        val result = runCatching { reflectRows(IntArray(4), width = 4, height = 4) }

        assertNotEquals("像素不够时不应该返回值", null, result.exceptionOrNull())
    }
}
