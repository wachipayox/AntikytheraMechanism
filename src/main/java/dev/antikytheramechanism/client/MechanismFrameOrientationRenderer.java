package dev.antikytheramechanism.client;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
public final class MechanismFrameOrientationRenderer implements BlockEntityRenderer<MechanismFrameBlockEntity>{
 private static final BlockState[] C={Blocks.RED_CONCRETE.defaultBlockState(),Blocks.YELLOW_CONCRETE.defaultBlockState(),Blocks.LIME_CONCRETE.defaultBlockState(),Blocks.BLUE_CONCRETE.defaultBlockState()};
 private static final double[][] P={{.125,.875,.125},{.8125,.875,.125},{.8125,.875,.8125},{.125,.875,.8125}};
 public MechanismFrameOrientationRenderer(BlockEntityRendererProvider.Context c){}
 public void render(MechanismFrameBlockEntity f,float p,PoseStack s,MultiBufferSource b,int l,int o){Quaterniond q=f.getFrameOrientation().quaternion(new Quaterniond());s.pushPose();s.translate(.5,.5,.5);s.mulPose(new Quaternionf((float)q.x,(float)q.y,(float)q.z,(float)q.w));s.translate(-.5,-.5,-.5);for(int i=0;i<4;i++){s.pushPose();s.translate(P[i][0],P[i][1],P[i][2]);s.scale(.0625f,.0625f,.0625f);Minecraft.getInstance().getBlockRenderer().renderSingleBlock(C[i],s,b,l,o);s.popPose();}s.popPose();}
}
