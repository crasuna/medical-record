package com.loveluke.medicalrecord.core.database

import androidx.room.TypeConverter
import com.loveluke.medicalrecord.core.model.AttachmentIntegrityState
import com.loveluke.medicalrecord.core.model.AttachmentKind
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** Stable, locale-independent encodings used by schema version 1. */
class DatabaseTypeConverters {
    @TypeConverter
    fun instantToEpochMillis(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun epochMillisToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter
    fun localDateToEpochDay(value: LocalDate?): Long? = value?.toEpochDay()

    @TypeConverter
    fun epochDayToLocalDate(value: Long?): LocalDate? = value?.let(LocalDate::ofEpochDay)

    @TypeConverter
    fun localTimeToMinuteOfDay(value: LocalTime?): Int? = value?.let {
        require(it.second == 0 && it.nano == 0) { "Stored times must have minute precision" }
        it.hour * MINUTES_PER_HOUR + it.minute
    }

    @TypeConverter
    fun minuteOfDayToLocalTime(value: Int?): LocalTime? = value?.let {
        require(it in 0 until MINUTES_PER_DAY) { "Stored time is outside a day" }
        LocalTime.of(it / MINUTES_PER_HOUR, it % MINUTES_PER_HOUR)
    }

    @TypeConverter
    fun attachmentKindToName(value: AttachmentKind?): String? = value?.name

    @TypeConverter
    fun nameToAttachmentKind(value: String?): AttachmentKind? =
        value?.let(AttachmentKind::valueOf)

    @TypeConverter
    fun attachmentIntegrityStateToName(value: AttachmentIntegrityState?): String? = value?.name

    @TypeConverter
    fun nameToAttachmentIntegrityState(value: String?): AttachmentIntegrityState? =
        value?.let(AttachmentIntegrityState::valueOf)

    private companion object {
        const val MINUTES_PER_HOUR = 60
        const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR
    }
}
