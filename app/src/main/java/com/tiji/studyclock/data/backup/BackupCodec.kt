package com.tiji.studyclock.data.backup

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** JSON 编解码只做格式层校验；记录之间的关联由 [BackupValidator] 负责。 */
object BackupCodec {
    /** 当前写出的备份格式（v0.11 / schema 6）；新增字段均可缺省，因此 v3/v4/v5 备份仍可恢复。 */
    const val SCHEMA_VERSION = 6
    const val MIN_SCHEMA_VERSION = 3
    const val MAX_BYTES = 20 * 1024 * 1024

    @OptIn(ExperimentalSerializationApi::class)
    private val format = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    fun encode(payload: BackupPayload, maxBytes: Int = MAX_BYTES): String {
        val text = format.encodeToString(BackupPayload.serializer(), payload)
        require(text.toByteArray(Charsets.UTF_8).size <= maxBytes) {
            "备份数据超出大小限制，未写入。"
        }
        return text
    }

    fun decode(bytes: ByteArray, maxBytes: Int = MAX_BYTES): BackupPayload {
        require(bytes.isNotEmpty()) { "备份文件是空的。" }
        require(bytes.size <= maxBytes) {
            "备份文件超出大小限制，无法读取。"
        }
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            throw IllegalArgumentException("备份文件编码无法识别，可能已损坏。")
        }
        return decode(text)
    }

    fun decode(text: String): BackupPayload {
        require(text.isNotBlank()) { "备份文件是空的。" }
        return try {
            format.decodeFromString(BackupPayload.serializer(), text)
        } catch (_: SerializationException) {
            throw IllegalArgumentException("备份文件格式无法识别，可能已损坏。")
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("备份文件格式无法识别，可能已损坏。")
        }
    }

    fun summary(payload: BackupPayload) = BackupSummary(
        subjects = payload.subjects.size,
        categories = payload.categories.size,
        sessions = payload.sessions.size,
        prescriptions = payload.prescriptions.size,
        examPlans = payload.examPlans.size,
        exportedAt = payload.exportedAt,
        hasSettings = payload.settings != null,
        wrongBatches = payload.wrongBatches.size,
        reviewAttempts = payload.reviewAttempts.size,
    )
}
