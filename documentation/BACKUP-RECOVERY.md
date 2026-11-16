# Backup and recovery · 1.2

## Export your saved references

Unlock administrator access, open Resources → Backup & restore, and choose Export knowledge backup. Select a destination through Android's file picker and wait for the successful-save message. Check that the file exists before removing the original installation.

Portable JSON **format 2** contains:

- Saved original knowledge and Q/A fields, record IDs, language/script/category, timestamps and source references.
- The imported-document catalog and its ordered passage mappings.
- Categories and up to 500 recent conversations, including separately stored trusted citation IDs.

It does **not** contain PIN/recovery credentials, model weights, app settings, embedding vectors, translation caches, full training/correction history or microphone recordings. Imported text is preserved in saved passages; the original PDF/DOCX/TXT file is separate and is not embedded in this backup. Keep the source file too if you need to preserve its original layout.

The export is bounded to 16 MiB and 20,000 knowledge records, with bounded catalog/mapping counts. It is not an unlimited streaming copy of the full SQLite database. Very large collections may exceed this portable limit.

**The JSON is plaintext.** It may contain personal facts, references and conversations. Keep trusted copies separately from the phone. Pocket AI itself makes no network requests; a destination supplied by an Android cloud document provider operates under that provider's own permissions.

## Restore a collection

1. Export the current collection first if you want to retain it.
2. Unlock administration, choose Restore from backup, and select a Pocket AI JSON backup.
3. Read the replacement warning and confirm.
4. Check the Documents library, a known passage and a question after success.

Restore validates supported version, size, IDs, source fields, conversation citation arrays and document mappings before destructive work. New catalog mappings cannot point at missing records or unrelated manual facts. Replacement occurs in a SQLite transaction, followed by count/integrity checks. Invalid input leaves existing data unchanged.

Restore replaces knowledge, catalog/mappings, categories and conversations; it is not a merge. Full prior training/correction history is replaced by a restore event. The current administrator PIN remains unchanged. Source record IDs are preserved so valid typed conversation citations still refer to the same saved passages. A citation whose source was deleted before export can remain historical and show Source removed; the app does not infer a replacement from a filename or model text.

Old **format 1** backups remain accepted. Their document catalog was not exported, so the app reconstructs groups from saved document records. Legacy conversation labels remain display text and may have no clickable trusted IDs. This reconstruction does not recover the original binary document or prove the original import-batch grouping.

Derived semantic vectors are cleared on restore and rebuilt locally. Open Offline tools, prepare/check included resources if necessary, then resume the reference search index. Keyword search and original reading remain available during indexing. Translation caches are disposable and invalidated when saved-reference revision changes; they are not restored as verified knowledge.

## Resources on a replacement installation

The 1.2 APK already contains the baseline search, translation, English-synthesis and recognition files. A fresh installation copies/checks them locally through Prepare offline tools; a cloud service or browser download is not needed for those baseline resources. Prepared copies, caches and indexes are separate from the knowledge backup.

Reconfigure answer language, Punjabi script, answer style and audio settings after moving to a new installation. Optional custom model files must be re-imported from copies you retained. Android TTS voices are system resources and may differ on another phone. Enough free storage/RAM and a supported CPU are still required.

## Forgotten administrator PIN

Use the recovery-code option on the unlock screen. Enter your private recovery code and a new 6–12 digit PIN. Save the new recovery code shown after success and discard the old one. Changing the PIN also rotates recovery credentials.

PINs/recovery codes are salted and derived with PBKDF2; they are not stored in readable form or included in exports. Repeated failures trigger delays. Sessions expire after inactivity, and normal backgrounding locks administration. There is no cloud account recovery or hidden bypass.

If both PIN and recovery code are lost, an existing knowledge backup can be restored into a fresh installation with new credentials. Clearing storage or uninstalling removes the current private database, prepared model copies and caches. Export first when you still have authorized access; do not rely on a reinstall to recover unsaved knowledge.

## Update and signing identity

The SQLite schema upgrades from version 1 to version 2 while retaining saved knowledge/history. New conversations have typed source IDs; legacy conversations default to an empty ID list rather than parsing IDs from visible labels. This schema migration is distinct from portable backup format conversion.

Android accepts an in-place release update only when application ID and signing identity match. Release uses `com.pocketai`; debug uses `com.pocketai.debug` with separate storage. Keep a knowledge backup before replacing an installation. A new signing key cannot update an APK signed by the old key.

Signing keys, passwords and signing-backup archives are excluded from public source/downloads. Developers must retain the original key privately to preserve its update identity. Neither the source ZIP nor an APK certificate can recover that private signing key.

## Device failure

Private app storage does not protect against physical failure or loss. Keep knowledge backups and original document files somewhere else. APK/model resources can reconstruct the runtime, but they do not reconstruct your saved collection without its backup.
