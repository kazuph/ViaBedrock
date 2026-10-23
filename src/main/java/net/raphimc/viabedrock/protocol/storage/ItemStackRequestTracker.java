/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package net.raphimc.viabedrock.protocol.storage;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.IntTag;
import com.viaversion.nbt.tag.StringTag;
import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.container.Container;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerEnumName;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ItemStackNetResult;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.FullContainerName;
import net.raphimc.viabedrock.protocol.model.InventoryRequestState;
import net.raphimc.viabedrock.protocol.model.ItemStackRequest;
import net.raphimc.viabedrock.protocol.model.ItemStackResponse;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import net.raphimc.viabedrock.protocol.types.inventory.ItemStackRequestType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

public final class ItemStackRequestTracker extends StoredObject {

    private static final ItemStackRequestType REQUEST_TYPE = new ItemStackRequestType();

    private final InventoryRequestState<Slot> state;
    private final Map<Integer, IntFunction<List<ItemStackRequest.Action>>> actions = new HashMap<>();

    public ItemStackRequestTracker(final UserConnection user) {
        super(user);
        this.state = new InventoryRequestState<>(slot -> slot.container.getItem(slot.index),
            (slot, item) -> slot.container.setItem(slot.index, item));
    }

    public BedrockItem projected(final Slot slot) {
        return this.state.projected(slot);
    }

    public Slot slot(final Container container, final int index) {
        return new Slot(container.type() == ContainerType.INVENTORY ? this.user().get(InventoryTracker.class).getInventoryContainer() : container, index);
    }

    public void observed(final Container container, final int index) {
        this.state.observed(this.slot(container, index));
    }

    public void submit(final Map<Slot, BedrockItem> after, final Set<Slot> reads, final IntFunction<List<ItemStackRequest.Action>> actions) {
        final var request = this.state.submit(after, reads);
        this.actions.put(request.id(), actions);
        this.flush();
    }

    private void flush() {
        final var ready = this.state.takeReady();
        ready.rejected().forEach(this.actions::remove);
        for (var request : ready.requests()) {
            try {
                final PacketWrapper packet = PacketWrapper.create(ServerboundBedrockPackets.ITEM_STACK_REQUEST, this.user());
                final ItemStackRequest wire = new ItemStackRequest(request.id(), this.actions.get(request.id()).apply(request.id()));
                packet.write(BedrockTypes.UNSIGNED_VAR_INT, 1);
                packet.write(REQUEST_TYPE, wire);
                packet.sendToServer(BedrockProtocol.class);
                if (Boolean.getBoolean("viabedrock.traceInventory")) {
                    ViaBedrock.getPlatform().getLogger().info("INVENTORY_REQUEST time=" + System.nanoTime() + " request=" + wire);
                }
            } catch (final RuntimeException error) {
                this.state.reject(request.id()).forEach(this.actions::remove);
                this.resync();
                throw error;
            }
        }
        if (!ready.rejected().isEmpty()) {
            this.resync();
        }
    }

