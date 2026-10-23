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
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.ItemEnchantments;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundRegistryDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundResourcePackPushPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundResourcePackPacket;
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
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
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
    private boolean closed;
    private String username;
    private double playerX, playerY, playerZ;
    private float playerYaw, playerPitch;
    private int blockSequence;
    private int abilitiesRevision;
    private int openWindow = -1;
    private String openMenu;
    private boolean permissionChanged;
    private final Map<Vector3i, Integer> blocks = new HashMap<>();

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
                    log("cursor", String.valueOf(cursor));
                    changed.notifyAll();
                }
            } else if (packet instanceof ClientboundSetEntityDataPacket metadata) {
                for (var entry : metadata.getMetadata()) {
                    if (entry.getValue() instanceof ItemStack item) log("item_entity_stack", Map.of("entityId", metadata.getEntityId(), "item", item));
                }
            } else if (packet instanceof ClientboundAddEntityPacket entity && entity.getType() == EntityType.ITEM) {
                log("item_entity_spawn", entity);
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
            case "fixture-slot", "fixture-hotbar" -> {
                int slot = Integer.parseInt(args[1]), count = Integer.parseInt(args[3]);
                boolean hotbar = args[0].equals("fixture-hotbar");
                if (!username.equals("ViaJavaTest") || slot < 0 || slot >= (hotbar ? 9 : 27) || !Set.of("stone", "glass", "air").contains(args[2]) || count < 0 || count > 64) throw new IllegalArgumentException(command);
                copyConsole("replaceitem entity ViaJavaTest slot." + (hotbar ? "hotbar" : "inventory") + " " + slot + " " + args[2] + " " + count);
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
            case "expect-ack" -> {
                if (responseLog == null) throw new IllegalStateException("The fourth argument must name the development proxy trace log");
                String expected = "response=ItemStackResponse[result=" + Integer.parseInt(args[2]) + ", requestId=" + Integer.parseInt(args[1]) + ",";
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Long.parseLong(args[3]));
                try (var watcher = responseLog.getFileSystem().newWatchService()) {
                    responseLog.getParent().register(watcher, StandardWatchEventKinds.ENTRY_MODIFY);
                    while (true) {
                        byte[] bytes = Files.readAllBytes(responseLog);
                        String received = new String(bytes, responseLogOffset, bytes.length - responseLogOffset, java.nio.charset.StandardCharsets.UTF_8);
                        if (received.contains(expected)) break;
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
            ticks.scheduleAtFixedRate(() -> session.send(ServerboundClientTickEndPacket.INSTANCE), 0, 50, TimeUnit.MILLISECONDS);
            client.log("ready", "MCProtocolLib 26.3");
            try (var input = args[2].equals("-") ? new BufferedReader(new InputStreamReader(System.in)) : Files.newBufferedReader(Path.of(args[2]))) {
                String command;
                while ((command = input.readLine()) != null) {
                    if (!command.isBlank() && !command.startsWith("#")) client.command(command);
                }
            }
        } finally {
            if (client.permissionChanged) setFixturePermission("member");
            ticks.shutdownNow();
            session.disconnect("Test finished");
        }
    }
}
