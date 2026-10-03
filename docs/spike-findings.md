# P0 Spike Findings — Google Photos API & Backup Exclusion

> Status: **Completed (web research)** + **2 items deferred to on-device validation** during P0 build.

## 1. Photos Library API scope availability — ANSWERED ✅

Source: [Google Photos API 2025 Updates](https://developers.google.com/photos/support/updates) (effective 2025-03-31).

### What happened
Google removed the scopes that would let us read/verify another account's existing library:

**Removed:**
- `photoslibrary.readonly`
- `photoslibrary.sharing`
- `photoslibrary` (full access)

**Remaining:**
- `photoslibrary.appendonly` — upload media & create albums (app-created only)
- `photoslibrary.readonly.appcreateddata` — list/get ONLY app-created items
- `photoslibrary.edit.appcreateddata` — edit/patch/remove ONLY app-created items

### Impact on GStoreShift
| Plan item | Verdict |
|---|---|
| Upload photos to aux account Photos library (YYYY-MM albums) | ✅ Possible via `photoslibrary.appendonly` — can be the future "move into Photos itself" enhancement. Uploaded items are **app-created**, so the app can also delete/verify them later. |
| Read host account's existing Photos library via API | ❌ Not possible. **Unneeded** — the host side works off the device's local MediaStore, not the cloud library. |
| Delete host-side Photos cloud copies via API | ❌ Not possible for non-app-created items. Host-side cleanup = local deletes + host account's own Photos app "Free up space" feature (guided). |
| Verify uploaded bytes exist in Photos | ✅ Only for app-created items (`readonly.appcreateddata`). |

### Conclusion
**Drive (`drive.file` scope) is confirmed as the v1 target**, exactly as planned. Photos Library API `appendonly` becomes the P5+ enhancement ("move to Photos proper") — viable but optional, and it changes how space is counted (uploaded items still count against aux account storage, same as Drive).

## 2. Google Drive quota & file visibility — MOSTLY CONFIRMED ✅

- `drive.file` scope: app can create/read/update/delete **only files/folders it created**. ✅ Sufficient; also keeps Play/OAuth verification simpler (non-restricted... note: `drive.file` is still a *sensitive* scope but easy to justify).
- Quota: `about.get?fields=storageQuota` returns `limit`/`usage`. ❗ *Must validate with `drive.file`-only tokens in the P0 build* — historically works, flag as VAL-1.
- Files uploaded with `drive.file` are **visible & searchable in the aux account's normal Drive UI**, satisfying the "easily searchable by Year/Month folder" requirement. ✅

## 3. Host backup exclusion ("the tricky part") — CONFIRMED NO PUBLIC API ⚠️

There is **no Android/Google API** to toggle Google Photos backup per device folder. Confirmed mitigations, in order of implementation:

1. **Relocate (v1 default):** move migrated files into `Android/media/<package>/` (app-specific external storage). Google Photos does **not** back up app-specific directories and won't scan them. User loses gallery visibility — mitigated by optional "keep local copy" toggle.
2. **Delete after verify (the actual goal):** once upload is checksum-verified, delete the local copy via `MediaStore.createDeleteRequest` (user-approved dialog). Nothing left to back up → problem dissolves. This is the primary path; relocation is the fallback for users who want local copies.
3. **Guided manual toggle:** deep-link to Photos app settings (best effort — `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` for `com.google.android.apps.photos`, then screenshot-annotated walkthrough to Backup → "Back up device folders"). ❗ Validate working deep link per Photos app version — VAL-2.
4. `.nomedia`: **rejected** — does not stop Google Photos backup.

## 4. Validation tasks carried into P0 build (on-device spike)

| ID | Task |
|---|---|
| VAL-1 | Confirm `about.get` storageQuota works with `drive.file`-scoped token. |
| VAL-2 | Probe deep links into Google Photos backup-settings screen on Android 13–15 + current Photos versions; document fallback to plain settings intent + walkthrough. |
| VAL-3 | Confirm `MediaStore.createDeleteRequest` batch UX & behavior on API 30/31/33/34 (trash vs delete differences). |
| VAL-4 | Confirm uploads >5 min session survive via Drive resumable-upload session URI persisted across process death. |

## 5. Decision record

- **Target v1:** Drive folder tree `GStoreShift/YYYY/MM/…` per aux account. ✅ (as user requested)
- **Future:** Photos upload via `photoslibrary.appendonly` into `YYYY-MM` albums — tracked as a P5+ backlog item.
