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

---

# Known Issues & Bug Status

Rentities is currently an **alpha / proof-of-concept** renderer. The core GPU-instanced rendering path is working, but some entity-specific rendering differences remain.

## Bug Status

### ✅ Armor stand rendering — FIXED

Armor stands now have their vanilla render state extracted, including individual pose rotations for:

* Head
* Body
* Left arm
* Right arm
* Left leg
* Right leg

Custom poses created with `/data` are now passed to the GPU renderer.

If an unusual custom armor-stand pose still renders incorrectly, please report the pose and command used.

---

### 🟡 Entity facing direction — MOSTLY FIXED

Entity body yaw is now explicitly extracted and converted to the coordinate convention used by the Rentities renderer.

The major global facing-direction problem has been addressed, but individual entity models may still have orientation differences because different vanilla model types use different root/model coordinate conventions.

If an entity faces backwards or sideways compared with vanilla rendering, please report:

* Entity type
* Entity rotation/yaw
* Whether the problem occurs at all rotations or only certain rotations

---

### 🟠 Animations — NOT VANILLA-ACCURATE

Rentities performs entity animation in the GPU vertex shader.

Walking, idle movement, limb movement, flying and other animations are implemented procedurally, but they are **not guaranteed to exactly match vanilla Minecraft**.

Known differences may include:

* Walking speed
* Limb swing timing
* Idle animations
* Head movement
* Flying animations
* Entity-specific animation timing

**Planned:** extract more of the actual vanilla animation state and reproduce it on the GPU.

---

### 🟠 Texture orientation / UVs — PARTIALLY FIXED

Fallback box UV generation has been corrected, and mesh winding has been improved.

However, vanilla model meshes are still captured from raw vertex UV data. Some model parts can therefore still have:

* Mirrored textures
* Vertically flipped textures
* Incorrect face orientation
* Textures appearing on the wrong side of a model part

This is still an active rendering bug.

**Planned:** preserve vanilla face/UV orientation during mesh extraction instead of relying only on captured vertex UV coordinates.

---

### 🟡 Head and model-part offsets — MOSTLY FIXED

Rentities now records baked model-part pivot information and uses those pivots when applying GPU-side transformations.

This removes the previous reliance on a single generalized head position.

Some entity models may nevertheless still have small differences in:

* Head position
* Head rotation
* Model-part pivots
* Relative body/head alignment

These are expected to require entity-specific testing and fixes.

---

### 🟢 Unscanned entities / magenta cubes — FIXED

Mesh-cache persistence and fallback rendering are implemented.

If an entity was not present when the mesh cache was generated, Rentities may initially display a magenta placeholder cube.

To resolve this:

1. Enable **Entity Scan Mode**.
2. Enter/reload a world containing the missing entity.
3. Allow Rentities to capture the entity mesh.
4. Disable Scan Mode after scanning.

The generated mesh is then stored in the mesh cache.

---

### 🔴 Mob spawner preview rendering — BUG

When looking at a mob spawner, the mob preview inside the spawner can incorrectly be captured and rendered by Rentities as a normal entity.

Symptoms include:

* The preview mob appearing at or around the player's head.
* The mob being rendered at full size instead of the smaller spawner-preview scale.
* The mob appearing outside of the spawner.
* The issue occurring only while looking at or rendering a mob spawner.

This is caused by the spawner's mob preview being rendered using a temporary local `PoseStack` transformation rather than as a normal world entity.

**Planned fix:** detect entity renders originating from block-entity renderers such as mob spawners and either preserve the complete local render transform or bypass Rentities batching for those renders.

**Workaround:** temporarily disable Rentities when inspecting mob spawners.

---

### 🔴 Iris / shader compatibility — NOT SUPPORTED

Rentities currently uses its own entity rendering shader pipeline.

As a result, entities rendered through Rentities may appear:

* Unlit
* Flat
* Visually different from vanilla
* Incorrect when Iris shaders are active

For now, disable Rentities when using shader packs that require Iris entity rendering compatibility.

**Planned:** Iris/shader compatibility.

---

### 🔴 AMD / Intel GPUs — NOT SUPPORTED

Rentities currently restricts itself to NVIDIA GPUs.

AMD and Intel GPU support has not yet been validated and is disabled.

**Planned:** AMD and Intel support after cross-vendor rendering and shader testing.

---

## Current Bug Summary

| Issue                       | Status             |
| --------------------------- | ------------------ |
| Armor stand poses           | ✅ Fixed            |
| Armor stand rotation        | ✅ Fixed            |
| Entity global yaw           | 🟡 Mostly fixed    |
| Vanilla-accurate animations | 🟠 Not fixed       |
| Texture / UV orientation    | 🟠 Partially fixed |
| Head/model-part offsets     | 🟡 Mostly fixed    |
| Magenta cache placeholders  | 🟢 Fixed           |
| Iris compatibility          | 🔴 Not supported   |
| AMD GPU support             | 🔴 Not supported   |
| Intel GPU support           | 🔴 Not supported   |

## Planned

* Complete vanilla-accurate animation reproduction
* Finish vanilla UV/face-orientation extraction
* Test and correct remaining entity-specific rotations
* Finish model-part pivot corrections
* Indirect draw calls with GPU-side frustum culling
* Reduce/skip vanilla render-state allocation for batched entities
* Player skin rendering
* AMD GPU support
* Intel GPU support
* Iris compatibility

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


## Planned

* Complete vanilla-accurate animation reproduction
* Finish vanilla UV/face-orientation extraction
* Test and correct remaining entity-specific rotations
* Finish model-part pivot corrections
* Fix GHAST 10-bone handling
* Indirect draw calls with GPU-side frustum culling
* Reduce/skip vanilla render-state allocation for batched entities
* Player skin rendering
* AMD GPU support
* Intel GPU support
* Iris compatibility

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
