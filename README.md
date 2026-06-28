# ComfyUI Android Remote

Android remote controller for running ComfyUI on a PC from an Android phone.

## Current direction

This app now prioritizes a native ComfyUI API mode instead of trying to control the desktop ComfyUI canvas through WebView.

Native mode talks to ComfyUI directly through:

```text
/system_stats
/upload/image
/prompt
/history/{prompt_id}
/history
/view
```

The desktop ComfyUI WebView is still available as `Graph` mode for advanced/manual workflow editing, but normal phone usage should happen through the native screen.

## What this version does

- Stores the ComfyUI URL locally on the phone.
- Tests ComfyUI by requesting `/system_stats`.
- Supports Tailscale Serve URLs such as `http://desktop-name.tailnet-name.ts.net:8188`.
- Adds a native workflow screen:
  - load or paste ComfyUI API-format workflow JSON;
  - parse workflow nodes and show editable primitive inputs as native Android fields;
  - show large Pixel-8a-friendly controls;
  - show multiline fields for prompts/text;
  - show numeric keyboards for numeric fields;
  - show `Choose image from phone` for `LoadImage` nodes;
  - upload images natively to `/upload/image`;
  - write the uploaded image filename into the API workflow JSON;
  - run the workflow by sending `/prompt`;
  - poll `/history/{prompt_id}` for output;
  - open generated output externally through `/view` without replacing the main ComfyUI graph page.
- Adds a bottom toolbar:
  - `Native` opens the API-based mobile UI;
  - `Graph` opens the normal ComfyUI web UI as a fallback/advanced mode;
  - `Run` sends the native workflow to ComfyUI;
  - `Output` opens the latest found output;
  - `Menu` opens actions such as loading workflow JSON and clearing the Graph cache.
- Keeps WebView file upload support for Graph mode.
- Includes a GitHub Actions workflow to build a debug APK.

## What it does not do yet

- It does not run ComfyUI on Android.
- It does not automatically convert every desktop workflow into API format.
- It does not yet provide model/LoRA dropdowns from ComfyUI object info.
- It does not yet provide a polished media gallery inside the app; output opens externally for stability.
- It does not make public exposure of ComfyUI safe.

## Required workflow format

Native mode expects a ComfyUI workflow saved/exported in API format. The JSON should look roughly like this:

```json
{
  "3": {
    "class_type": "LoadImage",
    "inputs": {
      "image": "example.png"
    }
  },
  "20": {
    "class_type": "KSampler",
    "inputs": {
      "seed": 123,
      "steps": 25
    }
  }
}
```

Visual/editor workflow JSON is different from API workflow JSON. If the workflow is not API format, `/prompt` will fail.

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

## Basic usage

1. Paste the ComfyUI URL and press `Test`.
2. Open `Native`.
3. Load or paste an API-format workflow JSON.
4. Edit native fields such as prompt, width, height, seed, steps, filename, etc.
5. For `LoadImage`, press `Choose image from phone`.
6. Press `Run`.
7. Press `Output` when the result is ready.
8. Use `Graph` only when you need the full desktop ComfyUI interface.

## Build APK

GitHub Actions builds a debug APK on every push and pull request. Open the Actions tab and download the `comfyui-android-remote-debug-apk` artifact.

Local build:

```bash
gradle assembleDebug
```
