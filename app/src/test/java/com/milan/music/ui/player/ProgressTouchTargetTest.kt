package com.milan.music.ui.player

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进度条的命中区与它占的版面高度。
 *
 * 这条守的是一次**看得见风险为 0 的改动**：把命中区从 26dp 抬到 48dp，很容易顺手把
 * 信息区整体推下去 22dp。做法是把多出来的 22dp 全部向上要（条仍画在命中区的底部），
 * 同步把 [ProgressTopGap] 从 26dp 收到 4dp —— 于是这一块的总高不变，条的中心、时间
 * 标签、控制条一格都不动。
 *
 * 另一个容易踩的点是"改一处忘另一处"：把 [ProgressTouchHeight] 调到 56dp 而不动间隙，
 * 整块就下沉 8dp。下面的等式就是那个提醒。
 */
class ProgressTouchTargetTest {

    @Test
    fun `the seek bar touch target meets the 48dp minimum`() {
        // Material 的最小触摸目标是 48dp。条本身只有 26dp —— 手指按在条上下一两个像素
        // 就落空，而这正是"进度条拖不动"的常见来源。
        assertTrue("命中区 ${ProgressTouchHeight.value}dp 低于 48dp", ProgressTouchHeight.value >= 48f)
        assertTrue(
            "命中区必须高于绘制区，否则这次改动没有意义",
            ProgressTouchHeight > ProgressBarHeight,
        )
    }

    @Test
    fun `enlarging the target does not push the rest of the panel down`() {
        // 52dp = 改动前的总高（间隙 26 + 条 26）。命中区与间隙之和必须仍然是它；
        // 条画在命中区底部，所以条底边的位置也仍然是它。
        assertEquals(
            "间隙 + 命中区 = ${ProgressTopGap + ProgressTouchHeight}，改动前是 52dp",
            52.dp,
            ProgressTopGap + ProgressTouchHeight,
        )
        assertTrue("条必须留在命中区的底部（见 AppleMusicProgress 的 BottomCenter）", ProgressBarHeight < ProgressTouchHeight)
    }
}
