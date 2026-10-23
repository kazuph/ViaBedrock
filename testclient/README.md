# Java 26.3 integration client

Uses the real `org.geysermc.mcprotocollib:protocol:26.3` codec, independently of the ViaBedrock build. Java 21 is required by the library. The ViaBedrock build itself continues to use Java 17.

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home ./gradlew -p testclient classes copyDependencies
<java21>/bin/java -cp 'testclient/build/classes/java/main:testclient/build/dependencies/*' JavaPlayClient 25570 u1u1u1u1u1 testclient/scenarios/login.txt
```

The client connects only to loopback and rejects the production proxy port 25568. The proxy must target the disposable copy at `100.80.152.112:19150`. Never run these scenarios against production.

Commands are read from a file, or from standard input when the third argument is `-`:

- `snapshot`: write received container state and cursor to JSON.
- `creative <java-slot> <java-item-id> <count>`: send a creative slot update; count 0 deletes.
- `click <window> <slot> <mode> <button>`: send an actual container click using the codec's seven action modes.
- `close <window>`: close a window.
- `expect <window> <slot> <java-item-id> <count> <timeout-seconds>`: await server-supplied state and fail with nonzero exit if the condition is unmet.

Unknown commands fail explicitly. Every expected state must come from a server packet; no local simulated inventory is used. A successful login is not proof that inventory operations work. This headless client does not verify rendering, frame times, or real-client input sequences.

Start the test proxy with `-Dviabedrock.traceInventory=true` to record inventory packet payloads and decoded START_GAME authority flags. The property is off by default; it must be off for performance comparisons. It excludes login packets and credentials.
