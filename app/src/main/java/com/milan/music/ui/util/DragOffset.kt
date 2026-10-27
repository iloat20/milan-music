package com.milan.music.ui.util

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 手势直接驱动的位移：写入是**同步**的，动画只能被同一处的写入打断。
 *
 * 这两块（播放页纵向收起、封面横向翻页）原来都是 `Animatable` +
 * `scope.launch { snapTo(...) }`。`snapTo` 是 suspend，而 `rememberCoroutineScope()`
 * 用的是组合的调度器（Android 上是一个 Handler）：同一批指针事件里的
 * `onDragXxx` 与 `onDragEnd` 是连着跑的，`onDragEnd` 读到的 `offset` 还是**上一批**
 * 留下的值。于是快速轻扫的最后一段位移对阈值判定完全不可见 —— 表现为"甩一下不翻页，
 * 慢慢拖反而翻得动"。每个移动事件再额外分配一个协程对象，也没有任何收益。
 *
 * 现在写入就是一次 `MutableFloatState` 赋值（绘制期读，不触发重组）；回弹与收起用
 * `animate` 写回同一个 state，任何一次手势写入都先把动画 cancel 掉 —— 这正是
 * `Animatable.snapTo` 靠 MutatorMutex 保证的语义，只是这里只有一条写路径，不需要锁。
 */
@Stable
internal class DragOffset(
    private val scope: CoroutineScope,
    initialValue: Float = 0f,
) {

    private val state = mutableFloatStateOf(initialValue)
    private var animation: Job? = null

    val value: Float get() = state.floatValue

    /** 手势写入。必须同步生效，理由见类注释。 */
    fun set(target: Float) {
        stopAnimation()
        state.floatValue = target
    }

    /**
     * 从当前位置动画到 [target]。
     *
     * [onFinished] 只在动画自己走完时执行：被打断时**不许**执行 —— 播放页靠它
     * "滑到底再卸载"，半路被打断还卸载就是页面凭空消失。
     *
     * 返回的 [Job] 给需要"动画走完再继续"的串行路径 `join` 用（翻页：先走完一个
     * pitch，再向会话发切歌请求）。
     */
    fun animateTo(
        target: Float,
        spec: AnimationSpec<Float>,
        onFinished: () -> Unit = {},
    ): Job {
        stopAnimation()
        val job = scope.launch {
            animate(
                initialValue = state.floatValue,
                targetValue = target,
                animationSpec = spec,
            ) { frame, _ -> state.floatValue = frame }
            onFinished()
        }
        animation = job
        return job
    }

    private fun stopAnimation() {
        animation?.cancel()
        animation = null
    }
}

@Composable
internal fun rememberDragOffset(initialValue: Float = 0f): DragOffset {
    val scope = rememberCoroutineScope()
    return remember(scope) { DragOffset(scope, initialValue) }
}
