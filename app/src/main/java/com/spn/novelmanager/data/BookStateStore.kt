package com.spn.novelmanager.data

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * 每本书的私有状态：上次阅读位置/时间、编辑撤销栈。
 * 全部存放在应用私有目录，不写入小说文件夹。
 */
class BookStateStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val undoDir = File(appContext.filesDir, "undo")

    data class LastRead(val offset: Int, val timestamp: Long)

    fun lastRead(bookUri: String): LastRead? {
        val offset = prefs.getInt(keyOffset(bookUri), -1)
        val time = prefs.getLong(keyTime(bookUri), 0L)
        return if (offset < 0) null else LastRead(offset, time)
    }

    fun saveLastRead(bookUri: String, offset: Int, timestamp: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putInt(keyOffset(bookUri), offset.coerceAtLeast(0))
            .putLong(keyTime(bookUri), timestamp)
            .apply()
    }

    @Synchronized
    fun loadUndo(bookUri: String, maxSteps: Int): ArrayDeque<String> {
        val file = undoFile(bookUri)
        val stack = ArrayDeque<String>()
        if (!file.isFile) return stack
        return runCatching {
            val decoded = UndoStackCodec.decode(file.readText())
            while (decoded.size > maxSteps.coerceAtLeast(1)) decoded.removeFirst()
            decoded
        }.getOrDefault(stack)
    }

    @Synchronized
    fun saveUndo(bookUri: String, stack: ArrayDeque<String>, maxSteps: Int) {
        runCatching {
            while (stack.size > maxSteps.coerceAtLeast(1)) stack.removeFirst()
            undoDir.mkdirs()
            undoFile(bookUri).writeText(UndoStackCodec.encode(stack))
        }
    }

    private fun undoFile(bookUri: String): File = File(undoDir, md5(bookUri) + ".json")

    private fun md5(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun keyOffset(uri: String): String = "offset_${md5(uri)}"
    private fun keyTime(uri: String): String = "time_${md5(uri)}"

    companion object {
        private const val PREFS_NAME = "book_state"
    }
}

/** 撤销栈的编解码（长度前缀格式，独立以便单元测试）。 */
object UndoStackCodec {
    fun encode(stack: ArrayDeque<String>): String = buildString {
        append(stack.size).append('\n')
        stack.forEach { snapshot ->
            append(snapshot.length).append('\n')
            append(snapshot)
        }
    }

    fun decode(raw: String): ArrayDeque<String> {
        val stack = ArrayDeque<String>()
        runCatching {
            var cursor = 0
            fun readNumber(): Int {
                val newline = raw.indexOf('\n', cursor)
                if (newline < 0) throw IllegalArgumentException("格式错误")
                val value = raw.substring(cursor, newline).toInt()
                cursor = newline + 1
                return value
            }
            val count = readNumber()
            repeat(count) {
                val length = readNumber()
                val end = (cursor + length).coerceAtMost(raw.length)
                stack.addLast(raw.substring(cursor, end))
                cursor = end
            }
        }
        return stack
    }

    fun push(stack: ArrayDeque<String>, snapshot: String, maxSteps: Int) {
        stack.addLast(snapshot)
        while (stack.size > maxSteps.coerceAtLeast(1)) stack.removeFirst()
    }
}
