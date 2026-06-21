# ComfyUI Android Remote

Android WebView remote client for opening a desktop ComfyUI interface from an Android phone.

## What this version does

- Opens the real ComfyUI web interface inside an Android app.
- Stores the ComfyUI URL locally on the phone.
- Adds a connection setup screen with a `Test` button.
- Tests ComfyUI by requesting `/system_stats` before opening the UI.
- Supports Tailscale Serve URLs such as `http://desktop-name.tailnet-name.ts.net:8188`.
- Enables JavaScript, DOM storage, zoom, mixed content, and cleartext HTTP.
- Includes a GitHub Actions workflow to build a debug APK.

## What it does not do

- It does not run ComfyUI on Android.
- It does not replace ComfyUI with a native mobile UI.
- It does not make public exposure of ComfyUI safe.

## Recommended connection mode

Recommended remote setup:

```text
Phone on mobile network
→ Tailscale on Android
→ Tailscale Serve on PC
→ http://127.0.0.1:8000 on PC
→ ComfyUI
```

Start ComfyUI locally on the PC:

```bash
python main.py --listen 127.0.0.1 --port 8000
```

Expose it through Tailscale Serve:

```bash
tailscale serve --bg http://127.0.0.1:8000
```

Check the serve URL:

```bash
tailscale serve status
```

Then paste the shown tailnet URL into the Android app. It will usually look like this:

```text
http://desktop-name.tailnet-name.ts.net:8188
```

Do not commit your personal tailnet URL to this public repository. Store it only inside the app settings.

## Build APK

GitHub Actions builds a debug APK on every push and pull request. Open the Actions tab and download the `comfyui-android-remote-debug-apk` artifact.

Local build:

```bash
gradle assembleDebug
```
