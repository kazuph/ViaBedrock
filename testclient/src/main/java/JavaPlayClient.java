import com.google.gson.Gson;
import io.netty.buffer.Unpooled;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.*;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.game.ResourcePackStatus;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.DropItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSetCarriedItemPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundForgetLevelChunkPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSetChunkCacheCenterPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSetChunkCacheRadiusPacket;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.ItemEnchantments;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundRegistryDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundResourcePackPushPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundResourcePackPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundClientInformationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerAbilitiesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.GameEvent;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundGameEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundPlayerLoadedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientTickEndPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundPlayerInputPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.zip.ZipFile;

/** A real Java 26.3 network client; it has no rendering or simulated server. */
public final class JavaPlayClient extends SessionAdapter {
    private Path responseLog;
    private int responseLogOffset;
    private final Map<String, Integer> enchantments = new HashMap<>();
    private final Gson json = new Gson();
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final CompletableFuture<Void> spawned = new CompletableFuture<>();
    private final Map<Integer, ItemStack[]> containers = new HashMap<>();
    private final Object changed = new Object();
    private Session session;
    private ItemStack cursor;
    private int revision;
    private int cursorUpdates;
    private int cursorUpdateMarker;
    private ItemStack rememberedItem;
    private ItemStack rememberedSwapLeft, rememberedSwapRight;
    private boolean closed;
    private String username;
    private double playerX, playerY, playerZ;
    private float playerYaw, playerPitch;
    private int blockSequence;
    private int abilitiesRevision;
    private int openWindow = -1;
    private String openMenu;
    private boolean permissionChanged;
    private long clientTick;
    private final Map<Vector3i, Integer> blocks = new HashMap<>();
    private final Set<EntityType> entities = new HashSet<>();
    private final List<String> chat = new ArrayList<>();
    private final List<Process> consoleCommands = new ArrayList<>();
    private final Set<Integer> itemEntities = new HashSet<>();
    private final Set<Integer> removedEntities = new HashSet<>();
    private final Map<Integer, ItemStack> droppedItems = new HashMap<>();
    private final Map<String, Set<Integer>> spawnMarkers = new HashMap<>();
    private final Set<ChunkPosition> loadedChunks = new HashSet<>();

    private record ChunkPosition(int x, int z) {}
    private int cacheCenterX, cacheCenterZ, cacheRadius = -1;

    private synchronized void log(String event, Object data) {
        System.out.println(json.toJson(Map.of("time", System.nanoTime(), "event", event, "data", data)));
        System.out.flush();
    }

