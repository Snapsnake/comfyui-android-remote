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
  - compact bottom toolbar with `Params`, `Graph`, `Run`, `Fit`, and `Menu`;
  - small floating toolbar toggle instead of a large `Hide` button;
  - `Graph` action for trying to close overlays such as Job Queue and return to the workflow canvas;
  - native `Params` drawer that reads the current ComfyUI graph and lists editable workflow parameters;
  - editable widget fields in the `Params` drawer;
  - `Menu` drawer with connection actions, settings, and debug actions;
  - injected viewport/CSS tweaks for larger touch targets and reduced accidental overscroll.
- Includes a GitHub Actions workflow to build a debug APK.

## What it does not do

- It does not run ComfyUI on Android.
- It does not replace ComfyUI with a native mobile UI.
- It does not make public exposure of ComfyUI safe.
- It does not make the ComfyUI node canvas fully mobile-native. The mobile toolbar and drawers are a usability layer over the existing desktop ComfyUI frontend.
- It does not guarantee that every custom node widget can be edited safely. Some custom widgets may need node-specific handling.

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

- `Params` opens a native drawer with the current editable workflow parameters.
- `Graph` tries to close ComfyUI overlays/panels and return to the workflow canvas.
- `Run` tries to press the visible ComfyUI run/queue/generate button.
- `Fit` tries to trigger the ComfyUI fit/reset-view action.
- `Menu` opens a native menu/settings drawer.
- The small floating button hides or shows the bottom toolbar.

The `Params` drawer reads `window.app.graph` from the loaded ComfyUI frontend and shows:

- node id;
- node title;
- editable widgets and their current values.

Tap a parameter card to expand or collapse its details. Change a widget value and press `Apply` to write it back into the loaded ComfyUI graph.

## Menu and settings

The `Menu` drawer contains:

- Connection actions: test connection, reload ComfyUI, show URL panel.
- Settings:
  - open Params by default;
  - show only editable nodes;
  - hide technical fields;
  - compact cards;
  - confirm before Run;
  - auto refresh after Apply;
  - aggressive Graph return.
- Debug actions: open full ComfyUI graph and clear WebView cache.

These controls are intentionally conservative. They do not depend on a private ComfyUI API; they search for common visible buttons and read/write the current in-browser graph from the loaded ComfyUI frontend.

## Build APK

GitHub Actions builds a debug APK on every push and pull request. Open the Actions tab and download the `comfyui-android-remote-debug-apk` artifact.

Local build:

```bash
gradle assembleDebug
```
