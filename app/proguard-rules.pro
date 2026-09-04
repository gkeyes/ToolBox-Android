# Room, WorkManager and kotlinx.serialization supply their own consumer keep rules.
# WorkManager keeps persisted worker class names stable across debug/release upgrades.
# Add only verified reflection entry points here; do not keep entire modules or dependencies.
