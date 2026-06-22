# ComfyUI Android Remote

Android WebView remote client for opening a desktop ComfyUI interface from an Android phone.

## What this version does

- Opens the real ComfyUI web interface inside an Android app.
- Stores the ComfyUI URL locally on the phone.
- Adds a connection setup screen with a `Test` button.
- Tests ComfyUI by requesting `/system_stats` before opening the UI.
- Supports Tailscale Serve URLs such as `http://desktop-name.tailnet-name.ts.net:8188`.
- Enables JavaScript, DOM storage, zoom, mixed content, cleartext HTTP, and Android file upload from WebView.
- Adds a basic mobile workspace layer after opening ComfyUI:
  - fullscreen immersive mode;
  - compact bottom toolbar with `Nodes`, `Run`, `Fit`, zoom in, zoom out, and `Menu`;
  - small floating toolbar toggle instead of a large `Hide` button;
  - native `Nodes` drawer that reads the current ComfyUI graph and lists workflow nodes;
  - injected viewport/CSS tweaks for larger touch targets and reduced accidental overscroll.
- Includes a GitHub Actions workflow to build a debug APK.

## What it does not do

- It does not run ComfyUI on Android.
- It does not replace ComfyUI with a native mobile UI.
- It does not make public exposure of ComfyUI safe.
- It does not make the ComfyUI node canvas fully mobile-native. The mobile toolbar and node drawer are a first usability layer over the existing desktop ComfyUI frontend.

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

## Mobile controls

After pressing `Open`, the connection panel is hidden and the app shows a compact bottom toolbar:

- `Nodes` opens a native drawer with the current workflow nodes.
- `Run` tries to press the visible ComfyUI run/queue/generate button.
- `Fit` tries to trigger the ComfyUI fit/reset-view action.
- `−` and `+` use Android WebView zoom.
- `Menu` shows or hides the connection panel.
- The small floating button hides or shows the bottom toolbar.

The `Nodes` drawer reads `window.app.graph` from the loaded ComfyUI frontend and shows:

- node id;
- node title;
- node type;
- widgets and their current values;
- inputs;
- outputs.

Tap a node in the drawer to expand or collapse its details. The drawer is read-only in this version.

These controls are intentionally conservative. They do not depend on a private ComfyUI API; they search for common visible buttons and read the current in-browser graph from the loaded ComfyUI frontend.

## Build APK

GitHub Actions builds a debug APK on every push and pull request. Open the Actions tab and download the `comfyui-android-remote-debug-apk` artifact.

Local build:

```bash
gradle assembleDebug
```
