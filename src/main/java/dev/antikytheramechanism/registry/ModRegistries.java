package dev.antikytheramechanism.registry;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

public final class ModRegistries {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(AntikytheraMechanism.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(AntikytheraMechanism.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, AntikytheraMechanism.MOD_ID);

    public static final DeferredBlock<MechanismFrameBlock> MECHANISM_FRAME = BLOCKS.register(
            "mechanism_frame",
            () -> new MechanismFrameBlock(BlockBehaviour.Properties.of()
                    .strength(3.0F, 6.0F)
                    .noOcclusion()
                    .dynamicShape()
                    .pushReaction(PushReaction.NORMAL)));

    public static final DeferredItem<BlockItem> MECHANISM_FRAME_ITEM = ITEMS.register(
            "mechanism_frame",
            () -> new BlockItem(MECHANISM_FRAME.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MechanismFrameBlockEntity>> MECHANISM_FRAME_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register(
                    "mechanism_frame",
                    () -> BlockEntityType.Builder.of(MechanismFrameBlockEntity::new, MECHANISM_FRAME.get()).build(null));

    private ModRegistries() {
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITY_TYPES.register(modBus);
        modBus.addListener(ModRegistries::addCreativeTabContents);
    }

    private static void addCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(MECHANISM_FRAME_ITEM.get());
        }
    }
}
