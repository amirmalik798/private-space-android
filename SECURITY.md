# Security policy and design notes

## Supported security properties

- Encrypted file contents and encrypted vault metadata at rest inside app-private storage.
- AES-GCM authenticated encryption, per-record associated data for newly written records, and rejection of failed integrity checks.
- AES keys held in Android Keystore.
- Password-based app access with PBKDF2-HMAC-SHA256, persisted failed-attempt lockout, and in-memory password-array clearing after verification/derivation.
- App lock on foreground exit, screenshot/recent-task protection, backup exclusion, and narrow FileProvider cache exposure.

## Explicit limitations

- Opening, sharing, or exporting requires plaintext to exist temporarily or at the destination.
- External apps may retain shared content; exported copies are not encrypted by Private Space.
- The app password is not used as the Android Keystore encryption key and is not a recovery mechanism.
- Device compromise, root access, malicious system components, and attacks against an already-unlocked device are outside the protection boundary.
- There is no account, sync, cloud storage, password reset, or recovery key.

## Reporting

Do not include private documents, passwords, or unredacted vault metadata in bug reports. Provide reproduction steps and sanitized logs only.
