package com.photocompress.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.MaterialTheme
import com.photocompress.app.ui.theme.appColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 系统缩略图（图片与视频通用），带内存缓存，避免滚动时反复解码。 */
object Thumbnails {

    private val cache = LruCache<String, ImageBitmap>(320)

    suspend fun load(context: Context, uri: Uri, sizePx: Int = 256): ImageBitmap? {
        val key = "${uri}#$sizePx"
        cache.get(key)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val bmp: Bitmap = context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
                bmp.asImageBitmap()
            }.getOrNull()
        }
        if (bitmap != null) cache.put(key, bitmap)
        return bitmap
    }
}

@Composable
fun ThumbImage(
    context: Context,
    uri: Uri?,
    modifier: Modifier = Modifier,
    sizePx: Int = 256,
    contentDescription: String? = null,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, uri) {
        value = uri?.let { Thumbnails.load(context, it, sizePx) }
    }
    Box(modifier = modifier.background(MaterialTheme.appColors.surfaceSunken)) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
