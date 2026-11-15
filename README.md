![Myelin banner](images/Banerr.png)

# What is this project?

Minecraft generates complete chunks, including underground terrain, caves, ores, structures and other features. That work is useful when you want to stop and play, but much of it is unnecessary and overkill when you are *flying, exploring the world, transporting or searching for a perfect place to finally start building your base*.

As almost all of us have experienced, travelling into new terrain can become a nightmare or an overall bad experience on a weak server and even powerful servers might struggle to keep up.

Myelin breaks this chain. You turn on **Flight Mode**, and newly generated chunks are generated as custom, lightweight **Surface chunks**, powered by **NullFrame Core** and [Cubiomes](https://github.com/Cubitect/cubiomes). You get similar terrain, water, biomes and vegetation without generating an entire underground world beneath your flight path. That means less generation work for the server and terrain appearing much faster.

**Fly now. Generate the full world when you come back to play.**

Surface chunks are meant to be temporary and do not replace vanilla chunks permanently. After Flight Mode is turned off and those chunks are unloaded, Minecraft will generate normal terrain when you come back.

And it's not "Just a few percent faster" or "Does not look like Vanilla at all". In the sections below, Myelin loads terrain in seconds instead of minutes, matching and **even beating Superflat world generation speed visibly and maintaining pretty accurate terrain**.

## Dependency on NullFrame Core

NullFrame Core handles terrain generation for Myelin. I made it a separate mod so other developers can use its tools too.

# Live World Generation

These comparisons show terrain appearing on client's screen, so, it includes the work needed to get it onto the screen with all overheads.

<details>
<summary>Test setup</summary>

**Local CPU:** Intel Core i7-13650HX laptop
**Minecraft:** 1.21.1  
**Server comparison:** Shared host advertised as 2 vCPUs  
**Other performance mods:** Sodium + Nvidium (For faster client-side rendering), ScalableLux and Krypton.

</details>

## Render Distance 32 — Singleplayer (My own hardware)

| **Myelin** | **Vanilla** |
|:---:|:---:|
| **~2 seconds** | **~63 seconds** |

#### Myelin - 1× playback & Vanilla - **5×** playback speed

![Myelin generating terrain at normal playback speed](images/Myelin%20x1.gif)
![Vanilla generating terrain at five times playback speed](images/Vanilla%20x5%20speedup.gif)

---

## Render Distance 22 — Shared 2-vCPU server

| **Myelin** | **Vanilla** |
|:---:|:---:|
| **~2 seconds** | **~135 seconds** |

#### Myelin - 1× playback & Vanilla - **15×** playback speed

![Myelin generating terrain at normal playback speed](images/Myelin%20x1%202vcpu.gif)
![Vanilla generating terrain at 15x playback speed](images/Vanilla%20x15%202vcpu.gif)

*The JVM reported 2 available processors. Since it’s shared hosting, performance can vary.*

---

## How fast can it keep up while flying?

In Singleplayer, my hardware, at approximately **785 blocks per second**, Myelin kept terrain visible ahead while vanilla Superflat fell behind.

Myelin on my hardware tends to break at ~1300 blocks per second player speed, while superflat reaches only ~790. _See "Where is the bottleneck now?" below_.

#### Myelin - 1× playback & Vanilla Superflat - 1× playback speed

![Myelin keeping terrain visible at approximately 785 blocks per second](images/Myelin%20at%20~785%20player%20speed.gif)
![Vanilla Superflat falling behind at approximately 785 blocks per second](images/Superflat%20~785%20player%20speed.gif)

---

<details>
<summary>How does it get this fast?</summary>

Myelin does not generate the world faster by just increasing the thread count. It surely does much more than that.

Here are some of the main parts:

---

### Native terrain generation in C

The terrain engine, NullFrame Core, runs terrain and vegetation calculations in a faster programming language: **C**, using built-in Cubiomes for approximate terrain and biome data.

Choosing C is good for servers because most of the calculations happen without creating many temporary Java objects and arrays or using extra memory.

Java (Myelin side) still handles the Minecraft integration, and the mod still has to create Java objects. 

---

### Multithreading

One of most obvious reason Myelin is this fast is because it can utilize **more than 1 generation worker**. Giving us control to divide requested terrain into batches and feed them to multiple workers.

This is one of the most common and practical optimization strategies used in performance mods like C2ME, although their 'work dividing process' might be different. With multithreading, there are more workers to handle the job so, it takes less time to finish it. Now, generation does not have to wait for one long queue of chunks waiting to finish on a single thread.

The worker count is also configurable with a configuration file.

---

### Generating ahead and caching results

Myelin constantly watches player movement in a dedicated thread and requests terrain **ahead** of the player from NullFrame Core.

Finished terrain is kept in a separate **RAM cache**, ready to be used when Minecraft needs it. Minecraft can use an already calculated result from hot cache instead of constantly waiting for output to appear.

Cache capacity, its lifetime and prefetch distance are configurable too! 

---

### Calculating the surface instead of the entire underground world

Normal Minecraft generation does much more work than creating a terrain visible from above. It generates underground caves, ores, heavy decoration and features.

Myelin's Flight Mode path skips much of that work. It uses similar terrain, adds the surface blocks and water, generates its own custom vegetation through NullFrame Core.

With bottom filling enabled, the ground beneath the surface can still be filled with stone and deepslate.

---

### Downsampling

The terrain sampling setting changes how many **direct** block data calculations Core does.

At coarser sampling levels, fewer blocks are sampled directly with Cubiomes. Then, Core uses "bilinear interpolation" to fill the space between them to create full chunks without gaps between them. For even coarser sampling levels (1:16+), sample points are placed on a world-aligned grid, since it is not physically possible to take less than 1 sample per chunk on chunk-aligned grid.

This is the main reason the snapshot benchmark (/myelin bench) becomes much faster at higher sampling steps.

The main tradeoff is detail: interpolated terrain will lose a lot of terrain detail, especially small hills and dips. That is why there is also a detail correction option in the GUI to add value noise on top of interpolated terrain. It will probably still look ugly, though. And I personally do not recommend sampling steps above 1:8 for in-game use, performance difference is ALMOST NONE on most hardware because of Myelin's advanced caching.

---

### Preparing whole chunk sections

Although NullFrame Core is extremely fast, getting the snapshots from there does not mean we can copy and paste that raw results to Minecraft chunk sections. Minecraft still needs block data in its own compatible format.

NullFrame Core builds terrain and vegetation into custom chunk sections before sending the result to Java (Myelin side). 

Myelin then translates those palettes (encoded values from Core) into Minecraft-compatible block states and installs. 

Trees and other vegetation are already stitched in that prepared data, rather than requiring a separate Java placement call and cache for every wood, leaf or grass :)

