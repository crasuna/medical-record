package com.loveluke.medicalrecord.core.attachment

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AttachmentServiceBindings {
    @Binds
    @Singleton
    abstract fun bindEncryptedAttachmentService(
        implementation: DefaultEncryptedAttachmentService,
    ): EncryptedAttachmentService
}
