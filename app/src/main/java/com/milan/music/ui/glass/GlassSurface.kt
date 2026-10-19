package com.milan.music.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * Ultra-translucent glass.
 *
 * The blur style is reduced to pure backdrop blur (`backgroundColor` transparent,
 * no colour effects) and the tint is a very light wash painted on top, so the
 * gradient behind stays visible.
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

    val wash = if (tint.isSpecified) {
        tint
    } else if (glass.isDark) {
        Color(0xFF191307).copy(alpha = 0.18f)
    } else {
        Color(0xFFFFF9E8).copy(alpha = 0.05f)
    }

    Box(
        modifier = modifier
            .clip(shape)
            .hazeBlur(
                input = HazeInput.Backdrop(hazeState),
                style = style,
            )
            .background(wash),
        content = content,
    )
}
