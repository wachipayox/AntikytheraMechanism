#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
path = root / "src/main/java/dev/antikytheramechanism/assembly/MechanismAssemblyManager.java"
text = path.read_text(encoding="utf-8")

def replace(old, new):
    global text
    if new in text:
        return
    if old not in text:
        raise RuntimeError("Missing manager fragment: " + old[:160])
    text = text.replace(old, new, 1)

replace(
'''    public Optional<PendingPistonMove> pendingPistonMove(UUID assemblyId) {
''',
'''    public boolean setFrameShellMode(ServerLevel level, BlockPos framePos, FrameShellMode mode) {
        MechanismAssembly assembly = getAssemblyAt(framePos).orElse(null);
        if (assembly == null || isStructuralMutationLocked(assembly)) {
            return false;
        }
        FrameShellMode safeMode = java.util.Objects.requireNonNull(mode, "mode");
        if (assembly.shellMode() != safeMode) {
            assembly.setShellMode(safeMode);
            synchronizeLoadedFrames(level, assembly);
            setDirty();
        }
        return true;
    }

    public boolean cycleFrameShellMode(ServerLevel level, BlockPos framePos) {
        MechanismAssembly assembly = getAssemblyAt(framePos).orElse(null);
        return assembly != null && setFrameShellMode(level, framePos, assembly.shellMode().nextFromWrench());
    }

    public boolean setFrameSkin(ServerLevel level, BlockPos framePos, FrameSkin skin) {
        MechanismAssembly assembly = getAssemblyAt(framePos).orElse(null);
        if (assembly == null || isStructuralMutationLocked(assembly)) {
            return false;
        }
        FrameSkin safeSkin = java.util.Objects.requireNonNull(skin, "skin");
        if (assembly.skin() != safeSkin) {
            assembly.setSkin(safeSkin);
            synchronizeLoadedFrames(level, assembly);
            setDirty();
        }
        return true;
    }

    /**
     * Rotates exactly one placed Frame. A member of a larger assembly is first extracted through the
     * ordinary mini-content transfer transaction; residual components then use the existing split
     * lifecycle and the rotated Frame participates in the normal deterministic merge pass.
     */
    public boolean rotateFrame(ServerLevel level, BlockPos framePos, Direction newFacing) {
        if (newFacing == null || newFacing.getAxis().isVertical()) {
            return false;
        }
        MechanismAssembly source = getAssemblyAt(framePos).orElse(null);
        BlockState currentState = level.getBlockState(framePos);
        if (source == null
                || isStructuralMutationLocked(source)
                || !currentState.is(ModRegistries.MECHANISM_FRAME.get())
                || !source.frames().contains(framePos)) {
            return false;
        }

        Direction currentFacing = currentState.getValue(BlockStateProperties.HORIZONTAL_FACING);
        if (currentFacing == newFacing) {
            return true;
        }
        FrameOrientation targetOrientation = new FrameOrientation(newFacing);

        if (source.frames().size() == 1) {
            source.setOrientation(targetOrientation);
            Quaterniond q = targetOrientation.quaternion(new Quaterniond());
            AssemblyPose pose = source.poseTarget();
            source.setPoseTarget(new AssemblyPose(
                    pose.anchorX(), pose.anchorY(), pose.anchorZ(),
                    q.x, q.y, q.z, q.w));
            level.setBlock(
                    framePos,
                    currentState.setValue(BlockStateProperties.HORIZONTAL_FACING, newFacing),
                    Block.UPDATE_ALL);
            syncFrameBlockEntity(level, framePos, source);
            MechanismSubLevelService.synchronizePlacedPhysicalPose(level, source);
            reconcileConnectedAssemblies(level);
            getAssemblyAt(framePos).ifPresent(owner -> synchronizeLoadedFrames(level, owner));
            setDirty();
            return true;
        }

        BlockPos logicalOffset = source.logicalFrameOffset(framePos);
        AssemblyPose rebased = AssemblyOrientationMath.rebaseLogical(source.poseTarget(), logicalOffset);
        Quaterniond q = targetOrientation.quaternion(new Quaterniond());
        MechanismAssembly extracted = new MechanismAssembly(
                UUID.randomUUID(), framePos, Set.of(framePos), targetOrientation);
        extracted.copyPresentationFrom(source);
        extracted.setPoseTarget(new AssemblyPose(
                rebased.anchorX(), rebased.anchorY(), rebased.anchorZ(),
                q.x, q.y, q.z, q.w));

        assemblies.put(extracted.id(), extracted);
        frameIndex.put(framePos.immutable(), extracted.id());
        AssemblyContentTransferService.TransferResult transferResult =
                AssemblyContentTransferService.transferFrames(
                        level,
                        source,
                        extracted,
                        Set.of(framePos),
                        AssemblyLifecycleListener.TransferKind.SPLIT);
        if (transferResult == AssemblyContentTransferService.TransferResult.ROLLED_BACK) {
            MechanismSubLevelService.remove(level, extracted);
            assemblies.remove(extracted.id());
            frameIndex.put(framePos.immutable(), source.id());
            setDirty();
            return false;
        }
        if (transferResult == AssemblyContentTransferService.TransferResult.RECOVERY_REQUIRED) {
            lockContentRecovery(source, extracted, "single-Frame rotation split");
            return false;
        }

        source.removeFrame(framePos);
        level.setBlock(
                framePos,
                currentState.setValue(BlockStateProperties.HORIZONTAL_FACING, newFacing),
                Block.UPDATE_ALL);
        syncFrameBlockEntity(level, framePos, extracted);
        MechanismSubLevelService.synchronizePlacedPhysicalPose(level, extracted);
        splitDisconnectedAssembly(level, source);
        reconcileConnectedAssemblies(level);

        if (assemblies.containsKey(source.id())) {
            synchronizeLoadedFrames(level, source);
        }
        getAssemblyAt(framePos).ifPresent(owner -> synchronizeLoadedFrames(level, owner));
        setDirty();
        return true;
    }

    private boolean isStructuralMutationLocked(MechanismAssembly assembly) {
        UUID id = assembly.id();
        if (pendingPistonMoves.containsKey(id)
                || pendingContraptionMoves.containsKey(id)
                || pendingFrameEvacuations.containsKey(id)
                || contentRecoveryLocks.contains(id)) {
            return true;
        }
        return assembly.frames().stream().anyMatch(evacuatingFrames::contains);
    }

    public Optional<PendingPistonMove> pendingPistonMove(UUID assemblyId) {
''')

