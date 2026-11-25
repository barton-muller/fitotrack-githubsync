# GitHub sync for FitoTrack

An unofficial patch on top of [FitoTrack](https://codeberg.org/jannis/FitoTrack). It keeps your workouts, personal measurements and settings in a git repo you own, on GitHub or any other git host. Sign in with GitHub and choose a repo or create a private one, or connect any host with a repo address and an access token, and sync.

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
3. **Sync now.** Merges the workouts and settings of the device with the repo, as described below.

### Another git host

**Advanced: another git host** on the same screen is collapsed until you open it. Enter the address of a repo (`https://…`), the branch and an access token, and tap **Connect**. The app talks to the host with git itself (JGit, over HTTPS), so it works with any git server: GitHub with a personal access token, GitLab (group paths with subgroups too), Codeberg, a self-hosted Forgejo or Gitea, or a server of your own. The app checks that it can reach the repo first and shows what is wrong if it cannot. The token is stored encrypted with the Keystore key, and the files are kept in a local bare repo, so only what changed is transferred.

Give the token only what it needs: write access to that one repo.

| Host | Token |
|---|---|
| GitHub | A fine-grained token with *Contents: Read and write* on that repo |
| GitLab | A project or personal access token with the `write_repository` scope (and the *Developer* role or higher) |
| Codeberg, Forgejo, Gitea | An access token with read and write access to repositories |

The repo may be empty: the branch is created by the first sync. If the connection fails, check the address, that the token has the access above, and that the server is reachable from the phone.

**Why this differs from the web projects.** A web page cannot speak the git protocol to these hosts (they do not allow it through CORS), so [pages-storage](https://github.com/barton-muller/pages-storage) uses each host's REST API there, with one adapter per host kept in step by a shared test suite. An Android app is not bound by CORS and uses git directly, so one transport covers every host.

After you record a workout, a sync runs on its own. It needs a network connection and is retried later when offline. Otherwise sync runs when you press **Sync now**.

## What is stored

| Path in the repo | Content |
|---|---|
| `fitness/<name>.gpx` | One GPX file per workout. Comment and workout type survive the round trip. |
| `fitness/settings.json` | Weight, step length and the app preferences. |

Device-specific and secret preferences are not synced: tokens, content URIs, directories, Bluetooth devices and first-run flags. The rule is `PreferencesSnapshot.excluded`.

Other apps can share the repo. Each app keeps its files in its own folder, and this one only touches `fitness/`.

## How sync works

Sync is a three-way merge. The device remembers what the last sync left behind (the base) and compares it with the workouts on the device and the files in the repo:

| In the base | On the device | In the repo | Meaning | Result |
|---|---|---|---|---|
| no | yes | no | new here | uploaded |
| no | no | yes | new in the repo | imported |
| yes | no | yes | deleted here | removed from the repo (switch *Delete from repo too*), otherwise left alone |
| yes | yes | no | deleted in the repo | deleted here (switch *Delete here too*), otherwise it stays here and is not uploaded again |
| yes | no | no | gone on both sides | forgotten |

Workouts are matched by start time, so a workout is never imported or uploaded twice. Everything that changed goes to the repo in one commit. When someone else committed in the meantime, the commit is refused and the merge runs again on the new state. Merging twice gives the same result, so a repeat is safe.

**Settings** are merged per key against the settings of the last sync. A key changed on one side wins over a key that did not change on the other. A key changed on both sides takes the value of the device that syncs last, except on the first sync of a device, where the repo wins, so a new device picks up your settings. A key only one side has is kept. Restart the app to see all of it. Switch: *Sync settings and measurements*.

**Test connection** on the sync screen checks DNS and HTTPS from the app's point of view, which helps when a VPN, private DNS or a firewall app gets in the way.

The merge does not depend on how the files travel. `RemoteStore` has three calls (fetch a snapshot, read a file, commit conditionally on a revision), and the engine only uses those. The same three calls are the core of the stores in pages-storage. `GitHubRemote` implements them with GitHub's API (used when you sign in with GitHub), `GitRemote` with git over HTTPS through JGit (used for every other host), so another transport can be added without touching the merge rules.

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
