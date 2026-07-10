# Connected input editing

Native field editors follow ComfyUI's connection model:

- A connected socket keeps its upstream link and is not silently replaced by a local value.
- The client exposes the upstream node for navigation.
- Widget-compatible connected inputs (for example STRING, INT, FLOAT, BOOLEAN and COMBO) can be explicitly disconnected and converted back to a local editable value.
- Non-widget sockets such as MODEL, CLIP, VAE, CONDITIONING and LATENT remain connection-only.
