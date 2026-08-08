# ADR 0002: Use wrapped local secrets and fail-closed access

- Status: Accepted
- Date: 2026-08-08

## Decision

Use one non-exportable AES-256 Android Keystore wrapping key per application ID. It independently
wraps a random SQLCipher database passphrase and a random attachment master key. Versioned
authenticated envelopes live under `noBackupFilesDir`; they are not stored in preferences or
included in backup.

Each attachment is stored in a versioned AES-GCM container with authenticated patient, encounter,
attachment, payload-kind, and format identity. A per-file random data key prevents reuse of the
long-lived master key for attachment payload encryption.

If existing data or an envelope cannot be unlocked, the app does not generate replacement keys,
delete files, or create an empty database. It exposes retry and an explicitly confirmed local-data
clear path. An authentication failure in one attachment quarantines only that attachment.

## Consequences

- Loss or invalidation of the wrapping key is visible and recoverable only by clearing local data;
  the app never gives a false impression that old records were recovered.
- Database and attachment keys can evolve independently while sharing the platform-protected root.
- Release and debug are isolated because aliases and envelope locations derive from the runtime
  package identity and each variant already has a separate app sandbox.