---

### Reusable buffers for each worker

Each generation worker gets to have its own **reusable direct memory buffer** for passing results back and forth from C to Java.

Workers can prepare their output themselves, send it to Java and the same buffer can be reused later instead of creating a new buffer every single time.

Java reads and decodes that output afterwards. 

---

### Processing several values together

Core uses **AVX2**, with optional **AVX-512** routines. It doesn't make everything use AVX-512 though.

AVX2 and AVX-512 are meant to process more values together in CPU, instead of calculating every operation one at a time.

---

### Avoiding extra generation and lighting work

Surface chunks skip vanilla generation stages that Myelin has replaced or does not need. Myelin does not generate vanilla structures, caves for that temporary terrain.

Fresh chunks still receive Minecraft's normal initial lighting, but Myelin avoids calculating  extra block light checks when normal initial lighting stage can already handle the chunk finely.

---

### Keeping Surface chunks out of your /world folder

Surface chunks are meant to be temporary and are not saved as permanent world chunks.

That also avoids saving, compressing and writing every block of our temporary terrain to the SSD when players enable Flight Mode. Myelin also handles their cleanup, so unloaded Surface chunks can be deleted from RAM and chunk holders.

When Flight Mode is turned off and those chunks have unloaded, Minecraft can generate the normal terrain again.

