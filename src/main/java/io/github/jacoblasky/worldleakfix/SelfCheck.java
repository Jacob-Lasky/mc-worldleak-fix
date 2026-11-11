package io.github.jacoblasky.worldleakfix;

import io.github.jacoblasky.worldleakfix.mixin.ChunkProviderClientAccessor;
import io.github.jacoblasky.worldleakfix.mixin.WorldAccessor;
import io.github.jacoblasky.worldleakfix.mixin.WorldClientAccessor;

import java.lang.reflect.Method;

/**
 * Logs at startup which parts of the fix actually applied, so a boot to the main menu proves
 * the mixins took effect, with no world joined.
 *
 * <p>Without this, nothing logs until a dimension change (Hollowed ...) or an MMCE queue call
 * (No server in this JVM ...), and both configs are {@code required: false}. A target that moved
 * in a pack update would then be invisible until the leak came back.
 *
 * <p>What each check proves:
 * <ul>
 *   <li>Minecraft hooks: the three injector handlers of MixinMinecraft are merged into Minecraft.
 *       Both configs set {@code injectors.defaultRequire: 1}, so a handler whose target was not
 *       found fails its whole mixin and is never merged; a merged handler means it injected.</li>
 *   <li>Accessors: the accessor interface was applied to the target class. Mixin applies lazily
 *       when a target class loads, and WorldClient / ChunkProviderClient are not loaded at the
 *       menu, so this loads them (without initialising them) to make Mixin transform them now.</li>
 *   <li>Betweenlands chunk index: The Betweenlands' world-storage capability and its two index
 *       fields were found by reflection ("found"); "absent" in a pack without that mod is not a
 *       failure (nothing to release), "failed" means its storage class changed shape.</li>
 *   <li>MMCE hook: one handler per target method is merged into TaskExecutor; with
 *       {@code defaultRequire: 1}, each merged handler means its own method was injected.</li>
 * </ul>
 *
 * <p>If a MixinMinecraft injection misses, the whole mixin is rejected, this check (which lives in
 * that mixin) never runs, and NO Self-check line appears. A missing line is therefore the failure
 * signal for the Minecraft hooks, as a DEGRADED line is for everything else.
 *
 * <p>Never throws: any failure inside a check reads as {@code false} and the line says DEGRADED.
 */
public final class SelfCheck {
    private static final String MINECRAFT = "net.minecraft.client.Minecraft";
    private static final String MMCE_EXECUTOR = "github.kasuminova.mmce.common.concurrent.TaskExecutor";
    private static final String[] MINECRAFT_HANDLERS = {
        "worldleakfix$captureLeaving", "worldleakfix$scheduleHollow", "worldleakfix$tick", "worldleakfix$selfCheck"};
    private static final String[] MMCE_HANDLERS = {
        "worldleakfix$dropSyncTask", "worldleakfix$dropTEUpdateTask", "worldleakfix$dropTEMarkNoUpdateTask"};

    private SelfCheck() {}

    /** Called once from MixinMinecraft at the end of Minecraft#init (all mods constructed). */
    public static void run(ClassLoader loader) {
        try {
            String minecraft = handlersMerged(loader, MINECRAFT, MINECRAFT_HANDLERS);
            boolean world = implementsAccessor(loader, "net.minecraft.world.World", WorldAccessor.class);
            boolean worldClient = implementsAccessor(loader, "net.minecraft.client.multiplayer.WorldClient",
                WorldClientAccessor.class);
            boolean chunkProvider = implementsAccessor(loader, "net.minecraft.client.multiplayer.ChunkProviderClient",
                ChunkProviderClientAccessor.class);
            String betweenlands = WorldHollower.betweenlandsLookup();
            String mmce = handlersMerged(loader, MMCE_EXECUTOR, MMCE_HANDLERS);
            boolean ok = "true".equals(minecraft) && world && worldClient && chunkProvider
                && !"failed".equals(betweenlands) && "true".equals(mmce);
            String line = "Self-check: Minecraft hooks={}, World accessor={}, WorldClient accessor={}, "
                + "ChunkProviderClient accessor={}, Betweenlands chunk index={}, MMCE TaskExecutor hook={} -> {}";
            if (ok) {
                WorldHollower.LOG.info(line, minecraft, world, worldClient, chunkProvider, betweenlands, mmce,
                    "ACTIVE");
            } else {
                WorldHollower.LOG.warn(line, minecraft, world, worldClient, chunkProvider, betweenlands, mmce,
                    "DEGRADED (a target moved; see Mixin errors above)");
            }
        } catch (RuntimeException | LinkageError e) {
            WorldHollower.LOG.warn("Self-check could not run -> DEGRADED", e);
        }
    }

    private static boolean implementsAccessor(ClassLoader loader, String target, Class<?> accessor) {
        try {
            return accessor.isAssignableFrom(Class.forName(target, false, loader));
        } catch (ClassNotFoundException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** "true" if every handler is merged, "absent" if the class is not installed, else "false". */
    private static String handlersMerged(ClassLoader loader, String target, String[] handlers) {
        Method[] methods;
        try {
            methods = Class.forName(target, false, loader).getDeclaredMethods();
        } catch (ClassNotFoundException e) {
            return "absent";
        } catch (RuntimeException | LinkageError e) {
            return "false";
        }
        for (String handler : handlers) {
            boolean found = false;
            // Mixin may rename merged handlers (e.g. handler$zzz000$worldleakfix$tick) but keeps the name as a suffix.
            for (Method m : methods) {
                if (m.getName().endsWith(handler)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return "false";
            }
        }
        return "true";
    }
}
