#!/usr/bin/env python3
"""Measure how long chunks near the player stay forgotten after the Java client received FORGET_LEVEL_CHUNK.

Input: JavaPlayClient output (JSON lines with level_chunk, forget_level_chunk, set_chunk_cache_center).
A "hole" is a FORGET for a chunk within --near chunks (Chebyshev) of the cache center that is later re-sent by
LEVEL_CHUNK. The proxy sends FORGET before the SET_CHUNK_CACHE_CENTER of the same move, so each FORGET is judged
against the next center update (the center the Java client uses once the move is applied). The duration is the time until that LEVEL_CHUNK; chunks never re-sent count as unresolved.
Times are the client's receive times (System.nanoTime), so the spread is real wall-clock."""
import argparse, json, statistics

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('log')
parser.add_argument('--near', type=int, default=14)
parser.add_argument('--json', action='store_true')
args = parser.parse_args()
center = (0, 0)
queued = {}
open_holes = {}
durations = []
forgets_near = 0


def settle():
    global forgets_near
    for key, time in queued.items():
        if max(abs(key[0] - center[0]), abs(key[1] - center[1])) <= args.near:
            forgets_near += 1
            open_holes[key] = time
    queued.clear()


for line in open(args.log):
    if not line.startswith('{'):
        continue
    event = json.loads(line)
    kind, data, time = event['event'], event['data'], event['time'] / 1e9
    if kind == 'set_chunk_cache_center':
        center = (data['x'], data['z'])
        settle()
    elif kind == 'forget_level_chunk':
        queued[(data['x'], data['z'])] = time
    elif kind == 'level_chunk':
        key = (data['x'], data['z'])
        if key in open_holes:
            durations.append(time - open_holes.pop(key))
settle()
durations.sort()
result = {'near_forgets': forgets_near, 'resolved': len(durations), 'unresolved': len(open_holes)}
if durations:
    result.update(median_s=round(statistics.median(durations), 2), p90_s=round(durations[int(len(durations) * .9)], 2), max_s=round(durations[-1], 2))
print(json.dumps(result) if args.json else result)
