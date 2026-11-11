package io.github.jacoblasky.worldleakfix.mixin;

import github.kasuminova.mmce.common.concurrent.TaskExecutor;
import io.github.jacoblasky.worldleakfix.WorldHollower;
import net.minecraftforge.fml.common.FMLCommonHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Modular Machinery CE 2.2.2 queues "run on the main thread" work into
 * {@code TaskExecutor.mainThreadActions} (and two tile-entity queues) and drains them ONLY from
 * {@code onServerTick}. A client connected to a remote server has no server tick, so every
 * controller structure check on the client (resetMachine, onStructureFormed, updateComponents,
 * updateStatedMachineComponentSync) adds a lambda that is never run and never released. Each
 * lambda holds its controller tile, the tile holds its world, so every dimension the client saw a
 * machine change state in stays in memory. Measured 2026-11-07 in a live heap dump: the shortest
 * strong path to 3 of 3 stale WorldClients ran through this queue.
 *
 * <p>Dropping the work when no server runs in this JVM changes nothing observable: on a remote
 * client these queues were never drained, so the work never ran anyway. With an integrated server
 * (singleplayer, LAN host) or on a dedicated server, getMinecraftServerInstance() is non-null and
 * MMCE behaves exactly as shipped.
 *
 * <p>CONTRACT LIMIT: the test is "a server object exists", not "a server tick will run". In the
 * short windows while an integrated server starts or stops, a task can be dropped that a later
 * tick would have run, or kept with no tick left to drain it. Both need singleplayer or a LAN
 * host; remote-server clients have neither window.
 */
@Mixin(value = TaskExecutor.class, remap = false)
public abstract class MixinTaskExecutor {

    @Unique
    private static volatile boolean worldleakfix$announced;

    // One injector per target method, so each must inject exactly where named (defaultRequire 1 in
    // the config): a miss rejects this mixin, no handler is merged, and SelfCheck reports DEGRADED.
    @Inject(method = "addSyncTask", at = @At("HEAD"), cancellable = true)
    private void worldleakfix$dropSyncTask(CallbackInfo ci) {
        worldleakfix$dropWithoutServer(ci);
    }

    @Inject(method = "addTEUpdateTask", at = @At("HEAD"), cancellable = true)
    private void worldleakfix$dropTEUpdateTask(CallbackInfo ci) {
        worldleakfix$dropWithoutServer(ci);
    }

    @Inject(method = "addTEMarkNoUpdateTask", at = @At("HEAD"), cancellable = true)
    private void worldleakfix$dropTEMarkNoUpdateTask(CallbackInfo ci) {
        worldleakfix$dropWithoutServer(ci);
    }

    @Unique
    private static void worldleakfix$dropWithoutServer(CallbackInfo ci) {
        if (FMLCommonHandler.instance().getMinecraftServerInstance() == null) {
            if (!worldleakfix$announced) {
                worldleakfix$announced = true;
                WorldHollower.LOG.info("No server in this JVM: dropping Modular Machinery CE server-tick tasks "
                    + "(only a server tick ever drains them, so on a remote client they never ran)");
            }
            ci.cancel();
        }
    }
}
