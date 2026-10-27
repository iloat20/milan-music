package com.milan.music.ui.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 位移写入的**时机**门禁。
 *
 * 这两块位移（播放页纵向收起、封面横向翻页）原来都是 `Animatable` +
 * `scope.launch { snapTo(...) }`。写入被推到调度器上之后，"最后一次移动"与"松手"
 * 落在同一批指针事件里时，松手读到的还是上一批的位移 —— 快速轻扫整段丢失
 * （表现为甩一下不翻页，慢慢拖反而翻得动）。下面这条用**从未推进过**的调度器把
 * 那个窗口固定下来：任何经过 launch 的写法都会红。
 */
class DragOffsetTest {

    /** 一次 launch 都没跑过：写入仍然必须已经在。 */
    @Test
    fun `a drag write lands before the dispatcher runs`() {
        val scope = CoroutineScope(StandardTestDispatcher())
        val offset = DragOffset(scope)

        offset.set(-240f)

        assertEquals(-240f, offset.value, 0f)
    }

    /**
     * 同一帧内的连续写入不能被合并或排队：手指走了三个事件，位移就是三段之和。
     */
    @Test
    fun `consecutive writes accumulate within the same frame`() {
        val scope = CoroutineScope(StandardTestDispatcher())
        val offset = DragOffset(scope)

        listOf(-40f, -80f, -160f).forEach { offset.set(offset.value + it) }

        assertEquals(-280f, offset.value, 0f)
    }
}
