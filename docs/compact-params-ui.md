# Compact Params UI

This change makes the phone-first Params screen less noisy for large ComfyUI workflows.

## What changed

- The bottom navigation label `Native` is now `Params`.
- The main screen now shows a compact `Main controls` card with image inputs, prompts and core generation settings.
- Low-level node parameters are grouped under one collapsed `Advanced nodes` card instead of rendering each editable node as a separate top-level panel.
- `Advanced nodes` can still be opened when a workflow needs manual tuning of custom/internal nodes.

## Main-control heuristic

A field is promoted to the main screen only when it belongs to common phone-editable workflow controls:

- `LoadImage` image picker
- prompt text fields from text/prompt nodes
- sampler fields such as seed, steps, CFG, sampler, scheduler and denoise
- latent size fields from latent/image-size nodes
- common model selectors like checkpoint, LoRA, VAE and CLIP
- output filename prefix

Everything else remains editable under `Advanced nodes`.
