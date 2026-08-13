package dev.antikytheramechanism.mixin;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import java.util.List;
import java.util.Set;
public final class AntikytheraMixinConfigPlugin implements IMixinConfigPlugin {
    private static final String CREATE_CLASS_RESOURCE="com/simibubi/create/content/contraptions/Contraption.class";
    private static final String CATNIP_PLACEMENT_CLASS_RESOURCE="net/createmod/catnip/placement/PlacementOffset.class";
    private static final String SIM_SERVER="dev/simulated_team/simulated/content/physics_staff/PhysicsStaffServerHandler.class";
    private static final String SIM_CLIENT="dev/simulated_team/simulated/content/physics_staff/PhysicsStaffClientHandler.class";
    public void onLoad(String p){}
    public String getRefMapperConfig(){return null;}
    public boolean shouldApplyMixin(String target,String mixin){
        ClassLoader l=AntikytheraMixinConfigPlugin.class.getClassLoader();
        if(mixin.endsWith(".CreateContraptionLifecycleMixin")
                || mixin.endsWith(".CreateContraptionEntityClientAccessMixin")
                || mixin.endsWith(".CreateContraptionBlocksClientAccessMixin")
                || mixin.endsWith(".ClientSubLevelCreateContraptionPoseMixin")) return l.getResource(CREATE_CLASS_RESOURCE)!=null;
        if(mixin.endsWith(".PlacementOffsetFrameMaskMixin")) return l.getResource(CATNIP_PLACEMENT_CLASS_RESOURCE)!=null;
        if(mixin.endsWith(".PhysicsStaffServerHandlerAntikytheraMixin")) return l.getResource(SIM_SERVER)!=null;
        if(mixin.endsWith(".PhysicsStaffClientHandlerAntikytheraMixin")) return l.getResource(SIM_CLIENT)!=null;
        return true;
    }
    public void acceptTargets(Set<String>a,Set<String>b){}
    public List<String> getMixins(){return null;}
    public void preApply(String a,ClassNode b,String c,IMixinInfo d){}
    public void postApply(String a,ClassNode b,String c,IMixinInfo d){}
}
