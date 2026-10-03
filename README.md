# GStoreShift

Android app that migrates photos/videos from a nearly-full host Gmail account to one or more
auxiliary Gmail accounts' **Google Drive** (`GStoreShift/YYYY/MM/` folders), with verified,
deduplicated, resumable uploads and safe host-side cleanup.

- **Plan:** [plan.md](plan.md) · **Tasks:** [TODO.md](TODO.md) · **API research:** [docs/spike-findings.md](docs/spike-findings.md)

## Key architecture decisions (from the P0 spike)

- **Drive (`drive.file` scope) is the v1 target.** As of 2025-03-31 Google removed the
  `photoslibrary`, `photoslibrary.readonly`, and `photoslibrary.sharing` scopes — only
  `photoslibrary.appendonly` / `*.appcreateddata` remain, so full-library operations are
  impossible. Uploading to aux Photos (`appendonly` + `YYYY-MM` albums) is a future enhancement.
- **No API exists to exclude folders from Google Photos backup.** Mitigations: verified local
  deletion, relocation to `Android/media/<pkg>/` (never backed up), and a guided manual
  walkthrough into Photos backup settings.
- **Idempotency:** files are deduped by MD5 (matches Drive's `md5Checksum` for verification).

## Build

Requires JDK 17 and the Android SDK (compileSdk 35).

```bash
./gradlew :app:assembleDebug    # Android Studio or gradle wrapper (add via `gradle wrapper`)
```

You must create a Google Cloud project with an OAuth Android client ID (your debug keystore
SHA-1) and enable the **Google Drive API** — see TODO.md P0.
