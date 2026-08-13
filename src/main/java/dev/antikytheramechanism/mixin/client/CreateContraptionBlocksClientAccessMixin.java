package dev.antikytheramechanism.mixin.client;
import com.simibubi.create.content.contraptions.Contraption;
import dev.antikytheramechanism.client.CreateContraptionClientAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import java.util.Map;
@Mixin(value = Contraption.class, remap = false)
abstract class CreateContraptionBlocksClientAccessMixin implements CreateContraptionClientAccess.BlockCarrier {
    @Shadow protected Map<BlockPos, StructureBlockInfo> blocks;
    public Map<BlockPos, StructureBlockInfo> getAntikytheraBlocks() { return blocks; }
}
