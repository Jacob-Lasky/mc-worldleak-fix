package io.github.jacoblasky.worldleakfix;

import io.github.jacoblasky.worldleakfix.mixin.ChunkProviderClientAccessor;
import io.github.jacoblasky.worldleakfix.mixin.WorldAccessor;
import io.github.jacoblasky.worldleakfix.mixin.WorldClientAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fml.common.Loader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;

/**
 * Empties a {@link WorldClient} the client has left, so whatever still references it pins a
 * near-empty shell instead of a whole dimension.
 *
 * <p>Why not find and cut every reference instead: a heap dump of the live client (2026-11-07)
 * showed old worlds pinned by Modular Machinery CE's never-drained task queue (fixed separately
 * in {@code MixinTaskExecutor}), and ALSO by a long tail of "last thing I rendered" caches:
 * CodeChickenLib's per-thread {@code CCRenderState.lightMatrix.access} on Nothirium's chunk-build
 * threads, Botania's {@code RenderTileBrewery.brewery}, Thaumcraft's
 * {@code TileThaumatoriumRenderer.entityitem}. Each holds one world until that renderer next runs
 * in a newer one, so visiting dimensions with different blocks pins a growing set of them. Every
 * one of those holders reaches the world, and the world reaches its chunks, entities and tile
 * entities; emptying those collections is the single cut that covers holders not yet found.
 * Since 1.2.0 it also empties The Betweenlands' per-chunk index of the world, which otherwise kept
 * every chunk of a pinned world alive (see {@link #releaseBetweenlandsChunks}).
 *
 * <p>DEFERRED by {@link #DELAY_TICKS} on purpose; DO NOT hollow inside loadWorld. Worker threads
 * (Nothirium chunk builds, JourneyMap mapping tasks) can still be reading the old world for a
 * moment after the switch, and clearing an ArrayList under a concurrent iterator throws in that
 * thread. Five seconds is far longer than any of that in-flight work.
 *
 * <p>CONTRACT LIMIT, stated rather than solved: the delay is a measured margin, not a proof. A worker
 * task that stalls longer than 5 s and then reads the old world can see emptied collections or
 * throw (for example a ConcurrentModificationException) in THAT worker thread. The client thread
 * never iterates a world it has left, so the failure stays inside the worker that touched a
 * world nobody renders any more. No such exception was seen across the 10 hollowings of a test session (2026-11-07/08). Second limit: a world is skipped if it is current when due, but a
 * mod that hands an ALREADY hollowed WorldClient back to loadWorld gets it empty. Vanilla and
 * Forge always build a new WorldClient per dimension change.
 *
 * <p>Only ever touched from the client thread (loadWorld and runTick), so no locking.
 */
public final class WorldHollower {
    public static final Logger LOG = LogManager.getLogger("WorldLeakFix");

    /** 100 client ticks = 5 s at 20 TPS. */
    static final int DELAY_TICKS = 100;

    private static final ArrayDeque<Pending> PENDING = new ArrayDeque<>();
    private static long tick;

    private WorldHollower() {}

    private static final class Pending {
        final WorldClient world;
        final long dueTick;
        /** True once the "player is still in it" deferral has been logged for this world. */
        final boolean announced;

        Pending(WorldClient world, long dueTick, boolean announced) {
            this.world = world;
            this.dueTick = dueTick;
            this.announced = announced;
        }
    }

    /** Called right after Minecraft#loadWorld has replaced {@code old} (and fired its Unload). */
    public static void schedule(WorldClient old) {
        PENDING.add(new Pending(old, tick + DELAY_TICKS, false));
    }

    /** Called at the head of every Minecraft#runTick, in game and on menus alike. */
    public static void tick() {
        tick++;
        Minecraft mc = Minecraft.getMinecraft();
        while (!PENDING.isEmpty() && PENDING.peek().dueTick <= tick) {
            Pending due = PENDING.poll();
            WorldClient world = due.world;
            // Never empty the world the client is using now: a mod that hands an old WorldClient
            // back to loadWorld within the delay would otherwise lose its live chunks and entities.
            if (world == mc.world) {
                continue;
            }
            // Nor the world the player entity still stands in. Vanilla's handleRespawn calls
            // loadWorld BEFORE it replaces the player; if anything throws in between (seen
            // 2026-11-08: Universal Tweaks 1.20.1's load-sound GuiOpenEvent handler NPEs on a null
            // GUI until the main menu has been shown once, which a client that auto-joins with
            // --server never does), the old EntityPlayerSP stays as mc.player inside the old world.
            // Hollowing that world removes the terrain under the player. Check again later instead:
            // once a later respawn replaces the player, the world is released as usual.
            if (mc.player != null && mc.player.world == world) {
                if (!due.announced) {
                    LOG.info("Not hollowing unloaded WorldClient DIM{} yet: the player entity is still in it "
                        + "(a respawn did not complete); will retry", world.provider.getDimension());
                }
                PENDING.add(new Pending(world, tick + DELAY_TICKS, true));
                continue;
            }
            hollow(world);
        }
    }