    public void response(final ItemStackResponse response) {
        final var request = this.state.get(response.requestId());
        if (request == null) {
            ViaBedrock.getPlatform().getLogger().warning("Ignoring response for unknown item stack request " + response.requestId());
            return;
        }
        if (response.result() != ItemStackNetResult.Success.getValue()) {
            this.state.reject(response.requestId()).forEach(this.actions::remove);
            ViaBedrock.getPlatform().getLogger().warning("Item stack request " + response.requestId() + " rejected: "
                + ItemStackNetResult.getByValue(response.result()) + " (" + response.result() + ")");
        } else {
            final Map<Slot, BedrockItem> corrections = new HashMap<>();
            for (ItemStackResponse.Container container : response.containers()) {
                for (ItemStackResponse.Slot correction : container.slots()) {
                    final Slot slot = this.responseSlot(container.name(), correction.slot(), request);
                    if (slot == null) {
                        continue;
                    }
                    BedrockItem item = request.after().containsKey(slot) ? request.after().get(slot).copy() : slot.container.getItem(slot.index).copy();
                    if (correction.count() == 0) {
                        item = BedrockItem.empty();
                    } else {
                        if (item.isEmpty()) {
                            throw new IllegalStateException("Server returned a nonempty correction without an item identity: " + slot);
                        }
                        item.setAmount(correction.count());
                        item.setNetId(correction.netId());
                        if (!correction.customName().isEmpty() || correction.durabilityCorrection() != 0) {
                            final CompoundTag tag = item.tag() == null ? new CompoundTag() : item.tag().copy();
                            if (!correction.customName().isEmpty()) {
                                CompoundTag display = tag.getCompoundTag("display");
                                if (display == null) {
                                    display = new CompoundTag();
                                    tag.put("display", display);
                                }
                                display.put("Name", new StringTag(correction.filteredCustomName() == null ? correction.customName() : correction.filteredCustomName()));
                            }
                            if (correction.durabilityCorrection() != 0) {
                                tag.put("Damage", new IntTag(correction.durabilityCorrection()));
                            }
                            item.setTag(tag);
                        }
                    }
                    corrections.put(slot, item);
                }
            }
            if (!this.state.complete(response.requestId(), corrections)) {
                throw new IllegalStateException("Received a response for an unsent inventory operation");
            }
            this.actions.remove(response.requestId());
        }
        if (Boolean.getBoolean("viabedrock.traceInventory")) {
            ViaBedrock.getPlatform().getLogger().info("INVENTORY_RESPONSE time=" + System.nanoTime() + " response=" + response);
        }
        this.resync();
        this.flush();
    }

    private Slot responseSlot(final FullContainerName name, final int index, final InventoryRequestState.Request<Slot> request) {
        final InventoryTracker inventory = this.user().get(InventoryTracker.class);
        final Container container = switch (name.name()) {
            case CombinedHotbarAndInventoryContainer, HotbarContainer, InventoryContainer -> inventory.getInventoryContainer();
            case ArmorContainer -> inventory.getArmorContainer();
            case OffhandContainer -> inventory.getOffhandContainer();
            case CursorContainer -> inventory.getHudContainer();
            case LevelEntityContainer -> request.before().keySet().stream()
                .filter(slot -> slot.container.type() == ContainerType.CONTAINER)
                .map(Slot::container).findFirst().orElse(null);
            case CreatedOutputContainer -> null;
            default -> throw new UnsupportedOperationException("Response container is not supported: " + name);
        };
        return container != null && index >= 0 && index < container.size() ? new Slot(container, index) : null;
    }

    public ItemStackRequest.Slot wireSlot(final Slot slot) {
        final InventoryTracker inventory = this.user().get(InventoryTracker.class);
        final ContainerEnumName name;
        if (slot.container == inventory.getInventoryContainer()) {
            name = ContainerEnumName.CombinedHotbarAndInventoryContainer;
        } else if (slot.container == inventory.getHudContainer() && slot.index == 0) {
            name = ContainerEnumName.CursorContainer;
        } else if (slot.container == inventory.getArmorContainer()) {
            name = ContainerEnumName.ArmorContainer;
        } else if (slot.container == inventory.getOffhandContainer()) {
            name = ContainerEnumName.OffhandContainer;
        } else {
            name = ContainerEnumName.LevelEntityContainer;
        }
        final BedrockItem item = slot.container.getItem(slot.index);
        if (!item.isEmpty() && item.netId() == null) {
            throw new IllegalStateException("No authoritative network ID for slot " + slot);
        }
        return new ItemStackRequest.Slot(new FullContainerName(name, null), slot.index, item.isEmpty() ? 0 : item.netId());
    }

    public void resync() {
        final InventoryTracker inventory = this.user().get(InventoryTracker.class);
        PacketFactory.sendJavaContainerSetContent(this.user(), inventory.getInventoryContainer());
        final Container open = inventory.getCurrentContainer();
        if (open != null && open != inventory.getInventoryContainer() && open.type() != ContainerType.INVENTORY) {
            PacketFactory.sendJavaContainerSetContent(this.user(), open);
        }
    }

    public record Slot(Container container, int index) {
    }

}
