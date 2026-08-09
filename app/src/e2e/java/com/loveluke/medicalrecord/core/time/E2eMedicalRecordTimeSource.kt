package com.loveluke.medicalrecord.core.time

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class E2eMedicalRecordTimeSource @Inject constructor() : MedicalRecordTimeSource {
    private val currentInstant = AtomicReference(DEFAULT_INSTANT)
    private val currentZoneId = AtomicReference(DEFAULT_ZONE_ID)

    override fun instant(): Instant = currentInstant.get()

    override fun zoneId(): ZoneId = currentZoneId.get()

    fun freeze(instant: Instant, zoneId: ZoneId = DEFAULT_ZONE_ID) {
        currentZoneId.set(zoneId)
        currentInstant.set(instant)
    }

    fun reset() {
        freeze(DEFAULT_INSTANT, DEFAULT_ZONE_ID)
    }

    companion object {
        val DEFAULT_INSTANT: Instant = Instant.parse("2030-06-15T02:00:00Z")
        val DEFAULT_ZONE_ID: ZoneId = ZoneId.of("Asia/Shanghai")
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class MedicalRecordTimeModule {
    @Binds
    @Singleton
    abstract fun bindMedicalRecordTimeSource(
        implementation: E2eMedicalRecordTimeSource,
    ): MedicalRecordTimeSource
}