    static void hollow(WorldClient world) {
        int dim = Integer.MIN_VALUE;
        int chunks = -1;
        int entities = -1;
        int tiles = -1;
        boolean complete = true;
        try {
            dim = world.provider.getDimension();
            entities = world.loadedEntityList.size();
            tiles = world.loadedTileEntityList.size();

            // The bulk: ~1,000 chunks of block and light data per world at render distance 16.
            // WorldClient#getChunkProvider is the public ChunkProviderClient override.
            ChunkProviderClientAccessor provider = (ChunkProviderClientAccessor) world.getChunkProvider();
            chunks = provider.worldleakfix$getLoadedChunks().size();
            provider.worldleakfix$getLoadedChunks().clear();

            world.loadedEntityList.clear();
            world.loadedTileEntityList.clear();
            world.tickableTileEntities.clear();
            world.playerEntities.clear();
            world.weatherEffects.clear();
            WorldAccessor w = (WorldAccessor) world;
            w.worldleakfix$getUnloadedEntityList().clear();
            w.worldleakfix$getEntitiesById().clearMap();
            WorldClientAccessor wc = (WorldClientAccessor) world;
            wc.worldleakfix$getEntityList().clear();
            wc.worldleakfix$getEntitySpawnQueue().clear();
        } catch (RuntimeException | LinkageError e) {
            // Best effort: the world is already unloaded, so a failure here must never take the
            // client down. Log it so a pack update that breaks an accessor is visible.
            LOG.warn("Could not fully hollow unloaded WorldClient DIM{} (chunks={}, entities={}, tiles={})",
                dim, chunks, entities, tiles, e);
            complete = false;
        }
        // Independent of the steps above: a broken entity accessor must not leave every chunk
        // indexed, and a failure here must not hide that the chunks above were released.
        String betweenlands = releaseBetweenlandsChunks(world, dim);
        if (complete) {
            LOG.info("Hollowed unloaded WorldClient DIM{}: {} chunks, {} entities, {} tile entities released, "
                + "Betweenlands chunk index: {}", dim, chunks, entities, tiles, betweenlands);
        } else {
            LOG.warn("Partly hollowed unloaded WorldClient DIM{}: Betweenlands chunk index: {}", dim, betweenlands);
        }
    }

    /**
     * Empties The Betweenlands' per-chunk index of a left world. Returns "&lt;n&gt; released",
     * "absent" (mod not installed, or no storage on this world) or "failed" (the reason is logged).
     *
     * <p>WHY: emptying the chunk map is not enough. The Betweenlands 3.8.1 keeps its own index of
     * every loaded chunk inside a WORLD CAPABILITY ({@code WorldStorageImpl.storageMap} and
     * {@code tickableStorages}, one ChunkStorageImpl per chunk, each holding the Chunk strongly)
     * and drops entries only on that chunk's ChunkEvent.Unload, which the client never fires for a
     * world it leaves. So any reference that still pins a left world (the long tail of "last thing
     * rendered" caches above) pinned its whole chunk set too. Measured on a test client
     * (pack 0.18.6.4, fix 1.1.1, 2026-11-08): a pinned, hollowed world kept 119 of its 120 chunks through
     * exactly {@code WorldClient.capabilities -> BetweenlandsWorldStorage.tickableStorages -> .chunk}.
     * A separate 1.1.0 session showed the matching signature (live Chunk = live WorldClient x 1089
     * after every world had been hollowed); no dump of that client exists to confirm the path there.
     *
     * <p>DO NOT generalise this into emptying the world's whole CapabilityDispatcher: tried on the
     * repro, and The Betweenlands' own client tick ({@code CameraPositionHandler} ->
     * {@code BetweenlandsWorldStorage.forWorld}) THROWS when that capability is missing, which
     * crashed the client tick. Clearing only the index keeps the capability present and leaves it
     * in the state of a world with no chunks loaded. Nor re-fire ChunkEvent.Unload for every chunk:
     * that runs hundreds of mods' handlers against a dead world, and a handler that keys its data
     * by position alone could drop the CURRENT world's chunk at the same coordinates.
     *
     * <p>CONTRACT LIMIT, the same as the class-level one: the Betweenlands collections are a plain
     * HashMap and ArrayList. The Betweenlands updates them from the client thread (chunk load and
     * unload events, the world tick), and this runs on the client thread 5 s after the world was
     * left, when no client-thread code touches that world any more. A worker thread that still
     * iterates them that late would get a ConcurrentModificationException in THAT worker, as it
     * would for the entity and tile lists above; none was seen on the repro.
     */
    static String releaseBetweenlandsChunks(WorldClient world, int dim) {
        try {
            if (!"found".equals(betweenlandsLookup())) {
                return betweenlandsLookup;
            }
            Object storage = world.getCapability(betweenlandsCapability, null);
            if (storage == null) {
                return "absent";
            }
            Map<?, ?> index = (Map<?, ?>) betweenlandsStorageMap.get(storage);
            List<?> tickable = (List<?>) betweenlandsTickableStorages.get(storage);
            int released = index.size();
            index.clear();
            tickable.clear();
            return released + " released";
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOG.warn("Could not release the Betweenlands chunk index of unloaded WorldClient DIM{}", dim, e);
            return "failed";
        }
    }

