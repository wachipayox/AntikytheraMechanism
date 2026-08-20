#!/usr/bin/env bash
set -euo pipefail

RESOURCE_DIR="src/main/resources/natives/antikytheramechanism_sable_rapier"
WINDOWS_NATIVE="$RESOURCE_DIR/sable_rapier_x86_64_windows.dll"
LINUX_NATIVE="$RESOURCE_DIR/sable_rapier_x86_64_linux.so"
SABLE_COMMIT="4bc206ee4718f2a7906e4366963ada98eadd78fae"

# A PR build may run again after the natives have already been committed by the orchestrating agent.
# Check the real integration ref first so subsequent runs exit without rebuilding Rust.
git fetch origin agent/integration-current
if git cat-file -e "origin/agent/integration-current:$WINDOWS_NATIVE" 2>/dev/null \
   && git cat-file -e "origin/agent/integration-current:$LINUX_NATIVE" 2>/dev/null; then
    echo "Patched Sable Rapier natives already exist on agent/integration-current; skipping."
    exit 0
fi

rm -rf /tmp/antikythera-sable /tmp/antikythera-patched-natives /tmp/antikythera-artifact-uploader
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

mkdir -p /tmp/antikythera-patched-natives
cp sable_rapier/src/main/rust/target/x86_64-pc-windows-msvc/release/sable_rapier.dll \
   /tmp/antikythera-patched-natives/sable_rapier_x86_64_windows.dll
cp sable_rapier/src/main/rust/target/x86_64-unknown-linux-gnu/release/libsable_rapier.so \
   /tmp/antikythera-patched-natives/sable_rapier_x86_64_linux.so

# PR GITHUB_TOKEN is read-only in this repository, so do not try to push. Upload the binaries through
# the Actions artifact service instead; the orchestration agent will download and commit them through
# the GitHub API after this job succeeds.
mkdir -p /tmp/antikythera-artifact-uploader
cd /tmp/antikythera-artifact-uploader
npm init -y >/dev/null 2>&1
npm install --silent @actions/artifact@5
cat > upload.cjs <<'NODE'
const {DefaultArtifactClient} = require('@actions/artifact');
(async () => {
  const root = '/tmp/antikythera-patched-natives';
  const files = [
    `${root}/sable_rapier_x86_64_windows.dll`,
    `${root}/sable_rapier_x86_64_linux.so`
  ];
  const client = new DefaultArtifactClient();
  const result = await client.uploadArtifact(
    'patched-sable-rapier-force-prototype',
    files,
    root,
    {retentionDays: 1}
  );
  console.log(`Uploaded patched native artifact id=${result.id} size=${result.size}`);
})().catch(err => {
  console.error(err);
  process.exit(1);
});
NODE
node upload.cjs
