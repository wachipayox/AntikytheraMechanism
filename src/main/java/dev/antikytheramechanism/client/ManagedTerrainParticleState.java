package dev.antikytheramechanism.client;

/** Marker exposed by the TerrainParticle mixin once mini debris has been projected into world space. */
public interface ManagedTerrainParticleState {
    boolean antikytheramechanism$isDetachedFromSubLevel();

    /** Marks an already world-space terrain particle as permanently detached from Sable tracking. */
    void antikytheramechanism$markDetachedFromSubLevel();
}