    /** Also checked by SelfCheck. */
    static final String BETWEENLANDS_STORAGE = "thebetweenlands.common.world.storage.WorldStorageImpl";
    static final String BETWEENLANDS_MODID = "thebetweenlands";

    private static Capability<?> betweenlandsCapability;
    private static Field betweenlandsStorageMap;
    private static Field betweenlandsTickableStorages;
    /** null = not looked up yet, else "found", "absent" (not installed) or "failed" (logged once). */
    private static String betweenlandsLookup;

    /**
     * Looks up, once, The Betweenlands' world-storage capability and the two index fields by
     * REFLECTION, and returns "found", "absent" or "failed". Called from SelfCheck at the end of
     * Minecraft#init (capabilities are injected by then) and at the first hollowing.
     *
     * <p>DO NOT turn this into a mixin accessor. An early mixin config naming a class from an
     * ordinary mod (not a coremod) makes Mixin look that class up before mod jars are on the
     * classpath; LaunchClassLoader then caches the miss and the mod's own later load of the class
     * fails. Tried on a test client 2026-11-08 with an {@code @Pseudo} accessor on
     * WorldStorageImpl: The Betweenlands' init died with NoClassDefFoundError and mod loading
     * failed. Reflection runs only after every mod is loaded. Mod classes live in the unnamed
     * module, so setAccessible on their private fields is allowed on Java 25.
     */
    static String betweenlandsLookup() {
        if (betweenlandsLookup == null) {
            try {
                Class<?> storage = Class.forName(BETWEENLANDS_STORAGE, false, WorldHollower.class.getClassLoader());
                Object capability = storage.getField("CAPABILITY_INSTANCE").get(null);
                Field map = storage.getDeclaredField("storageMap");
                Field tickable = storage.getDeclaredField("tickableStorages");
                if (!(capability instanceof Capability) || !Map.class.isAssignableFrom(map.getType())
                    || !List.class.isAssignableFrom(tickable.getType())) {
                    LOG.warn("The Betweenlands is installed but its world storage does not look like 3.8.1 "
                        + "(capability {}, storageMap {}, tickableStorages {})", capability, map.getType(),
                        tickable.getType());
                    betweenlandsLookup = "failed";
                } else {
                    map.setAccessible(true);
                    tickable.setAccessible(true);
                    betweenlandsCapability = (Capability<?>) capability;
                    betweenlandsStorageMap = map;
                    betweenlandsTickableStorages = tickable;
                    betweenlandsLookup = "found";
                }
            } catch (ClassNotFoundException e) {
                // Absent is fine only when the mod is not installed. Installed without this class
                // means a pack update moved it: say "failed" so the self-check reads DEGRADED.
                if (Loader.isModLoaded(BETWEENLANDS_MODID)) {
                    LOG.warn("The Betweenlands is installed but {} was not found", BETWEENLANDS_STORAGE);
                    betweenlandsLookup = "failed";
                } else {
                    betweenlandsLookup = "absent";
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                LOG.warn("The Betweenlands is installed but its world storage could not be read", e);
                betweenlandsLookup = "failed";
            }
        }
        return betweenlandsLookup;
    }
}
