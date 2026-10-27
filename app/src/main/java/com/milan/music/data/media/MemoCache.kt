package com.milan.music.data.media

import kotlinx.coroutines.CancellationException
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
 *  4. 重读仍然没有，说明生产者是**被取消**而不是给出了结论：等待方要**接手生产**。
 *     只重读不接手会留下一个真实的可见缺陷 —— 列表里 A 行先请求某张封面，A 行滚出
 *     屏幕时它的 `LaunchedEffect` 被取消，同一张封面正在等待的 B 行仍然可见，它拿到的
 *     `null` 会被当成"没有封面"，而它的 key 没变、不会重试，于是那一行**一直是占位图**，
 *     直到滚出去再滚回来。所以「结论」与「 abandonment」必须区分开，见 [Outcome]。
 *
 * `peek` 由调用方注入而不是内建 `LruCache`：缓存的所有权（尺寸统计、trim、evict、
 * 同步读取）留在仓库里，这里只管时序。这也让本类能用普通值单测 —— 四条不变量全部
 * 与时序有关，与 `Bitmap` 无关。
 */
internal class MemoCache<K : Any, V : Any>(
    private val peek: (K) -> V?,
) {

    /**
     * 一次生产的结果。
     *
     * 两种情况的区别是第 4 条不变量的全部意义所在：`Done(null)` 是**结论**（这个 key
     * 确实没有值，等待方必须直接接受），[Abandoned] 是**没有结论**（生产者被它自己的
     * 调用方取消了，等待方应当接手）。
     */
    private sealed interface Outcome<out V> {
        data class Done<V>(val value: V?) : Outcome<V>
        data object Abandoned : Outcome<Nothing>
    }

    /** 正在生产的 key。没有它，N 个并发请求就是 N 次解码。 */
    private val pending = ConcurrentHashMap<K, CompletableDeferred<Outcome<V>>>()

    /**
     * 取 [key] 的值：缓存命中、等别人正在生产的结果、或者自己调 [produce] 生产。
     *
     * 写缓存由 [produce] 自己做，本类不代劳 —— 写入口的时机是有讲究的（见上条第 3 点，
     * 播放页那份必须活过调用方的取消）。[produce] 返回 `null` 表示「这个 key 确实没有
     * 值」，与「被取消」在返回值上相同、在**后续动作上必须不同**：前者直接交出去，
     * 后者让等待方接手再试。
     */
    suspend fun load(key: K, produce: suspend (K) -> V?): V? {
        peek(key)?.let { return it }

        // `repeat` 而不是 `for (attempt in ...)`：没有用到的循环变量就是一个 `w:` 警告，
        // 而"零警告"是本工程的构建门禁。
        repeat(TAKEOVER_ATTEMPTS) {
            val own = CompletableDeferred<Outcome<V>>()
            val inFlight = pending.putIfAbsent(key, own)
            if (inFlight != null) {
                when (val outcome = inFlight.await()) {
                    is Outcome.Done -> return outcome.value ?: peek(key)
                    // 上一个生产者被取消：先看它取消前有没有写进缓存（第 3 条），
                    // 仍然没有就自己接手 —— 需求还在，不能让这个请求停在没有封面上。
                    Outcome.Abandoned -> {
                        peek(key)?.let { return it }
                        return@repeat
                    }
                }
            }

            var outcome: Outcome<V> = Outcome.Done(null)
            try {
                val produced = produce(key)
                outcome = Outcome.Done(produced)
                return produced
            } catch (cancelled: CancellationException) {
                // 不把自己的取消当成结论交给等待方 —— 那正是"等待方永远拿到 null"的来源。
                outcome = Outcome.Abandoned
                throw cancelled
            } finally {
                // 先移出再完成，顺序不能反：被唤醒的等待方会立刻重读 `peek` 并可能自己成为
                // 下一个生产者，此时在飞表里若还挂着这个已完成的 deferred，`putIfAbsent`
                // 只认 key 是否存在、不认它是否完成，那条路径就永远拿不到新请求。
                // 带 value 的 remove 而不是只按 key：万一表里已经是别人的条目，别误删。
                pending.remove(key, own)
                own.complete(outcome)
            }
        }

        // 只剩"连续被取消"这一种可能：这一轮不再争当生产者，交出缓存里现有的东西。
        return peek(key)
    }

    private companion object {
        /**
         * 最多重投几轮。
         *
         * 每次重投都意味着「上一个生产者被别处的调用方取消」，而当前这个协程仍然活着，
         * 所以正常路径最多一轮。上限只是防御，保证这条路径永远可终止。
         */
        const val TAKEOVER_ATTEMPTS = 3
    }
}
