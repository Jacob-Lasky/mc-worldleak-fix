package io.github.jacoblasky.worldleakfix.mixin;

import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

@Mixin(WorldClient.class)
public interface WorldClientAccessor {
    /** {@code entityList}. */
    @Accessor(value = "field_73032_d", remap = false)
    Set<Entity> worldleakfix$getEntityList();

    /** {@code entitySpawnQueue}. */
    @Accessor(value = "field_73036_L", remap = false)
    Set<Entity> worldleakfix$getEntitySpawnQueue();
}
