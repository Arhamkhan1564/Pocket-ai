# Backup and recovery

## Export knowledge

Unlock administrator access, open Resources → Backup & restore, and choose Export knowledge backup. Pick a destination in Android's file picker. Wait for the successful-save message.

The portable JSON backup contains:

- Original verified knowledge, question/answer fields, language/script/category and source metadata.
- Categories and the most recent bounded conversation history.

It does not include administrator PIN/recovery credentials, model files, app settings, full correction history or training history. Imported document text remains in knowledge records with its source references; the original document file is separate. A portable backup is limited to 16 MB and 20,000 knowledge records. Very large collections need a future streaming/full-database export workflow.

**The JSON backup is plaintext.** It can contain private facts and conversations. Store it privately, check that it exists and can be opened as JSON, and keep another trusted copy if the device matters to you. A destination supplied by a cloud document provider is governed by that provider; Pocket AI itself makes no network request.

## Restore knowledge

1. Export the current installation first if it contains information you want to keep.
2. Unlock administrator access, choose Restore from backup, and select a Pocket AI JSON backup.
3. Read the replacement warning and confirm.
4. After success, check the memory browser and a known question in User Mode.

Restore validates the backup format/version, sizes, record IDs and fields before destructive changes. Replacement runs in a database transaction and checks the resulting count and SQLite integrity. Invalid backups are rejected. Restore replaces current knowledge/categories/conversations and prior training/correction history; it does not merge two collections. The resulting restore event is recorded in the new training history.

Restore does not change the current administrator PIN. Reconfigure languages/settings and re-import model files separately on a new installation. If the source file is larger than the portable safety limit, split/re-export a smaller supported collection rather than editing record IDs manually.

## Forgotten administrator PIN

Use the recovery-code option on the administrator unlock screen. Enter the private recovery code and a new 6–12 digit PIN. On success, a **new recovery code** is shown; keep it and discard the old code. Changing the PIN also rotates the recovery code.

PINs and recovery codes are salted and derived using PBKDF2. They are not stored as readable text and are not included in knowledge backups. Repeated failures trigger increasing delays. Sessions expire after inactivity, and backgrounding normally locks administration.

If both PIN and recovery code are lost, there is no bypass or cloud account recovery. Retained private backup files can be restored into a fresh installation with new credentials. Clearing Android app storage or uninstalling destroys the current private database and imported models, so do not do either while relying on data that has not been exported.

## Updating or rebuilding the app

Android accepts an in-place update only when its application ID and signing identity match. The supplied release uses `com.pocketai`; the debug build uses `com.pocketai.debug` and stores separate data.

Signing keys, passwords and signing-backup archives are excluded from the public repository and downloads. If you hold the original signing key privately, keep it and its credentials secure; anyone who obtains them can sign APKs with that identity.

A developer needs the same privately retained key and a local `signing.properties` configuration to build an update with the supplied release's identity. Generating a new key creates a different signing identity and will not update the old release. Export knowledge before replacing an installation. No signing credentials can be recovered from the source archive.

## Device loss or failure

An app-private database does not protect against physical device failure. Keep portable knowledge backups separately from the phone and separately from model downloads. Knowledge recovery requires the JSON backup; rebuilding the APK or retaining its signing key alone does not recover the database.
