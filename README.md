# World Leak Fix

A client-side coremod for the dimension-change memory leak in **MeatballCraft
1.12.2 with Cleanroom**. Without the fix, memory grows as you change dimensions,
eventually causing an `OutOfMemoryError` crash even with a large heap.

Tested with MeatballCraft 0.18.6.4, Cleanroom 0.5.17-alpha, Modular Machinery CE
2.3.2 (the affected task executor also occurs in 2.2.2), and The Betweenlands 3.8.1.
Other pack versions may need updated targets.

## Install

Download `mc-worldleak-fix-1.2.0.jar` from the [v1.2.0 release](https://github.com/Jacob-Lasky/mc-worldleak-fix/releases/tag/v1.2.0).
Remove any older `mc-worldleak-fix` jar, drop the download in the client's `mods/`
folder, and restart the client. The server needs nothing: this is a pure coremod
with no FML mod-list handshake entry. To uninstall, remove the jar and restart.

## Check that it is working

At the main menu, `logs/latest.log` should contain:

```text
[WorldLeakFix]: Self-check: Minecraft hooks=true, World accessor=true, WorldClient accessor=true, ChunkProviderClient accessor=true, Betweenlands chunk index=found, MMCE TaskExecutor hook=true -> ACTIVE
```

About five seconds (100 client ticks) after leaving a dimension, look for:

```text
[WorldLeakFix]: Hollowed unloaded WorldClient DIM<n>: <c> chunks, <e> entities, <t> tile entities released, Betweenlands chunk index: <k> released
```

`Betweenlands chunk index=absent` is expected when that mod is absent. `failed`
or `DEGRADED` means part of the fix could not apply; check the nearby warnings.
If the self-check is missing entirely, the Minecraft hooks did not apply.
If a respawn did not complete and the player still occupies the old world,
hollowing waits until the player has left it.

## What it fixes

Modular Machinery CE queues server-tick work even on a client connected to a
remote server, where that work is never drained. Those queued tasks retain tile
entities and their worlds. The fix drops those tasks when no server exists in
the client JVM. During integrated-server startup or shutdown, that test has a
small timing window; remote-server clients have no such window.

Renderer caches can also retain old worlds. After a dimension change and the
mods' unload handlers, this coremod waits 100 client ticks, then clears the old
world's chunks, entity lists and tile-entity lists. Version 1.2.0 also clears
The Betweenlands 3.8.1 chunk index (`WorldStorageImpl.storageMap` and
`tickableStorages`), which otherwise retains chunks after the main map is empty.
The world capability itself stays present because Betweenlands client ticks
still require it. A few old world shells can remain referenced without retaining
whole chunk sets.

The delay is a measured margin: a worker stalled beyond it could observe emptied
collections. A mod that reuses an already hollowed world would receive it empty;
vanilla and Forge create a new world instead. Mixin configs are optional so an
incompatible target leaves the leak in place with warnings instead of preventing
launch.

In a controlled comparison across the same **12 dimension changes**, live `Chunk`
count grew **123 → 617** with 1.1.1, while 1.2.0 stayed at **132–137** and post-GC
heap stayed flat at about **4.6 GB**. These are results for that test setup,
not a fixed memory target for every client.

## Build

Use a modern JDK that can run Gradle 9.9 (JDK 17 or newer). The wrapper downloads
Gradle and the build provisions an Azul Java 8 compiler toolchain. Supply the
Modular Machinery CE and MixinBooter jars from the tested pack in a separate
directory; third-party mod jars are not included here.

```sh
./gradlew build -Ppack_mods=/path/to/build-dependencies
```

That directory must contain exactly one `ModularMachinery-CE-*.jar` and one
`*mixinbooter-*.jar` (tested: `ModularMachinery-CE-2.3.2.jar` and
`!mixinbooter-10.7.jar`). Alternatively pass `-Pmmce_jar=/path/to/mmce.jar`
and `-Pmixinbooter_jar=/path/to/mixinbooter.jar`.
The installable output is `build/libs/mc-worldleak-fix-1.2.0.jar`, not the `-dev` jar.
Vanilla annotation targets use runtime SRG names; RetroFuturaGradle reobfuscates
ordinary bytecode references. There is deliberately no Mixin annotation processor
or refmap.

The release preserves the original tested v1.2.0 binary. The verified build from
this cleaned source is not byte-identical to that archive; the release ships
the original jar, with SHA-256:

```text
SHA-256 fc958bc6fa7749c318d47046264f199f83382b23b9ccbc617a8dad3ac8e45dfd
```

`tools/HprofPaths.java` is an optional standalone HPROF analyzer for modders. Its
header documents usage and memory requirements; it follows strong references
while excluding `Reference.referent` edges. Heap dumps are private diagnostic
data and should not be committed.

## License

MIT; see [LICENSE](LICENSE). Third-party build dependencies retain their own licenses.
