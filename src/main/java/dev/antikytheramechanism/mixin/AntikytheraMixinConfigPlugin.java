package dev.antikytheramechanism.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Prevents the JVM from loading Create-linked mixins when Create is absent. */
public final class AntikytheraMixinConfigPlugin implements IMixinConfigPlugin {
    private static final String CREATE_MIXIN_SUFFIX = ".CreateContraptionLifecycleMixin";
    private static final String CREATE_CLASS_RESOURCE =
            "com/simibubi/create/content/contraptions/Contraption.class";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!mixinClassName.endsWith(CREATE_MIXIN_SUFFIX)) {
            return true;
        }
        ClassLoader loader = AntikytheraMixinConfigPlugin.class.getClassLoader();
        return loader.getResource(CREATE_CLASS_RESOURCE) != null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(
            String targetClassName,
            ClassNode targetClass,
            String mixinClassName,
            IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(
            String targetClassName,
            ClassNode targetClass,
            String mixinClassName,
            IMixinInfo mixinInfo) {
    }
}
