package dev.antikytheramechanism.client;

/**
 * Implemented by Antikythera's TerrainParticle mixin so the Particle-level light hook can decide
 * whether to bypass Sable's SubLevel-aware lighting without depending on fields added by Sable's
 * own mixins.
 */
public interface ManagedTerrainParticleLightAccess {
    boolean antikytheramechanism$shouldUseVanillaParentLight();
}
