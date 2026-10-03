# GStoreShift — TODO

Derived from `plan.md` (approved) and `docs/spike-findings.md`.
Legend: ⬜ todo · 🔵 in progress · ✅ done · ❌ blocked

## P0 — Spike validation & foundation
- [ ] **VAL-1** Confirm `about.get?fields=storageQuota` works with a `drive.file`-scoped token (test OAuth client + emulator).
- [ ] **VAL-2** Probe deep links into Google Photos backup-settings screen on Android 13/14/15; document fallback (app-details settings intent + guided walkthrough).
- [ ] **VAL-3** Confirm `MediaStore.createDeleteRequest` / trash behavior on API 30/31/33/34.
- [ ] **VAL-4** Confirm Drive resumable-upload session URI survives process death and completes after restart.
- [ ] Create Google Cloud project, OAuth client IDs (debug + release), enable Drive API & (later) Photos Library API.
- [ ] Gradle project scaffold compiles on CI/local (AGP, Kotlin, Compose, Hilt-less DI or Koin — decide: start with manual DI).

## P1 — Foundation
- [ ] `AccountManager`: Google Sign-In for host + N aux accounts; token storage in EncryptedSharedPreferences.
- [ ] `QuotaRepository`: fetch/cache `storageQuota` per account; expose as Flow.
- [ ] Room DB: entities `MediaItem`, `MigrationTask`, `AuxAccount`, `ExclusionEntry`, `UploadReceipt`.
- [ ] Dashboard UI shell: storage gauges (host vs each aux), navigation scaffold.

## P2 — Scanner
- [ ] `MediaScanner`: MediaStore query (images+videos), EXIF/`DATE_TAKEN` → Year/Month grouping; MD5/SHA-256 hash (chunked).
- [ ] Selection UI: tree by Year → Month, with sizes; select/unselect; total estimate bar.
- [ ] Persist scan to `MediaItem` table with state = SCANNED.

## P3 — Uploader & Migration Engine
- [ ] `DriveClient`: folder-tree creation/cache (`GStoreShift/YYYY/MM`), resumable upload (chunked, 8 MB), MD5 verify (`md5Checksum` on response), retry w/ exponential backoff.
- [ ] Quota-aware `AccountRotator`: pick aux account with free space ≥ file size + 5% headroom; mark account FULL, rotate on `insufficientStorage`.
- [ ] `MigrationWorker` (WorkManager): foreground service + progress notification; constraints (Wi-Fi/charging configurable); idempotent via hash registry (skip if receipt exists).
- [ ] Progress UI: live queue, per-file status, pause/resume, failure list with retry.

## P4 — Cleanup & Exclusion
- [ ] Verify-then-delete: compare remote MD5 vs local hash → batch `createDeleteRequest` user approval.
- [ ] `ExclusionRegistry`: record migrated folders; optional **relocate mode** moving kept local copies to `Android/media/<pkg>/` (backup-safe per spike §3).
- [ ] Guided walkthrough screen: disable backup of device folders in Photos (uses VAL-2 result), with annotated screenshots.
- [ ] Audit log per migration batch ( Room + export to CSV/JSON).

## P5 — Polish & Release
- [ ] Error recovery UX (token expiry → silent re-auth; network loss resume).
- [ ] Onboarding polish + empty states.
- [ ] Privacy policy + Play data-safety form; OAuth verification submission (`drive.file` scope justification).
- [ ] Play internal → closed beta rollout.
- [ ] **Backlog (future):** Photos Library API `photoslibrary.appendonly` target — upload into aux Photos as `YYYY-MM` albums ("move to Photos proper").

## Open risks
- OAuth verification turnaround time for sensitive scope.
- Photos app UI changes breaking guided walkthrough (VAL-2).
- Large video uploads over cellular — default to Wi-Fi only.
