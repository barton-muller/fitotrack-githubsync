# GitHub sync for FitoTrack

A patch on top of [FitoTrack](https://codeberg.org/jannis/FitoTrack) that keeps your workouts, personal measurements and settings in a GitHub repo you own. Sign in with GitHub, choose a repo or create a private one, and sync.

Everything lives in `app/src/main/java/de/tadris/fitness/sync/github/`. Upstream files touched: `ExportTarget.kt`, `AppNavigation.kt`, `BackupSettingsScreen.kt`, `AndroidManifest.xml` (one permission) and a strings and an icon file.

## Use it

Settings → Backup → **GitHub sync**.

1. **Sign in with GitHub.** A code is copied and GitHub opens in an in-app tab. Paste the code and approve. Close the tab to return.
2. **Choose a repo.** The list shows the repos the GitHub App is installed on. **New private repo** creates `fitotrack-data`. **Choose repos on GitHub** opens the install page to add more, then **Refresh list**.
3. **Sync now.** Imports new workouts from the repo, uploads local ones, applies the rules below.

New workouts you record are uploaded on their own after the workout ends (needs a network connection; retried later if offline). Everything else happens when you press **Sync now**.

## What is stored

| Path in the repo | Content |
|---|---|
| `fitness/<name>.gpx` | One GPX per workout. Comment and workout type survive the round trip. |
| `fitness/settings.json` | Weight, step length and app preferences. |

Device-specific and sensitive keys are not synced: anything matching `token`, `secret`, `auth`, `uri`, `director`, `path`, `file`, `bluetooth`, `device`, plus a few first-run flags. See `deny` in `GitHubSync.kt`.

## Rules

- **Workouts** are matched by start time. A workout is never imported twice and never uploaded twice. Files you deleted on this device are not downloaded again.
- **Deleting** a workout here removes its GPX from the repo on the next Sync now (switch: *Delete from repo too*). Git history keeps the file. Deleting in the repo does not delete the workout on devices that already have it.
- **Settings:** the first sync on a device applies what the repo has. After that, a change made on this device wins and is pushed. Otherwise a change in the repo is applied. Restart the app to see all changes. Switch: *Sync settings and measurements*.
- Sync is manual for downloads. A file larger than the GitHub API limit (100 MB) is not a concern for GPX.

## Set up your own copy

1. Make a **GitHub App**: Settings → Developer settings → GitHub Apps → New.
   - Homepage URL: anything valid. No callback URL. Webhook: untick *Active*.
   - **Enable Device Flow**: on. **Expire user authorization tokens**: off, because refreshing a token needs the client secret, which an APK must not contain.
   - Repository permissions: **Contents: Read and write**.
   - Optional, for *New private repo*: the repository-creation permission.
2. Put the app's **Client ID** and **slug** into `GitHubConfig.kt`.
3. Install the app on your account and select the repos it may use.

## Build and releases

`.github/workflows/build.yml` builds a debug APK on every push to `main`, uploads it as an artifact, and replaces the **Latest build** pre-release. Pushing a tag `v*` makes a permanent release. The APK has the id `de.tadris.fitness.debug`, so it installs beside the F-Droid app, and it is signed with the debug key committed in `ci/debug.keystore` so that new builds update in place. That key is public: treat these APKs as personal builds.

## Following upstream

`.github/workflows/upstream-sync.yml` rebases `main` onto `jannis/FitoTrack` every day and opens an issue when it conflicts. The settings screen is the file most likely to conflict.

## Limits

- Android only, same as FitoTrack. Needs a GitHub account.
- One commit per workout file. A big first sync makes many commits.
- Two devices editing the same setting offline: the one that syncs last wins.
- Heart-rate data is part of the GPX; other sensor data FitoTrack does not export to GPX is not synced.
