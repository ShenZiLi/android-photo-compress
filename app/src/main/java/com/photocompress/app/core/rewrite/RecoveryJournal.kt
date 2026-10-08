package com.photocompress.app.core.rewrite

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** 原文件改写前持久化恢复入口。失败或进程中断后，备份不可被回收站清理。 */
class RecoveryJournal(context: Context) {
    private val root = File(context.filesDir, "recovery")
    companion object { private val lock = Any() }

    data class Entry(
        val id: String,
        val path: String,
        val backupRelPath: String,
        val sha256: String,
        val modifiedTime: String,
        val mediaUri: String,
        val dateTakenMs: Long,
        val dateAddedSec: Long,
        val dateModifiedSec: Long,
        val safetyBackupRelPath: String? = null,
        val ledgerId: String = id,
        val dateTakenWasNull: Boolean = false,
        val convertedPath: String? = null,
        val sourceInode: Long? = null,
    ) {
        val displayName: String get() = File(path).name
        /**
         * 本条目涉及的原片路径。
         *
         * 原先每次访问都重新 `listOfNotNull(...).toSet()`，而派生整库列表时
         * 会按媒体项逐条调用 [matches]，等于为每个媒体项都分配一次列表与集合。
         * 路径只由构造参数决定，这里算一次即可。
         */
        val paths: Set<String> = listOfNotNull(path, convertedPath).toSet()
        fun matches(path: String, uri: String): Boolean = path in paths || (convertedPath != null && uri == mediaUri)
    }

    fun begin(entry: Entry) = synchronized(lock) {
        check(root.isDirectory || root.mkdirs()) { "无法保存恢复记录，原文件未改动" }
        val json = JSONObject().apply {
            put("id", entry.id); put("path", entry.path); put("backup", entry.backupRelPath)
            put("sha256", entry.sha256); put("mtime", entry.modifiedTime); put("uri", entry.mediaUri)
            put("taken", entry.dateTakenMs); put("added", entry.dateAddedSec); put("modified", entry.dateModifiedSec)
            put("safety", entry.safetyBackupRelPath ?: JSONObject.NULL)
            put("ledgerId", entry.ledgerId)
            put("takenNull", entry.dateTakenWasNull)
            put("convertedPath", entry.convertedPath ?: JSONObject.NULL)
            put("sourceInode", entry.sourceInode ?: JSONObject.NULL)
        }
        val atomic = file(entry.id)
        val stream = atomic.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
            val saved = JSONObject(atomic.readFully().toString(Charsets.UTF_8))
            check(saved.getString("id") == entry.id && saved.getString("backup") == entry.backupRelPath &&
                saved.getString("sha256") == entry.sha256 && saved.optString("safety") == json.optString("safety")) {
                "恢复记录保存校验失败，原文件未改动"
            }
            check(saved.optString("convertedPath") == json.optString("convertedPath") &&
                saved.optString("sourceInode") == json.optString("sourceInode")) { "转换恢复记录未完整保存，原片未改动" }
        } catch (failure: Throwable) {
            atomic.failWrite(stream)
            throw failure
        }
    }

    fun entries(): List<Entry> = synchronized(lock) {
        if (!root.exists()) return@synchronized emptyList()
        val files = checkNotNull(root.listFiles()) { "无法读取恢复记录，备份已保护" }
        // AtomicFile 在进程中断后可能只剩 .bak，读取时完成恢复。
        files.map { it.name.removeSuffix(".bak").removeSuffix(".new") }
            .filter { it.endsWith(".json") }.distinct().map { name ->
                val json = JSONObject(AtomicFile(File(root, name)).readFully().toString(Charsets.UTF_8))
                Entry(
                    json.getString("id"), json.getString("path"), json.getString("backup"),
                    json.getString("sha256"), json.getString("mtime"), json.getString("uri"),
                    json.getLong("taken"), json.getLong("added"), json.getLong("modified"),
                    if (json.isNull("safety")) null else json.getString("safety"),
                    json.optString("ledgerId", json.getString("id")),
                    json.optBoolean("takenNull", false),
                    if (json.isNull("convertedPath")) null else json.getString("convertedPath"),
                    if (json.isNull("sourceInode")) null else json.getLong("sourceInode"),
                )
            }
    }

    fun finish(id: String) = synchronized(lock) {
        val atomic = file(id)
        atomic.delete()
        check(!atomic.baseFile.exists()) { "恢复记录尚未清除，备份继续保留" }
    }

    private fun file(id: String): AtomicFile {
        require(id.matches(Regex("[A-Za-z0-9-]+"))) { "无效恢复编号" }
        return AtomicFile(File(root, "$id.json"))
    }
}
