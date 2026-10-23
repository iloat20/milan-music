package com.milan.music.data.media

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * 按 key 合并并发的「解码 + 缓存」请求。
 *
 * 四个封面缓存（列表 / 播放页 / 背景板 / 倒影）走的是同一套流程，而流程里有三条
 * 容易漏、漏了又不会报错的不变量，所以它只写一次：
 *
 *  1. **同步命中优先**：`peek` 命中就直接返回，不进入挂起、不碰在飞表。
 *  2. **同 key 只有一个生产者**：后来者等第一个人的 deferred，而不是再解一次。
 *     快速滚动时同一张封面会被一屏里十几行同时请求，没有这一条就是十几份重复解码。
 *  3. **被取消的生产者不得产生一个错误结论**。生产者中途被取消时，它的 `finally`
 *     已经清掉在飞表、并以 `null` 完成自己；如果等待方只 `await()`，就会把「没拿到
 *     结果」读成「这张专辑没有封面」。而这些等待方通常不会重试（它们的
 *     `LaunchedEffect` key 没变，不会重新触发）。所以拿到 `null` 之后必须**重读一次
 *     `peek`** —— 生产者完全可能在取消之前就已经把结果写进缓存了
 *     （[ArtworkRepository.loadPlayerArtwork] 的解码就故意跑在 `NonCancellable` 里）。
 *
 * `peek` 由调用方注入而不是内建 `LruCache`：缓存的所有权（尺寸统计、trim、evict、
 * 同步读取）留在仓库里，这里只管时序。这也让本类能用普通值单测 —— 三条不变量全部
 * 与时序有关，与 `Bitmap` 无关。
 */
internal class MemoCache<K : Any, V : Any>(
    private val peek: (K) -> V?,
) {

    /** 正在生产的 key。没有它，N 个并发请求就是 N 次解码。 */
    private val pending = ConcurrentHashMap<K, CompletableDeferred<V?>>()

    /**
     * 取 [key] 的值：缓存命中、等别人正在生产的结果、或者自己调 [produce] 生产。
     *
     * 写缓存由 [produce] 自己做，本类不代劳 —— 写入口的时机是有讲究的（见上条第 3 点，
     * 播放页那份必须活过调用方的取消）。[produce] 返回 `null` 表示「这个 key 确实没有
     * 值」，与「被取消」在调用方看来相同：两者都不写缓存，下次请求重新尝试。
     */
    suspend fun load(key: K, produce: suspend (K) -> V?): V? {
        peek(key)?.let { return it }

        val own = CompletableDeferred<V?>()
        pending.putIfAbsent(key, own)?.let { inFlight ->
            return inFlight.await() ?: peek(key)
        }

        var result: V? = null
        try {
            result = produce(key)
            return result
        } finally {
            // 先移出再完成，顺序不能反：被唤醒的等待方会立刻重读 `peek` 并可能自己成为
            // 下一个生产者，此时在飞表里若还挂着这个已完成的 deferred，`putIfAbsent`
            // 只认 key 是否存在、不认它是否完成，那条路径就永远拿不到新请求。
            // 带 value 的 remove 而不是只按 key：万一表里已经是别人的条目，别误删。
            pending.remove(key, own)
            own.complete(result)
        }
    }
}
