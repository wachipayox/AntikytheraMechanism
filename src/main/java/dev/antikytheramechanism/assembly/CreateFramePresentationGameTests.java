package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
import dev.antikytheramechanism.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Set;

/** Real Create-wrench integration coverage without hard Create class references. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CreateFramePresentationGameTests {
    private CreateFramePresentationGameTests() {}

    @GameTest(batch = "frame_presentation", template = "frame_rotation_empty", timeoutTicks = 160)
    public static void wrenchCyclesModesTargetsHiddenAndAppliesSkinsWithoutConsumption(GameTestHelper helper) {
        if (!ModList.get().isLoaded("create")) {
            helper.succeed();
            return;
        }

        ServerLevel level = helper.getLevel();
        BlockPos framePos = helper.absolutePos(new BlockPos(5, 3, 5));
        placeFrame(level, framePos);
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(framePos).orElseThrow();
        Player player = maintenancePlayer(helper);
        Item wrench = requireCreateItem("wrench");
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(wrench));
        player.setShiftKeyDown(true);

        useWrench(player, framePos, Direction.UP);
        check(assembly.shellMode() == FrameShellMode.GLASS, "sneak+wrench did not cycle NORMAL -> GLASS");
        check(level.getBlockState(framePos).is(ModRegistries.MECHANISM_FRAME.get()), "sneak+wrench removed Frame in GLASS transition");
        useWrench(player, framePos, Direction.UP);
        check(assembly.shellMode() == FrameShellMode.HIDDEN, "sneak+wrench did not cycle GLASS -> HIDDEN");
        check(level.getBlockState(framePos).is(ModRegistries.MECHANISM_FRAME.get()), "sneak+wrench removed Frame in HIDDEN transition");

        BlockState hidden = level.getBlockState(framePos);
        check(hidden.getCollisionShape(level, framePos, CollisionContext.of(player)).isEmpty(),
                "wrench targeting reactivated HIDDEN physical collision");
        check(!hidden.getShape(level, framePos, CollisionContext.of(player)).isEmpty(),
                "HIDDEN is not targetable while holding Create wrench");
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        check(hidden.getShape(level, framePos, CollisionContext.of(player)).isEmpty(),
                "HIDDEN intercepts selection without Create wrench");

        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(wrench));
        player.setShiftKeyDown(false);
        useWrench(player, framePos, Direction.UP);
        check(assembly.shellMode() == FrameShellMode.NORMAL, "non-sneak wrench did not reveal HIDDEN -> NORMAL");

        check(manager.setFrameShellMode(level, framePos, FrameShellMode.GLASS), "could not prepare GLASS skin test");
        Item brassCasing = requireCreateItem("brass_casing");
        ItemStack brass = new ItemStack(brassCasing, 7);
        player.setItemInHand(InteractionHand.OFF_HAND, brass);
        player.setShiftKeyDown(true);
        useWrench(player, framePos, Direction.UP);
        check(assembly.skin() == FrameSkin.BRASS_CASING, "offhand brass casing did not apply BRASS skin");
        check(assembly.shellMode() == FrameShellMode.GLASS, "applying skin changed shell mode");
        check(brass.getCount() == 7, "applying casing skin consumed the offhand item");

        ItemStack copperIngot = new ItemStack(Items.COPPER_INGOT, 5);
        player.setItemInHand(InteractionHand.OFF_HAND, copperIngot);
        useWrench(player, framePos, Direction.UP);
        check(assembly.skin() == FrameSkin.COPPER, "Copper Ingot did not restore COPPER skin");
        check(assembly.shellMode() == FrameShellMode.GLASS, "Copper Ingot reset changed shell mode");
        check(copperIngot.getCount() == 5, "Copper Ingot reset consumed item");

        player.setItemInHand(InteractionHand.OFF_HAND, brass);
        useWrench(player, framePos, Direction.UP);
        check(assembly.skin() == FrameSkin.BRASS_CASING, "could not reapply BRASS skin before Copper Block reset");
        ItemStack copperBlock = new ItemStack(Items.COPPER_BLOCK, 3);
        player.setItemInHand(InteractionHand.OFF_HAND, copperBlock);
        useWrench(player, framePos, Direction.UP);
        check(assembly.skin() == FrameSkin.COPPER, "Copper Block did not restore COPPER skin");
        check(assembly.shellMode() == FrameShellMode.GLASS, "Copper Block reset changed shell mode");
        check(copperBlock.getCount() == 3, "Copper Block reset consumed item");
        helper.succeed();
    }

    @GameTest(batch = "frame_presentation", template = "frame_rotation_empty", timeoutTicks = 240)
    public static void normalWrenchRotatesOnlyClickedFrameAndTopologyFollowsOrientation(GameTestHelper helper) {
        if (!ModList.get().isLoaded("create")) {
            helper.succeed();
            return;
        }

        ServerLevel level = helper.getLevel();
        BlockPos left = helper.absolutePos(new BlockPos(5, 3, 5));
        BlockPos right = left.east();
        placeFrame(level, left);
        placeFrame(level, right);
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly joined = manager.getAssemblyAt(left).orElseThrow();
        check(joined.frames().equals(Set.of(left, right)), "wrench rotation fixture did not start joined");
        check(manager.setFrameShellMode(level, left, FrameShellMode.GLASS), "could not set rotation mode");
        check(manager.setFrameSkin(level, left, FrameSkin.BRASS_CASING), "could not set rotation skin");

        Player player = maintenancePlayer(helper);
        Item wrench = requireCreateItem("wrench");
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(wrench));
        player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        player.setShiftKeyDown(false);

        Direction previous = level.getBlockState(right).getValue(BlockStateProperties.HORIZONTAL_FACING);
        useWrench(player, right, Direction.UP);
        Direction rotatedFacing = level.getBlockState(right).getValue(BlockStateProperties.HORIZONTAL_FACING);
        check(rotatedFacing != previous, "normal wrench did not rotate clicked Frame");
        check(level.getBlockState(left).getValue(BlockStateProperties.HORIZONTAL_FACING) == Direction.NORTH,
                "normal wrench rotated neighboring Frame");
        MechanismAssembly leftAssembly = manager.getAssemblyAt(left).orElseThrow();
        MechanismAssembly rightAssembly = manager.getAssemblyAt(right).orElseThrow();
        check(!leftAssembly.id().equals(rightAssembly.id()), "orientation-incompatible wrench rotation did not split assembly");
        check(leftAssembly.shellMode() == FrameShellMode.GLASS && rightAssembly.shellMode() == FrameShellMode.GLASS,
                "rotation split changed/incompletely inherited mode");
        check(leftAssembly.skin() == FrameSkin.BRASS_CASING && rightAssembly.skin() == FrameSkin.BRASS_CASING,
                "rotation split changed/incompletely inherited skin");

        // Three more quarter-turns bring the isolated Frame back to NORTH. Its normal topology
        // reconciliation should then merge it with the untouched neighbor.
        useWrench(player, right, Direction.UP);
        useWrench(player, right, Direction.UP);
        useWrench(player, right, Direction.UP);
        check(level.getBlockState(right).getValue(BlockStateProperties.HORIZONTAL_FACING) == Direction.NORTH,
                "four normal wrench turns did not return clicked Frame to original orientation");
        MechanismAssembly merged = manager.getAssemblyAt(right).orElseThrow();
        check(merged.id().equals(manager.getAssemblyAt(left).orElseThrow().id()),
                "orientation-compatible wrench rotation did not allow normal merge");
        check(merged.frames().equals(Set.of(left, right)), "merged assembly does not contain both Frames");
        check(merged.shellMode() == FrameShellMode.GLASS && merged.skin() == FrameSkin.BRASS_CASING,
                "normal wrench rotation changed presentation");
        helper.succeed();
    }

    private static Player maintenancePlayer(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        player.getAbilities().mayBuild = true;
        player.getAbilities().instabuild = true;
        return player;
    }

    private static InteractionResult useWrench(Player player, BlockPos pos, Direction face) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), face, pos, false);
        UseOnContext context = new UseOnContext(player, InteractionHand.MAIN_HAND, hit);
        return player.getMainHandItem().useOn(context);
    }

    private static Item requireCreateItem(String path) {
        ResourceLocation id = ResourceLocation.parse("create:" + path);
        Item item = BuiltInRegistries.ITEM.get(id);
        check(id.equals(BuiltInRegistries.ITEM.getKey(item)), "missing Create item " + id);
        return item;
    }

    private static void placeFrame(ServerLevel level, BlockPos position) {
        BlockState state = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        check(level.setBlock(position, state, Block.UPDATE_ALL), "could not place Mechanism Frame at " + position);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
