package com.spn.novelmanager.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 图片解码缓存。磁盘上的原图不做任何压缩，仅按展示宽度降采样。 */
object ImageLoader {

    private val cache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 6).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    suspend fun load(context: Context, uri: Uri, targetWidthPx: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val key = "$uri#${targetWidthPx.coerceAtLeast(1)}"
            cache.get(key)?.let { return@withContext it }
            val bitmap = decode(context, uri, targetWidthPx)
            if (bitmap != null) cache.put(key, bitmap)
            bitmap
        }

    private fun decode(context: Context, uri: Uri, targetWidthPx: Int): Bitmap? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) return@runCatching null

            var sample = 1
            while (width / (sample * 2) >= targetWidthPx.coerceAtLeast(1)) sample *= 2

            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }
        }.getOrNull()
    }
}
