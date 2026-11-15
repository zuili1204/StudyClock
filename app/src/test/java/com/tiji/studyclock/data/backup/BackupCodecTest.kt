package com.tiji.studyclock.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecTest {
    @Test
    fun roundTripKeepsIdsNullsMillisAndAssociations() {
        val payload = BackupFixtures.validPayload()
        val decoded = BackupCodec.decode(BackupCodec.encode(payload).toByteArray(Charsets.UTF_8))
        assertEquals(payload, decoded)
    }

    @Test
    fun encodedJsonKeepsNullFieldsExplicit() {
        val json = BackupCodec.encode(BackupFixtures.validPayload())
        assertTrue(json.contains("\"settings\":{\"dailyQuestionGoal\":37,\"dailyMinuteGoal\":null"))
        assertTrue(json.contains("\"beforeSessionIds\":[100]"))
        assertTrue(json.contains("\"durationSec\":600"))
        assertTrue(json.contains("\"wrongTags\":\"属性规律|增长率\""))
    }

    @Test
    fun legacyV3FileWithoutWrongTagsStillDecodes() {
        val payload = BackupFixtures.validPayload()
        val legacy = payload.copy(
            schemaVersion = 3,
            sessions = payload.sessions.map { it.copy(wrongTags = null) },
        )
        val json = BackupCodec.encode(legacy).replace("\"wrongTags\":null,", "")
        val decoded = BackupCodec.decode(json.toByteArray(Charsets.UTF_8))
        assertEquals(null, decoded.sessions.single().wrongTags)
        assertEquals(legacy, decoded)
    }

    @Test
    fun emptyFileIsRejectedWithReadableMessage() {
        val error = runCatching { BackupCodec.decode(ByteArray(0)) }.exceptionOrNull()
        assertEquals("备份文件是空的。", error?.message)
    }

    @Test
    fun malformedJsonIsRejectedWithReadableMessage() {
        val error = runCatching { BackupCodec.decode("{\"schemaVersion\":3") }.exceptionOrNull()
        assertEquals("备份文件格式无法识别，可能已损坏。", error?.message)
    }

    @Test
    fun missingRequiredTableIsRejectedInsteadOfDefaultingToEmpty() {
        val error = runCatching { BackupCodec.decode("{}") }.exceptionOrNull()
        assertEquals("备份文件格式无法识别，可能已损坏。", error?.message)
    }

    @Test
    fun invalidUtf8IsRejected() {
        val error = runCatching { BackupCodec.decode(byteArrayOf(0xC3.toByte(), 0x28)) }.exceptionOrNull()
        assertEquals("备份文件编码无法识别，可能已损坏。", error?.message)
    }

    @Test
    fun sizeLimitAppliesToBothDirections() {
        val payload = BackupFixtures.validPayload()
        val encodeError = runCatching { BackupCodec.encode(payload, maxBytes = 32) }.exceptionOrNull()
        assertEquals("备份数据超出大小限制，未写入。", encodeError?.message)

        val bytes = BackupCodec.encode(payload).toByteArray(Charsets.UTF_8)
        val decodeError = runCatching { BackupCodec.decode(bytes, maxBytes = 32) }.exceptionOrNull()
        assertEquals("备份文件超出大小限制，无法读取。", decodeError?.message)
    }

    @Test
    fun summaryCountsRowsForTheRestorePreview() {
        val summary = BackupCodec.summary(BackupFixtures.validPayload())
        assertEquals(1, summary.subjects)
        assertEquals(1, summary.categories)
        assertEquals(1, summary.sessions)
        assertEquals(1, summary.prescriptions)
        assertEquals(1, summary.examPlans)
        assertTrue(summary.hasSettings)
    }
}
