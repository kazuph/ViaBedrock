#!/usr/bin/env python3
"""Exercise review fixes and existing behavior on the real isolated copy server."""
import argparse
import json
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--java', required=True, type=Path)
parser.add_argument('--trace', required=True, type=Path)
parser.add_argument('--evidence', required=True, type=Path)
args = parser.parse_args()
args.evidence.mkdir(parents=True, exist_ok=True)
results = []


def run(name, command):
    output = args.evidence / f'{name}.log'
    with output.open('w') as log:
        result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=900)
    results.append(dict(name=name, command=command, output=str(output), exit=result.returncode))
    (args.evidence / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
    return result.returncode == 0


def client(name, scenario):
    return run(name, [str(args.java), '-cp', 'testclient/build/classes/java/main:testclient/build/dependencies/*',
                      'JavaPlayClient', '25575', 'ViaJavaTest', str(scenario), str(args.trace)])


def console(command):
    subprocess.run(['ssh', 'pc', f"docker exec minecraft-edu-test send-command '{command}'"], check=True, timeout=30)


try:
    for name in ('creative-native-number-swap', 'creative-native-number-catalog', 'creative-native-number-empty',
                 'creative-native-number-stale', 'creative-native-local-pickup-refusal', 'creative-native-local-refusal',
                 'creative-native-named-swap', 'creative-native-named-persisted', 'forced-close-distance', 'forced-close-server'):
        if not client(name, Path('testclient/scenarios') / f'{name}.txt'):
            raise SystemExit(f'{name} failed')
    # A real admin clear races RakNet processing. Success or no response never counts as rejection proof.
    rejected = Path('testclient/scenarios/creative-native-rejected-pickup.txt').read_text()
    for delay in (150, 180, 200, 220, 250, 300, 350):
        scenario = args.evidence / f'rejected-pickup-{delay}.txt'
        scenario.write_text(rejected.replace('stone 200', f'stone {delay}'))
        if client(f'rejected-pickup-{delay}', scenario):
            break
    else:
        raise SystemExit('No passing real server-rejection trial')
    persisted = args.evidence / 'rejected-pickup-persisted.txt'
    persisted.write_text('expect-spawn 30\ncount-server stone 0\nexpect-cursor 0 0 10\nsnapshot\n')
    if not client('rejected-pickup-persisted', persisted):
        raise SystemExit('Rejected pickup changed persisted total')
    if not run('native-existing', ['python3', 'testclient/tools/run_native_creative.py', '--java', str(args.java),
                                  '--trace', str(args.trace), '--evidence', str(args.evidence / 'native'), '--cases',
                                  'pickup', 'swap', 'half', 'trash', 'close-carried', 'tabs', 'close-race', 'drop-q', 'drop-ctrl-q', 'drop-q-partial']):
        raise SystemExit('Native regression failed')
    if not run('existing', ['python3', 'testclient/tools/run_creative_regressions.py', '--java', str(args.java),
                           '--trace', str(args.trace), '--evidence', str(args.evidence / 'existing')]):
        raise SystemExit('Existing regression failed')
    for name in ('creative-catalog-q-regression', 'delete-versus-drop'):
        if not client(name, Path('testclient/scenarios') / f'{name}.txt'):
            raise SystemExit(f'{name} failed')
finally:
    for index in range(27):
        item, count = ('stone', 32) if index < 4 else ('diamond_sword', 1) if index == 4 else ('oak_planks', 64) if index == 5 else ('air', 1)
        console(f'replaceitem block 240 76 -80 slot.container {index} {item} {count}')
    for index, item, count in [(0, 'stone', 16), (1, 'air', 1), (30, 'glass', 20)]:
        console(f'replaceitem block 242 76 -80 slot.container {index} {item} {count}')
    with (args.evidence / 'server-console.txt').open('w') as output:
        subprocess.run(['ssh', 'pc', 'docker logs minecraft-edu-test --tail 500'], stdout=output, stderr=subprocess.STDOUT, check=True, timeout=30)
print(json.dumps(results, indent=2))
