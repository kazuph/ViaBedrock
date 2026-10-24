#!/usr/bin/env python3
"""Repeat existing scenarios with explicit real copy-server fixtures, restoring chests."""
import argparse
import json
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--java', type=Path, required=True)
parser.add_argument('--trace', type=Path, required=True)
parser.add_argument('--evidence', type=Path, required=True)
args = parser.parse_args()
args.evidence.mkdir(parents=True, exist_ok=True)
results = []


def console(command):
    subprocess.run(['ssh', 'pc', f"docker exec minecraft-edu-test send-command '{command}'"], check=True, timeout=30)


def player_slot(area, index, item, count=1):
    preparation.append(f'fixture-{"hotbar" if area == "hotbar" else "slot"} {index} {item} {count}')


def chest_normal():
    for index in range(27):
        item, count = ('stone', 32) if index < 4 else ('diamond_sword', 1) if index == 4 else ('oak_planks', 64) if index == 5 else ('air', 1)
        console(f'replaceitem block 240 76 -80 slot.container {index} {item} {count}')


def chest_large():
    for index, item, count in [(0, 'stone', 16), (1, 'air', 1), (30, 'glass', 20)]:
        console(f'replaceitem block 242 76 -80 slot.container {index} {item} {count}')


try:
    for name in ('creative-book', 'creative-book-persisted', 'click-all', 'click-drag-outside', 'click-rapid',
                 'click-close-race', 'chest-actions', 'chest-full', 'hotbar-immediate-place', 'creative-break', 'creative-break-denied'):
        preparation = ['expect-spawn 30']
        if name not in ('creative-book-persisted', 'creative-break', 'creative-break-denied'):
            preparation.append('fixture-reset')
        if name == 'click-all':
            player_slot('hotbar', 0, 'glass', 64)
            player_slot('hotbar', 1, 'stone', 64)
            player_slot('hotbar', 2, 'diamond_sword')
            player_slot('inventory', 0, 'oak_planks', 64)
            player_slot('inventory', 1, 'oak_planks', 64)
        if name == 'click-drag-outside':
            player_slot('hotbar', 2, 'diamond_sword')
        if name in ('chest-actions', 'chest-full'):
            for index in range(27):
                player_slot('inventory', index, 'glass', 64)
            for index in range(9):
                player_slot('hotbar', index, 'glass', 64)
            player_slot('hotbar', 2, 'stone', 64)
            if name == 'chest-actions':
                player_slot('inventory', 12, 'stone', 32)
            else:
                player_slot('hotbar', 8, 'air')
            chest_normal()
            chest_large()
        if name == 'hotbar-immediate-place':
            console('setblock 236 76 -84 air')
            console('setblock 237 76 -84 air')
        if name == 'creative-break-denied':
            console('setblock 235 76 -83 oak_planks')
        source = Path('testclient/scenarios') / f'{name}.txt'
        text = source.read_text()
        if name.startswith('chest-'):
            first, rest = text.split('\n', 1)
            text = first + '\nexpect-chunk 15 -5 30\n' + rest
        scenario = args.evidence / f'{name}.txt'
        scenario.write_text('\n'.join(preparation + ['chat VB-regression-setup', 'expect-chat VB-regression-setup 10']) + '\n' + text)
        output = args.evidence / f'{name}.log'
        command = [str(args.java), '-cp', 'testclient/build/classes/java/main:testclient/build/dependencies/*',
                   'JavaPlayClient', '25575', 'ViaJavaTest', str(scenario), str(args.trace)]
        with output.open('w') as log:
            result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=180)
        results.append(dict(original=str(source), scenario=str(scenario), output=str(output), command=command, exit=result.returncode))
        (args.evidence / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
        if result.returncode:
            raise SystemExit(f'{name} failed; see {output}')
finally:
    chest_normal()
    chest_large()
    with (args.evidence / 'server-console.txt').open('w') as output:
        subprocess.run(['ssh', 'pc', 'docker logs minecraft-edu-test --tail 400'], stdout=output, stderr=subprocess.STDOUT, check=True, timeout=30)
print(json.dumps(results, indent=2))
