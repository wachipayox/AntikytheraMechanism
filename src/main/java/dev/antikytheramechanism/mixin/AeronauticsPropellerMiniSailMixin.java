package dev.antikytheramechanism.mixin;

import com.simibubi.create.api.stress.BlockStressValues;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import dev.antikytheramechanism.compat.create.DynamicMiniSailCarrier;
import dev.antikytheramechanism.compat.create.DynamicMiniSailSnapshot;
import dev.antikytheramechanism.compat.create.MiniSailPropellerBridge;
import dev.eriksonn.aeronautics.content.blocks.propeller.bearing.propeller_bearing.PropellerBearingBlockEntity;
import dev.eriksonn.aeronautics.content.blocks.propeller.behaviour.PropellerActorBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Adds the dynamic Mechanism-Frame sail overlay to Aeronautics without changing its captured
 * contraption structure. GyroscopicPropellerBearingBlockEntity inherits this implementation.
 */
@Pseudo
@Mixin(targets = "dev.eriksonn.aeronautics.content.blocks.propeller.bearing.propeller_bearing.PropellerBearingBlockEntity", remap = false)
abstract class AeronauticsPropellerMiniSailMixin implements MiniSailPropellerBridge {
    @Unique
    private static final String ANTIKYTHERA_MINI_SAILS_TAG = "AntikytheraMiniSails";
    @Unique
    private static final float ANTIKYTHERA_MINIMUM_SAIL_POWER = 2.0f;

    @Shadow public float totalSailPower;
    @Shadow public boolean disassemblySlowdown;
    @Shadow protected PropellerActorBehaviour behavior;
    @Shadow protected List<BlockPos> sailPositions;

    @Unique
    private DynamicMiniSailSnapshot antikytheramechanism$clientMiniSails = DynamicMiniSailSnapshot.EMPTY;

    @Inject(method = "findSails", at = @At("TAIL"))
    private void antikytheramechanism$appendMiniSails(CallbackInfo callback) {
        PropellerBearingBlockEntity self = (PropellerBearingBlockEntity) (Object) this;
        DynamicMiniSailSnapshot snapshot = antikytheramechanism$currentSnapshot(self);
        this.totalSailPower += (float) snapshot.miniSailPower();

        // Native Aero already collapses all macro sails sharing an axial plane into one radial span.
        // Rebuild those same spans together with fractional mini spans so behavior.radius cannot retain
        // a stale outer radius after a dynamic removal.
        Map<Double, double[]> layers = new TreeMap<>();
        for (PropellerActorBehaviour.PropellerLayer layer : new ArrayList<>(this.behavior.getLayers())) {
            antikytheramechanism$mergeLayer(layers, layer.offset(), layer.innerRadius(), layer.outerRadius());
        }
        for (DynamicMiniSailSnapshot.MiniLayer layer : snapshot.layers(self.getBlockDirection())) {
            antikytheramechanism$mergeLayer(layers, layer.offset(), layer.innerRadius(), layer.outerRadius());
        }
        this.behavior.getLayers().clear();
        this.behavior.radius = 0.0;
        layers.forEach((offset, radii) -> this.behavior.addPropellerLayer(
                new PropellerActorBehaviour.PropellerLayer(offset, radii[0], radii[1])));

        // These guards are defensive while Create's ordinary disassembly route is temporarily blocked
        // by an unsafe multi-Frame angle. The overlay manager still requires disassembly below minimum.
        this.behavior.setParticleAmountUpdater(() -> this.totalSailPower + 1.0E-6f < ANTIKYTHERA_MINIMUM_SAIL_POWER
                ? 0.0
                : 0.02 * Math.abs(self.getClampedRotationRate()) * this.totalSailPower);
        this.behavior.setParticlePositionUpdater((position, random) ->
                antikytheramechanism$randomSailPosition(self, snapshot, random, position)
                        .add(self.facingDirection));

        antikytheramechanism$updateNetworkStress(self);
    }

