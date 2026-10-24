#!/usr/bin/env python3
"""Interleaved real copy-server comparison; never starts an AI or a simulated server."""
import argparse
import hashlib
import json
import re
import statistics
import subprocess
import threading
import time
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--before', type=Path, required=True)
parser.add_argument('--after', type=Path, required=True)
parser.add_argument('--java', type=Path, required=True)
parser.add_argument('--client-java', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--pairs', type=int, default=5)
args = parser.parse_args()
repo = Path(__file__).resolve().parents[2]
args.output.mkdir(parents=True, exist_ok=True)
scenario = repo / 'testclient/scenarios/performance.txt'
classpath = str(repo / 'testclient/build/classes/java/main') + ':' + str(repo / 'testclient/build/dependencies/*')
results = []
for trial in range(args.pairs):
    for side, jar in [('before', args.before), ('after', args.after)]:
        run = args.output / f'{trial + 1}-{side}'
        run.mkdir()
        ready = threading.Event()
        command = [str(args.java), '-Dviabedrock.measureInventory=true', '-Xlog:gc*:file=gc.log:time,uptime,level,tags', '-jar', str(jar.resolve()),
                   'cli', '--bind-address', '127.0.0.1:25573', '--target-address', '100.80.152.112:19150', '--target-version', 'Bedrock 1.26.51', '--auth-method', 'NONE']
        proxy = subprocess.Popen(command, cwd=run, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        def drain():
            with (run / 'proxy-console.log').open('wb') as output:
                for line in proxy.stdout:
                    output.write(line)
                    if b'Binding proxy server to 127.0.0.1:25573' in line:
                        ready.set()
        reader = threading.Thread(target=drain)
        reader.start()
        try:
            if not ready.wait(30):
                raise RuntimeError(f'Proxy startup failed: {run}')
            with (run / 'client.log').open('wb') as output:
                client = subprocess.run([str(args.client_java), '-cp', classpath, 'JavaPlayClient', '25573', 'ViaJavaTest', str(scenario), str(run / 'logs/latest.log')],
                                        cwd=repo, stdout=output, stderr=subprocess.STDOUT, timeout=120)
            if client.returncode:
                raise RuntimeError(f'Real-client scenario failed: {run}')
        finally:
            # This exact PID belongs to the process created immediately above.
            proxy.terminate()
            try:
                proxy.wait(timeout=15)
            except subprocess.TimeoutExpired:
                raise RuntimeError(f'Proxy did not stop: pid={proxy.pid}; {run}')
            reader.join(timeout=15)
        log = (run / 'logs/latest.log').read_text()
        events = []
        for line in (run / 'client.log').read_text().splitlines():
            try:
                events.append(json.loads(line))
            except ValueError:
                pass
        request_times = {}
        latencies = []
        statuses = []
        for match in re.finditer(r'INVENTORY_TIMING time=(\d+) requestId=(-?\d+) (sent=true|result=(\d+))', log):
            timestamp, request_id = int(match[1]), int(match[2])
            if match[3] == 'sent=true':
                request_times[request_id] = timestamp
            else:
                statuses.append(int(match[4]))
                latencies.append((timestamp - request_times[request_id]) / 1e6)
        if len(statuses) != 4 or any(statuses):
            raise AssertionError(f'Expected four real successful responses: {statuses}; {run}')
        walk_start = next(e['time'] for e in events if e['event'] == 'command' and e['data'].startswith('walk '))
        walk_end = max(e['time'] for e in events if e['event'] == 'PASS' and e['data'].startswith('walk '))
        corrections = [e for e in events if e['event'] == 'position' and walk_start <= e['time'] <= walk_end]
        correction_x = [e['data']['position']['x'] for e in corrections]
        if correction_x and max(correction_x) == min(correction_x):
            raise AssertionError(f'No server-observed X movement in correction stream: {run}')
        gc = (run / 'gc.log').read_text()
        pauses = [float(m[1]) for m in re.finditer(r'Pause[^\n]*? (\d+\.\d+)ms', gc)]
        row = dict(trial=trial + 1, side=side, chunk_errors=log.count('Error reading chunk data'),
                   log_bytes=len(log.encode()), walk_ms=(walk_end - walk_start) / 1e6,
                   position_corrections=len(corrections), correction_x_range=[min(correction_x), max(correction_x)] if correction_x else [],
                   request_ack_ms=latencies, gc_pauses_ms=pauses,
                   successful_responses=len(statuses), client_exit=client.returncode)
        results.append(row)
        (args.output / 'runs.json').write_text(json.dumps(results, indent=2))
        print(json.dumps(row), flush=True)
summary = {'scope': 'headless Java 26.3 codec, same 18-block walking route, Java 17 proxy, fresh process and identical flags; no rendering or human feel measurement',
           'before_sha256': hashlib.sha256(args.before.read_bytes()).hexdigest(),
           'after_sha256': hashlib.sha256(args.after.read_bytes()).hexdigest(),
           'scenario_sha256': hashlib.sha256(scenario.read_bytes()).hexdigest(), 'runs': results, 'summary': {}}
for side in ['before', 'after']:
    rows = [r for r in results if r['side'] == side]
    aggregate = {}
    for metric in ['chunk_errors', 'log_bytes', 'walk_ms', 'position_corrections']:
        values = [r[metric] for r in rows]
        aggregate[metric] = dict(median=statistics.median(values), minimum=min(values), maximum=max(values))
    for metric in ['request_ack_ms', 'gc_pauses_ms']:
        values = sorted(v for r in rows for v in r[metric])
        aggregate[metric] = dict(count=len(values), median=statistics.median(values) if values else None,
                                 p95=values[max(0, (len(values) * 95 + 99) // 100 - 1)] if values else None,
                                 maximum=max(values) if values else None)
    summary['summary'][side] = aggregate
(args.output / 'summary.json').write_text(json.dumps(summary, indent=2))
print(json.dumps(summary['summary'], indent=2), flush=True)
