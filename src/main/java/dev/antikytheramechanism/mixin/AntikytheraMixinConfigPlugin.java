package dev.antikytheramechanism.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class AntikytheraMixinConfigPlugin implements IMixinConfigPlugin {
    private static final String CREATE = "com/simibubi/create/content/contraptions/Contraption.class";
    private static final String CATNIP = "net/createmod/catnip/placement/PlacementOffset.class";
    private static final String SIM_SERVER =
            "dev/simulated_team/simulated/content/physics_staff/PhysicsStaffServerHandler.class";
    private static final String SIM_CLIENT =
            "dev/simulated_team/simulated/content/physics_staff/PhysicsStaffClientHandler.class";
    private static final String SIM_PHYSICS_ASSEMBLER =
            "dev/simulated_team/simulated/content/blocks/physics_assembler/PhysicsAssemblerBlockEntity.class";
    private static final String SIM_SPRING =
            "dev/simulated_team/simulated/content/blocks/spring/SpringBlockEntity.class";
    private static final String SIM_ASSEMBLY_CONTRAPTION =
            "dev/simulated_team/simulated/util/assembly/SimAssemblyContraption.class";
    private static final String SIM_ASSEMBLY_HELPER =
            "dev/simulated_team/simulated/util/SimAssemblyHelper.class";
    private static final String SIM_MERGING_GLUE_PACKET =
            "dev/simulated_team/simulated/network/packets/PlaceMergingGluePacket.class";
    private static final String OFFROAD_WHEEL_MOUNT =
            "dev/ryanhcode/offroad/content/blocks/wheel_mount/WheelMountBlockEntity.class";

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
        if (mixinClassName.endsWith(".CreateWrenchMechanismFrameMixin")
                || mixinClassName.endsWith(".CreateContraptionLifecycleMixin")
                || mixinClassName.endsWith(".CreateContraptionEntityDisassemblyMixin")
                || mixinClassName.endsWith(".CreateMechanicalBearingDisassemblyMixin")
                || mixinClassName.endsWith(".CreateRotationPropagatorMiniKineticsMixin")
                || mixinClassName.endsWith(".CreateRotationPropagatorAccessor")
                || mixinClassName.endsWith(".CreateContraptionEntityClientAccessMixin")
                || mixinClassName.endsWith(".CreateContraptionBlocksClientAccessMixin")
                || mixinClassName.endsWith(".ClientSubLevelCreateContraptionPoseMixin")
                || mixinClassName.endsWith(".ContraptionVisualManagedScaleMixin")
                || mixinClassName.endsWith(".ContraptionEntityRendererManagedScaleMixin")) {
            return loader.getResource(CREATE) != null;
        }
        if (mixinClassName.endsWith(".PlacementOffsetFrameMaskMixin")) {
            return loader.getResource(CATNIP) != null;
        }
        if (mixinClassName.endsWith(".PhysicsStaffServerHandlerAntikytheraMixin")) {
            return loader.getResource(SIM_SERVER) != null;
        }
        if (mixinClassName.endsWith(".PhysicsStaffClientHandlerAntikytheraMixin")) {
            return loader.getResource(SIM_CLIENT) != null;
        }
        if (mixinClassName.endsWith(".PhysicsAssemblerMiniPhysicsMixin")) {
            return loader.getResource(SIM_PHYSICS_ASSEMBLER) != null;
        }
        if (mixinClassName.endsWith(".SpringBlockEntityHostedMiniMixin")) {
            return loader.getResource(SIM_SPRING) != null;
        }
        if (mixinClassName.endsWith(".SimAssemblyContraptionMiniBoundaryMixin")) {
            return loader.getResource(SIM_ASSEMBLY_CONTRAPTION) != null;
        }
        if (mixinClassName.endsWith(".SimAssemblyHelperMiniBoundaryMixin")) {
            return loader.getResource(SIM_ASSEMBLY_HELPER) != null;
        }
        if (mixinClassName.endsWith(".PlaceMergingGluePacketAntikytheraMixin")) {
            return loader.getResource(SIM_MERGING_GLUE_PACKET) != null;
        }
        if (mixinClassName.endsWith(".OffroadWheelMountExperimentalSuspensionOnlyMixin")) {
            return loader.getResource(OFFROAD_WHEEL_MOUNT) != null;
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
