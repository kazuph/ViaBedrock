/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package net.raphimc.viabedrock.protocol.storage;

import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.exception.InformativeException;
import com.viaversion.viaversion.protocols.v26_2to26_3.packet.ServerboundPackets26_3;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.enums.Direction;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Set;

/** Keeps later inventory input behind an interaction waiting for its selected stack. */
public final class SelectedItemActions extends StoredObject {

    private static final Set<ServerboundPackets26_3> ORDERED = EnumSet.of(
        ServerboundPackets26_3.USE_ITEM, ServerboundPackets26_3.USE_ITEM_ON, ServerboundPackets26_3.ATTACK,
        ServerboundPackets26_3.INTERACT, ServerboundPackets26_3.PLAYER_ACTION, ServerboundPackets26_3.SET_CARRIED_ITEM,
        ServerboundPackets26_3.SET_CREATIVE_MODE_SLOT, ServerboundPackets26_3.CONTAINER_CLICK, ServerboundPackets26_3.CONTAINER_CLOSE);

    private final Deque<Deferred> pending = new ArrayDeque<>();
    private boolean replaying;

    public SelectedItemActions(final UserConnection user) {
        super(user);
    }

    public boolean defer(final PacketWrapper wrapper) throws InformativeException {
        if (this.replaying || wrapper.getId() < 0 || wrapper.getId() >= ServerboundPackets26_3.values().length) {
            return false;
        }
        final ServerboundPackets26_3 type = ServerboundPackets26_3.values()[wrapper.getId()];
        if (!ORDERED.contains(type)) {
            return false;
        }
        if (this.pending.isEmpty() && !this.needsSelectedItem(type) && type != ServerboundPackets26_3.SET_CARRIED_ITEM) {
            return false;
        }
        if (this.pending.isEmpty()) {
            final var requests = this.user().get(ItemStackRequestTracker.class);
            final var inventory = this.user().get(InventoryTracker.class).getInventoryContainer();
            if (type == ServerboundPackets26_3.SET_CARRIED_ITEM ? !requests.isPending()
                : !requests.isPending(requests.slot(inventory, inventory.getSelectedHotbarSlot()))) {
                return false;
            }
        }
        final ByteBuf bytes = Unpooled.buffer();
        final Deferred deferred;
        try {
            wrapper.writeToBuffer(bytes);
            Types.VAR_INT.read(bytes);
            final byte[] payload = new byte[bytes.readableBytes()];
            bytes.readBytes(payload);
            deferred = new Deferred(type, payload);
        } finally {
            bytes.release();
        }
        if (this.pending.isEmpty() && !this.waitingForStack(deferred)) {
            // writeToBuffer consumed the original input; replay the complete packet once.
            this.replay(deferred);
            return true;
        }
        this.pending.addLast(deferred);
        this.drain();
        return true;
    }

    public void drain() {
        while (!this.pending.isEmpty()) {
            final Deferred next = this.pending.getFirst();
            if (this.waitingForStack(next)) {
                return;
            }
            this.pending.removeFirst();
            final var inventory = this.user().get(InventoryTracker.class).getInventoryContainer();
            final var requests = this.user().get(ItemStackRequestTracker.class);
            if (next.expected != null && (next.selectedSlot != inventory.getSelectedHotbarSlot()
                || !requests.creative().sameItem(next.expected, this.user().get(ItemRewriter.class).javaItem(inventory.getSelectedHotbarItem().copy())))) {
                requests.unsupported("Selected item change was rejected or superseded before " + next.type);
                this.restorePrediction(next);
                continue;
            }
            this.replay(next);
        }
    }

    private boolean needsSelectedItem(final ServerboundPackets26_3 type) {
        return switch (type) {
            case USE_ITEM, USE_ITEM_ON, ATTACK, INTERACT, PLAYER_ACTION -> true;
            default -> false;
        };
    }

    private boolean waitingForStack(final Deferred packet) {
        if (!this.needsSelectedItem(packet.type) && packet.type != ServerboundPackets26_3.SET_CARRIED_ITEM) {
            return false;
        }
        final var inventory = this.user().get(InventoryTracker.class).getInventoryContainer();
        int selected = inventory.getSelectedHotbarSlot();
        if (packet.type == ServerboundPackets26_3.SET_CARRIED_ITEM) {
            final ByteBuf input = Unpooled.wrappedBuffer(packet.payload);
            try {
                selected = input.readShort();
            } finally {
                input.release();
            }
            if (selected < 0 || selected >= 9) {
                return false;
            }
        }
        final var requests = this.user().get(ItemStackRequestTracker.class);
        final var slot = requests.slot(inventory, selected);
        if (requests.hasQueuedInventoryActions()) {
            return true;
        }
        if (!requests.isPending(slot)) {
            return false;
        }
        if (packet.expected == null && this.needsSelectedItem(packet.type)) {
            packet.selectedSlot = selected;
            packet.expected = this.user().get(ItemRewriter.class).javaItem(requests.projected(slot));
        }
        return true;
    }

    private void replay(final Deferred packet) {
        final ByteBuf input = Unpooled.wrappedBuffer(packet.payload);
        this.replaying = true;
        try {
            PacketWrapper.create(packet.type, input, this.user()).sendToServer(BedrockProtocol.class, false);
        } catch (final InformativeException error) {
            throw new IllegalStateException("Failed to resume selected-item action " + packet.type, error);
        } finally {
            this.replaying = false;
            input.release();
        }
    }

    private void restorePrediction(final Deferred packet) {
        if (packet.type != ServerboundPackets26_3.USE_ITEM_ON) {
            return;
        }
        final ByteBuf input = Unpooled.wrappedBuffer(packet.payload);
        try {
            Types.VAR_INT.read(input); // hand
            final BlockPosition position = Types.BLOCK_POSITION1_14.read(input);
            final Direction direction = Direction.getFromVerticalId(input.readUnsignedByte());
            input.skipBytes(3 * Float.BYTES + 2); // hit position, inside block, world border
            PacketFactory.sendJavaBlockChangedAck(this.user(), Types.VAR_INT.read(input));
            final ChunkTracker chunks = this.user().get(ChunkTracker.class);
            PacketFactory.sendJavaBlockUpdate(this.user(), position, chunks.getJavaBlockState(position));
            if (direction != null) {
                final BlockPosition adjacent = position.getRelative(direction.blockFace());
                PacketFactory.sendJavaBlockUpdate(this.user(), adjacent, chunks.getJavaBlockState(adjacent));
            }
        } finally {
            input.release();
        }
    }

    private static final class Deferred {
        private final ServerboundPackets26_3 type;
        private final byte[] payload;
        private Item expected;
        private int selectedSlot = -1;

        private Deferred(final ServerboundPackets26_3 type, final byte[] payload) {
            this.type = type;
            this.payload = payload;
        }
    }

}
