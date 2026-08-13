package dev.antikytheramechanism.assembly;

public final class AssemblyOrientationConstructionContext {
    private static final ThreadLocal<FrameOrientation> VALUE = new ThreadLocal<>();
    private AssemblyOrientationConstructionContext() {}
    public static void begin(FrameOrientation orientation) { VALUE.set(orientation); }
    public static FrameOrientation current() { return VALUE.get(); }
    public static void end() { VALUE.remove(); }
}