---

</details>

---

# What is the catch?

Myelin skips work to achieve this speed. **Surface chunks are temporary and approximate but similar enough terrain for travelling. They are not completely identical to vanilla chunks (at least yet).**

- Terrain heights, smaller features and vegetation & decoration can differ from vanilla.
- Flight Mode path does not generate underground caves, ores or structures (yet).
- Surface chunks are not meant for mining, building or to start a life on. Block breaking and placement are intentionally blocked there.

## What happens when I turn Flight Mode off?

Existing Surface chunks **do not become normal terrain instantly**. They remain until they unload _naturally_. You can turn off Flight Mode, move a little away and allow the Surface chunks to unload naturally. After you come back, you will see Vanilla chunks being generated. 

# Installation, configuration and in-game commands

Installation requirements:

- **Myelin**
- **NullFrame Core**
- **Fabric API** and **Fabric Loader**
- **YetAnotherConfigLib (YACL)**

---

<details>
<summary>Configuration</summary>

**Options such as Flight Mode, terrain sampling, biome sampling and HUD settings are available through the in-game GUI screen on the client!**

Server configuration file is stored in **`config/myelin.json`**, in your Minecraft server folder.

| Setting name | What does it do |
|---|---|
| `enabled` | Enables Myelin. |
| `workerThreads` | Number of Myelin terrain generation worker threads. `-1` means Myelin chooses thread count automatically. |
| `addVegetation` | Adds custom vegetation - trees, plants, grass and so on. Recommended to turn on. |
| `fillBottom` | Fills beneath the ground with stone and deepslate. Off means that chunks only get a thin surface and bottom filled with air. |
| `surfaceChunkDepth` | Select how much blocks beneath the surface should be filled when `fillBottom` option is off. |
| `snapshotPrefetchMarginChunks` | How many chunks ahead the player should Myelin generate and cache. |
| `cacheMaxEntries` | The maximum chunk capacity of Myelin cache. Larger cache can hold more terrain hot, but consume more space and unnecessary work. |
| `cacheTtlSeconds` | How long should cached snapshots remain in cache for use before unloading. |
| `samplingStep` | Default downsampling step. Players can also change terrain sampling themselves live in-game via client GUI. |
| `regionPathMode` | Chooses if terrain calculations use the region path. Type `"off"`, `"on"` or `"auto"`. |
| `dimensionAllowlist` / `dimensionDenylist` | Which dimensions (end, nether, overworld) Myelin can work in. This version of Myelin targets only overworld. |
| `debugColorChunksCacheStage` | Colors terrain blue, pink or orange depending on their cache stage. Meant for debugging, you can leave it off during normal game play. |
| `requireClientMod` | If enabled, only players with Myelin installed on their client can use Flight Mode. Players without Myelin can still join the server normally. |

</details>

<details>
<summary>In-game commands</summary>

### /myelin bench

`/myelin bench <chunk count> <sampling step>`
Myelin in-game benchmarker. See more below.

### /myelin cache

Meant for debugging.
You can review Myelin cache information, how many chunks have been hot in cache and other stages. 

With `/myelin cache reset` you can reset the cache information and start all over

### /myelin steps

Meant for debugging. 
See information about important steps, for long they took and more. 

You can reset step information with `/myelin steps reset`

### /myelin surface

`/myelin surface <player name> <on/off>`
Turn a player's Flight Mode on or off.

</details>

---

# Multiplayer — read this

Multiplayer Myelin server currently has some limitations:

- **Since this is Myelin's first alpha release, multiplayer with multiple players hasn't been tested. Bugs are expected, especially when more than one player uses Flight Mode.**
- Terrain quality, Flight Mode and Region Path settings might conflict between players.
- **The current connection handshake sends the world seed to clients.**

