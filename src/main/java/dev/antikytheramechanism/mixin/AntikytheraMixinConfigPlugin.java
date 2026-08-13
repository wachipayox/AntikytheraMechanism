package dev.antikytheramechanism.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class AntikytheraMixinConfigPlugin implements IMixinConfigPlugin {
    private static final String CREATE_LIFECYCLE_MIXIN_SUFFIX = ".CreateContraptionLifecycleMixin";
    private static final String CREATE_CLASS_RESOURCE = "com/simibubi/create/content/contraptions/Contraption.class";
    private static final String CATNIP_PLACEMENT_MIXIN_SUFFIX = ".PlacementOffsetFrameMaskMixin";
    private static final String CATNIP_PLACEMENT_CLASS_RESOURCE = "net/createmod/catnip/placement/PlacementOffset.class";
    private static final String SIMULATED_STAFF_SERVER_MIXIN_SUFFIX = ".PhysicsStaffServerHandlerAntikytheraMixin";
    private static final String SIMULATED_STAFF_SERVER_CLASS_RESOURCE = "dev/simulated_team/simulated/content/physics_staff/PhysicsStaffServerHandler.class";
    private static final String SIMULATED_STAFF_CLIENT_MIXIN_SUFFIX = ".PhysicsStaffClientHandlerAntikytheraMixin";
    private static final String SIMULATED_STAFF_CLIENT_CLASS_RESOURCE = "dev/simulated_team/simulated/content/physics_staff/PhysicsStaffClientHandler.class";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        ClassLoader loader = AntikytheraMixinConfigPlugin.class.getClassLoader();
        if (mixinClassName.endsWith(CREATE_LIFECYCLE_MIXIN_SUFFIX)) {
            return loader.getResource(CREATE_CLASS_RESOURCE) != null;
        }
        if (mixinClassName.endsWith(CATNIP_PLACEMENT_MIXIN_SUFFIX)) {
            return loader.getResource(CATNIP_PLACEMENT_CLASS_RESOURCE) != null;
        }
        if (mixinClassName.endsWith(SIMULATED_STAFF_SERVER_MIXIN_SUFFIX)) {
            return loader.getResource(SIMULATED_STAFF_SERVER_CLASS_RESOURCE) != null;
        }
        if (mixinClassName.endsWith(SIMULATED_STAFF_CLIENT_MIXIN_SUFFIX)) {
            return loader.getResource(SIMULATED_STAFF_CLIENT_CLASS_RESOURCE) != null;
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
