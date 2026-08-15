package dev.antikytheramechanism.mixin;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.sublevel.DetachedMiniPhysicsSubLevelService;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.network.packets.PlaceMergingGluePacket;
import foundry.veil.api.network.handler.ServerPacketContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents Simulated's merging glue from starting a destructive merge between incompatible grids.
 *
 * <p>This is intentionally earlier than the generic Sable assembly guard. Merging glue does not call
 * {@code SubLevelAssemblyHelper.assembleBlocks}; it places a temporary glue pair and later calls
 * {@code SimAssemblyHelper.disassembleSubLevel}, moving one already-live body into the other. Once
 * that path starts, a 0.5/1.0 pair can already have been mutated before Sable notices the invalid
 * coordinates. Reject the packet before the first glue BlockEntity is written instead.</p>
 */
@Mixin(value = PlaceMergingGluePacket.class, remap = false)
abstract class PlaceMergingGluePacketAntikytheraMixin {
    @Inject(
            method = "handle",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/simulated_team/simulated/network/packets/PlaceMergingGluePacket;addMergingGlue(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;ZF)Ldev/simulated_team/simulated/content/blocks/merging_glue/MergingGlueBlockEntity;",
                    ordinal = 0),
            cancellable = true)
    private void antikytheramechanism$rejectMixedScaleDetachedMerge(
            ServerPacketContext context,
            CallbackInfo callback) {
        PlaceMergingGluePacket packet = (PlaceMergingGluePacket) (Object) this;
        Level level = context.level();
        BlockPos parentRelative = packet.parentPos().relative(packet.parentFacing());
        BlockPos childRelative = packet.childPos().relative(packet.childFacing());
        SubLevel parent = Sable.HELPER.getContaining(level, parentRelative);
        SubLevel child = Sable.HELPER.getContaining(level, childRelative);

        if (DetachedMiniPhysicsSubLevelService.canMergeWithDetached(parent, child)) {
            return;
        }

        AntikytheraMechanism.LOGGER.warn(
                "Rejected Simulated merging glue between detached Antikythera body {} and incompatible body {}",
                parent == null ? "<root>" : parent.getUniqueId(),
                child == null ? "<root>" : child.getUniqueId());
        callback.cancel();
    }
}