replace(
'''            MechanismAssembly split = new MechanismAssembly(
                    UUID.randomUUID(), extractedOrigin, extractedFrames, source.orientation());
            split.setPoseTarget(AssemblyOrientationMath.rebaseLogical(
''',
'''            MechanismAssembly split = new MechanismAssembly(
                    UUID.randomUUID(), extractedOrigin, extractedFrames, source.orientation());
            split.copyPresentationFrom(source);
            split.setPoseTarget(AssemblyOrientationMath.rebaseLogical(
''')

replace(
'''                source.removeFrames(extractedFrames);
                extractedFrames.forEach(frame -> syncFrameBlockEntity(level, frame, split));
                if (!source.frames().contains(source.origin())) {
''',
'''                source.removeFrames(extractedFrames);
                synchronizeLoadedFrames(level, split);
                synchronizeLoadedFrames(level, source);
                if (!source.frames().contains(source.origin())) {
''')

replace(
'''        for (MechanismAssembly neighbor : List.copyOf(neighbors.values())) {
            if (neighbor != selected && AssemblyOrientationMath.compatiblePhysical(selected, neighbor, 1.0E-6)) {
                mergeAssemblies(level, selected, neighbor);
            }
        }
        setDirty();
        return selected;
''',
'''        for (MechanismAssembly neighbor : List.copyOf(neighbors.values())) {
            if (neighbor != selected && AssemblyOrientationMath.compatiblePhysical(selected, neighbor, 1.0E-6)) {
                mergeAssemblies(level, selected, neighbor);
            }
        }
        MechanismAssembly owner = getAssemblyAt(framePos).orElse(selected);
        synchronizeLoadedFrames(level, owner);
        setDirty();
        return owner;
''')

