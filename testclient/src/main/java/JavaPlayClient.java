import com.google.gson.Gson;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.*;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.game.ResourcePackStatus;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundResourcePackPushPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundResourcePackPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundPlayerLoadedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

/** A real Java 26.3 network client; it has no rendering or simulated server. */
public final class JavaPlayClient extends SessionAdapter {
    private final Gson json = new Gson();
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final Map<Integer, ItemStack[]> containers = new HashMap<>();
    private final Object changed = new Object();
    private Session session;
    private ItemStack cursor;
    private int revision;
    private boolean closed;

    private synchronized void log(String event, Object data) {
        System.out.println(json.toJson(Map.of("time", System.nanoTime(), "event", event, "data", data)));
        System.out.flush();
    }

    @Override
    public void packetReceived(Session session, Packet packet) {
        try {
            if (packet instanceof ClientboundResourcePackPushPacket pack) {
                session.send(new ServerboundResourcePackPacket(pack.getId(), ResourcePackStatus.ACCEPTED));
                Path downloaded = Files.createTempFile("viabedrock-java26-pack-", ".zip");
                try {
                    var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(pack.getUrl()))
                        .timeout(Duration.ofSeconds(60)).build(), HttpResponse.BodyHandlers.ofFile(downloaded));
                    if (response.statusCode() != 200) throw new IllegalStateException("Pack HTTP " + response.statusCode());
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
                session.send(new ServerboundAcceptTeleportationPacket(position.getId(), position.getPosition().getX(), position.getPosition().getY(),
                    position.getPosition().getZ(), position.getYRot(), position.getXRot()));
                session.send(new ServerboundMovePlayerPosRotPacket(false, false, position.getPosition().getX(), position.getPosition().getY(),
                    position.getPosition().getZ(), position.getYRot(), position.getXRot()));
                session.send(ServerboundPlayerLoadedPacket.INSTANCE);
                ready.complete(null);
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
            } else if (packet.getClass().getSimpleName().contains("Cursor") || packet instanceof ClientboundOpenScreenPacket) {
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
            case "snapshot" -> {
                synchronized (changed) { log("snapshot", Map.of("containers", containers, "cursor", String.valueOf(cursor))); }
            }
            case "creative" -> session.send(new ServerboundSetCreativeModeSlotPacket(Short.parseShort(args[1]),
                Integer.parseInt(args[3]) == 0 ? null : new ItemStack(Integer.parseInt(args[2]), Integer.parseInt(args[3]))));
            case "click" -> {
                int window = Integer.parseInt(args[1]), slot = Integer.parseInt(args[2]);
                ContainerActionType type = ContainerActionType.values()[Integer.parseInt(args[3])];
                session.send(new ServerboundContainerClickPacket(window, revision, slot, type,
                    type.actionFrom(Integer.parseInt(args[4]), slot), null, Map.of()));
            }
            case "close" -> session.send(new ServerboundContainerClosePacket(Integer.parseInt(args[1])));
            case "expect" -> {
                int window = Integer.parseInt(args[1]), slot = Integer.parseInt(args[2]), id = Integer.parseInt(args[3]), count = Integer.parseInt(args[4]);
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

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Usage: JavaPlayClient <port> <username> <commands-file-or->");
        int port = Integer.parseInt(args[0]);
        if (port == 25568) throw new IllegalArgumentException("Production proxy is forbidden");
        JavaPlayClient client = new JavaPlayClient();
        var session = ClientNetworkSessionFactory.factory().setAddress("127.0.0.1", port).setProtocol(new MinecraftProtocol(args[1])).create();
        client.session = session;
        session.addListener(client);
        session.connect(false);
        try {
            client.ready.get(90, TimeUnit.SECONDS);
            client.log("ready", "MCProtocolLib 26.3");
            try (var input = args[2].equals("-") ? new BufferedReader(new InputStreamReader(System.in)) : Files.newBufferedReader(Path.of(args[2]))) {
                String command;
                while ((command = input.readLine()) != null) {
                    if (!command.isBlank() && !command.startsWith("#")) client.command(command);
                }
            }
        } finally { session.disconnect("Test finished"); }
    }
}
