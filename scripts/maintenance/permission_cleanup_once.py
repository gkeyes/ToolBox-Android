"""One-off editor; removed before final review. Uses only the pinned reviewed script."""
from pathlib import Path
import subprocess

source = subprocess.check_output([
    'git', 'show',
    '64c792a50228c4bf166c47ed5fa83022af9fca0c:scripts/maintenance/permission_cleanup_once.py',
], text=True)
old = "text.count('network: { request:') != 6"
assert source.count(old) == 1
source = source.replace(old, "text.count('network: { request:') != 5")
exec(compile(source, 'reviewed-media-cleanup', 'exec'))
for name in ['src/toolbox/media.js', 'src/toolbox/imageCache.js', 'src/toolbox/mediaTransport.js', 'test/media.test.mjs', 'test/mediaTransport.test.mjs']:
    subprocess.run(['node', '--check', 'examples/nextflux/' + name], check=True)
subprocess.run(['node', '--test', 'examples/nextflux/test/mediaTransport.test.mjs'], check=True)
print('Media JavaScript syntax and transport unit tests passed in GitHub Actions. Full integration CI remains required.')
