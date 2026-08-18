package dev.antikytheramechanism.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Queue;

@Mixin(LevelTicks.class)
public interface LevelTicksAccessor<T> {
    @Accessor("allContainers")
    Long2ObjectMap<LevelChunkTicks<T>> antikytheramechanism$getAllContainers();

    @Accessor("toRunThisTick")
    Queue<ScheduledTick<T>> antikytheramechanism$getToRunThisTick();

    @Accessor("alreadyRunThisTick")
    List<ScheduledTick<T>> antikytheramechanism$getAlreadyRunThisTick();
}
