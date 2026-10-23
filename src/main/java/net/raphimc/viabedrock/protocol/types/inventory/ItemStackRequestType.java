/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package net.raphimc.viabedrock.protocol.types.inventory;

import com.viaversion.viaversion.api.type.Type;
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.protocol.model.ItemStackRequest;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

public final class ItemStackRequestType extends Type<ItemStackRequest> {

    public ItemStackRequestType() {
        super(ItemStackRequest.class);
    }

    @Override
    public ItemStackRequest read(final ByteBuf buffer) {
        throw new UnsupportedOperationException("Item stack requests are serverbound only");
    }

    @Override
    public void write(final ByteBuf buffer, final ItemStackRequest request) {
        BedrockTypes.VAR_INT.write(buffer, request.id());
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, request.actions().size());
        for (ItemStackRequest.Action action : request.actions()) {
            BedrockTypes.UNSIGNED_VAR_INT.write(buffer, action.type().getValue());
            // Protocol 2193 uses both an action type and a union discriminator.
            buffer.writeByte(action.type().getValue());
            if (action instanceof ItemStackRequest.Transfer transfer) {
                writeByte(buffer, transfer.count());
                writeSlot(buffer, transfer.source());
                writeSlot(buffer, transfer.destination());
            } else if (action instanceof ItemStackRequest.Swap swap) {
                writeSlot(buffer, swap.source());
                writeSlot(buffer, swap.destination());
            } else if (action instanceof ItemStackRequest.Drop drop) {
                writeByte(buffer, drop.count());
                writeSlot(buffer, drop.source());
                buffer.writeBoolean(drop.randomly());
            } else if (action instanceof ItemStackRequest.Destroy destroy) {
                writeByte(buffer, destroy.count());
                writeSlot(buffer, destroy.source());
            } else if (action instanceof ItemStackRequest.CraftCreative creative) {
                BedrockTypes.UNSIGNED_VAR_INT.write(buffer, creative.creativeNetId());
                writeByte(buffer, creative.count());
            } else {
                throw new UnsupportedOperationException("Unsupported inventory action: " + action);
            }
        }
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, 0); // filter strings
        buffer.writeIntLE(-1); // no text-processing origin
    }

    private static void writeSlot(final ByteBuf buffer, final ItemStackRequest.Slot slot) {
        BedrockTypes.FULL_CONTAINER_NAME.write(buffer, slot.container());
        writeByte(buffer, slot.slot());
        buffer.writeIntLE(slot.netId());
    }

    private static void writeByte(final ByteBuf buffer, final int value) {
        if (value < 0 || value > 0xFF) {
            throw new IllegalArgumentException("Value does not fit the protocol's unsigned byte: " + value);
        }
        buffer.writeByte(value);
    }

}
