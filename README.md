# ComfyUI Android Remote

Android WebView remote client for opening a desktop ComfyUI interface from an Android phone.

## What this version does

- Opens the real ComfyUI web interface inside an Android app.
- Stores the ComfyUI URL locally.
- Supports local network URLs such as `http://192.168.1.10:8188`.
- Supports Tailscale URLs such as `http://100.x.x.x:8188`.
- Enables JavaScript, DOM storage, zoom, file chooser, and cleartext HTTP.
- Adds simple controls: Open, Reload, Home, and hide/show top bar.
- Includes a GitHub Actions workflow to build a debug APK.

## What it does not do

- It does not run ComfyUI on Android.
- It does not replace ComfyUI with a native mobile UI.
- It does not make public exposure of ComfyUI safe.

## Recommended ComfyUI launch on PC

```bash
python main.py --listen 0.0.0.0 --port 8188
```

Then open one of these in the app:

```text
http://192.168.1.XX:8188
http://100.x.x.x:8188
```

Use the `100.x.x.x` address with Tailscale.

## Build APK

GitHub Actions builds a debug APK on every push and pull request. Open the Actions tab and download the `comfyui-android-remote-debug-apk` artifact.

Local build:

```bash
gradle assembleDebug
```
