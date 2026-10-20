package com.milan.music.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.milan.music.ui.theme.LocalGlassColors
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur

/**
 * Frosted glass with a real body.
 *
 * Pure backdrop blur plus a 5% wash is invisible on a pale page — and is
 * completely gone when there is nothing behind the panel to blur (an empty
 * "liked" tab), because blurring a flat gradient returns the same flat gradient.
 * So the surface paints its own milky vertical gradient on top of the blur, plus
 * a soft elevation shadow, and draws no outline at all (a 1dp white rim reads as
 * a bright edge on this palette and was removed).
 *
 * Note: a `hazeEffect` node may not draw an ancestor `hazeSource`. Callers must
 * therefore place glass surfaces as siblings of the source, not inside it.
 */
@Composable
fun GlassSurface(
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    blurRadius: Dp = 26.dp,
    tint: Color = Color.Unspecified,
    content: @Composable BoxScope.() -> Unit,
) {
    val glass = LocalGlassColors.current

    val style = remember(blurRadius) {
        HazeBlurStyle {
            blurRadius(blurRadius)
            noiseFactor(0.03f)
            backgroundColor(Color.Transparent)
        }
    }

    // 一层几乎透明的薄幕，不是奶白填充。
    //
    // 关键是加性的白和乘性的灰在亮底上完全不是一回事：
    //  - 奶白是"加亮"，页面亮度已经 232，5% 只加 2 级（看不见），66% 又白得发死；
    //  - 暖灰薄幕是"乘性"的，背后不管是什么都按同一比例压暗，空页和"背后有行在
    //    滚"的曲库得到的是同一块面板（都约 −9 级），且透明度还有 94%——就是以前
    //    那种通透感。
    val body = when {
        tint.isSpecified -> Brush.verticalGradient(listOf(tint, tint))
        glass.isDark -> Brush.verticalGradient(
            listOf(
                Color(0xFFF8F1E2).copy(alpha = 0.06f),
                Color(0xFFF8F1E2).copy(alpha = 0.04f),
            ),
        )
        else -> Brush.verticalGradient(
            listOf(
                Color(0xFF6C604A).copy(alpha = 0.07f),
                Color(0xFF6C604A).copy(alpha = 0.05f),
            ),
        )
    }

    // 极轻投影只负责轮廓脱离，不参与"体"：12dp、8%~10% 黑，深浅主题共用。
    val elevation = if (glass.isDark) 0.dp else 12.dp

    Box(
        modifier = modifier
            .shadow(
                elevation = elevation,
                shape = shape,
                ambientColor = Color(0x0D000000),
                spotColor = Color(0x14000000),
            )
            .clip(shape)
            .hazeBlur(
                input = HazeInput.Backdrop(hazeState),
                style = style,
            )
            .background(body),
        content = content,
    )
}
