# Worbus for Android

The Android app of **Worbus**: a music app for your own Navidrome server, with one playback session
shared across your devices, stations and mixes, and a library that knows what it is missing.

> **Worbus for Android is a fork of Navic by paige** ([ssalggnikool/Navic](https://github.com/ssalggnikool/Navic)),
> with the fork's own UI, server and features on top. All credit for Navic itself belongs to paige
> and its contributors. Licensed GPL-3.0, like the original.
>
> Worbus is not affiliated with Navidrome.

**Downloads and issues: see [`illuminator69/worbus`](https://github.com/illuminator69/worbus).** That
repository is the front door: it has the releases of every piece (this app, Worbus for desktop and
Worbus Server), the install guide and the one issue tracker.

## Installing

- **Worbus installs beside Navic.** It has its own app ID (`app.worbus.android`), so an installed
  Navic keeps working, with its own login and downloads, until you uninstall it. Worbus starts with
  empty settings: sign in once and download again what you want offline.
- **Later releases of Worbus install over each other** and keep your login. An APK you build
  yourself does not: it is signed with your own key (see [Building](#building)).
- **Android 7.0 or newer.** Android is the only platform.

## What it adds

Without a Worbus Server it is an ordinary Navidrome client. With one, it is a client for a
**shared playback session** across devices, like Spotify Connect over your own Navidrome, and a front
end for **lb-bot**, which knows what your library is missing.

<p align="center">
  <img src="docs/screenshots/navic-home.webp" width="190"
       alt="Home: mood chips, a Continue listening pill over 20 saved queues, Recently added and Fresh rows, and a mini-player reading Playing on another device" />
  <img src="docs/screenshots/navic-discography.webp" width="190"
       alt="An artist's discography: filters by release type and ownership, each album marked Owned, Partly owned with a progress bar, or Missing" />
  <img src="docs/screenshots/navic-album.webp" width="190"
       alt="A partly owned album washed in its cover's colours, with an 8 tracks missing card and a Fill gaps button" />
  <img src="docs/screenshots/navic-album-missing-tracks.webp" width="190"
       alt="The same card unfolded: the eight missing tracks listed, the release they were measured against, and Re-check and Open in lb-bot" />
</p>

- **Its own UI.** Three tabs and a **+** button. **Home** has a pill to resume a saved queue,
  mood chips and your mixes. **Search** also holds the discovery rows. **+** makes a playlist, a
  smart playlist or a custom radio, or finds what a pasted streaming link points to (with lb-bot).
  An **avatar menu** holds settings and the lb-bot pages.
- **A now-playing screen with the queue inside it**, and the controls in reach.
- **Pages in the artwork's colours**, known before the page is drawn.
- **The albums you don't own**, shown on the artist page beside the ones you do. One album page
  takes a missing album from choosing an edition to downloading it.
- **lb-bot in the app**: Needs attention, Source history, listening stats, Library tools, all from
  the avatar menu.
- **Remote control and transfer with resume.** The player mirrors the shared session, and the
  notification, lock screen and Bluetooth controls drive the *remote* session.
- **Native Chromecast**, via `NsdManager` and a hand-rolled castv2 client: no Cast SDK, no Play
  Services.
- **Stations and mixes from the server**: radio and autoplay that carry on unchanged across devices,
  custom radio from up to eight artists, and mood mixes built with AudioMuse-AI.
- **Saved queues and Continue listening**, synced through the server and shared with Worbus for
  desktop.

## Setting it up

Install Worbus Server first (the install guide is in
[`illuminator69/worbus`](https://github.com/illuminator69/worbus)). Then sign in to Navidrome in the
app: it finds the Worbus Server beside Navidrome or on your home network by itself, and **avatar menu
→ Settings → Server** shows what it found. When you report a bug, attach the app's log:
**Settings → About → Copy logs for a report**.

## Building

```bash
git clone https://github.com/illuminator69/worbus-android.git
cd worbus-android
./gradlew :androidApp:assembleRelease    # androidApp/build/outputs/apk/release/Worbus.apk
```

Gradle provisions its own JDK 21; the Android SDK path goes in `local.properties` (`sdk.dir=…`).
`:androidApp` is the application module; `:composeApp` is the shared KMP library.

- **Judge performance on a release build.** Debug Compose is far choppier and will mislead you.
- **Signing.** Set `SIGNING_*` to sign with a release key. With them unset, the release APK is
  signed with your machine's debug key (`~/.android/debug.keystore`) and Gradle says so in one
  warning line. The published APK carries the release key, whose certificate's SHA-256 is in
  `androidApp/release-cert.sha256`; a build of your own is not it, so to put one on a phone that has
  the release you must uninstall first.

## Documentation

- **`CLAUDE.md`** (this repo): the app's architecture, theming engine and gotchas. The single source
  of truth for working *in this tree*.
- **[`illuminator69/worbus`](https://github.com/illuminator69/worbus)**: Worbus Server, the wire
  protocol, the desktop app, and how the whole stack fits together.

## Licence

GPL-3.0, like the original. See [LICENSE](LICENSE).
