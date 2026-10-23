package com.milan.music.data.media

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [MemoCache] 的三条不变量，全部与时序有关，与 `Bitmap` 无关，所以用普通字符串驱动。
 *
 * 这些用例守的是"缓存 + 合并 + 取消兜底"这一份代码：它此前以四份拷贝的形式存在
 * （列表 / 播放页 / 背景板 / 倒影），任何一次修改都要同时改四处，漏一处就是静默的
 * 封面错位，而错位不会被任何现有测试发现。
 */
// runCurrent 仍是实验 API，但在 1.9.0 上没有替代品：它的语义正是"把已经就绪的协程
// 跑到挂起为止"，而这几条用例全部由"谁先跑到挂起点"决定。
@OptIn(ExperimentalCoroutinesApi::class)
class MemoCacheTest {

    @Test
    fun `a cache hit never produces`() = runTest {
        val cache = mutableMapOf(7L to "cached")
        val memo = MemoCache<Long, String>(peek = { cache[it] })
        var produced = false

        val value = memo.load(7L) {
            produced = true
            "decoded"
        }

        assertEquals("cached", value)
        assertFalse("缓存命中时不该再解一次", produced)
    }

    @Test
    fun `concurrent requests for one key decode once`() = runTest {
        val memo = MemoCache<Long, String>(peek = { null })
        val gate = CompletableDeferred<Unit>()
        var decodes = 0
        val produce: suspend (Long) -> String? = {
            decodes++
            gate.await()
            "cover"
        }

        val first = async { memo.load(1L, produce) }
        runCurrent()
        val second = async { memo.load(1L, produce) }
        runCurrent()

        gate.complete(Unit)
        assertEquals("cover", first.await())
        assertEquals("cover", second.await())
        assertEquals("同一张封面只该解一次", 1, decodes)
    }

    /**
     * 最重要的一条。生产者被取消时它的 `finally` 已经把自己以 `null` 完成，等待方若只
     * 看这个 `null`，就会把"没拿到结果"读成"这张专辑没有封面"，而它通常不会重试。
     * 生产者在取消前写进缓存的值必须能被等待方看到。
     *
     * 时序是这条用例的全部：写缓存必须发生在**等待方已经挂在 await 上之后**。否则
     * 等待方一进 `load` 就被 `peek` 挡住了，走的是缓存命中路径，这个用例就退化成
     * 只验证 `peek` —— 改坏兜底那一行照样全绿。中间那句 `assertNull` 就是钉这个前提。
     */
    @Test
    fun `a cancelled producer hands over what it already cached`() = runTest {
        val cache = mutableMapOf<Long, String>()
        val memo = MemoCache<Long, String>(peek = { cache[it] })
        val producerStarted = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()

        val producer = launch {
            memo.load(1L) {
                producerStarted.complete(Unit)
                try {
                    hold.await()
                    "unreachable"
                } finally {
                    // 对应 loadPlayerArtwork：NonCancellable 块已经把结果写进了缓存，
                    // 取消是在之后的返回途中生效的 —— 结果有效，但生产它的协程已经
                    // 没机会把它交出来了。
                    cache[1L] = "written-before-cancel"
                }
            }
        }
        producerStarted.await()

        val waiter = async {
            memo.load(1L) { error("等待方不该成为第二个生产者") }
        }
        runCurrent()
        assertNull("前提：生产者在被取消前还没写缓存，等待方走的必须是在飞快路径", cache[1L])

        producer.cancel()
        producer.join()

        assertEquals("written-before-cancel", waiter.await())
    }

    @Test
    fun `a cancelled producer with nothing cached reports no value`() = runTest {
        val memo = MemoCache<Long, String>(peek = { null })
        val producerStarted = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()

        val producer = launch {
            memo.load(2L) {
                producerStarted.complete(Unit)
                hold.await()
                "unreachable"
            }
        }
        producerStarted.await()

        val waiter = async {
            memo.load(2L) { error("等待方不该成为第二个生产者") }
        }
        runCurrent()

        producer.cancel()
        producer.join()

        assertNull(waiter.await())
    }

    /**
     * 生产能力恢复后必须能重试：`finally` 把 key 从在飞表里移走，下一次请求才会成为
     * 新的生产者。留着移除那一行的话，第二次请求会 `await` 一个已完成的 `null`
     * 直接返回 null，且**永远**如此 —— 那张封面在本次进程内就再也加载不出来。
     */
    @Test
    fun `a key that produced nothing can be retried`() = runTest {
        val memo = MemoCache<Long, String>(peek = { null })
        var decodes = 0

        assertNull(memo.load(3L) { decodes++; null })
        assertEquals("second try", memo.load(3L) { decodes++; "second try" })
        assertEquals(2, decodes)
    }

    @Test
    fun `different keys do not block each other`() = runTest {
        val memo = MemoCache<Long, String>(peek = { null })
        val hold = CompletableDeferred<Unit>()

        val slow = async { memo.load(1L) { hold.await(); "a" } }
        runCurrent()
        val fast = async { memo.load(2L) { "b" } }
        runCurrent()

        assertEquals("b", fast.await())
        hold.complete(Unit)
        assertEquals("a", slow.await())
    }
}
