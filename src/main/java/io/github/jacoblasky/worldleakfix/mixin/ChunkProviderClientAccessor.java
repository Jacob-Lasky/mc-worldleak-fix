package io.github.jacoblasky.worldleakfix.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.multiplayer.ChunkProviderClient;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkProviderClient.class)
public interface ChunkProviderClientAccessor {
    /** {@code loadedChunks}. */
    @Accessor(value = "field_73236_b", remap = false)
    Long2ObjectMap<Chunk> worldleakfix$getLoadedChunks();
}
