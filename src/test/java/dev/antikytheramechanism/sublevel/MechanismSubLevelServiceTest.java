package dev.antikytheramechanism.sublevel;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MechanismSubLevelServiceTest {
    @Test
    void recognizesOnlyPersistedSubLevelsWithBothManagedNameAndOwner() {
        UUID assemblyId = UUID.randomUUID();
        CompoundTag owner = new CompoundTag();
        owner.putUUID("assembly_id", assemblyId);
        CompoundTag userData = new CompoundTag();
        userData.put("antikytheramechanism", owner);
        CompoundTag serialized = new CompoundTag();
        serialized.putString("display_name", "antikythera-" + assemblyId);
        serialized.put("user_data", userData);

        assertTrue(MechanismSubLevelService.isSerializedManagedSubLevel(serialized));

        CompoundTag missingOwner = serialized.copy();
        missingOwner.remove("user_data");
        assertFalse(MechanismSubLevelService.isSerializedManagedSubLevel(missingOwner));

        CompoundTag foreignName = serialized.copy();
        foreignName.putString("display_name", "foreign-sublevel");
        assertFalse(MechanismSubLevelService.isSerializedManagedSubLevel(foreignName));
    }
}