---

<details>
<summary>/myelin bench</summary>

Want to test the terrain generation speeds on your own hardware or server? The commands are included in the mod.

`/myelin bench <chunk count> <sampling step>`

You can also select the number of benchmark workers:

`/myelin bench 500000 128 threads 7`

Though, the requested worker count must fit within the server's configured worker pool.

## What does this benchmark measure?

This benchmark measures terrain and biome generation, interpolation, and sending the result from C to Java. It does not include vegetation or the work Minecraft does to install and render chunks. These are snapshot results, not fully built chunks per second.

**Workers:** 9  
**Requested chunk snapshots per run:** 500,000  
**Non-region actual count:** 500,000  
**Region actual count:** 516,096, rounded up to whole regions  

| Sampling | Non-region time | Non-region snapshots/s | Region time | Region snapshots/s |
|:---:|---:|---:|---:|---:|
| 1:1 | 39.810 s | **12,560** | 35.442 s | **14,562** |
| 1:2 | 15.751 s | **31,744** | 15.507 s | **33,281** |
| 1:4 | 5.090 s | **98,232** | 5.091 s | **101,374** |
| 1:8 | 2.069 s | **241,663** | 2.079 s | **248,242** |
| 1:16 | 0.373 s | **1,340,483** | 0.319 s | **1,617,856** |
| 1:32 | 0.238 s | **2,100,840** | 0.260 s | **1,984,985** |
| 1:64 | 0.201 s | **2,487,562** | 0.260 s | **1,984,985** |
| 1:128 | 0.192 s | **2,604,167** | 0.252 s | **2,048,000** |

**You can also check out NullFrame Core's benchmarks, its snapshot generation speed in Core, native C is even higher!**

</details>

---

# Compatibility

Flight Mode uses Minecraft's normal world-chunk rendering path and Vanilla's own chunk packets, so client side rendering mods are compatible.

However, mods that change chunk generation, scheduling, saving / unloading can conflict with Myelin. **Compatibility with C2ME, Terralith, Tectonic and other generation is not guaranteed.** 

**VMP (Very Many Players) combined with Krypton** had previously conflicted with Myelin Surface chunk unloading and caused RAM leak and above normal storage usage. 

# Planned Roadmap

My long-term goal is to bring Myelin and NullFrame Core much closer to vanilla terrain while keeping generation incredibly fast.

Eventually, I want Myelin to remove client-togglable "Flight Mode" and directly inject almost Vanilla identical chunks with structures, caves and so on automatically.  

That means much more than improving the surface: underground terrain, caves, ores, structures and compatibility all need work. This is the direction I want to take though, not a promise.

## Where is the bottleneck now?

In my tests, the reported chunk-cache hit rate reached above **99%** with directional prefetch. Most requests being hot in cache and terrain already prepared.

<details>
<summary>Cache hit rate</summary>
  
![Cache hit rate +99%](images/Cache%20hit%20rate%20%2B99%25.png)

</details>

With **Render Cached Terrain** enabled, I could also see prepared terrain appearing ahead of the actual Minecraft world chunks. So the terrain calculations were not necessarily the part holding up visible loading.

That leaves the rest of the pipeline as a major suspect: installing chunk sections, lighting, sending and handling chunk data, and building the terrain mesh on the client although I used Sodium + Nvidium combo.

This can also explain why **1:1 and 1:128 sampling can feel incredibly similar in-game**. Because both settings prepare terrain in Myelin cache before Minecraft even needs it, making the calculation faster will not necessarily make chunks appear sooner.

# Credits and licensing

Myelin uses **NullFrame Core** and [Cubiomes by Cubitect](https://github.com/Cubitect/cubiomes).

- **Myelin:** GPL-3.0-only
- **NullFrame Core:** LGPL-3.0-only
- **Cubiomes:** MIT

Special thanks to Cubitect (and Cubiomes contributors), the creator of Cubiomes. Without Cubiomes, creating Myelin would have been much harder.