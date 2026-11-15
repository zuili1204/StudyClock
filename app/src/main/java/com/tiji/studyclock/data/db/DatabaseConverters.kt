package com.tiji.studyclock.data.db

import androidx.room.TypeConverter
import com.tiji.studyclock.domain.model.PrescriptionStatus
import com.tiji.studyclock.domain.model.PrescriptionType
import com.tiji.studyclock.domain.model.SessionSource
import com.tiji.studyclock.domain.model.Verdict

class DatabaseConverters {
    @TypeConverter fun sourceToString(value: SessionSource): String = value.name
    @TypeConverter fun stringToSource(value: String): SessionSource = SessionSource.valueOf(value)
    @TypeConverter fun typeToString(value: PrescriptionType): String = value.name
    @TypeConverter fun stringToType(value: String): PrescriptionType = PrescriptionType.valueOf(value)
    @TypeConverter fun statusToString(value: PrescriptionStatus): String = value.name
    @TypeConverter fun stringToStatus(value: String): PrescriptionStatus = PrescriptionStatus.valueOf(value)
    @TypeConverter fun verdictToString(value: Verdict): String = value.name
    @TypeConverter fun stringToVerdict(value: String): Verdict = Verdict.valueOf(value)

    // Numeric IDs only; comma boundaries also allow exact membership checks in SessionDao.
    @TypeConverter fun idsToString(value: List<Long>): String = value.joinToString(",")
    @TypeConverter fun stringToIds(value: String): List<Long> =
        if (value.isEmpty()) emptyList() else value.split(',').map(String::toLong)
}
