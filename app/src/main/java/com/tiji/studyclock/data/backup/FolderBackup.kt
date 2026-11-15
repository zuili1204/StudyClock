package com.tiji.studyclock.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 用户通过系统文件夹选择器指定的外部备份位置；只清理自己命名的 `tiji-backup-*.json`。 */
@Singleton
class FolderBackup @Inject constructor(@ApplicationContext private val context: Context) {
    fun displayName(treeUri: Uri): String? = runCatching {
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        context.contentResolver.query(
            documentUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    suspend fun write(treeUri: Uri, json: String, now: Long = System.currentTimeMillis()): String =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootId)
            val existing = mutableListOf<Pair<String, String>>()
            resolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) existing += cursor.getString(0) to cursor.getString(1)
            }
            val name = fileName(now)
            val created = DocumentsContract.createDocument(resolver, rootUri, "application/json", name)
                ?: error("无法在所选文件夹中创建备份")
            resolver.openOutputStream(created)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                ?: error("无法写入所选文件夹")
            val stale = staleBackups(existing.map { it.first } + name, KEEP_FILES)
            existing.filter { it.first in stale }.forEach { (_, id) ->
                runCatching {
                    DocumentsContract.deleteDocument(
                        resolver,
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                    )
                }
            }
            name
        }

    private fun fileName(now: Long): String {
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(now))
        return "$FILE_PREFIX$stamp.json"
    }

    companion object {
        const val KEEP_FILES = 7
        const val FILE_PREFIX = "tiji-backup-"

        fun isOwnBackup(name: String): Boolean = name.startsWith(FILE_PREFIX) && name.endsWith(".json")

        /** 输入目录内全部文件名，返回超出保留份数、应删除的备份文件名。 */
        fun staleBackups(names: Collection<String>, keep: Int): Set<String> {
            val own = names.filter(::isOwnBackup).sorted()
            if (own.size <= keep) return emptySet()
            return own.take(own.size - keep).toSet()
        }
    }
}
