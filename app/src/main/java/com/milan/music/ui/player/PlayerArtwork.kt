package com.milan.music.ui.player

import androidx.compose.ui.graphics.ImageBitmap

/**
 * 一首曲目的封面解码产物 —— 注意是**按曲目归属**，不是"播放页当前的三个可空变量"。
 *
 * 归属是这里的唯一不变量。封面、镜像、边缘糊版都是"某一张封面"的派生物，脱离那张封面
 * 就没有意义：上一首的镜像铺在下一首的位置上，画出来的是错误的图，不是"效果差一点"。
 * 所以三者不独立保存，绑定到同一个 [trackId] 上，由 [forTrack] 一并作废。
 *
 * 曾经它们是三个独立的 `var`，只在加载成功时写入、换曲时从不清空。无内嵌封面的曲目
 * 恰好会走到 `syncCover == null`，于是 `bandCover` 退回上一首的封面 —— 镜面线以下
 * 铺着别人的镜像（2026-10-23 审查 P0-2）。
 *
 * [palette] 是唯一允许为 `null` 且**不被归属作废**的值，但它也不在这个类里决定去留：
 * 调用方单独保存它，换曲时有意沿用上一套颜色，否则每切一首都会先闪一下兜底色。
 */
internal data class PlayerArtwork(
    val trackId: Long,
    val cover: ImageBitmap?,
    val mirror: ImageBitmap?,
    val haze: ImageBitmap?,
    val palette: PlayerPalette?,
) {
    /**
     * 这份产物能否画在 [trackId] 这一首上，不能就是 `null`。
     *
     * `null` 是"这一帧没有封面"，不是错误：带子不画、糊版不画、封面走占位渐变。
     * 换曲后必然有一帧（或者说，从换曲到解码完成之间的每一帧）走到这里，
     * 因为此时手上的产物还属于上一首。
     */
    fun forTrack(trackId: Long?): PlayerArtwork? =
        takeIf { trackId != null && it.trackId == trackId }
}
