# ADR 0001: Rebuild as a new application identity

- Status: Accepted
- Date: 2026-08-08

## Decision

Rebuild the application as a new Android/Gradle project within the existing Git repository and
history. The release identity is `com.loveluke.medicalrecord`; debug is
`com.loveluke.medicalrecord.debug`. Database schema history restarts at version 1 and application
`versionCode` restarts at 1.

The old `com.crasuna.medicalrecord` project is a product and field reference only. Its database,
attachments, Keystore entries, alarms, signing identity, installation compatibility, and backup
state are not migrated.

## Consequences

- The new app can establish correct patient, encryption, attachment, and reminder invariants from
  its first schema.
- Existing installations are intentionally separate and cannot be upgraded in place.
- Debug and release have separate Android sandboxes, Keystore aliases, alarms, notifications,
  preferences, and attachment stores.
