package dev.antikytheramechanism.compat.create.transmission;

import dev.antikytheramechanism.AntikytheraMechanism;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.concurrent.atomic.AtomicBoolean;

/** Create-linked registrations. This class must only be loaded through {@code CreateIntegration}. */
public final class CreateTransmissionRegistries {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(AntikytheraMechanism.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(AntikytheraMechanism.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, AntikytheraMechanism.MOD_ID);

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    public static final DeferredBlock<TransmissionBoxBlock> FOUR_SHAFT_TRANSMISSION_BOX = box(
            "four_shaft_transmission_box", TransmissionBoxKind.FOUR_SHAFTS);
    public static final DeferredBlock<TransmissionBoxBlock> FOUR_SMALL_COG_TRANSMISSION_BOX = box(
            "four_small_cog_transmission_box", TransmissionBoxKind.FOUR_SMALL_COGS);
    public static final DeferredBlock<TransmissionBoxBlock> TWO_LARGE_COG_TRANSMISSION_BOX = box(
            "two_large_cog_transmission_box", TransmissionBoxKind.TWO_LARGE_COGS);
    public static final DeferredBlock<TransmissionBoxBlock> TWO_SMALL_COG_TRANSMISSION_BOX = box(
            "two_small_cog_transmission_box", TransmissionBoxKind.TWO_SMALL_COGS);

    public static final DeferredBlock<InternalShaftPortBlock> INTERNAL_SHAFT_PORT = BLOCKS.register(
            "internal_shaft_port", () -> new InternalShaftPortBlock(internalProperties()));
    public static final DeferredBlock<InternalCogPortBlock> INTERNAL_SMALL_COG_PORT = BLOCKS.register(
            "internal_small_cog_port", () -> new InternalCogPortBlock(false, internalProperties()));
    public static final DeferredBlock<InternalCogPortBlock> INTERNAL_LARGE_COG_PORT = BLOCKS.register(
            "internal_large_cog_port", () -> new InternalCogPortBlock(true, internalProperties()));

    public static final DeferredItem<BlockItem> FOUR_SHAFT_TRANSMISSION_BOX_ITEM = blockItem(
            "four_shaft_transmission_box", FOUR_SHAFT_TRANSMISSION_BOX);
    public static final DeferredItem<BlockItem> FOUR_SMALL_COG_TRANSMISSION_BOX_ITEM = blockItem(
            "four_small_cog_transmission_box", FOUR_SMALL_COG_TRANSMISSION_BOX);
    public static final DeferredItem<BlockItem> TWO_LARGE_COG_TRANSMISSION_BOX_ITEM = blockItem(
            "two_large_cog_transmission_box", TWO_LARGE_COG_TRANSMISSION_BOX);
    public static final DeferredItem<BlockItem> TWO_SMALL_COG_TRANSMISSION_BOX_ITEM = blockItem(
            "two_small_cog_transmission_box", TWO_SMALL_COG_TRANSMISSION_BOX);
    public static final DeferredItem<Item> MINI_SHAFT_COVER = ITEMS.register(
            "mini_shaft_cover", () -> new Item(new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TransmissionBoxBlockEntity>>
            TRANSMISSION_BOX_BLOCK_ENTITY = BLOCK_ENTITY_TYPES.register(
                    "transmission_box",
                    () -> BlockEntityType.Builder.of(
                            TransmissionBoxBlockEntity::new,
                            FOUR_SHAFT_TRANSMISSION_BOX.get(),
                            FOUR_SMALL_COG_TRANSMISSION_BOX.get(),
                            TWO_LARGE_COG_TRANSMISSION_BOX.get(),
                            TWO_SMALL_COG_TRANSMISSION_BOX.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<InternalTransmissionPortBlockEntity>>
            INTERNAL_TRANSMISSION_PORT_BLOCK_ENTITY = BLOCK_ENTITY_TYPES.register(
                    "internal_transmission_port",
                    () -> BlockEntityType.Builder.of(
                            InternalTransmissionPortBlockEntity::new,
                            INTERNAL_SHAFT_PORT.get(),
                            INTERNAL_SMALL_COG_PORT.get(),
                            INTERNAL_LARGE_COG_PORT.get()).build(null));

    private CreateTransmissionRegistries() {
    }

    public static void register(IEventBus modBus) {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITY_TYPES.register(modBus);
        modBus.addListener(CreateTransmissionRegistries::addCreativeTabContents);
    }

    private static DeferredBlock<TransmissionBoxBlock> box(String name, TransmissionBoxKind kind) {
        return BLOCKS.register(name, () -> new TransmissionBoxBlock(kind, BlockBehaviour.Properties.of()
                .strength(3.0F, 6.0F)
                .noOcclusion()
                .dynamicShape()
                .pushReaction(PushReaction.NORMAL)));
    }

    private static DeferredItem<BlockItem> blockItem(
            String name,
            DeferredBlock<? extends TransmissionBoxBlock> block) {
        return ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
    }

    private static BlockBehaviour.Properties internalProperties() {
        return BlockBehaviour.Properties.of()
                .strength(-1.0F, 3_600_000.0F)
                .noOcclusion()
                .noCollission()
                .noLootTable()
                .dynamicShape()
                .pushReaction(PushReaction.BLOCK);
    }

    private static void addCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(FOUR_SHAFT_TRANSMISSION_BOX_ITEM.get());
            event.accept(FOUR_SMALL_COG_TRANSMISSION_BOX_ITEM.get());
            event.accept(TWO_LARGE_COG_TRANSMISSION_BOX_ITEM.get());
            event.accept(TWO_SMALL_COG_TRANSMISSION_BOX_ITEM.get());
        }
        if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            event.accept(MINI_SHAFT_COVER.get());
        }
    }
}