    @Inject(method = "getThrust", at = @At("HEAD"), cancellable = true)
    private void antikytheramechanism$zeroThrustBelowMinimum(CallbackInfoReturnable<Double> callback) {
        if (this.totalSailPower + 1.0E-6f < ANTIKYTHERA_MINIMUM_SAIL_POWER) {
            callback.setReturnValue(0.0);
        }
    }

    @Inject(method = "getAirflow", at = @At("HEAD"), cancellable = true)
    private void antikytheramechanism$zeroAirflowBelowMinimum(CallbackInfoReturnable<Double> callback) {
        if (this.totalSailPower + 1.0E-6f < ANTIKYTHERA_MINIMUM_SAIL_POWER) {
            callback.setReturnValue(0.0);
        }
    }

    @Inject(method = "activeTick", at = @At("HEAD"), cancellable = true)
    private void antikytheramechanism$skipAirflowEffectsBelowMinimum(CallbackInfo callback) {
        if (this.totalSailPower + 1.0E-6f < ANTIKYTHERA_MINIMUM_SAIL_POWER) {
            callback.cancel();
        }
    }

    @Inject(method = "calculateStressApplied", at = @At("HEAD"), cancellable = true)
    private void antikytheramechanism$useEffectiveSailStress(CallbackInfoReturnable<Float> callback) {
        PropellerBearingBlockEntity self = (PropellerBearingBlockEntity) (Object) this;
        float stress;
        if (!self.isRunning()
                || this.disassemblySlowdown
                || this.totalSailPower + 1.0E-6f < ANTIKYTHERA_MINIMUM_SAIL_POWER) {
            // A below-minimum propeller is pending ordinary disassembly. Do not leave stale kinetic
            // load or thrust active during any short interval in which placement is not yet safe.
            stress = 0.0f;
        } else {
            stress = this.totalSailPower * (float) BlockStressValues.getImpact(self.getBlockState().getBlock());
        }
        ((CreateKineticBlockEntityStressAccessor) self).antikytheramechanism$setLastStressApplied(stress);
        callback.setReturnValue(stress);
    }

    @Inject(method = "write", at = @At("TAIL"))
    private void antikytheramechanism$writeMiniSailsToClient(
            CompoundTag compound,
            HolderLookup.Provider registries,
            boolean clientPacket,
            CallbackInfo callback) {
        if (!clientPacket) {
            return;
        }
        PropellerBearingBlockEntity self = (PropellerBearingBlockEntity) (Object) this;
        DynamicMiniSailSnapshot snapshot = antikytheramechanism$serverSnapshot(self);
        ListTag list = new ListTag();
        for (DynamicMiniSailSnapshot.MiniSail sail : snapshot.sails()) {
            CompoundTag entry = new CompoundTag();
            entry.putDouble("x", sail.x());
            entry.putDouble("y", sail.y());
            entry.putDouble("z", sail.z());
            list.add(entry);
        }
        compound.put(ANTIKYTHERA_MINI_SAILS_TAG, list);
    }

    @Inject(method = "read", at = @At("TAIL"))
    private void antikytheramechanism$readMiniSailsFromServer(
            CompoundTag compound,
            HolderLookup.Provider registries,
            boolean clientPacket,
            CallbackInfo callback) {
        if (!clientPacket) {
            return;
        }
        ListTag list = compound.getList(ANTIKYTHERA_MINI_SAILS_TAG, Tag.TAG_COMPOUND);
        List<DynamicMiniSailSnapshot.MiniSail> sails = new ArrayList<>(list.size());
        for (int index = 0; index < list.size(); index++) {
            CompoundTag entry = list.getCompound(index);
            sails.add(new DynamicMiniSailSnapshot.MiniSail(
                    entry.getDouble("x"), entry.getDouble("y"), entry.getDouble("z")));
        }
        this.antikytheramechanism$clientMiniSails = DynamicMiniSailSnapshot.fromClientCenters(sails);

        PropellerBearingBlockEntity self = (PropellerBearingBlockEntity) (Object) this;
        if (this.behavior != null && self.getMovedContraption() != null) {
            self.findSails();
        }
    }

