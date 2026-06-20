# Connection options

## Same Wi-Fi network

Use this when the Android phone and the PC with ComfyUI are on the same local network.

Start ComfyUI on the PC with:

```bash
python main.py --listen 0.0.0.0 --port 8188
```

Then open this in the Android app:

```text
http://192.168.1.XX:8188
```

If it does not connect, check Windows Firewall and the PC IP address.

## Tailscale or another private VPN

Recommended for remote access without exposing ComfyUI to the public internet.

Flow:

```text
PC with ComfyUI + Tailscale
Android phone + Tailscale
Android app opens http://100.x.x.x:8188
```

You can turn Tailscale on only when you need remote ComfyUI access.

## Public tunnel

Examples: Cloudflare Tunnel, VPS reverse proxy, or ngrok.

Do not expose ComfyUI publicly without authentication and network restrictions.
