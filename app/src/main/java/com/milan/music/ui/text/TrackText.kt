package com.milan.music.ui.text

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.milan.music.R
import com.milan.music.data.model.SortMode
import com.milan.music.data.model.Track

/**
 * 曲目字段与排序模式的**显示**文本。
 *
 * 兜底文案（"未知曲目"…）属于界面，不属于模型。`Track` 的同名字段原本直接返回中文字面量，
 * 而它同时被排序和筛选使用 —— 领域层由此依赖了某一种语言：换一种语言，排序键就跟着变，
 * 空白标题还会被排到"未"字开头的分组里。现在模型只保留 MediaStore 的原值，
 * 由这里补齐显示层。
 *
 * 两套上下文共用同一批资源：Compose 用无参重载，service / widget 用带 [Context] 的重载。
 */

@Composable
fun Track.titleText(): String =
    title.ifBlank { stringResource(R.string.track_untitled) }

@Composable
fun Track.artistText(): String =
    artist.takeIf { it.isKnownTag() } ?: stringResource(R.string.track_unknown_artist)

@Composable
fun Track.albumText(): String =
    album.takeIf { it.isKnownTag() } ?: stringResource(R.string.track_unknown_album)

fun Track.titleText(context: Context): String =
    title.ifBlank { context.getString(R.string.track_untitled) }

fun Track.artistText(context: Context): String =
    artist.takeIf { it.isKnownTag() } ?: context.getString(R.string.track_unknown_artist)

/**
 * 专辑艺人，缺失时退回 [artist]。
 *
 * 单艺人专辑占绝大多数，而系统 UI（Android Auto、锁屏）读 `albumArtist` 是为了归类。
 * 直接给空串会让这些界面拿不到艺人名，所以缺省值取 `artist`。
 */
fun Track.albumArtistText(context: Context): String =
    albumArtist.takeIf { it.isKnownTag() } ?: artistText(context)

fun Track.albumText(context: Context): String =
    album.takeIf { it.isKnownTag() } ?: context.getString(R.string.track_unknown_album)

/** MediaStore 用 `<unknown>` 表示"扫描到了但没读到"，与空值一样当作缺失。 */
private fun String.isKnownTag(): Boolean = isNotBlank() && this != Track.UNKNOWN

@Composable
fun SortMode.labelText(): String = stringResource(
    when (this) {
        SortMode.TITLE -> R.string.sort_title
        SortMode.ARTIST -> R.string.sort_artist
        SortMode.ALBUM -> R.string.sort_album
        SortMode.DATE_ADDED -> R.string.sort_date_added
        SortMode.DURATION -> R.string.sort_duration
    },
)
