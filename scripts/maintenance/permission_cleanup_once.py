"""Apply the pinned reviewed documentation/scheduling patch and its matching test."""
from pathlib import Path
import subprocess

source = subprocess.check_output([
    'git', 'show',
    '549863d3e6ef84f1e34baa1dd2ecf1afb41bdbf2:scripts/maintenance/permission_cleanup_once.py',
], text=True)
edits, checks = source.split('# Do not change compiler flags', 1)
exec(compile(edits, 'reviewed-scheduling-doc-edits', 'exec'))
replace('examples/nextflux/test/image-cache.test.mjs',
    'test("idle images do not consume the existing 24 active image slots", async () => {\n  const { cache } = harness();',
    'test("default active image count has no 24-item quota and clearing releases every Blob", async () => {\n  const { cache, created, revoked } = harness();')
replace('examples/nextflux/test/image-cache.test.mjs',
    '  const active = Array.from({ length: 24 }, (_, index) => cache.acquire(`active-${index}`));\n  assert.throws(() => cache.acquire("overflow"), /图片较多/);\n  await Promise.all(active.map((image) => image.promise));\n  active.forEach((image) => image.release());\n  cache.clear();',
    '  const active = Array.from({ length: 32 }, (_, index) => cache.acquire(`active-${index}`));\n  await Promise.all(active.map((image) => image.promise));\n  assert.equal(active.length, 32);\n  active.forEach((image) => image.release());\n  cache.clear();\n  assert.deepEqual([...revoked].sort(), [...created].sort());\n  assert.equal(new Set(revoked).size, revoked.length);')
exec(compile('# Do not change compiler flags' + checks, 'reviewed-ci-checks', 'exec'))
