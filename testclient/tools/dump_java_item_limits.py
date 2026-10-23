"""Export the official installed Java game's item stack limits, without launching its UI.

Usage: python3 dump_java_item_limits.py <minecraft-dir> <java-executable> <version> <output-json>
The selected game's own runtime version and library files must already be installed.
"""
import json
import subprocess
import sys
from pathlib import Path

minecraft, java, version, output = sys.argv[1:]
root = Path(minecraft).resolve()
manifest = json.loads((root / 'versions' / version / f'{version}.json').read_text())
classpath = [str(root / 'versions' / version / f'{version}.jar')]
for library in manifest['libraries']:
    artifact = library.get('downloads', {}).get('artifact', {})
    if 'path' in artifact:
        classpath.append(str(root / 'libraries' / artifact['path']))
source = Path(__file__).with_name('DumpJavaItemStackSizes.java').resolve()
raise SystemExit(subprocess.run([java, '-cp', ':'.join(classpath), str(source), str(Path(output).resolve())]).returncode)
