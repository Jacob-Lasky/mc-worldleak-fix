package io.github.jacoblasky.worldleakfix.mixin;

import io.github.jacoblasky.worldleakfix.SelfCheck;
import io.github.jacoblasky.worldleakfix.WorldHollower;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands every WorldClient the client leaves to {@link WorldHollower}.
 *
 * <p>Target names are SRG with {@code remap = false} (no refmap in this build, see build.gradle):
 * {@code func_71353_a} = {@code loadWorld(WorldClient, String)}, the overload Forge patches to post
 * {@code WorldEvent.Unload} for the old world on every dimension change and disconnect;
 * {@code func_71407_l} = {@code runTick()}.
 *
 * <p>Scheduling happens at RETURN, i.e. after every mod's Unload handler has run against the
 * still-intact old world. Never hollow at HEAD: JourneyMap and others read the old world in their
 * Unload handlers.
 */
@Mixin(Minecraft.class)
public abstract class MixinMinecraft {

    @Unique
    private WorldClient worldleakfix$leaving;

    @Inject(method = "func_71353_a(Lnet/minecraft/client/multiplayer/WorldClient;Ljava/lang/String;)V",
        at = @At("HEAD"), remap = false)
    private void worldleakfix$captureLeaving(WorldClient next, String message, CallbackInfo ci) {
        worldleakfix$leaving = ((Minecraft) (Object) this).world;
    }

    @Inject(method = "func_71353_a(Lnet/minecraft/client/multiplayer/WorldClient;Ljava/lang/String;)V",
        at = @At("RETURN"), remap = false)
    private void worldleakfix$scheduleHollow(WorldClient next, String message, CallbackInfo ci) {
        WorldClient leaving = worldleakfix$leaving;
        worldleakfix$leaving = null;
        if (leaving != null && leaving != next) {
            WorldHollower.schedule(leaving);
        }
    }

    /** {@code func_71384_a} = {@code init()}: every mod is constructed by its RETURN. */
    @Inject(method = "func_71384_a()V", at = @At("RETURN"), remap = false)
    private void worldleakfix$selfCheck(CallbackInfo ci) {
        SelfCheck.run(Minecraft.class.getClassLoader());
    }

    @Inject(method = "func_71407_l()V", at = @At("HEAD"), remap = false)
    private void worldleakfix$tick(CallbackInfo ci) {
        WorldHollower.tick();
    }
}
