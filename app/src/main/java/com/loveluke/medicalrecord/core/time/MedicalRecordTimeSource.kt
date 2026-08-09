package com.loveluke.medicalrecord.core.time

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Single application clock so date-sensitive behavior can be deterministic in the E2E target. */
interface MedicalRecordTimeSource {
    fun instant(): Instant

    fun zoneId(): ZoneId

    fun today(): LocalDate = instant().atZone(zoneId()).toLocalDate()
}

internal object SystemMedicalRecordTimeSource : MedicalRecordTimeSource {
    override fun instant(): Instant = Instant.now()

    override fun zoneId(): ZoneId = ZoneId.systemDefault()
}

internal class ClockMedicalRecordTimeSource(
    private val clock: Clock,
    private val zoneIdProvider: () -> ZoneId = { clock.zone },
) : MedicalRecordTimeSource {
    override fun instant(): Instant = clock.instant()

    override fun zoneId(): ZoneId = zoneIdProvider()
}

internal fun MedicalRecordTimeSource.asClock(): Clock = MedicalRecordSourceClock(this)

private class MedicalRecordSourceClock(
    private val source: MedicalRecordTimeSource,
    private val zoneOverride: ZoneId? = null,
) : Clock() {
    override fun getZone(): ZoneId = zoneOverride ?: source.zoneId()

    override fun withZone(zone: ZoneId): Clock = MedicalRecordSourceClock(source, zone)

    override fun instant(): Instant = source.instant()
}
