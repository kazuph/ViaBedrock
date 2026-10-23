#!/usr/bin/env python3
"""Search a measured console/network race using only real copy-server operations."""
import argparse
import json
import signal
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--java', required=True, type=Path)
parser.add_argument('--proxy-log', required=True, type=Path)
parser.add_argument('--output', required=True, type=Path)
parser.add_argument('--delay-ms', required=True, type=int, nargs='+')
args = parser.parse_args()
repo = Path(__file__).resolve().parents[2]
args.output.mkdir(parents=True, exist_ok=True)
base = (repo / 'testclient/scenarios/stale-network-id.txt').read_text()
classpath = str(repo / 'testclient/build/classes/java/main') + ':' + str(repo / 'testclient/build/dependencies/*')
rows = []


def restore_chest():
    for slot, item, count in [(0, 'stone', 16), (1, 'air', 1), (30, 'glass', 20)]:
        subprocess.run(['ssh', 'pc', f"docker exec minecraft-edu-test send-command 'replaceitem block 242 76 -80 slot.container {slot} {item} {count}'"], check=True, timeout=30)


def interrupt(signum, frame):
    raise KeyboardInterrupt


signal.signal(signal.SIGTERM, interrupt)
success = False
try:
    for index, delay in enumerate(args.delay_ms):
        restore_chest()
        scenario = args.output / f'{index + 1}-delay-{delay}.txt'
        scenario.write_text(base.replace('fixture-clear-race glass 200', f'fixture-clear-race glass {delay}'))
        with scenario.with_suffix('.log').open('wb') as output:
            client = subprocess.Popen([str(args.java), '-cp', classpath, 'JavaPlayClient', '25571', 'ViaJavaTest', str(scenario), str(args.proxy_log)], cwd=repo, stdout=output, stderr=subprocess.STDOUT)
            try:
                code = client.wait(timeout=120)
            finally:
                if client.poll() is None:
                    client.terminate()
                    client.wait(timeout=30)
        row = dict(delay_ms=delay, exit=code, evidence=str(scenario.with_suffix('.log')))
        rows.append(row)
        (args.output / 'results.json').write_text(json.dumps(rows, indent=2))
        print(json.dumps(row), flush=True)
        if code == 0:
            success = True
            break
finally:
    restore_chest()
if not success:
    raise SystemExit('Required real rejection plus dependent-only rollback was not reproduced; not met')
