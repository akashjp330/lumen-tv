# Lumen TV

A good-looking IPTV player for Fire TV and Android TV. It works with Xtream Codes logins (server, username, password) and with M3U playlist links plus an XMLTV guide.

## Getting it onto your Fire TV Stick

You do this once. After that, updates take two minutes.

### 1. Let GitHub build the app (about 10 minutes)

1. Make a free account at github.com.
2. Click **+** (top right) → **New repository**. Name it `lumen-tv` and choose **Public**. Public lets the Fire Stick download the app without signing in. Your IPTV login is never in this code; you type it on the TV.
3. On the new repository page, click **uploading an existing file**. Drag in everything from the unzipped `lumen-tv` folder, then click **Commit changes**.
4. **Check the hidden build folder came along.** On a Mac, folders that start with a dot are hidden and often get skipped. Open the repository and look for a `.github` folder. If it's missing:
   - Click **Add file** → **Create new file**.
   - Name it `.github/workflows/build.yml`.
   - Paste in the contents of that file from the zip (open it in TextEdit), then commit.
5. Click the **Actions** tab. A build named "Build app" runs for about 5–8 minutes. A green check means it worked. If it's red, click into it and send Claude the error text.

### 2. Allow installs on the Fire Stick

1. On the Fire TV, go to **Settings → My Fire TV → Developer options**.
   - If you don't see Developer options, go to **My Fire TV → About** and click the device name 7 times.
2. Turn on **Install unknown apps** for **Downloader**.
3. Install the free **Downloader** app from the Amazon app store if you don't have it.

### 3. Install

1. Open Downloader and type the code **9667181**.
   (Or the full address: `https://github.com/akashjp330/lumen-tv/releases/latest/download/LumenTV.apk`)
2. Choose **Install**. When it finishes, **Delete** the downloaded file to save space.
3. Lumen TV appears in **Your Apps & Channels**. Hold the Home button and pick **Apps** if you want to move it to the front.

### Updating

Whenever the code changes, upload the changed files to GitHub. When the new build is green, enter **9667181** in Downloader again. It installs over the old version and keeps your login and favorites.

## Remote control

| Where | Button | Does |
|---|---|---|
| Anywhere | OK | Open / play |
| Anywhere | ≡ (menu) | Favorite a channel (on a channel tile or guide row) |
| Live TV | ▲ ▼ | Next / previous channel |
| Live TV | ◀ | Jump back to the previous channel |
| Live TV | 0–9 | Type a channel number |
| Live TV | OK or ▶ | Show channel info and options |
| Movies & episodes | ◀ ▶ | Back 10 s / ahead 30 s |
| Movies & episodes | ⏪ ⏩ | Back / ahead 30 s |
| Guide | OK on a past show marked REPLAY | Watch it from the start (if your provider keeps catch-up) |
| Guide | OK on a future show | Set a reminder |
| Multiview | OK / ≡ | Full screen / change that tile's channel |

## What's in this version

- Sign in with Xtream Codes or M3U + guide link. More than one provider is supported.
- Login is stored encrypted on the device.
- Home screen:
  - muted live preview of the highlighted channel
  - continue watching, favorites, recently watched, on now, newest movies, top series
- Live guide:
  - time grid with a "now" line
  - category filters and program details
  - catch-up replay
  - reminders, which pop up while the app is open
- Player:
  - fast channel switching, previous channel, number entry
  - reconnects automatically when a stream drops
  - audio and subtitle selection
  - "Start over" on channels with catch-up
- Multiview with up to 4 channels. The highlighted tile plays sound.
- Movies and series with posters, details, seasons and episodes, and resume. Missing artwork can come from TMDB with an optional free key.
- Search across channels, guide listings, movies and series.
- Hide categories you never watch. This also shrinks the guide download.
- PIN-lock categories.
- Account status, including expiry date and streams in use.
- "Fix guide match" for channels whose guide data doesn't line up.
- A diagnostics log with usernames and passwords removed.

## Not yet

- Recording.
- Pausing live TV and rewinding it. For now, "Start over" covers channels with catch-up.
- Reminders while the app is closed.
- An Apple TV version.

## For developers

- `core/` is plain Kotlin with no Android dependency. It holds the Xtream client, the M3U and XMLTV parsers, guide matching, catch-up URLs and caching. Its tests run against a fake IPTV server: `./gradlew :core:test`.
- `app/` is the Android TV app, built with Jetpack Compose and Media3/ExoPlayer.
- `app/sideload.jks` is a fixed signing key so updates install over each other. It's only for personal sideloading.
