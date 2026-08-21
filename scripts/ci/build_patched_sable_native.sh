#!/usr/bin/env bash
set -euo pipefail

WORKSPACE="${GITHUB_WORKSPACE:-$(pwd)}"
RESOURCE_DIR="$WORKSPACE/src/main/resources/natives/antikytheramechanism_sable_rapier"
ARTIFACT_DIR="/tmp/antikythera-patched-natives"
WINDOWS_NATIVE="$RESOURCE_DIR/sable_rapier_x86_64_windows.dll"
LINUX_NATIVE="$RESOURCE_DIR/sable_rapier_x86_64_linux.so"
SABLE_COMMIT="4bc206ee4718f2a7906e4366963ada98eadd78fae"
JNI_SYMBOL="Java_dev_antikytheramechanism_compat_offroad_OffroadNativeForceBridge_addWorldForceAndTorque"

rm -rf /tmp/antikythera-sable "$ARTIFACT_DIR"
git clone https://github.com/ryanhcode/sable.git /tmp/antikythera-sable
git -C /tmp/antikythera-sable checkout "$SABLE_COMMIT"

python3 - <<'PY'
from pathlib import Path
p = Path('/tmp/antikythera-sable/sable_rapier/src/main/rust/rapier/src/lib.rs')
text = p.read_text()
marker = 'Java_dev_antikytheramechanism_compat_offroad_OffroadNativeForceBridge_addWorldForceAndTorque'
if marker in text:
    raise SystemExit('JNI prototype already present unexpectedly')
text += r'''

/// Antikythera diagnostic extension: add a world-space force and torque to one Sable rigid body.
/// Java removes the exact same vectors after one Rapier step so this contribution is transient and
/// does not reset or overwrite Sable's other persistent external forces (notably buoyancy).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_antikytheramechanism_compat_offroad_OffroadNativeForceBridge_addWorldForceAndTorque<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jint,
    fx: jdouble,
    fy: jdouble,
    fz: jdouble,
    tx: jdouble,
    ty: jdouble,
    tz: jdouble,
    wake_up: jboolean,
) {
    with_handle(handle, |scene| {
        let sable_data = scene.sable_data.read().unwrap();
        let mut sim_data = scene.sim_data.write().unwrap();
        let rb = get_rigid_body_mut(&mut sim_data, &sable_data, id as LevelColliderID);
        rb.add_force(Vec3::new(fx as Real, fy as Real, fz as Real), wake_up > 0);
        rb.add_torque(Vec3::new(tx as Real, ty as Real, tz as Real), wake_up > 0);
    });
}
'''
p.write_text(text)
PY

cd /tmp/antikythera-sable
./gradlew sable_rapier:buildImages
./gradlew sable_rapier:compileRust-windows-x86_64 sable_rapier:compileRust-linux-x86_64

mkdir -p "$ARTIFACT_DIR" "$RESOURCE_DIR"
cp sable_rapier/src/main/rust/target/x86_64-pc-windows-msvc/release/sable_rapier.dll \
   "$ARTIFACT_DIR/sable_rapier_x86_64_windows.dll"
cp sable_rapier/src/main/rust/target/x86_64-unknown-linux-gnu/release/libsable_rapier.so \
   "$ARTIFACT_DIR/sable_rapier_x86_64_linux.so"
cp "$ARTIFACT_DIR/sable_rapier_x86_64_windows.dll" "$WINDOWS_NATIVE"
cp "$ARTIFACT_DIR/sable_rapier_x86_64_linux.so" "$LINUX_NATIVE"

test -s "$WINDOWS_NATIVE"
test -s "$LINUX_NATIVE"
file "$WINDOWS_NATIVE" "$LINUX_NATIVE"
nm -D "$LINUX_NATIVE" | grep -F "$JNI_SYMBOL"

python3 - "$WINDOWS_NATIVE" "$LINUX_NATIVE" "$JNI_SYMBOL" <<'PY'
from pathlib import Path
import sys
win = Path(sys.argv[1]).read_bytes()
linux = Path(sys.argv[2]).read_bytes()
symbol = sys.argv[3].encode('ascii')
if not win.startswith(b'MZ'):
    raise SystemExit('Windows native is not a PE image')
if not linux.startswith(b'\x7fELF'):
    raise SystemExit('Linux native is not an ELF image')
if symbol not in win:
    raise SystemExit('JNI symbol missing from Windows native')
if symbol not in linux:
    raise SystemExit('JNI symbol missing from Linux native')
PY

{
  echo "sable_source_sha=$SABLE_COMMIT"
  echo "jni_symbol=$JNI_SYMBOL"
  echo "jni_symbol_windows=present"
  echo "jni_symbol_linux=present"
  echo
  sha256sum "$WINDOWS_NATIVE" "$LINUX_NATIVE"
  echo
  stat --printf='%n size=%s bytes\n' "$WINDOWS_NATIVE" "$LINUX_NATIVE"
  echo
  file "$WINDOWS_NATIVE" "$LINUX_NATIVE"
} > "$RESOURCE_DIR/VALIDATION.txt"

cat "$RESOURCE_DIR/VALIDATION.txt"
