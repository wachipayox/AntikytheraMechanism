package dev.antikytheramechanism.assembly;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import static org.junit.jupiter.api.Assertions.*;
class AssemblyPoseTest {
 @Test void identityPoseUsesFrameCenter(){var p=AssemblyPose.identityAt(new BlockPos(-3,7,11));assertEquals(-2.5,p.anchorX());assertEquals(7.5,p.anchorY());assertEquals(11.5,p.anchorZ());assertEquals(1.0,p.quaternionW());}
 @Test void orientationIsNormalizedAndRoundTripsThroughNbt(){var p=new AssemblyPose(1.25,-2.5,8,0,2,0,2);assertEquals(p,AssemblyPose.load(p.save(),AssemblyPose.identityAt(BlockPos.ZERO)));assertEquals(1.0,p.orientation(new Quaterniond()).lengthSquared(),1e-12);}
 @Test void rebasingRotatesLogicalOffset(){var q=new Quaterniond().rotateY(Math.PI/2);var p=AssemblyPose.of(new Vector3d(10,20,30),q);var r=p.rebased(BlockPos.ZERO,new BlockPos(1,0,0));var o=q.transform(new Vector3d(1,0,0));assertEquals(10+o.x,r.anchorX(),1e-12);assertEquals(20+o.y,r.anchorY(),1e-12);assertEquals(30+o.z,r.anchorZ(),1e-12);}
 @Test void dockedMergeUsesPhysicalOriginDelta(){var aPos=new BlockPos(4,8,12);var bPos=aPos.east();var q=new Quaterniond().rotateY(Math.PI/2);var a=AssemblyPose.of(new Vector3d(20,30,40),q);var b=AssemblyPose.of(new Vector3d(21,30,40),q);assertTrue(a.isCompatibleWhenRebasedTo(aPos,b,bPos,1e-10));assertFalse(a.isCompatibleWhenRebasedTo(aPos,b.translated(new Vector3d(.01,0,0)),bPos,1e-6));var opposite=new AssemblyPose(b.anchorX(),b.anchorY(),b.anchorZ(),-b.quaternionX(),-b.quaternionY(),-b.quaternionZ(),-b.quaternionW());assertTrue(a.isCompatibleWhenRebasedTo(aPos,opposite,bPos,1e-10));}
 @Test void rejectsInvalid(){assertThrows(IllegalArgumentException.class,()->new AssemblyPose(0,0,0,0,0,0,0));assertThrows(IllegalArgumentException.class,()->new AssemblyPose(Double.NaN,0,0,0,0,0,1));}
}
