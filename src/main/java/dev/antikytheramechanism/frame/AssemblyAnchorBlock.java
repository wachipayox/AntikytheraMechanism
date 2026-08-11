package dev.antikytheramechanism.frame;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A non-player-facing service block that keeps an otherwise empty Sable SubLevel alive.
 */
public final class AssemblyAnchorBlock extends Block {
    public static final MapCodec<AssemblyAnchorBlock> CODEC = simpleCodec(AssemblyAnchorBlock::new);
    private static final VoxelShape ANCHOR_SHAPE = box(7.0, 15.0, 7.0, 9.0, 16.0, 9.0);

    public AssemblyAnchorBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return ANCHOR_SHAPE;
    }
}
