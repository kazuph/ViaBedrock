/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package net.raphimc.viabedrock.protocol.types.inventory;

import com.viaversion.viaversion.api.type.Type;
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.protocol.model.FullContainerName;
import net.raphimc.viabedrock.protocol.model.ItemStackResponse;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.ArrayList;
import java.util.List;

public final class ItemStackResponseType extends Type<ItemStackResponse> {

    public ItemStackResponseType() {
        super(ItemStackResponse.class);
    }

    @Override
    public ItemStackResponse read(final ByteBuf buffer) {
        final int result = buffer.readUnsignedByte();
        final int requestId = BedrockTypes.VAR_INT.read(buffer);
        final List<ItemStackResponse.Container> containers = new ArrayList<>();
        if (buffer.readBoolean()) {
            final int containerCount = BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
            for (int i = 0; i < containerCount; i++) {
                final FullContainerName name = BedrockTypes.FULL_CONTAINER_NAME.read(buffer);
                final int slotCount = BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
                final List<ItemStackResponse.Slot> slots = new ArrayList<>();
                for (int j = 0; j < slotCount; j++) {
                    slots.add(new ItemStackResponse.Slot(buffer.readUnsignedByte(), buffer.readUnsignedByte(), buffer.readUnsignedByte(),
                        buffer.readBoolean() ? BedrockTypes.VAR_INT.read(buffer) : 0, BedrockTypes.STRING.read(buffer),
                        buffer.readBoolean() ? BedrockTypes.STRING.read(buffer) : null, BedrockTypes.VAR_INT.read(buffer)));
                }
                containers.add(new ItemStackResponse.Container(name, List.copyOf(slots)));
            }
        }
        return new ItemStackResponse(result, requestId, List.copyOf(containers));
    }

    @Override
    public void write(final ByteBuf buffer, final ItemStackResponse response) {
        throw new UnsupportedOperationException("Item stack responses are clientbound only");
    }

}
