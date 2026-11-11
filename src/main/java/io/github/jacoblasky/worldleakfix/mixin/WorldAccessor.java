package io.github.jacoblasky.worldleakfix.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.util.IntHashMap;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(World.class)
public interface WorldAccessor {
    /** {@code unloadedEntityList}. */
    @Accessor(value = "field_72997_g", remap = false)
    List<Entity> worldleakfix$getUnloadedEntityList();

    /** {@code entitiesById}. */
    @Accessor(value = "field_175729_l", remap = false)
    IntHashMap<Entity> worldleakfix$getEntitiesById();
}
