"""Prepare only the explicitly designated disposable server for human capture."""
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
JAVA = Path.home() / '.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.12.1+1/Contents/Home/bin/java'
player = 'u1u1u1u1u1'
client = subprocess.Popen([str(JAVA), '-cp', 'testclient/build/classes/java/main:testclient/build/dependencies/*',
                           'JavaPlayClient', '25570', player, '-'], cwd=ROOT,
                          stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)
try:
    for line in client.stdout:
        print(line, end='', flush=True)
        event = json.loads(line)
        if event['event'] == 'ready':
            break
        if event['event'] in ('failure', 'disconnected'):
            raise RuntimeError('Fixture login failed')
    else:
        raise RuntimeError('Fixture client exited before login')
    commands = [
        'fill 399 63 159 407 63 163 stone',
        'setblock 402 64 160 chest',
        'setblock 404 64 160 chest',
        'setblock 405 64 160 chest',
        'replaceitem block 402 64 160 slot.container 0 oak_planks 64',
        f'clear {player}',
        f'give {player} stone 64',
        f'give {player} dirt 32',
        f'give {player} iron_sword 1',
        f'tp {player} 400 64 162 180 0',
    ]
    for command in commands:
        print('COPY_SERVER_COMMAND', command, flush=True)
        subprocess.run(['ssh', '-o', 'BatchMode=yes', 'pc',
                        shlex.join(['docker', 'exec', '--user', 'root', 'minecraft-edu-test', 'send-command', command])], check=True)
    client.stdin.write('expect 0 36 1 64 30\nsnapshot\n')
    client.stdin.close()
    for line in client.stdout:
        print(line, end='', flush=True)
    code = client.wait(timeout=45)
    if code != 0:
        raise RuntimeError(f'Fixture assertion failed: {code}')
finally:
    if client.poll() is None:
        own = subprocess.run(['pgrep', '-u', str(os.getuid()), '-f', 'JavaPlayClient 25570 ' + player], capture_output=True, text=True)
        if str(client.pid) in own.stdout.split():
            os.kill(client.pid, 15)
            client.wait(timeout=10)
        else:
            raise RuntimeError('Cannot verify test client PID for cleanup')
