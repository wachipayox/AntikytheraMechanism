package dev.antikytheramechanism.compat.create.transmission;

import dev.antikytheramechanism.AntikytheraMechanism;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Create-only registry slice. This class is loaded only through CreateIntegration. */
public final class CreateTransmissionRegistries {
    private static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(AntikytheraMechanism.MOD_ID);
    private static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(AntikytheraMechanism.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, AntikytheraMechanism.MOD_ID);

    public static final DeferredBlock<TransmissionBoxBlock> TRANSMISSION_BOX = BLOCKS.register(
            "transmission_box",
            () -> new TransmissionBoxBlock(BlockBehaviour.Properties.of()
                    .strength(3.0F, 6.0F)
                    .noOcclusion()));

    public static final DeferredItem<BlockItem> TRANSMISSION_BOX_ITEM = ITEMS.register(
            "transmission_box",
            () -> new BlockItem(TRANSMISSION_BOX.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TransmissionBoxBlockEntity>>
            TRANSMISSION_BOX_BLOCK_ENTITY = BLOCK_ENTITY_TYPES.register(
                    "transmission_box",
                    () -> BlockEntityType.Builder.of(
                            TransmissionBoxBlockEntity::new,
                            TRANSMISSION_BOX.get()).build(null));

    private CreateTransmissionRegistries() {
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITY_TYPES.register(modBus);
        modBus.addListener(CreateTransmissionRegistries::addCreativeTabContents);
    }

    private static void addCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(TRANSMISSION_BOX_ITEM.get());
        }
    }
}
