# Plan: Gmail Storage Migration Assistant (Android App)

## Working Name
**GStoreShift** — an Android app that moves photo/video storage from an almost-full Gmail (Google Photos) account to one or more nearly-empty auxiliary Gmail accounts.

---

## 1. Problem Statement

A user's **host Gmail account** is nearly out of Google storage (15 GB free tier), primarily because of **photos backed up to Google Photos** by their Android device. They have (or will create) **one or more auxiliary Gmail accounts** with almost-empty storage. The app should:

1. Move/back up photos from the host account to auxiliary account(s), organized into **Year/Month folders** for easy restore.
2. **Prevent the host device's Google backup from re-uploading** the already-migrated folders (the "tricky part").

---

## 2. Goals & Non-Goals

### Goals
- Run entirely on the Android device that owns the host Gmail account.
- Migrate photos from host Google Photos → auxiliary Google Photos/Drive, organized as `YYYY/MM` folders.
- Mark migrated local folders so Google Backup/Photos on the host skips them (deduplication, no double-backup).
- Support multiple auxiliary accounts, distributing storage when one fills up.
- Progress UI: storage before/after, per-account usage, migration logs.

### Non-Goals (v1)
- Migrating Gmail emails, Drive documents, or WhatsApp backups (photos/videos only).
- iOS / desktop support.
- Root-requiring features.

---

## 3. Feasibility & Constraints (must verify before/while building)

| Area | Constraint | Mitigation |
|---|---|---|
| Google Photos API | The Photos Library API is **write-limited**: new uploads go to the app's own album scope; full read/delete of another account's library requires the **partner/scope** permissions and OAuth verification. Use of `photoslibrary` scopes needs Google verification (sensitive scope). | Verify current Photos API scope availability; design graceful degradation (use Drive API folders as backup target if Photos write is restricted). |
| Drive API as alternative | `drive.file` scope allows uploading to folders the app created in the aux account — reliable fallback target, organized per Year/Month. | Default v1 target: **Drive folder tree** `GStoreShift/YYYY/MM/…` in the auxiliary account. Photos API upload as optional enhancement. |
| Blocking host re-backup | There is **no public API to configure Google Photos backup settings on-device**. | Strategy: (a) `.nomedia`-style approach does NOT stop Google Photos; instead (b) **move migrated files out of backed-up folders** (e.g., into an app-private or excluded directory), and/or (c) instruct/guide the user to disable backup for specific **device folders** in Photos settings (guided walkthrough with intents where possible), and (d) detect/re-verify via Photos API that items are absent before local deletion. |
| Media access | Android 10+ scoped storage; need `MANAGE_MEDIA`, `READ_MEDIA_IMAGES/VIDEO`, or SAF. | Use MediaStore + MediaStore.createDeleteRequest/trash flows, with user confirmation dialogs. |

---

## 4. Architecture

### 4.1 High-level flow
```
┌─────────────────────────────── Android Device ───────────────────────────────┐
│                                                                               │
│  Host Gmail (full)          GStoreShift App              Aux Gmail #1..N (empty)
│  ┌────────────┐   scan    ┌──────────────┐   upload    ┌────────────────────┐ │
│  │ MediaStore │──────────▶│ Migration    │────────────▶│ Drive/Photos via   │ │
│  │ (photos &  │           │ Engine       │             │ Google Sign-In     │ │
│  │ DCIM etc.) │◀──────────│ (WorkManager)│◀────────────│ OAuth tokens       │ │
│  └────────────┘  exclude/ └──────┬───────┘  resumable  └────────────────────┘ │
│                   skip list      │                                            │
│                            ┌─────▼─────┐                                      │
│                            │ Local DB  │  (Room: items, state, accounts,     │
│                            │ (Room)    │   per-file hashes, month folders)   │
│                            └───────────┘                                     │
└───────────────────────────────────────────────────────────────────────────────┘
```

