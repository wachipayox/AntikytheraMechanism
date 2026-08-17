package dev.antikytheramechanism.mixin;

import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerChunkCache.class)
public interface ServerChunkCacheAccessor {
    @Invoker("clearCache")
    void antikytheramechanism$clearCache();
}
