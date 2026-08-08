package com.loveluke.medicalrecord.core.database

import com.loveluke.medicalrecord.core.model.AttachmentIntegrityState
import com.loveluke.medicalrecord.core.model.AttachmentKind
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseTypeConvertersTest {
    private val converters = DatabaseTypeConverters()

    @Test
    fun temporalAndEnumEncodingsRoundTripWithoutLocaleOrTimezoneState() {
        val instant = Instant.parse("2026-08-08T08:09:10.123Z")
        val date = LocalDate.of(2026, 8, 8)
        val time = LocalTime.of(23, 59)

        assertEquals(
            instant,
            converters.epochMillisToInstant(converters.instantToEpochMillis(instant)),
        )
        assertEquals(
            date,
            converters.epochDayToLocalDate(converters.localDateToEpochDay(date)),
        )
        assertEquals(
            time,
            converters.minuteOfDayToLocalTime(converters.localTimeToMinuteOfDay(time)),
        )
        assertEquals(
            AttachmentKind.PDF,
            converters.nameToAttachmentKind(
                converters.attachmentKindToName(AttachmentKind.PDF),
            ),
        )
        assertEquals(
            AttachmentIntegrityState.QUARANTINED,
            converters.nameToAttachmentIntegrityState(
                converters.attachmentIntegrityStateToName(
                    AttachmentIntegrityState.QUARANTINED,
                ),
            ),
        )
    }

    @Test
    fun storedLocalTimesRequireMinutePrecisionAndValidRange() {
        assertTrue(
            runCatching {
                converters.localTimeToMinuteOfDay(LocalTime.of(10, 30, 1))
            }.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { converters.minuteOfDayToLocalTime(1_440) }
                .exceptionOrNull() is IllegalArgumentException,
        )
    }
}
