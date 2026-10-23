# Java 26.3 integration client

Uses the real `org.geysermc.mcprotocollib:protocol:26.3` codec, independently of the ViaBedrock build. Java 21 is required by the library. The ViaBedrock build itself continues to use Java 17.

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home ./gradlew -p testclient classes copyDependencies
<java21>/bin/java -cp 'testclient/build/classes/java/main:testclient/build/dependencies/*' JavaPlayClient 25571 ViaJavaTest testclient/scenarios/login.txt
```

The client connects only to loopback and rejects the production proxy port 25568. The proxy must target the disposable copy at `100.80.152.112:19150`. Never run these scenarios against production.

Commands are read from a file, or from standard input when the third argument is `-`:

- `snapshot`: write received container state and cursor to JSON.
- `creative <java-slot> <java-item-id> <count>`: send a creative slot update; count 0 deletes.
- `creative-damage <slot> <item-id> <count> <damage>`: include a real damage component for catalog rejection tests.
- `creative-book <slot> <enchantment-resource-name> <level> <item-id>`: create a book with stored enchantments, resolving the enchantment ID from the received registry.
- `click <window> <slot> <mode> <button>`: send an actual container click using the codec's seven action modes.
- `close <window>`: close a window.
- `select <hotbar-slot>` / `drop-selected one|all`: select a hotbar slot and send the real world Q / Ctrl+Q player action.
- `fixture <x> <y> <z> <timeout-seconds>`: after actual spawn, teleport only `ViaJavaTest` using the authorized SSH copy-server console, then await its received position.
- `fixture-permission visitor|member <timeout-seconds>`: change only `ViaJavaTest` on the copy server, await the Java abilities update, and always restore member on exit.
- `dig START_DIGGING|CANCEL_DIGGING|FINISH_DIGGING <x> <y> <z> <face>`: send the actual Java block action and sequence number.
- `use-on <x> <y> <z> <face> <hit-x> <hit-y> <hit-z>`: send a main-hand block interaction.
- `expect-block <x> <y> <z> <java-block-state> <timeout-seconds>`: compare a received block update; independently verify world changes with the copy server console.
- `expect-menu <27|54> <timeout-seconds>`: verify the real menu type and chest plus 36 player slots. Use `open` as the window argument to `click`, `expect`, or `close` for its received ID.
- `fixture-slot <inventory-index> <stone|glass|air> <count>` / `fixture-hotbar <hotbar-index> <stone|glass|air> <count>`: replace only the automatic test player slot through the copy-server console. Await the received slot with `expect` before using it.
- `fixture-chest full|normal`: fill the authorized normal test chest, or restore its documented initial contents through real server commands.
- `expect-cursor <item-id> <count> <timeout-seconds>`: compare the actual received cursor.
- `expect <window> <slot> <java-item-id> <count> <timeout-seconds>`: await server-supplied state and fail with nonzero exit if the condition is unmet.
- `expect-spawn <timeout-seconds>`: wait for the server's spawn completion game event before testing world interactions such as dropping.
- `expect-ack <request-id> <result-code> <timeout-seconds>`: verify an actual Bedrock response in the development proxy trace log supplied as the optional fourth argument. Only newly appended log content is inspected.
- `expect-book <slot> <enchantment-resource-name> <level> <timeout-seconds>`: compare the received stored enchantment map, including the level.
- `save-inventory <path>` / `expect-inventory <path> <timeout-seconds>`: save and compare every player slot and cursor using the real item wire codec, including components.

Unknown commands fail explicitly. Received Java state may include the proxy's pending projection: use `expect-ack` and a separate reconnect with `expect-inventory` to prove server acceptance and persistence. The client has no local simulated inventory. The scenarios document the fixture state at their start; they are not all independent or repeatable without restoring that state. A successful login is not proof that inventory operations work. This headless client does not verify rendering, frame times, or real-client input sequences.

Start the test proxy with `-Dviabedrock.traceInventory=true` to record inventory packet payloads and decoded START_GAME authority flags. The property is off by default; it must be off for performance comparisons. It excludes login packets and credentials.

## Performance comparison

`tools/compare_proxy.py` runs at least five before/after pairs in alternating order against the real copy server, with fresh Java 17 proxy processes, matching flags, GC logs, and the same scenario. It uses port 25573 and stops only the exact processes it creates. `walk <x> <z> <client-ticks>` sends actual forward input plus position/rotation at the ordinary 20 ticks/second. This is a headless input path, not a native rendering measurement.

Use `-Dviabedrock.measureInventory=true` for request/response IDs and monotonic timestamps without item payloads. This flag and `traceInventory` are off by default. The comparison leaves payload tracing off on both sides. `expect-ack` also accepts the timing records; its wait includes log flushing, so use timestamps inside the log for latency, not the command's wall time. The JSON reports errors, real response statuses, correction counts, GC pauses and log bytes. A missing response or failed scenario fails the run. Retain raw runs and describe any invalid workload separately; do not report a speedup from failures or missing movement.