    @Override
    public void antikytheramechanism$refreshMiniSails(DynamicMiniSailSnapshot snapshot) {
        PropellerBearingBlockEntity self = (PropellerBearingBlockEntity) (Object) this;
        if (self.getLevel() == null || self.getLevel().isClientSide) {
            return;
        }
        ControlledContraptionEntity moved = self.getMovedContraption();
        if (moved != null && moved.getContraption() instanceof DynamicMiniSailCarrier carrier) {
            carrier.antikytheramechanism$setMiniSails(snapshot);
        }
        self.findSails();
        self.sendData();
    }

    @Override
    public double antikytheramechanism$getEffectiveSailPower() {
        return this.totalSailPower;
    }

    @Override
    public double antikytheramechanism$getMinimumSailPower() {
        return ANTIKYTHERA_MINIMUM_SAIL_POWER;
    }

    @Unique
    private DynamicMiniSailSnapshot antikytheramechanism$currentSnapshot(PropellerBearingBlockEntity self) {
        return self.getLevel() != null && self.getLevel().isClientSide
                ? this.antikytheramechanism$clientMiniSails
                : antikytheramechanism$serverSnapshot(self);
    }

    @Unique
    private DynamicMiniSailSnapshot antikytheramechanism$serverSnapshot(PropellerBearingBlockEntity self) {
        ControlledContraptionEntity moved = self.getMovedContraption();
        if (moved != null && moved.getContraption() instanceof DynamicMiniSailCarrier carrier) {
            return carrier.antikytheramechanism$getMiniSails();
        }
        return DynamicMiniSailSnapshot.EMPTY;
    }

    @Unique
    private static void antikytheramechanism$mergeLayer(
            Map<Double, double[]> layers,
            double offset,
            double innerRadius,
            double outerRadius) {
        double[] radii = layers.computeIfAbsent(offset, ignored -> new double[]{innerRadius, outerRadius});
        radii[0] = Math.min(radii[0], innerRadius);
        radii[1] = Math.max(radii[1], outerRadius);
    }

    @Unique
    private Vector3d antikytheramechanism$randomSailPosition(
            PropellerBearingBlockEntity self,
            DynamicMiniSailSnapshot snapshot,
            RandomSource random,
            Vector3d destination) {
        int macroCount = this.sailPositions.size();
        double miniArea = snapshot.miniSailPower();
        double totalArea = macroCount + miniArea;
        if (totalArea <= 0.0 || self.getMovedContraption() == null) {
            return destination.zero();
        }

        double choice = random.nextDouble() * totalArea;
        Vec3 center;
        double halfExtent;
        if (choice < macroCount) {
            BlockPos sail = this.sailPositions.get(Math.min((int) choice, macroCount - 1));
            center = new Vec3(sail.getX(), sail.getY(), sail.getZ());
            halfExtent = 0.5;
        } else {
            int miniIndex = Math.min(
                    (int) ((choice - macroCount) / DynamicMiniSailSnapshot.MINI_SAIL_POWER),
                    snapshot.sails().size() - 1);
            DynamicMiniSailSnapshot.MiniSail sail = snapshot.sails().get(miniIndex);
            center = new Vec3(sail.x(), sail.y(), sail.z());
            halfExtent = DynamicMiniSailSnapshot.MINI_HALF_EXTENT;
        }
        center = self.getMovedContraption().applyRotation(center, 0);

        destination.set(
                        random.nextDouble() * 2.0 - 1.0,
                        random.nextDouble() * 2.0 - 1.0,
                        random.nextDouble() * 2.0 - 1.0)
                .mul(halfExtent);
        destination.fma(-self.thrustDirection.dot(destination), self.thrustDirection);
        destination.add(center.x, center.y, center.z);
        return destination;
    }

    @Unique
    private static void antikytheramechanism$updateNetworkStress(PropellerBearingBlockEntity self) {
        if (self.getLevel() == null || self.getLevel().isClientSide || !self.hasNetwork()) {
            return;
        }
        self.getOrCreateNetwork().updateStressFor(self, self.calculateStressApplied());
    }
}
