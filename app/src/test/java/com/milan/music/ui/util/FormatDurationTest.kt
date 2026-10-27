package com.milan.music.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * 播放时间标签必须与系统 locale 无关。
 *
 * 这一条守的不是"好看"，是两个真实后果：
 *
 *  1. `ar`/`fa`/`bn` 这类 locale 下 `%d` 会输出本地数字（`٣:٤٥`）；
 *  2. 本地数字的字符宽度与拉丁数字不同，进度条两端的两枚标签会随秒数跳动**逐秒左右微移**，
 *     而 `ar` 下冒号的位置也会跟着翻。
 *
 * `ar-EG` 在 JDK 上的默认数字系统确实是阿拉伯-印度数字，所以去掉 `Locale.ROOT` 时
 * 这里的第 2 条会立刻变红。
 */
class FormatDurationTest {

    @Test
    fun `missing durations read as placeholders`() {
        assertEquals("--:--", formatDuration(0L))
        assertEquals("--:--", formatDuration(-1_000L))
    }

    @Test
    fun `minutes pad the seconds but not themselves`() {
        assertEquals("0:45", formatDuration(45_000L))
        assertEquals("3:45", formatDuration(225_000L))
        assertEquals("59:59", formatDuration(3_599_000L))
    }

    @Test
    fun `an hour switches to the third field`() {
        assertEquals("1:00:00", formatDuration(3_600_000L))
        assertEquals("1:02:03", formatDuration(3_723_000L))
        assertEquals("12:34:56", formatDuration(45_296_000L))
    }

    /**
     * 默认 locale 换成把本地数字当默认数字系统的那些，输出必须**逐字节不变**。
     *
     * 用 `Locale.Category.FORMAT` 而不是整个 `Locale.setDefault`：只影响格式化，
     * 不会把同一 JVM 里其它测试的文案语言一起改掉。还原写在 `finally`，测试失败时
     * 也不会把污染留给下一个用例。
     */
    @Test
    fun `durations never follow the system numbering system`() {
        val previous = Locale.getDefault(Locale.Category.FORMAT)
        val localizedDigits = listOf("ar-EG", "fa-IR", "bn-IN", "th-TH-u-nu-thai")
        try {
            localizedDigits.forEach { tag ->
                Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag(tag))
                assertEquals("$tag 下短时长被本地化", "3:45", formatDuration(225_000L))
                assertEquals("$tag 下长时长被本地化", "1:02:03", formatDuration(3_723_000L))
            }
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, previous)
        }
    }

    /**
     * 输出只允许出现 ASCII 数字与冒号。
     *
     * 上面的用例钉的是"这两个值恰好对"，这一条钉的是**字符集**：哪怕将来改成手工
     * 拼接或换掉 `String.format`，只要混进一个本地数字（或全角冒号）就会红。
     */
    @Test
    fun `formatted durations stay inside the ascii digits and colon`() {
        val asciiOnly = Regex("""^[0-9]+:[0-9]{2}(:[0-9]{2})?$""")
        listOf(1_000L, 45_000L, 225_000L, 3_599_000L, 3_600_000L, 45_296_000L).forEach { ms ->
            val text = formatDuration(ms)
            assertTrue("${ms}ms 输出 $text 含非 ASCII 字符", asciiOnly.matches(text))
        }
    }
}