### 4.2 Components
1. **Account Manager** — Google Sign-In for host + N aux accounts; encrypted token storage (EncryptedSharedPreferences); storage-quota checker (Drive `about.get?fields=storageQuota`).
2. **Media Scanner** — Enumerates MediaStore photos/videos; computes size/hash; groups by Year/Month from EXIF/`DATE_TAKEN`.
3. **Migration Engine** — WorkManager-based, resumable, chunked uploads; quota-aware account rotation (when aux #1 fills, spill to #2); idempotency via hash registry in Room so re-runs never duplicate.
4. **Folder Organizer** — Builds `GStoreShift/YYYY/MM/` Drive folder tree (or Photos albums `YYYY-MM`).
5. **Cleanup & Exclusion Manager** — After verified upload:
   - Delete local copy (MediaStore delete/trash with user approval).
   - Record folder in exclusion registry; guide user through Photos backup-settings screen for those folders (deep-link via intent where possible).
   - Optional: physically relocate leftover files to `Android/media/<app>/` which Google Photos does not back up.
6. **UI (Jetpack Compose)** — Dashboard (quota gauges), account onboarding, migration queue, per-month progress, exclusion list, settings.

### 4.3 Tech Stack
- **Kotlin**, Jetpack Compose, Material 3
- **Google Sign-In** + `play-services-auth`; scopes: `drive.file` (v1), optionally `photoslibrary`
- **WorkManager** for background/resumable work; **Room** for registry; **DataStore** for settings
- **MediaStore API** + Glide/Coil for thumbnails
- Google Drive REST v3 (files.create, resumable uploads)
- EncryptedSharedPreferences for tokens/PII

---

## 5. Key User Flows

1. **Onboarding**: Sign in host account → app shows "you're 93% full". Add aux account(s) → shows free space. Set target organizational scheme (default YYYY/MM).
2. **Scan**: Enumerate media, estimate total GB, show breakdown by year/month; user selects ranges to migrate.
3. **Migrate**: Background sync; live progress; quota-aware failover between aux accounts; pause/resume on network loss; charging/Wi-Fi-only constraints.
4. **Verify & Clean**: Confirm remote copies (size+checksum), then prompt user approval to remove local media; auto-add folders to host backup exclusion list; guided screenshot-assisted walkthrough to disable those device folders in Google Photos backup settings.
5. **Status**: Before/after storage bars; audit log exportable.

---

## 6. Security, Privacy & Compliance
- OAuth tokens only via Google Sign-In; no passwords stored; tokens encrypted at rest.
- All transfers direct device→Google over HTTPS; no third-party servers.
- In-app Play policy: declaration for `photoslibrary`/`drive` scope usage; Google OAuth verification required for production.
- Never delete a local file until remote copy is verified (size + MD5 comparison).
- Data-deletion & privacy policy screens (Play requirement).

---

## 7. Risks & Open Questions
1. **Photos API write limits** — confirm current Google Photos Library API policy; Drive fallback keeps v1 viable.
2. **Backup-exclusion automation** — no API to toggle Photos backup per folder; v1 ships guided manual step + folder relocation fallback. *(Research spike scheduled.)*
3. **Duplicate detection across accounts** — hash registry mitigates, but Photos compression ("storage saver") changes bytes; compare on original bytes where API allows.
4. **Play review scrutiny** for broad media access — must justify `READ_MEDIA_*` with core-functionality declaration.

---

## 8. Milestones (phased)

| Phase | Deliverable |
|---|---|
| P0 – Research spike | Validate Photos/Drive scopes, backup-exclusion feasibility, scoped-storage behavior on target Android versions (13–15). |
| P1 – Foundation | Project scaffold, account sign-in (host+aux), quota dashboard, Room schema. |
| P2 – Scanner | MediaStore scan, YYYY/MM grouping, selection UI, migration queue persisted. |
| P3 – Uploader | Drive resumable uploads, hash registry, quota-based aux failover, WorkManager scheduling. |
| P4 – Cleanup/Exclusion | Verified-delete flow, exclusion registry, guided backup-settings walkthrough. |
| P5 – Polish & Release | Error recovery UX, onboarding polish, Play listing + OAuth verification, beta rollout. |

---

## 9. Approval
Once this plan.md is approved, a **TODO.md** will be generated with actionable, ordered tasks derived from the phases above.
