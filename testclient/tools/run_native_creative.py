#!/usr/bin/env python3
"""Run real Java 26.3 creative slot sequences against the copy-server proxy."""
import argparse
import json
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--java', type=Path, required=True)
parser.add_argument('--trace', type=Path, required=True)
parser.add_argument('--evidence', type=Path, required=True)
parser.add_argument('--port', type=int, default=25575)
parser.add_argument('--cases', nargs='+', choices=('pickup', 'swap', 'half', 'trash', 'close-carried', 'tabs', 'close-race', 'drop-q', 'drop-ctrl-q', 'drop-q-partial'),
                    default=('pickup', 'swap', 'half', 'trash', 'close-carried', 'tabs', 'close-race'))
args = parser.parse_args()
if args.port != 25575:
    parser.error('Use the isolated native-creative proxy on 25575; human proxies are reserved')
args.evidence.mkdir(parents=True, exist_ok=True)
results = []
for name in args.cases:
    scenario = Path('testclient/scenarios') / f'creative-native-{name}.txt'
    output = args.evidence / f'{name}.log'
    command = [str(args.java), '-cp', 'testclient/build/classes/java/main:testclient/build/dependencies/*',
               'JavaPlayClient', str(args.port), 'ViaJavaTest', str(scenario), str(args.trace)]
    with output.open('w') as log:
        result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=180)
    results.append(dict(scenario=str(scenario), command=command, output=str(output), exit=result.returncode))
    (args.evidence / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
    if result.returncode:
        raise SystemExit(f'{name} failed; see {output}')
print(json.dumps(results, indent=2))
