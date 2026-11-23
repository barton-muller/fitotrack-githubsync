# GitHub sync for FitoTrack

An unofficial patch on top of [FitoTrack](https://codeberg.org/jannis/FitoTrack). It keeps your workouts, personal measurements and settings in a GitHub repo you own. Sign in with GitHub, choose a repo or create a private one, and sync.

## Where the idea comes from

[pages-storage](https://github.com/barton-muller/pages-storage) is a small library for static web pages that have no backend. The person signs in with a GitHub App, picks which repos the app may use, and the page reads and writes files in a repo that person owns. No server, no database, no password, and the data stays with its owner.

This patch applies the same model to a native Android app:

| | pages-storage (web) | GitHub sync (this patch) |
|---|---|---|
| Identity | GitHub App, user picks repos | The same |
| Where the data lives | A repo the person owns | The same |
| Sign-in | Web flow, with a small relay that holds the client secret | Device flow, which needs no secret and no relay |
| Token | Expires after 8 hours, refreshed through the relay | Does not expire, kept encrypted with an Android Keystore key |
| Repo list and creation | `repos()` and `createRepo()` | The same API calls, re-implemented in Kotlin |
| Storage | One file per request, small files | GPX files and one JSON file, read through the git blobs API |

No code is shared: pages-storage is JavaScript for browsers and this is Kotlin for Android. If you want the web counterpart, for example to show your workouts on a page, build it with pages-storage and read the same repo.

## Use it

Settings, Backup, **GitHub sync**.

1. **Sign in with GitHub.** A code is copied and GitHub opens in an in-app tab. Paste the code and approve. Close the tab to come back.
2. **Choose a repo.** The list shows the repos the GitHub App is installed on. **New private repo** creates `fitotrack-data`. **Choose repos on GitHub** opens the install page to add more, then **Refresh list**.
3. **Sync now.** Imports workouts from the repo, uploads local workouts, removes repo files of workouts you deleted, and syncs the settings.

New workouts you record are uploaded on their own after the workout ends. They need a network connection and are retried later when offline. Everything else happens when you press **Sync now**.

## What is stored

| Path in the repo | Content |
|---|---|
| `fitness/<name>.gpx` | One GPX file per workout. Comment and workout type survive the round trip. |
| `fitness/settings.json` | Weight, step length and the app preferences. |

Device-specific and secret preferences are not synced: tokens, content URIs, directories, Bluetooth devices and first-run flags. The rule is `PreferencesSnapshot.excluded`.

Other apps can share the repo. Each app keeps its files in its own folder, and this one only touches `fitness/`.

## Rules

- **Workouts** are matched by start time. A workout is never imported twice and never uploaded twice. A workout you deleted on this device is not downloaded again.
- **Deleting** a workout here removes its GPX from the repo on the next Sync now. Switch: *Delete from repo too*. The git history keeps the file. Deleting in the repo does not delete the workout on devices that already have it.
- **Settings.** The first sync on a device applies what the repo has, so a new device picks up your settings. After that a change made on this device wins and is pushed, and otherwise a change in the repo is applied. Restart the app to see all of it. Switch: *Sync settings and measurements*.
- **Test connection** on the sync screen checks DNS and HTTPS from the app's point of view, which helps when a VPN, private DNS or a firewall app gets in the way.

## Privacy and security

- The app talks to `github.com` and `api.github.com` and nowhere else for sync. There is no server of mine.
- The access token is stored encrypted with a key in the Android Keystore. Signing out deletes it. To revoke it everywhere, remove the app under GitHub, Settings, Applications.
- The GitHub App can only reach the repos you select when you install it. The data is as private as the repo.
- A GPX file holds your route and times. Use a private repo.

## Set up your own copy

The Client ID and app name in `GitHubConfig.kt` belong to my GitHub App. Anyone who builds this should make their own, otherwise their sign-ins go through mine.

1. Make a **GitHub App**: Settings, Developer settings, GitHub Apps, New.
   - Homepage URL: anything valid. No callback URL. Webhook: untick *Active*.
   - **Enable Device Flow**: on. **Expire user authorization tokens**: off. Refreshing a token needs the client secret, which an app must not contain.
   - Repository permissions: **Contents: Read and write**.
   - Optional, for *New private repo*: the repository-creation permission. Without it the app shows an error and you create the repo on github.com.
2. Put the **Client ID** and the app's **slug** into `GitHubConfig.kt`.
3. Install the app on your account and select the repos it may use.

## Build and releases

`.github/workflows/build.yml` builds a debug APK on every push to `main`, keeps it as an artifact and replaces the **Latest build** pre-release. Pushing a tag `v*` makes a permanent release.

The APK has the id `de.tadris.fitness.debug`, so it installs next to the F-Droid app. It is signed with the debug key in `ci/debug.keystore`, so that new builds update in place. That key is public: these are personal builds, not a trusted distribution. Use your own signing key if you publish releases to others.

Locally: `./gradlew assembleDebug`, and `./gradlew testDebugUnitTest` for the unit tests.

## Following upstream

`.github/workflows/upstream-sync.yml` rebases `main` onto `jannis/FitoTrack` every day, builds the result, and opens an issue when the rebase conflicts. Upstream files this patch touches: `ExportTarget.kt`, `AppNavigation.kt`, `BackupSettingsScreen.kt`, `AndroidManifest.xml` (one permission) and `app/build.gradle` (one test dependency). The settings screen is the file most likely to conflict.

## Limits

- Android only, like FitoTrack. Needs a GitHub account.
- Downloads happen on Sync now, not in the background.
- One commit per workout file, so a big first sync makes many commits.
- Two devices changing the same setting offline: the one that syncs last wins.
- Only what FitoTrack writes to GPX is synced, which includes heart rate but not other sensor data.

## Licence

GPL-3.0-or-later, the same as FitoTrack. See `LICENSE.txt`.
