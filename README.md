# GifTap Android Floating Overlay App (Source Code Kit)

> **Important:** This `.zip` contains the **Android source code** (not an `.apk` installer yet). Compile it into `giftap-overlay.apk` using **GitHub Actions** (no Android Studio needed) or **Android Studio**.

## Option 1: Build Automatically on GitHub (Recommended — No Android Studio Needed)
1. Unzip `giftap-android-overlay.zip`.
2. Create a new repository on GitHub (for example `giftap-overlay`).
3. Upload the files inside `giftap-android-overlay` (including `.github/workflows/build-apk.yml`) to the repository.
4. Open the **Actions** tab → click **Build Android Overlay APK** → **Run workflow** (or it starts automatically on push).
5. When the green checkmark finishes, open **Releases** (on the right side of your GitHub repo) and download **`giftap-overlay.apk`** directly onto your phone — or share that release link with all your users.

## Option 2: Build the `.apk` in Android Studio (Computer)
1. Unzip `giftap-android-overlay.zip`.
2. Open the **inner** `giftap-android-overlay` folder (the one containing `settings.gradle.kts` and `app/`) in **Android Studio**.
3. Wait for Gradle sync to finish, then click **Build → Build Bundle(s) / APK(s) → Build APK(s)** (or press Play `▶` with your phone plugged in).

## How the Installed APK Works on Your Phone
1. Open **GifTap** on your phone and tap **Start Bubble**.
2. Allow **Display over other apps** and tap **Continue → Start now** on the screen-capture prompt.
3. Open **TikTok**, open any video’s **Comments**, tap the floating **GIF** icon → **Capture Comment GIF**.
4. Move and resize the lime box over the comment GIF and tap **Capture 3s GIF** — GifTap previews the looping `.gif`, saves it to `Pictures/GifTap`, and lets you tap **Share .GIF to WhatsApp**.
