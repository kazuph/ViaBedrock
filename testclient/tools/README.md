# Java 26.3 item stack limits

`DumpJavaItemStackSizes.java` uses the installed official game's registry and component initializers. It exports `Item.getDefaultMaxStackSize()` for all 1658 item names, the 84 explicit default damage values, and the 84 equippable slot names from the same component registry. It does not start the client UI, connect to a server, or simulate game data.

```sh
python3 testclient/tools/dump_java_item_limits.py \
  "$HOME/Library/Application Support/minecraft" \
  "$HOME/Library/Application Support/minecraft/runtime/java-runtime-epsilon/mac-os-arm64/java-runtime-epsilon/jre.bundle/Contents/Home/bin/java" \
  26.3 src/main/resources/assets/viabedrock/data/java/item_stack_limits.json
```

Input: official installed `26.3.jar`, SHA-256 `4508d006323f24fa02876310c192d739af56516eb259000ac50f0909a68c9a2d`.
Output SHA-256: `08ee55b09c20433465eca48035deb2714989700de37d9fabd83bea5b6c9ba415`.
Every name in `via_mappings.json` was present in the output, with no invented default limit. Explicit item `max_stack_size` components override the vanilla default during menu calculations; Bedrock still validates every resulting request.

`item_default_damage.json` distinguishes an explicitly transmitted vanilla default from an actual metadata change. `item_equipment_slots.json` supplies Shift-equip destinations; no item-name suffix rules are used.
