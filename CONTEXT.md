# Medical Record domain context

Medical Record is a private, offline Android application for one person's medical history. Version
1 has no account, cloud, export, or visible patient-management surface. The data model still makes
patient ownership explicit so a later multi-patient or account model does not require reinterpreting
existing records.

## Terms

- **UserAccount** is a future authentication and synchronization identity. It does not exist in v1.
- **PatientProfile** is the person whose medical information is recorded. On first successful
  database creation, v1 creates one hidden default profile with a random UUID. That UUID is stored
  in the encrypted database and remains stable for the life of the local data.
- **Encounter** is one visit or episode of care. It owns zero or more encrypted **Attachments**.
- **Attachment** is metadata in Room plus an encrypted image or PDF in app-private storage. Its
  display name is not its storage path. A quarantined attachment failed authentication or format
  verification; that failure does not lock the database or other attachments.
- **Medication** describes a course with an inclusive start date and optional inclusive end date.
  A course is current only when `startDate <= today` and `endDate` is absent or `endDate >= today`.
- **Reminder intention** is the user's persisted request for a medication time. Notification
  permission, exact-alarm access, and the one occurrence currently mirrored in AlarmManager are
  separate scheduling state; losing platform permission must not delete the intention.
- **Database locked** means existing encrypted data or key envelopes cannot be authenticated or
  opened. It is an installation-level fail-closed state, distinct from attachment quarantine.

## Invariants

- Every encounter, attachment, medication, and reminder has a non-null `patientId`.
- Composite foreign keys prevent an attachment or reminder from referring to a parent belonging to
  another patient.
- Deleting an encounter cascades its attachment metadata; deleting a medication cascades reminders.
- Parent updates never use SQLite `REPLACE`, because replacement can trigger cascade deletion.
- Medication and normalized reminder changes commit in one Room transaction.
- Database and attachment bytes are encrypted at rest. Key material and medical content are never
  written to logs, backups, notification public views, or long-lived plaintext cache files.