replace(
'''        MechanismSubLevelService.remove(level, source);
        assemblies.remove(source.id());
        sourceFrames.forEach(pos -> syncFrameBlockEntity(level, pos, target));
        setDirty();
        return true;
''',
'''        MechanismSubLevelService.remove(level, source);
        assemblies.remove(source.id());
        // Presentation is assembly-owned; the deterministic target/survivor keeps its values.
        synchronizeLoadedFrames(level, target);
        setDirty();
        return true;
''')

replace(
'''            MechanismAssembly split = new MechanismAssembly(
                    UUID.randomUUID(), origin, component, source.orientation());
            split.setPoseTarget(AssemblyOrientationMath.rebaseLogical(
''',
'''            MechanismAssembly split = new MechanismAssembly(
                    UUID.randomUUID(), origin, component, source.orientation());
            split.copyPresentationFrom(source);
            split.setPoseTarget(AssemblyOrientationMath.rebaseLogical(
''')

replace(
'''        if (!source.frames().containsAll(retained)) {
            throw new IllegalStateException("Retained frame component was lost while splitting " + source.id());
        }
        setDirty();
''',
'''        if (!source.frames().containsAll(retained)) {
            throw new IllegalStateException("Retained frame component was lost while splitting " + source.id());
        }
        synchronizeLoadedFrames(level, source);
        setDirty();
''')

replace(
'''    private static void syncFrameBlockEntity(
            ServerLevel level, BlockPos pos, MechanismAssembly assembly) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof MechanismFrameBlockEntity frame) {
            frame.setAssemblyMapping(
                    assembly.id(), assembly.orientation(), assembly.logicalFrameOffset(pos));
        }
    }

''',
'''    private void syncFrameBlockEntity(
            ServerLevel level, BlockPos pos, MechanismAssembly assembly) {
        if (!level.hasChunkAt(pos)) {
            return;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof MechanismFrameBlockEntity frame) {
            frame.setAssemblyMapping(
                    assembly.id(), assembly.orientation(), assembly.logicalFrameOffset(pos));
            frame.setPresentationSkin(assembly.skin());
        }

        BlockState state = level.getBlockState(pos);
        if (!state.is(ModRegistries.MECHANISM_FRAME.get())) {
            return;
        }
        BlockState desired = state.setValue(MechanismFrameBlock.SHELL_MODE, assembly.shellMode());
        Direction facing = desired.getValue(BlockStateProperties.HORIZONTAL_FACING);
        for (Direction direction : Direction.values()) {
            BlockPos neighborPos = pos.relative(direction);
            boolean connected = assembly.id().equals(frameIndex.get(neighborPos));
            if (connected && level.hasChunkAt(neighborPos)) {
                BlockState neighbor = level.getBlockState(neighborPos);
                connected = neighbor.is(ModRegistries.MECHANISM_FRAME.get())
                        && neighbor.getValue(BlockStateProperties.HORIZONTAL_FACING) == facing;
            }
            desired = MechanismFrameBlock.withConnection(desired, direction, connected);
        }
        if (desired != state) {
            level.setBlock(pos, desired, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
    }

    private void synchronizeLoadedFrames(ServerLevel level, MechanismAssembly assembly) {
        for (BlockPos framePos : assembly.frames()) {
            if (level.hasChunkAt(framePos)
                    && level.getBlockState(framePos).is(ModRegistries.MECHANISM_FRAME.get())) {
                syncFrameBlockEntity(level, framePos, assembly);
            }
        }
    }

''')

path.write_text(text, encoding="utf-8")
print("MechanismAssemblyManager presentation changes applied")
