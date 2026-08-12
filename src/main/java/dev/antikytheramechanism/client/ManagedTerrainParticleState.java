package dev.antikytheramechanism.client;

/** Marker exposed by the TerrainParticle mixin for debris owned by Antikythera's parent-world path. */
public interface ManagedTerrainParticleState {
    boolean antikytheramechanism$isDetachedFromSubLevel();

    /**
     * True when this particle originated from an Antikythera mini block, or from parent-world block
     * debris that Antikythera deliberately detached near a Mechanism Frame. Such particles must not
     * enter Sable's transformed SubLevel lighting path after creation.
     */
    boolean antikytheramechanism$usesParentWorldPath();

    /** Marks this terrain particle as owned by Antikythera's parent-world particle path. */
    void antikytheramechanism$markParentWorldPath();

    /** Marks an already world-space terrain particle as permanently detached from Sable tracking. */
    void antikytheramechanism$markDetachedFromSubLevel();
}
