package dev.antikytheramechanism.compat.create.transmission;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.phys.BlockHitResult;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Configurable one-node gearbox bridging ordinary Create shafts and half-scale Frame kinetics. */
public final class TransmissionBoxBlock extends RotatedPillarKineticBlock
        implements IBE<TransmissionBoxBlockEntity> {

    public TransmissionBoxBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(AXIS, Direction.Axis.Y);
    }

    @Override
    public boolean hasShaftTowards(LevelReader level, BlockPos pos, BlockState state, Direction face) {
        if (face.getAxis() == state.getValue(AXIS)) {
            return false;
        }
        return level.getBlockEntity(pos) instanceof TransmissionBoxBlockEntity box
                && box.faceMode(face) == TransmissionBoxFaceMode.MACRO;
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(AXIS);
    }

    @Override
    protected ItemInteractionResult useItemOn(
            ItemStack stack,
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            BlockHitResult hitResult) {
        if (!player.isShiftKeyDown() && player.mayBuild()) {
            Set<UUID> existingManagedChildren = level instanceof ServerLevel serverLevel
                    ? managedChildrenAround(serverLevel, pos)
                    : Set.of();
            BlockItem placedBlockItem = stack.getItem() instanceof BlockItem blockItem ? blockItem : null;

            if (TransmissionBoxCogPlacementHelper.supportsItem(stack)) {
                ItemInteractionResult cogResult = TransmissionBoxCogPlacementHelper.placeFromBox(
                        stack, level, pos, player, hand, hitResult);
                if (cogResult != ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION) {
                    // A configured corner cog is a real half-scale Create cog placement source. Its
                    // native helper may deliberately fail when every suggestion leaves all Frames;
                    // never reinterpret that as permission for a full-size macro placement.
                    playFirstManagedPlacementSoundIfNeeded(
                            level, pos, placedBlockItem, existingManagedChildren, cogResult);
                    return cogResult;
                }
            }

            if (TransmissionBoxMiniPlacementHelper.supportsItem(stack)
                    && level.getBlockEntity(pos) instanceof TransmissionBoxBlockEntity box
                    && box.faceMode(hitResult.getDirection()) == TransmissionBoxFaceMode.MICRO) {
                // A MICRO face is a half-scale placement surface. Never fall through to ordinary macro
                // BlockItem placement for a Create shaft-helper item: a missing/blocked Frame target is a
                // deliberate cancelled mini placement, not permission to place a full block beside us.
                ItemInteractionResult miniResult = TransmissionBoxMiniPlacementHelper.placeFromBox(
                        stack, level, pos, player, hand, hitResult);
                playFirstManagedPlacementSoundIfNeeded(
                        level, pos, placedBlockItem, existingManagedChildren, miniResult);
                return miniResult;
            }
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    /**
     * Catnip normally emits its placement sound at the managed plot coordinate. Once a managed child
     * is already tracked, Sable projects that sound back to the physical mini world. The very first
     * helper placement is special: it creates the child during the same interaction, before clients
     * can receive/traverse that child, so the plot-side sound has nowhere visible to project yet.
     * Detect only that transition and emit one physical placement sound beside the Transmission Box;
     * later placements keep the ordinary Catnip/Sable sound path and are not doubled.
     */
    private static void playFirstManagedPlacementSoundIfNeeded(
            Level level,
            BlockPos boxPos,
            BlockItem blockItem,
            Set<UUID> childrenBefore,
            ItemInteractionResult result) {
        if (result != ItemInteractionResult.SUCCESS
                || blockItem == null
                || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        Set<UUID> childrenAfter = managedChildrenAround(serverLevel, boxPos);
        childrenAfter.removeAll(childrenBefore);
        if (childrenAfter.isEmpty()) {
            return;
        }

        SoundType soundType = blockItem.getBlock().defaultBlockState().getSoundType();
        serverLevel.playSound(
                null,
                boxPos,
                soundType.getPlaceSound(),
                SoundSource.BLOCKS,
                (soundType.getVolume() + 1.0F) / 2.0F,
                soundType.getPitch() * 0.8F);
    }

    private static Set<UUID> managedChildrenAround(ServerLevel level, BlockPos boxPos) {
        Set<UUID> children = new HashSet<>();
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    BlockPos framePos = boxPos.offset(x, y, z);
                    if (!level.getBlockState(framePos).is(ModRegistries.MECHANISM_FRAME.get())) {
                        continue;
                    }
                    MechanismAssembly assembly = manager.getAssemblyAt(framePos).orElse(null);
                    if (assembly == null) {
                        continue;
                    }
                    ServerSubLevel child = MechanismSubLevelService.findExisting(level, assembly);
                    if (child != null && !child.isRemoved()) {
                        children.add(child.getUniqueId());
                    }
                }
            }
        }
        return children;
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (!(level.getBlockEntity(pos) instanceof TransmissionBoxBlockEntity box)) {
            return InteractionResult.PASS;
        }

        BlockHitResult hit = new BlockHitResult(
                context.getClickLocation(),
                context.getClickedFace(),
                pos,
                false);
        TransmissionBoxHitTarget target = TransmissionBoxHitTarget.resolveWrench(hit, box);

        if (target.kind() == TransmissionBoxHitTarget.Kind.NONE) {
            return InteractionResult.SUCCESS;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        switch (target.kind()) {
            case CORNER -> {
                if (box.cycleCorner(target.corner())) {
                    IWrenchable.playRotateSound(level, pos);
                }
            }
            case FACE -> {
                if (box.cycleFace(target.face())) {
                    IWrenchable.playRotateSound(level, pos);
                }
            }
            case ROTATE -> {
                // Only an axial, non-configurable face reaches this branch. Create's pillar wrench
                // rotation leaves AXIS unchanged there, while the four lateral port assignments rotate
                // around that axis as one physical box.
                BlockState rotated = getRotatedBlockState(state, context.getClickedFace());
                box.beginTopologyMutation();
                box.rotateConfiguration(context.getClickedFace().getAxis());
                KineticBlockEntity.switchToBlockState(level, pos, rotated);
                box.finishTopologyMutation();
                IWrenchable.playRotateSound(level, pos);
            }
            case NONE -> {
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected boolean areStatesKineticallyEquivalent(BlockState oldState, BlockState newState) {
        // A structural-axis change necessarily changes which four physical faces can carry ports.
        return oldState == newState;
    }

    @Override
    public Class<TransmissionBoxBlockEntity> getBlockEntityClass() {
        return TransmissionBoxBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends TransmissionBoxBlockEntity> getBlockEntityType() {
        return CreateTransmissionRegistries.TRANSMISSION_BOX_BLOCK_ENTITY.get();
    }
}
