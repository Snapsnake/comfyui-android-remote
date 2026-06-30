# Mobile generator UI redesign

The current node tile screen is not enough. It still forces users to hunt through workflow nodes and can open empty nodes. The next UI should be controls-first, with the node map secondary.

## Target structure

1. Create
   - Show every direct editable workflow field first.
   - Group fields by source node.
   - Do not require tapping tiles just to edit prompts, seeds, LoRAs or primitive values.

2. Generate
   - Apply fields.
   - Run workflow.
   - Show progress and latest output.

3. Workflow map
   - Keep every node visible as a compact tile.
   - Show counters: editable field count and dropdown count.
   - Use the map only for inspection and jumping to linked source nodes.

## Design references

The useful pattern from generator apps is not a raw node list. It is task-first editing: prompt/assets/settings near the generate action, with advanced/workflow structure secondary.

Runway separates generation tasks such as image-to-video, text-to-video, text/image-to-image, upscaling, sound effects and task management. Adobe Firefly-style workflows place a prompt field and general settings near the generation action, with reference images/settings in a sidebar or focused control area.
