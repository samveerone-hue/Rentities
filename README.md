# Rentities — GPU Instanced Entity Rendering

**Minecraft 1.21.11 | Fabric | Requires Sodium**

> ⚠️ **Alpha — Proof of Concept Release**
> This is the first public release. The core rendering system works and delivers real performance gains, but several visual issues are known and will be fixed in future updates. Do not use on important worlds without backups.

---

## What it does

Vanilla Minecraft issues a separate draw call to the GPU for every entity on screen. 500 cows means 500 draw calls. Rentities intercepts the entity render loop and batches all entities of the same type into **one instanced GPU draw call**, uploading positions, rotations, and animation state via a Shader Storage Buffer Object (SSBO). All per-bone animation is computed in a custom GLSL vertex shader — the CPU does no animation work at all.

---

## Benchmarks

**RTX 5070 · Minecraft 1.21.11 · Sodium 0.8.6 · Lithium · Render distance 20 · ~3000 entities in view**

| Setup | Avg FPS | 1% Low |
|---|---|---|
| Sodium + Lithium | 122 | 70 |
| Sodium + Lithium + **Rentities** | 311 | 145 |

**+155% average FPS. +107% improvement in 1% lows.**

Rentities and Lithium are complementary — Lithium reduces CPU server tick cost, Rentities reduces GPU draw call overhead. Together they compound.

---

## Requirements

- NVIDIA GPU (GTX 10 series or newer)
- Fabric Loader 0.17.2+
- Sodium 0.8.13
- Minecraft 1.21.11

Works standalone. Does not require Nvidium, though both mods are compatible and stack together.

---

## In-Game Settings

**Sodium → Video Settings → Rentities**

| Option | What it does |
|---|---|
| GPU Entity Batching | Master on/off toggle. Takes effect immediately without rejoining |
| Entity Scan Mode | ON = extract and save meshes to disk. OFF = load from saved cache. Rejoin world after changing |
| Mesh Cache | Shows whether a cache file exists on disk |
| Delete Mesh Cache | Clears the cache so it gets rebuilt on next world load |

## Reporting Rendering Bugs

When reporting an entity rendering problem, include:

* Minecraft version
* Rentities version/commit
* Sodium version
* GPU model
* Entity type
* Whether the problem occurs with Rentities disabled
* Screenshot/video if possible
* Any `/summon` or `/data` command used to reproduce the problem

This makes it much easier to determine whether the issue is caused by mesh baking, UV extraction, entity rotation, animation state, or GPU rendering.
---

## Planned

- Fix armor stand pose reading
- Fix entity facing direction
- Vanilla-accurate animations
- Fix texture UV orientation issues
- Indirect draw calls with GPU-side frustum culling
- Skip vanilla render state allocation for batched entities entirely
- Player skin rendering
- AMD and Intel support
- Iris compatibility

---

## FAQ

**Does this work without Nvidium?**
Yes, completely standalone.

**Does this work with Nvidium?**
Yes, they are compatible. Nvidium handles terrain, Rentities handles entities.

**Why does toggling batching off/on in settings work instantly?**
The renderer is created and destroyed at runtime without requiring a world reload.

**Why NVIDIA only right now?**
AMD/Intel support is coming. The rendering code itself uses standard OpenGL 4.5 — the restriction is temporary while stability is confirmed.

---

## Consider Supporting

Rentities is free and open source. If it helped your performance and you want to support continued development:

**[→ Patreon](https://www.patreon.com/cw/balancinglight)**

No obligation — enjoying it and sharing it is already more than enough.

---

## Fun Fact

The name **Rentities** comes from **Re + Entities** — a nod to the fact that the entire entity rendering pipeline was rewritten from the ground up. It also sounds like "re-entities", which is exactly what this mod does: takes vanilla entities and gives them a fundamentally different rendering path.

The core vertex shader that handles all 21 animation categories is 500+ lines of GLSL and runs entirely on your GPU. Your CPU has no idea how any of those zombies are moving.

---

*MIT License · BalancingLight · Minecraft 1.21.11*