    @Override
    public void packetReceived(Session session, Packet packet) {
        try {
            if (packet instanceof ClientboundRegistryDataPacket registry && registry.getRegistry().asString().equals("minecraft:enchantment")) {
                for (int i = 0; i < registry.getEntries().size(); i++) enchantments.put(registry.getEntries().get(i).getId().asString(), i);
            } else if (packet instanceof ClientboundResourcePackPushPacket pack) {
                session.send(new ServerboundResourcePackPacket(pack.getId(), ResourcePackStatus.ACCEPTED));
                Path downloaded = Files.createTempFile("viabedrock-java26-pack-", ".zip");
                try {
                    HttpURLConnection response = (HttpURLConnection) URI.create(pack.getUrl()).toURL().openConnection();
                    response.setConnectTimeout(60_000);
                    response.setReadTimeout(60_000);
                    try {
                        if (response.getResponseCode() != 200) throw new IllegalStateException("Pack HTTP " + response.getResponseCode());
                        try (var in = response.getInputStream()) { Files.copy(in, downloaded, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
                    } finally { response.disconnect(); }
                    log("pack_download", Map.of("bytes", Files.size(downloaded),
                        "prefix", HexFormat.of().formatHex(Arrays.copyOf(Files.readAllBytes(downloaded), 16))));
                    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(downloaded)));
                    if (!pack.getHash().isEmpty() && !hash.equalsIgnoreCase(pack.getHash())) throw new IllegalStateException("Pack hash mismatch");
                    int entries = 0;
                    try (ZipFile zip = new ZipFile(downloaded.toFile())) {
                        if (zip.getEntry("pack.mcmeta") == null) throw new IllegalStateException("Missing pack.mcmeta");
                        var files = zip.entries();
                        while (files.hasMoreElements()) {
                            var file = files.nextElement();
                            try (var in = zip.getInputStream(file)) { in.transferTo(java.io.OutputStream.nullOutputStream()); }
                            entries++;
                        }
                    }
                    log("pack_validated", Map.of("sha1", hash, "entries", entries));
                    session.send(new ServerboundResourcePackPacket(pack.getId(), ResourcePackStatus.DOWNLOADED));
                    session.send(new ServerboundResourcePackPacket(pack.getId(), ResourcePackStatus.SUCCESSFULLY_LOADED));
                } finally { Files.deleteIfExists(downloaded); }
            } else if (packet instanceof ClientboundPlayerPositionPacket position) {
                log("position", position);
                synchronized (changed) {
                    playerX = position.getPosition().getX() + (position.getRelatives().contains(PositionElement.X) ? playerX : 0);
                    playerY = position.getPosition().getY() + (position.getRelatives().contains(PositionElement.Y) ? playerY : 0);
                    playerZ = position.getPosition().getZ() + (position.getRelatives().contains(PositionElement.Z) ? playerZ : 0);
                    playerYaw = position.getYRot() + (position.getRelatives().contains(PositionElement.Y_ROT) ? playerYaw : 0);
                    playerPitch = position.getXRot() + (position.getRelatives().contains(PositionElement.X_ROT) ? playerPitch : 0);
                    changed.notifyAll();
                }
                session.send(new ServerboundAcceptTeleportationPacket(position.getId(), playerX, playerY, playerZ, playerYaw, playerPitch));
                if (position.getId() >= 0) {
                    session.send(new ServerboundMovePlayerPosRotPacket(false, false, playerX, playerY, playerZ, playerYaw, playerPitch));
                }
                if (!ready.isDone()) session.send(ServerboundPlayerLoadedPacket.INSTANCE);
                ready.complete(null);
            } else if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
                synchronized (changed) {
                    loadedChunks.add(new ChunkPosition(chunk.getX(), chunk.getZ()));
                    log("level_chunk", Map.of("x", chunk.getX(), "z", chunk.getZ()));
                    changed.notifyAll();
                }
            } else if (packet instanceof ClientboundForgetLevelChunkPacket forget) {
                synchronized (changed) {
                    loadedChunks.remove(new ChunkPosition(forget.getX(), forget.getZ()));
                    log("forget_level_chunk", Map.of("x", forget.getX(), "z", forget.getZ()));
                    changed.notifyAll();
                }
            } else if (packet instanceof ClientboundSetChunkCacheCenterPacket center) {
                synchronized (changed) {
                    cacheCenterX = center.getChunkX();
                    cacheCenterZ = center.getChunkZ();
                    log("set_chunk_cache_center", Map.of("x", center.getChunkX(), "z", center.getChunkZ()));
                    changed.notifyAll();
                }
            } else if (packet instanceof ClientboundSetChunkCacheRadiusPacket radius) {
                synchronized (changed) {
                    cacheRadius = radius.getViewDistance();
                    log("set_chunk_cache_radius", Map.of("radius", radius.getViewDistance()));
                    changed.notifyAll();
                }
            } else if (packet instanceof ClientboundBlockUpdatePacket block) {
                synchronized (changed) {
                    blocks.put(block.getEntry().getPosition(), block.getEntry().getBlock());
                    log("block_update", block);
                    changed.notifyAll();
                }
            } else if (packet instanceof ClientboundPlayerAbilitiesPacket abilities) {
                synchronized (changed) {
                    abilitiesRevision++;
                    log("abilities", abilities);
                    changed.notifyAll();
                }
            } else if (packet instanceof ClientboundGameEventPacket event) {
                log("game_event", event);
                if (event.getNotification() == GameEvent.LEVEL_CHUNKS_LOAD_START) spawned.complete(null);
            } else if (packet instanceof ClientboundContainerSetContentPacket content) {
                synchronized (changed) {
                    containers.put(content.getContainerId(), content.getItems().clone());
                    cursor = content.getCarriedItem();
                    cursorUpdates++;
                    revision = content.getStateId();
                    log("content", content);
                    changed.notifyAll();
                }
            } else if (packet instanceof ClientboundContainerSetSlotPacket slot) {
                synchronized (changed) {
                    ItemStack[] items = containers.get(slot.getContainerId());
                    if (items != null && slot.getSlot() >= 0 && slot.getSlot() < items.length) items[slot.getSlot()] = slot.getItem();
                    revision = slot.getStateId();
                    log("slot", slot);
                    changed.notifyAll();
                }
            } else if (packet instanceof ClientboundSetCursorItemPacket carried) {
                synchronized (changed) {
                    cursor = carried.getContents();
                    cursorUpdates++;
                    log("cursor", String.valueOf(cursor));
                    changed.notifyAll();
                }
            } else if (packet instanceof ClientboundSetEntityDataPacket metadata) {
                for (var entry : metadata.getMetadata()) {
                    if (entry.getValue() instanceof ItemStack item) {
                        synchronized (changed) {
                            if (itemEntities.contains(metadata.getEntityId())) droppedItems.put(metadata.getEntityId(), item);
                            log("item_entity_stack", Map.of("entityId", metadata.getEntityId(), "item", item));
                            changed.notifyAll();
                        }
                    }
                }
            } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveEntitiesPacket removal) {
                synchronized (changed) {
                    for (int id : removal.getEntityIds()) removedEntities.add(id);
                    log("entity_removed", removal);
                    changed.notifyAll();
                }
            } else if (packet instanceof ClientboundAddEntityPacket entity) {
                synchronized (changed) {
                    entities.add(entity.getType());
                    if (entity.getType() == EntityType.ITEM) {
                        itemEntities.add(entity.getEntityId());
                        log("item_entity_spawn", entity);
                    }
                    if (entity.getType() == EntityType.CAT) log("cat_spawn", entity);
                    changed.notifyAll();
                }
            } else if (packet instanceof ClientboundSystemChatPacket message) {
                synchronized (changed) {
                    chat.add(message.getContent().toString());
                    log("chat", message);
                    changed.notifyAll();
                }
            } else if (packet instanceof ClientboundContainerClosePacket close) {
                synchronized (changed) {
                    if (openWindow == close.getContainerId()) openWindow = -1;
                    log("server_close", close);
                    changed.notifyAll();
                }
            } else if (packet instanceof ClientboundOpenScreenPacket open) {
                synchronized (changed) {
                    openWindow = open.getContainerId();
                    openMenu = open.getType().toString();
                    log("open_screen", open);
                    changed.notifyAll();
                }
            } else if (packet.getClass().getSimpleName().contains("Cursor")) {
                log(packet.getClass().getSimpleName(), packet);
            }
        } catch (Exception failure) {
            ready.completeExceptionally(failure);
            log("failure", failure.toString());
            session.disconnect("Test client failure", failure);
        }
    }

    @Override
    public void disconnected(DisconnectedEvent event) {
        synchronized (changed) {
            closed = true;
            changed.notifyAll();
        }
        log("disconnected", event.getReason().toString());
        if (event.getCause() != null) event.getCause().printStackTrace();
        ready.completeExceptionally(new IllegalStateException(event.getReason().toString(), event.getCause()));
    }

    private void command(String command) throws Exception {
        log("command", command);
        String[] args = command.split(" ");
        switch (args[0]) {
            case "expect-chunk" -> {
                ChunkPosition position = new ChunkPosition(Integer.parseInt(args[1]), Integer.parseInt(args[2]));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Long.parseLong(args[3]));
                synchronized (changed) {
                    while (!loadedChunks.contains(position)) {
                        long remaining = deadline - System.nanoTime();
                        if (closed || remaining <= 0) throw new AssertionError("Chunk not received: " + command);
                        TimeUnit.NANOSECONDS.timedWait(changed, remaining);
                    }
                }
                log("PASS", command);
            }
            case "view-distance" -> {
                // Same ClientInformation a real client sends when its render distance setting changes.
                session.send(new ServerboundClientInformationPacket("en_us", Integer.parseInt(args[1]),
                    org.geysermc.mcprotocollib.protocol.data.game.setting.ChatVisibility.FULL, true,
                    List.of(org.geysermc.mcprotocollib.protocol.data.game.setting.SkinPart.values()),
                    org.geysermc.mcprotocollib.protocol.data.game.entity.player.HandPreference.RIGHT_HAND, false, true,
                    org.geysermc.mcprotocollib.protocol.data.game.setting.ParticleStatus.ALL));
                log("PASS", command);
            }
            case "chunk-report" -> {
                // chunk-report <label> <render-distance> <settle-seconds> [strict]: wait until the loaded set stops changing, then list missing chunks
                // inside the square around the player's chunk (what a real client would draw) and around the last SET_CHUNK_CACHE_CENTER.
                int distance = Integer.parseInt(args[2]);
                long settle = TimeUnit.SECONDS.toNanos(Long.parseLong(args[3]));
                long stableSince = System.nanoTime();
                int last = -1;
                synchronized (changed) {
                    while (System.nanoTime() - stableSince < settle) {
                        if (loadedChunks.size() != last) { last = loadedChunks.size(); stableSince = System.nanoTime(); }
                        TimeUnit.MILLISECONDS.timedWait(changed, 200);
                    }
                    int px = (int) Math.floor(playerX) >> 4, pz = (int) Math.floor(playerZ) >> 4;
                    List<String> missingPlayer = new ArrayList<>(), missingCenter = new ArrayList<>();
                    for (int dx = -distance; dx <= distance; dx++) for (int dz = -distance; dz <= distance; dz++) {
                        if (!loadedChunks.contains(new ChunkPosition(px + dx, pz + dz))) missingPlayer.add((px + dx) + "," + (pz + dz));
                        if (!loadedChunks.contains(new ChunkPosition(cacheCenterX + dx, cacheCenterZ + dz))) missingCenter.add((cacheCenterX + dx) + "," + (cacheCenterZ + dz));
                    }
                    int total = (2 * distance + 1) * (2 * distance + 1);
                    log("chunk_report", Map.of("label", args[1], "player_chunk", px + "," + pz, "cache_center", cacheCenterX + "," + cacheCenterZ,
                        "cache_radius", cacheRadius, "loaded", loadedChunks.size(), "expected", total,
                        "missing_around_player", missingPlayer.size(), "missing_around_center", missingCenter.size(),
                        "missing_player_sample", missingPlayer.subList(0, Math.min(40, missingPlayer.size()))));
                    if (args.length > 4 && args[4].equals("strict") && !missingPlayer.isEmpty()) throw new AssertionError("Missing " + missingPlayer.size() + "/" + total + " chunks around player at " + args[1]);
                }
                log("PASS", command);
            }
            case "mark-item-spawns" -> {
                synchronized (changed) { spawnMarkers.put(args[1], Set.copyOf(itemEntities)); }
            }
            case "expect-item-spawns", "expect-live-item-spawns" -> {
                int expectedEntities = Integer.parseInt(args[2]), id = Integer.parseInt(args[3]), expectedCount = Integer.parseInt(args[4]);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Long.parseLong(args[5]));
                synchronized (changed) {
                    if (!spawnMarkers.containsKey(args[1])) throw new IllegalArgumentException("No spawn marker: " + args[1]);
                    while (true) {
                        Set<Integer> added = new HashSet<>(itemEntities);
                        added.removeAll(spawnMarkers.get(args[1]));
                        if (args[0].equals("expect-live-item-spawns")) added.removeAll(removedEntities);
                        int count = 0;
                        for (int entityId : added) {
                            ItemStack item = droppedItems.get(entityId);
                            if (item != null) {
                                if (item.getId() != id) throw new AssertionError("Unexpected dropped item: " + item);
                                count += item.getAmount();
                            }
                        }
                        if (added.size() > expectedEntities || count > expectedCount) throw new AssertionError("Unexpected dropped item entities: " + added.size() + "; count=" + count);
                        if (added.size() == expectedEntities && count == expectedCount) break;
                        long remaining = deadline - System.nanoTime();
                        if (closed || remaining <= 0) throw new AssertionError("Unmet: " + command + "; entities=" + added.size() + "; count=" + count);
                        TimeUnit.NANOSECONDS.timedWait(changed, remaining);
                    }
                }
                log("PASS", command);
            }
            case "chat" -> session.send(new ServerboundChatPacket(command.substring(5), System.currentTimeMillis(), 0L, null, 0, new BitSet(20), 0));
            case "expect-chat", "expect-entity" -> {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Long.parseLong(args[2]));
                synchronized (changed) {
                    while (args[0].equals("expect-chat") ? chat.stream().noneMatch(text -> text.contains(args[1])) : !entities.contains(EntityType.valueOf(args[1]))) {
                        long remaining = deadline - System.nanoTime();
                        if (closed || remaining <= 0) throw new AssertionError("Unmet: " + command);
                        TimeUnit.NANOSECONDS.timedWait(changed, remaining);
                    }
                }
                log("PASS", command);
            }
            case "mark-cursor-updates" -> {
                synchronized (changed) { cursorUpdateMarker = cursorUpdates; }
            }
            case "expect-no-cursor-updates" -> {
                synchronized (changed) {
                    if (cursorUpdates != cursorUpdateMarker) throw new AssertionError("Server overwrote the native creative cursor: " + (cursorUpdates - cursorUpdateMarker));
                }
                log("PASS", command);
            }
            case "count-server" -> {
                if (!username.equals("ViaJavaTest") || !Set.of("stone", "glass", "oak_planks", "oak_log", "diamond_sword", "book").contains(args[1])) throw new IllegalArgumentException(command);
                Process fence = new ProcessBuilder("ssh", "pc", "docker logs --timestamps --tail 1 minecraft-edu-test").redirectErrorStream(true).start();
                String lastLine = new String(fence.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).replaceFirst("[\\r\\n]+$", "");
                if (!fence.waitFor(30, TimeUnit.SECONDS) || fence.exitValue() != 0 || lastLine.isEmpty()) throw new IllegalStateException("No copy-server log watermark");
                String timestamp = lastLine.substring(0, lastLine.indexOf(' '));
                Process logs = new ProcessBuilder("ssh", "pc", "docker logs --timestamps --follow --since " + timestamp + " minecraft-edu-test").redirectErrorStream(true).start();
                try {
                    CompletableFuture<Integer> result = CompletableFuture.supplyAsync(() -> {
                        try (var reader = new BufferedReader(new InputStreamReader(logs.getInputStream()))) {
                            boolean marked = false;
                            String line;
                            while ((line = reader.readLine()) != null) {
                                log("server_count_log", line);
                                if (!marked) {
                                    if (line.equals(lastLine)) marked = true;
                                    continue;
                                }
                                var count = java.util.regex.Pattern.compile("ViaJavaTest has (\\d+) items that match the criteria").matcher(line);
                                if (count.find()) return Integer.parseInt(count.group(1));
                                if (line.contains("Could not clear the inventory of ViaJavaTest, no items to remove")) return 0;
                            }
                            throw new IllegalStateException("Server log ended before item count");
                        } catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
                    });
                    copyConsole("clear ViaJavaTest " + args[1] + " " + (args.length > 3 ? Integer.parseInt(args[3]) : 0) + " 0");
                    int actual = result.get(30, TimeUnit.SECONDS);
                    if (actual != Integer.parseInt(args[2])) throw new AssertionError(command + "; actual=" + actual);
                    log("PASS", command);
                } finally { logs.destroy(); }
            }
            case "fixture-reset" -> {
                if (!username.equals("ViaJavaTest")) throw new IllegalArgumentException(command);
                copyConsole("clear ViaJavaTest");
            }
            case "fixture-clear" -> {
                if (!username.equals("ViaJavaTest") || !Set.of("stone", "glass", "oak_planks", "oak_log", "diamond_sword", "book").contains(args[1])) throw new IllegalArgumentException(command);
                copyConsole("clear ViaJavaTest " + args[1]);
            }
            case "fixture-clear-race" -> {
                if (!username.equals("ViaJavaTest") || !Set.of("stone", "glass", "oak_planks", "oak_log", "diamond_sword", "book").contains(args[1])) throw new IllegalArgumentException(command);
                long delay = Long.parseLong(args[2]);
                if (delay < 0) throw new IllegalArgumentException(command);
                consoleCommands.add(new ProcessBuilder("ssh", "pc", "docker exec minecraft-edu-test send-command 'clear ViaJavaTest " + args[1] + "'").inheritIO().start());
                CompletableFuture.runAsync(() -> {}, CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS)).get(30, TimeUnit.SECONDS);
            }
            case "expect-no-request" -> {
                if (responseLog == null) throw new IllegalStateException("Development proxy trace log is required");
                byte[] bytes = Files.readAllBytes(responseLog);
                String received = new String(bytes, responseLogOffset, bytes.length - responseLogOffset, java.nio.charset.StandardCharsets.UTF_8);
                if (received.contains("request=ItemStackRequest[id=" + Integer.parseInt(args[1]) + ",")) throw new AssertionError("Dependent request reached server: " + command);
                log("PASS", command);
            }
            case "walk" -> {
                double startX, startZ, y;
                synchronized (changed) { startX = playerX; startZ = playerZ; y = playerY; }
                double endX = Double.parseDouble(args[1]), endZ = Double.parseDouble(args[2]);
                int duration = Integer.parseInt(args[3]);
                if (duration <= 0) throw new IllegalArgumentException(command);
                playerYaw = (float) Math.toDegrees(Math.atan2(startX - endX, endZ - startZ));
                session.send(new ServerboundPlayerInputPacket(true, false, false, false, false, false, false));
                for (int i = 1; i <= duration; i++) {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    synchronized (changed) {
                        long previous = clientTick;
                        while (clientTick == previous) {
                            long remaining = deadline - System.nanoTime();
                            if (closed || remaining <= 0) throw new AssertionError("Client tick stopped");
                            TimeUnit.NANOSECONDS.timedWait(changed, remaining);
                        }
                        playerX = startX + (endX - startX) * i / duration;
                        playerZ = startZ + (endZ - startZ) * i / duration;
                        session.send(new ServerboundMovePlayerPosRotPacket(true, false, playerX, y, playerZ, playerYaw, playerPitch));
                    }
                }
                session.send(new ServerboundPlayerInputPacket(false, false, false, false, false, false, false));
                log("PASS", command);
            }
            case "fixture-slot", "fixture-hotbar" -> {
                int slot = Integer.parseInt(args[1]), count = Integer.parseInt(args[3]);
                boolean hotbar = args[0].equals("fixture-hotbar");
                if (!username.equals("ViaJavaTest") || slot < 0 || slot >= (hotbar ? 9 : 27) || !Set.of("stone", "glass", "oak_planks", "oak_log", "diamond_sword", "air").contains(args[2]) || count < 0 || count > 64) throw new IllegalArgumentException(command);
                copyConsole("replaceitem entity ViaJavaTest slot." + (hotbar ? "hotbar" : "inventory") + " " + slot + " " + args[2] + " " + count + (args.length > 4 ? " " + Integer.parseInt(args[4]) : ""));
            }
            case "fixture-chest" -> {
                if (!username.equals("ViaJavaTest") || !Set.of("normal", "full").contains(args[1])) throw new IllegalArgumentException(command);
                for (int i = 0; i < 27; i++) {
                    String item = args[1].equals("full") || i < 4 ? "stone" : i == 4 ? "diamond_sword" : i == 5 ? "oak_planks" : "air";
                    int count = args[1].equals("full") ? i == 0 ? 60 : 64 : i < 4 ? 32 : i == 5 ? 64 : 1;
                    copyConsole("replaceitem block 240 76 -80 slot.container " + i + " " + item + " " + count);
                }
            }
            case "expect-menu" -> {
                int slots = Integer.parseInt(args[1]);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Long.parseLong(args[2]));
                synchronized (changed) {
                    while (openWindow == -1 || !containers.containsKey(openWindow) || containers.get(openWindow).length != slots + 36) {
                        long remaining = deadline - System.nanoTime();
                        if (closed || remaining <= 0) throw new AssertionError("Menu not received: " + command + "; window=" + openWindow + "; type=" + openMenu);
                        TimeUnit.NANOSECONDS.timedWait(changed, remaining);
                    }
                    if (!openMenu.equals(slots == 27 ? "GENERIC_9X3" : "GENERIC_9X6")) throw new AssertionError("Incorrect Java menu: " + openMenu);
                }
                log("PASS", command);
            }
            case "fixture-permission" -> {
                if (!username.equals("ViaJavaTest") || !Set.of("visitor", "member").contains(args[1])) throw new IllegalArgumentException(command);
                int before;
                synchronized (changed) { before = abilitiesRevision; }
                permissionChanged = true;
                setFixturePermission(args[1]);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Long.parseLong(args[2]));
                synchronized (changed) {
                    while (abilitiesRevision == before) {
                        long remaining = deadline - System.nanoTime();
                        if (closed || remaining <= 0) throw new AssertionError("Permission update not received: " + command);
                        TimeUnit.NANOSECONDS.timedWait(changed, remaining);
                    }
                }
                log("PASS", command);
            }
            case "dig" -> session.send(new ServerboundPlayerActionPacket(PlayerAction.valueOf(args[1]),
                Vector3i.from(Integer.parseInt(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4])),
                Direction.valueOf(args[5]), ++blockSequence));
            case "use-on" -> session.send(new ServerboundUseItemOnPacket(Vector3i.from(Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3])),
                Direction.valueOf(args[4]), Hand.MAIN_HAND, Float.parseFloat(args[5]), Float.parseFloat(args[6]), Float.parseFloat(args[7]), false, false, ++blockSequence));
            case "expect-block" -> {
                Vector3i position = Vector3i.from(Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]));
                int state = Integer.parseInt(args[4]);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Long.parseLong(args[5]));
                synchronized (changed) {
                    while (!Objects.equals(blocks.get(position), state)) {
                        long remaining = deadline - System.nanoTime();
                        if (closed || remaining <= 0) throw new AssertionError("Unmet: " + command + "; actual=" + blocks.get(position));
                        TimeUnit.NANOSECONDS.timedWait(changed, remaining);
                    }
                }
                log("PASS", command);
            }
            case "fixture" -> {
                if (!username.equals("ViaJavaTest")) throw new IllegalStateException("Fixture teleport is restricted to ViaJavaTest");
                double x = Double.parseDouble(args[1]), y = Double.parseDouble(args[2]), z = Double.parseDouble(args[3]);
                long timeout = Long.parseLong(args[4]);
                spawned.get(timeout, TimeUnit.SECONDS);
                Process console = new ProcessBuilder("ssh", "pc", "docker exec minecraft-edu-test send-command 'tp ViaJavaTest " + x + " " + y + " " + z + "'")
                    .inheritIO().start();
                if (!console.waitFor(timeout, TimeUnit.SECONDS) || console.exitValue() != 0) throw new IllegalStateException("Copy-server fixture teleport failed");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
                synchronized (changed) {
                    while (Math.abs(playerX - x) > Math.ulp((float) x) || Math.abs(playerY - y) > Math.ulp((float) y)
                        || Math.abs(playerZ - z) > Math.ulp((float) z)) {
                        long remaining = deadline - System.nanoTime();
                        if (closed || remaining <= 0) throw new AssertionError("Fixture teleport not confirmed: " + playerX + "," + playerY + "," + playerZ);
                        TimeUnit.NANOSECONDS.timedWait(changed, remaining);
                    }
                }
                log("PASS", command);
            }
            case "expect-cursor" -> {
                int id = Integer.parseInt(args[1]), count = Integer.parseInt(args[2]);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Long.parseLong(args[3]));
                synchronized (changed) {
                    while (!(count == 0 ? cursor == null || cursor.getAmount() == 0
                        : cursor != null && cursor.getId() == id && cursor.getAmount() == count)) {
                        long remaining = deadline - System.nanoTime();
                        if (closed || remaining <= 0) throw new AssertionError("Unmet: " + command + "; actual=" + cursor);
                        TimeUnit.NANOSECONDS.timedWait(changed, remaining);
                    }
                }
                log("PASS", command);
            }
            case "save-inventory" -> {
                synchronized (changed) { Files.write(Path.of(args[1]), inventoryBytes()); }
                log("saved_inventory", args[1]);
            }
            case "expect-inventory" -> {
                byte[] expected = Files.readAllBytes(Path.of(args[1]));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Long.parseLong(args[2]));
                synchronized (changed) {
                    while (!containers.containsKey(0) || !Arrays.equals(expected, inventoryBytes())) {
                        long remaining = deadline - System.nanoTime();
                        if (closed || remaining <= 0) throw new AssertionError("Inventory slots or components differ from " + args[1]);
                        TimeUnit.NANOSECONDS.timedWait(changed, remaining);
                    }
                }
                log("PASS", command);
            }
            case "expect-spawn" -> {
                spawned.get(Long.parseLong(args[1]), TimeUnit.SECONDS);
                log("PASS", command);
            }
            case "expect-rejected" -> {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Long.parseLong(args[2]));
                try (var watcher = responseLog.getFileSystem().newWatchService()) {
                    responseLog.getParent().register(watcher, StandardWatchEventKinds.ENTRY_MODIFY);
                    while (true) {
                        byte[] bytes = Files.readAllBytes(responseLog);
                        String received = new String(bytes, responseLogOffset, bytes.length - responseLogOffset, java.nio.charset.StandardCharsets.UTF_8);
                        var match = java.util.regex.Pattern.compile("response=ItemStackResponse\\[result=([0-9]+), requestId=" + Integer.parseInt(args[1]) + ",").matcher(received);
                        if (match.find()) {
                            if (Integer.parseInt(match.group(1)) == 0) throw new AssertionError("Race did not cause a real server rejection");
                            log("PASS", command + "; result=" + match.group(1));
                            break;
                        }
                        long remaining = deadline - System.nanoTime();
                        if (remaining <= 0) throw new AssertionError("No real server rejection observed");
                        var key = watcher.poll(remaining, TimeUnit.NANOSECONDS);
                        if (key != null) { key.pollEvents(); key.reset(); }
                    }
                }
            }
            case "expect-ack" -> {
                if (responseLog == null) throw new IllegalStateException("The fourth argument must name the development proxy trace log");
                String expected = "response=ItemStackResponse[result=" + Integer.parseInt(args[2]) + ", requestId=" + Integer.parseInt(args[1]) + ",";
                String timing = " requestId=" + Integer.parseInt(args[1]) + " result=" + Integer.parseInt(args[2]);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Long.parseLong(args[3]));
                try (var watcher = responseLog.getFileSystem().newWatchService()) {
                    responseLog.getParent().register(watcher, StandardWatchEventKinds.ENTRY_MODIFY);
                    while (true) {
                        byte[] bytes = Files.readAllBytes(responseLog);
                        String received = new String(bytes, responseLogOffset, bytes.length - responseLogOffset, java.nio.charset.StandardCharsets.UTF_8);
                        if (received.contains(expected) || received.contains(timing)) break;
                        long remaining = deadline - System.nanoTime();
                        if (remaining <= 0) throw new AssertionError("Server acknowledgement missing: " + expected);
                        var key = watcher.poll(remaining, TimeUnit.NANOSECONDS);
                        if (key != null) { key.pollEvents(); key.reset(); }
                    }
                }
                log("PASS", command);
            }
            case "snapshot" -> {
                synchronized (changed) { log("snapshot", Map.of("containers", containers, "cursor", String.valueOf(cursor))); }
            }
            case "native-keep-book" -> {
                int source = -1;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                synchronized (changed) {
                    while (source < 0) {
                        ItemStack[] items = containers.get(0);
                        if (items != null) for (int i = 9; i < 45; i++) {
                            ItemStack item = items[i];
                            if (item != null && item.getId() == 1146 && item.getDataComponentsPatch() != null
                                && item.getDataComponentsPatch().get(DataComponentTypes.CUSTOM_NAME) != null) source = i;
                        }
                        if (source >= 0) break;
                        long remaining = deadline - System.nanoTime();
                        if (closed || remaining <= 0) throw new AssertionError("No real server quiz book");
                        TimeUnit.NANOSECONDS.timedWait(changed, remaining);
                    }
                }
                if (source != 44) {
                    command("native-swap " + source + " 44");
                    command("expect-ack -1 0 10");
                    command("expect 0 44 1146 1 10");
                }
            }
            case "native-named-swaps" -> {
                int source = -1;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                synchronized (changed) {
                    while (source < 0) {
                        ItemStack[] items = containers.get(0);
                        if (items != null) for (int i = 9; i < 45; i++) {
                            ItemStack item = items[i];
                            if (item != null && item.getDataComponentsPatch() != null && item.getDataComponentsPatch().get(DataComponentTypes.CUSTOM_NAME) != null) source = i;
                        }
                        if (source >= 0) break;
                        long remaining = deadline - System.nanoTime();
                        if (closed || remaining <= 0) throw new AssertionError("No real server-provided named item");
                        TimeUnit.NANOSECONDS.timedWait(changed, remaining);
                    }
                    log("named_fixture", Map.of("slot",source,"item",containers.get(0)[source]));
                }
                command("save-inventory /tmp/vb-native-named-before.bin");
                command("native-swap " + source + " 44");
                command("expect-ack -1 0 10");
                command("expect 0 " + source + " 231 32 10");
                command("native-swap " + source + " 44");
                command("expect-ack -3 0 10");
                command("expect-inventory /tmp/vb-native-named-before.bin 10");
                log("PASS", command);
            }
            case "remember-swap" -> {
                synchronized (changed) {
                    rememberedSwapLeft = containers.get(0)[Integer.parseInt(args[1])];
                    rememberedSwapRight = containers.get(0)[Integer.parseInt(args[2])];
                }
            }
            case "native-swap-saved" -> {
                synchronized (session) {
                    session.send(new ServerboundSetCreativeModeSlotPacket(Short.parseShort(args[1]), rememberedSwapRight));
                    session.send(new ServerboundSetCreativeModeSlotPacket(Short.parseShort(args[2]), rememberedSwapLeft));
                    if (args.length > 3) {
                        session.send(new ServerboundSetCreativeModeSlotPacket(Short.parseShort(args[3]),
                            new ItemStack(Integer.parseInt(args[4]), Integer.parseInt(args[5]))));
                    }
                }
            }
            case "native-images" -> {
                if ((args.length - 1) % 3 != 0) throw new IllegalArgumentException(command);
                synchronized (session) {
                    for (int i = 1; i < args.length; i += 3) {
                        int count = Integer.parseInt(args[i + 2]);
                        session.send(new ServerboundSetCreativeModeSlotPacket(Short.parseShort(args[i]),
                            count == 0 ? null : new ItemStack(Integer.parseInt(args[i + 1]), count)));
                    }
                }
            }
            case "native-swap" -> {
                int left = Integer.parseInt(args[1]), right = Integer.parseInt(args[2]);
                ItemStack a, b;
                synchronized (changed) { a = containers.get(0)[left]; b = containers.get(0)[right]; }
                synchronized (session) {
                    session.send(new ServerboundSetCreativeModeSlotPacket((short) left, b));
                    session.send(new ServerboundSetCreativeModeSlotPacket((short) right, a));
                }
            }
            case "remember-item" -> {
                synchronized (changed) { rememberedItem = containers.get(0)[Integer.parseInt(args[1])]; }
                if (rememberedItem == null) throw new AssertionError("No item to remember");
            }
            case "expect-cursor-update" -> {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Long.parseLong(args[1]));
                synchronized (changed) {
                    while (cursorUpdates <= cursorUpdateMarker) {
                        long remaining = deadline - System.nanoTime();
                        if (closed || remaining <= 0) throw new AssertionError("Native cursor was not resynchronized");
                        TimeUnit.NANOSECONDS.timedWait(changed, remaining);
                    }
                }
                log("PASS", command);
            }
            case "native-stale-pickup" -> {
                synchronized (changed) { cursor = rememberedItem; }
                session.send(new ServerboundSetCreativeModeSlotPacket(Short.parseShort(args[1]), null));
            }
            case "native-pickup" -> {
                int source = Integer.parseInt(args[1]);
                synchronized (changed) { cursor = containers.get(0)[source]; containers.get(0)[source] = null; }
                session.send(new ServerboundSetCreativeModeSlotPacket((short) source, null));
            }
            case "native-place" -> {
                ItemStack carried;
                synchronized (changed) { carried = cursor; cursor = null; }
                session.send(new ServerboundSetCreativeModeSlotPacket(Short.parseShort(args[1]), carried));
            }
            case "native-drop" -> {
                int slot = Integer.parseInt(args[1]), id = Integer.parseInt(args[2]);
                int count = Integer.parseInt(args[3]), remaining = Integer.parseInt(args[4]);
                // The official screen emits both slot images in one game-thread action.
                synchronized (session) {
                    session.send(new ServerboundSetCreativeModeSlotPacket((short) -1, new ItemStack(id, count)));
                    session.send(new ServerboundSetCreativeModeSlotPacket((short) slot, remaining == 0 ? null : new ItemStack(id, remaining)));
                }
            }
            case "creative" -> session.send(new ServerboundSetCreativeModeSlotPacket(Short.parseShort(args[1]),
                Integer.parseInt(args[3]) == 0 ? null : new ItemStack(Integer.parseInt(args[2]), Integer.parseInt(args[3]))));
            case "creative-damage" -> {
                DataComponents components = new DataComponents(new HashMap<>());
                components.put(DataComponentTypes.DAMAGE, Integer.parseInt(args[4]));
                session.send(new ServerboundSetCreativeModeSlotPacket(Short.parseShort(args[1]),
                    new ItemStack(Integer.parseInt(args[2]), Integer.parseInt(args[3]), components)));
            }
            case "creative-book" -> {
                Integer enchantment = enchantments.get(args[2]);
                if (enchantment == null) throw new IllegalArgumentException("Enchantment absent from server registry: " + args[2]);
                DataComponents components = new DataComponents(new HashMap<>());
                components.put(DataComponentTypes.STORED_ENCHANTMENTS, new ItemEnchantments(Map.of(enchantment, Integer.parseInt(args[3]))));
                session.send(new ServerboundSetCreativeModeSlotPacket(Short.parseShort(args[1]),
                    new ItemStack(Integer.parseInt(args[4]), 1, components)));
            }
            case "expect-book" -> {
                int slot = Integer.parseInt(args[1]), level = Integer.parseInt(args[3]);
                Integer enchantment = enchantments.get(args[2]);
                if (enchantment == null) throw new IllegalArgumentException("Unknown enchantment: " + args[2]);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Long.parseLong(args[4]));
                synchronized (changed) {
                    while (true) {
                        ItemStack[] items = containers.get(0);
                        ItemStack item = items == null ? null : items[slot];
                        ItemEnchantments effects = item == null || item.getDataComponentsPatch() == null ? null
                            : item.getDataComponentsPatch().get(DataComponentTypes.STORED_ENCHANTMENTS);
                        if (effects != null && effects.getEnchantments().equals(Map.of(enchantment, level))) break;
                        long remaining = deadline - System.nanoTime();
                        if (closed || remaining <= 0) throw new AssertionError("Unmet: " + command + "; actual=" + effects);
                        TimeUnit.NANOSECONDS.timedWait(changed, remaining);
                    }
                }
                log("PASS", command);
            }
            case "burst-swaps" -> {
                int count = Integer.parseInt(args[1]);
                for (int i = 0; i < count; i++) command("click open 0 2 1");
                // Player-to-player number swap is independent of the chest and its cursor.
                command("click open 28 2 2");
            }
            case "fixture-creative" -> {
                if (!username.equals("ViaJavaTest")) throw new IllegalArgumentException(command);
                copyConsole("gamemode creative ViaJavaTest");
            }
            case "fixture-close-race" -> {
                if (!username.equals("ViaJavaTest") || !Set.of("distance", "server").contains(args[1])) throw new IllegalArgumentException(command);
                String action = args[1].equals("distance") ? "tp ViaJavaTest 244.5 76 -84.5" : "clone 240 76 -80 240 76 -80 240 76 -80 replace force";
                consoleCommands.add(new ProcessBuilder("ssh", "pc", "docker exec minecraft-edu-test send-command '" + action + "'").inheritIO().start());
            }
            case "expect-closed" -> {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Long.parseLong(args[1]));
                synchronized (changed) {
                    while (openWindow != -1) {
                        long remaining = deadline - System.nanoTime();
                        if (closed || remaining <= 0) throw new AssertionError("Menu was not forcibly closed");
                        TimeUnit.NANOSECONDS.timedWait(changed, remaining);
                    }
                }
                log("PASS", command);
            }
            case "expect-invalidated" -> {
                byte[] bytes = Files.readAllBytes(responseLog);
                String text = new String(bytes, responseLogOffset, bytes.length - responseLogOffset, java.nio.charset.StandardCharsets.UTF_8);
                var invalidated = java.util.regex.Pattern.compile("INVENTORY_INVALIDATED container=[0-9]+ requests=\\[-[0-9]+").matcher(text);
                if (!invalidated.find()) throw new AssertionError("No queued menu operation invalidated");
                String afterClose = text.substring(invalidated.start());
                if (afterClose.lines().anyMatch(line -> line.contains("INVENTORY_REQUEST") && line.contains("LevelEntityContainer"))) throw new AssertionError("Old chest operation was sent after invalidation");
                log("PASS", command);
            }
            case "save-total", "expect-total" -> {
                int chestSize = Integer.parseInt(args[2]);
                synchronized (changed) {
                    byte[] actual = totalBytes(chestSize);
                    if (args[0].equals("save-total")) Files.write(Path.of(args[1]), actual);
                    else if (!Arrays.equals(Files.readAllBytes(Path.of(args[1])), actual)) throw new AssertionError("Items, components or total changed across forced close");
                }
                log("PASS", command);
            }
            case "click" -> {
                int window = window(args[1]), slot = Integer.parseInt(args[2]);
                ContainerActionType type = ContainerActionType.values()[Integer.parseInt(args[3])];
                session.send(new ServerboundContainerClickPacket(window, revision, slot, type,
                    type == ContainerActionType.DROP_ITEM ? DropItemAction.from(Integer.parseInt(args[4]))
                        : type.actionFrom(Integer.parseInt(args[4]), slot), null, Map.of()));
            }
            case "select" -> session.send(new ServerboundSetCarriedItemPacket(Integer.parseInt(args[1])));
            case "drop-selected" -> session.send(new ServerboundPlayerActionPacket(
                args[1].equals("all") ? PlayerAction.DROP_ITEM_STACK : PlayerAction.DROP_ITEM, Vector3i.ZERO, Direction.DOWN, 0));
            case "close" -> {
                session.send(new ServerboundContainerClosePacket(window(args[1])));
                synchronized (changed) { openWindow = -1; }
            }
            case "expect" -> {
                int window = window(args[1]), slot = Integer.parseInt(args[2]), id = Integer.parseInt(args[3]), count = Integer.parseInt(args[4]);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Long.parseLong(args[5]));
                synchronized (changed) {
                    while (true) {
                        ItemStack[] items = containers.get(window);
                        ItemStack item = items != null && slot >= 0 && slot < items.length ? items[slot] : null;
                        if (items != null && (count == 0 ? item == null || item.getAmount() == 0 : item != null && item.getId() == id && item.getAmount() == count)) break;
                        long remaining = deadline - System.nanoTime();
                        if (closed || remaining <= 0) throw new AssertionError("Unmet: " + command + "; actual=" + item);
                        TimeUnit.NANOSECONDS.timedWait(changed, remaining);
                    }
                }
                log("PASS", command);
            }
            default -> throw new UnsupportedOperationException(command);
        }
    }

    private byte[] totalBytes(int chestSize) {
        List<String> encoded = new ArrayList<>();
        List<ItemStack> all = new ArrayList<>(Arrays.asList(containers.get(0)));
        all.addAll(Arrays.asList(containers.get(openWindow)).subList(0, chestSize));
        all.add(cursor);
        for (ItemStack item : all) {
            if (item == null || item.getAmount() == 0) continue;
            var buffer = Unpooled.buffer();
            try {
                MinecraftTypes.writeOptionalItemStack(buffer, item, true);
                byte[] bytes = new byte[buffer.readableBytes()];buffer.readBytes(bytes);
                encoded.add(HexFormat.of().formatHex(bytes));
            } finally { buffer.release(); }
        }
        Collections.sort(encoded);
        return String.join("\n", encoded).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private byte[] inventoryBytes() {
        ItemStack[] items = containers.get(0);
        if (items == null) throw new IllegalStateException("No received player inventory");
        var buffer = Unpooled.buffer();
        try {
            buffer.writeInt(items.length);
            for (ItemStack item : items) MinecraftTypes.writeOptionalItemStack(buffer, item, true);
            MinecraftTypes.writeOptionalItemStack(buffer, cursor, true);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally { buffer.release(); }
    }

    private int window(String value) {
        if (!value.equals("open")) return Integer.parseInt(value);
        synchronized (changed) {
            if (openWindow == -1) throw new IllegalStateException("No received open menu");
            return openWindow;
        }
    }

    private static void setFixturePermission(String permission) throws Exception {
        copyConsole("permission set ViaJavaTest " + permission);
    }

    private static void copyConsole(String command) throws Exception {
        Process console = new ProcessBuilder("ssh", "pc", "docker exec minecraft-edu-test send-command '" + command + "'").inheritIO().start();
        if (!console.waitFor(30, TimeUnit.SECONDS) || console.exitValue() != 0) throw new IllegalStateException("Copy-server permission command failed");
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) throw new IllegalArgumentException("Usage: JavaPlayClient <port> <username> <commands-file-or-> [development-proxy-trace-log]");
        int port = Integer.parseInt(args[0]);
        if (port == 25568) throw new IllegalArgumentException("Production proxy is forbidden");
        JavaPlayClient client = new JavaPlayClient();
        client.username = args[1];
        if (args.length == 4) {
            client.responseLog = Path.of(args[3]).toAbsolutePath();
            client.responseLogOffset = Math.toIntExact(Files.size(client.responseLog));
        }
        var session = ClientNetworkSessionFactory.factory().setAddress("127.0.0.1", port).setProtocol(new MinecraftProtocol(args[1])).create();
        client.session = session;
        session.addListener(client);
        session.connect(false);
        var ticks = Executors.newSingleThreadScheduledExecutor();
        try {
            client.ready.get(90, TimeUnit.SECONDS);
            // Match the ordinary Java client's 20 game ticks per second.
            ticks.scheduleAtFixedRate(() -> {
                synchronized (session) { session.send(ServerboundClientTickEndPacket.INSTANCE); }
                synchronized (client.changed) { client.clientTick++; client.changed.notifyAll(); }
            }, 0, 50, TimeUnit.MILLISECONDS);
            client.log("ready", "MCProtocolLib 26.3");
            try (var input = args[2].equals("-") ? new BufferedReader(new InputStreamReader(System.in)) : Files.newBufferedReader(Path.of(args[2]))) {
                String command;
                while ((command = input.readLine()) != null) {
                    if (!command.isBlank() && !command.startsWith("#")) client.command(command);
                }
            }
        } finally {
            try {
                for (Process console : client.consoleCommands) {
                    if (!console.waitFor(30, TimeUnit.SECONDS) || console.exitValue() != 0) throw new IllegalStateException("Fixture console command failed");
                }
                if (client.permissionChanged) setFixturePermission("member");
            } finally {
                ticks.shutdownNow();
                session.disconnect("Test finished");
            }
        }
    }
}
