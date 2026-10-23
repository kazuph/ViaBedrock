/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package net.raphimc.viabedrock.protocol.model;

import com.viaversion.viaversion.api.minecraft.data.StructuredData;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.api.minecraft.item.Item;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** A match includes every Java component, never just the item identifier. */
public final class CreativeInventory {

    private final Map<ByteBuffer, Entry> entries = new HashMap<>();
    private final Set<ByteBuffer> ambiguous = new HashSet<>();
    private final Set<Integer> networkIds = new HashSet<>();
    private final Map<Integer, Integer> defaultDamage;

    public CreativeInventory(final Map<Integer, Integer> defaultDamage) {
        this.defaultDamage = Map.copyOf(defaultDamage);
    }

    public void clear() {
        this.entries.clear();
        this.ambiguous.clear();
        this.networkIds.clear();
    }

    public void add(final int networkId, final BedrockItem bedrock, final Item java) {
        if (networkId == 0 || !this.networkIds.add(networkId)) {
            throw new IllegalArgumentException("Invalid or duplicate creative network identifier: " + networkId);
        }
        final ByteBuffer key = this.key(java);
        final Entry entry = new Entry(networkId, bedrock.copy(), java.copy());
        final Entry existing = this.entries.putIfAbsent(key, entry);
        if (existing != null && existing.bedrock.isDifferent(bedrock)) {
            this.ambiguous.add(key);
        }
    }

    public Entry match(final Item item) {
        final ByteBuffer key = this.key(item);
        return this.ambiguous.contains(key) ? null : this.entries.get(key);
    }

    public boolean sameItem(final Item left, final Item right) {
        return this.key(left).equals(this.key(right));
    }

    private ByteBuffer key(final Item item) {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            buffer.writeInt(item.identifier());
            final var components = item.dataContainer().data().entrySet().stream()
                .filter(entry -> entry.getKey() != StructuredDataKey.DAMAGE || entry.getValue().isEmpty()
                    || !java.util.Objects.equals(entry.getValue().value(), this.defaultDamage.get(item.identifier())))
                .map(Map.Entry::getValue).sorted(Comparator.comparingInt(StructuredData::id)).toList();
            buffer.writeInt(components.size());
            for (StructuredData<?> component : components) {
                buffer.writeInt(component.id());
                buffer.writeBoolean(component.isEmpty());
                if (!component.isEmpty()) {
                    component.write(buffer);
                }
            }
            final byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return ByteBuffer.wrap(bytes).asReadOnlyBuffer();
        } finally {
            buffer.release();
        }
    }

    public record Entry(int networkId, BedrockItem bedrock, Item java) {
    }

}
