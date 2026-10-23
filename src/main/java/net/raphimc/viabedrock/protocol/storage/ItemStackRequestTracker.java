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
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.container.Container;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerEnumName;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ItemStackNetResult;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ItemStackRequestActionType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InteractPacketPayload_Action;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.CreativeInventory;
import net.raphimc.viabedrock.protocol.model.FullContainerName;
import net.raphimc.viabedrock.protocol.model.InventoryRequestState;
import net.raphimc.viabedrock.protocol.model.InventoryClickPlan;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.ContainerInput;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.GameMode;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import net.raphimc.viabedrock.protocol.model.ItemStackRequest;
import net.raphimc.viabedrock.protocol.model.ItemStackResponse;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import net.raphimc.viabedrock.protocol.types.inventory.ItemStackRequestType;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.function.IntFunction;

public final class ItemStackRequestTracker extends StoredObject {

    private static final ItemStackRequestType REQUEST_TYPE = new ItemStackRequestType();
    // Mojang ServerAuthInventory/PlayerUIContainer: Created Item Output is HUD slot 50.
    private static final int CREATED_ITEM_OUTPUT_SLOT = 50;

    private final CreativeInventory creative = new CreativeInventory(BedrockProtocol.MAPPINGS.getJavaItemDefaultDamage());
    private final Deque<Runnable> waitingForInventory = new ArrayDeque<>();
    private boolean inventoryOpenRequested;
    private int dragWindow = -1;
    private int dragMode = -1;
    private final Set<Slot> dragSlots = new LinkedHashSet<>();
    private final List<Runnable> settled = new ArrayList<>();
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

    public boolean isPending() {
        return this.state.isPending() || this.hasQueuedInventoryActions();
    }

    public boolean isPending(final Slot slot) {
        return this.state.isPending(slot) || this.hasQueuedInventoryActions();
    }

    public boolean hasQueuedInventoryActions() {
        return this.inventoryOpenRequested || !this.waitingForInventory.isEmpty();
    }

    public CreativeInventory creative() {
        return this.creative;
    }

    public Slot javaInventorySlot(final int javaSlot) {
        final InventoryTracker inventory = this.user().get(InventoryTracker.class);
        if (javaSlot >= 36 && javaSlot <= 44) {
            return this.slot(inventory.getInventoryContainer(), javaSlot - 36);
        } else if (javaSlot >= 9 && javaSlot <= 35) {
            return this.slot(inventory.getInventoryContainer(), javaSlot);
        } else if (javaSlot >= 5 && javaSlot <= 8) {
            return this.slot(inventory.getArmorContainer(), javaSlot - 5);
        } else if (javaSlot == 45) {
            return this.slot(inventory.getOffhandContainer(), 0);
        }
        return null;
    }

    public void creativeSlot(final int javaSlot, final Item requested) {
        this.withInventory(() -> this.applyCreativeSlot(javaSlot, requested));
    }

    private void withInventory(final Runnable action) {
        final InventoryTracker inventory = this.user().get(InventoryTracker.class);
        if (inventory.getCurrentContainer() != null && inventory.getPendingCloseContainer() == null) {
            action.run();
            return;
        }
        this.waitingForInventory.add(action);
        this.openInventoryIfNeeded();
    }

    private void openInventoryIfNeeded() {
        final InventoryTracker inventory = this.user().get(InventoryTracker.class);
        if (this.waitingForInventory.isEmpty() || this.inventoryOpenRequested || inventory.isContainerOpen()) {
            return;
        }
        this.inventoryOpenRequested = true;
        final PacketWrapper interact = PacketWrapper.create(ServerboundBedrockPackets.INTERACT, this.user());
        interact.write(Types.UNSIGNED_BYTE, (short) InteractPacketPayload_Action.OpenInventory.getValue());
        interact.write(BedrockTypes.UNSIGNED_VAR_LONG, this.user().get(EntityTracker.class).getClientPlayer().runtimeId());
        interact.write(BedrockTypes.OPTIONAL_POSITION_3F, null);
        interact.sendToServer(BedrockProtocol.class);
    }

    public void opened() {
        this.inventoryOpenRequested = false;
        final InventoryTracker inventory = this.user().get(InventoryTracker.class);
        while (!this.waitingForInventory.isEmpty() && inventory.getCurrentContainer() != null && inventory.getPendingCloseContainer() == null) {
            this.waitingForInventory.removeFirst().run();
        }
        this.user().get(SelectedItemActions.class).drain();
    }

    public void closed() {
        this.inventoryOpenRequested = false;
        this.openInventoryIfNeeded();
    }

    public void close(final int window) {
        final InventoryTracker inventory = this.user().get(InventoryTracker.class);
        final Container container = inventory.getCurrentContainer();
        if (container == null) {
            if (this.inventoryOpenRequested && window == 0) {
                this.waitingForInventory.add(() -> this.close(window));
            }
            return;
        }
        if (container.javaContainerId() != window) {
            this.unsupported("Close refers to a different menu: " + window);
            return;
        }
        this.dragSlots.clear();
        this.dragWindow = -1;
        inventory.markPendingClose(container);
        final Runnable close = () -> PacketFactory.sendBedrockContainerClose(this.user(), container.containerId(), ContainerType.NONE);
        if (this.state.isPending()) {
            this.settled.add(close);
        } else {
            close.run();
        }
    }

    private void applyCreativeSlot(final int javaSlot, final Item requested) {
        final Slot destination = javaSlot == -1 ? null : this.javaInventorySlot(javaSlot);
        if ((javaSlot != -1 && destination == null) || requested.amount() < 0 || requested.amount() > 0xFF) {
            this.unsupported("Invalid creative slot or count");
            return;
        }
        this.applyCreativeChange(destination, requested, Set.of());
    }

    private void applyCreativeChange(final Slot destination, final Item requested, final Set<Slot> reads) {
        final InventoryTracker inventory = this.user().get(InventoryTracker.class);
        final Slot output = this.slot(inventory.getHudContainer(), CREATED_ITEM_OUTPUT_SLOT);
        final BedrockItem before = destination == null ? BedrockItem.empty() : this.projected(destination);
        if (requested.isEmpty()) {
            if (destination != null && !before.isEmpty()) {
                this.submit(Map.of(destination, BedrockItem.empty()), reads,
                    id -> List.of(new ItemStackRequest.Destroy(before.amount(), this.wireSlot(destination))));
            } else {
                this.resync();
            }
            return;
        }
        final ItemRewriter items = this.user().get(ItemRewriter.class);
        final boolean same = !before.isEmpty() && this.creative.sameItem(items.javaItem(before.copy()), requested);
        if (same && requested.amount() <= before.amount()) {
            if (requested.amount() < before.amount()) {
                final BedrockItem after = before.copy();
                after.setAmount(requested.amount());
                this.submit(Map.of(destination, after), reads,
                    id -> List.of(new ItemStackRequest.Destroy(before.amount() - requested.amount(), this.wireSlot(destination))));
            }
            return;
        }
        final CreativeInventory.Entry entry = this.creative.match(requested);
        if (entry == null) {
            this.unsupported("Creative item is absent from the server catalog, ambiguous, or has different components");
            return;
        }
        final BedrockItem after = entry.bedrock().copy();
        after.setAmount(requested.amount());
        final Map<Slot, BedrockItem> changes = new HashMap<>();
        changes.put(output, BedrockItem.empty());
        if (destination != null) {
            changes.put(destination, after);
        }
        final int count = same ? requested.amount() - before.amount() : requested.amount();
        this.submit(changes, reads, id -> {
            final List<ItemStackRequest.Action> request = new ArrayList<>();
            if (!same && !before.isEmpty()) {
                request.add(new ItemStackRequest.Destroy(before.amount(), this.wireSlot(destination)));
            }
            request.add(new ItemStackRequest.CraftCreative(entry.networkId(), count));
            final ItemStackRequest.Slot created = new ItemStackRequest.Slot(new FullContainerName(ContainerEnumName.CreatedOutputContainer, null), CREATED_ITEM_OUTPUT_SLOT, id);
            if (destination == null) {
                request.add(new ItemStackRequest.Drop(count, created, false));
            } else {
                final ItemStackRequest.Slot target = this.wireSlot(destination);
                request.add(new ItemStackRequest.Transfer(ItemStackRequestActionType.Place,
                    count, created, same ? target : new ItemStackRequest.Slot(target.container(), target.slot(), 0)));
            }
            return request;
        });
    }

    public void click(final int window, final int index, final int button, final ContainerInput action) {
        if (window == 0) {
            this.withInventory(() -> this.applyClick(window, index, button, action));
        } else {
            this.applyClick(window, index, button, action);
        }
    }

    private Slot menuSlot(final Container container, final int index) {
        if (container.type() == ContainerType.INVENTORY) {
            return this.javaInventorySlot(index);
        }
        if (container.type() == ContainerType.CONTAINER) {
            if (index >= 0 && index < container.size()) {
                return this.slot(container, index);
            }
            final int playerIndex = index - container.size();
            if (playerIndex >= 0 && playerIndex < 36) {
                return this.javaInventorySlot(playerIndex + 9);
            }
        }
        return null;
    }

    private List<Slot> menuSlots(final Container container) {
        final List<Slot> slots = new ArrayList<>();
        final int size = container.type() == ContainerType.INVENTORY ? 46 : container.size() + 36;
        for (int i = 0; i < size; i++) {
            final Slot slot = this.menuSlot(container, i);
            if (slot != null) {
                slots.add(slot);
            }
        }
        return slots;
    }

    private int capacity(final Slot slot, final BedrockItem item) {
        final InventoryTracker inventory = this.user().get(InventoryTracker.class);
        if (slot.container == inventory.getArmorContainer()) {
            return 1;
        }
        final Item java = this.user().get(ItemRewriter.class).javaItem(item.copy());
        final var override = java.dataContainer().getData(StructuredDataKey.MAX_STACK_SIZE);
        return override != null && !override.isEmpty() ? override.value() : BedrockProtocol.MAPPINGS.getJavaItemStackLimit(java.identifier());
    }

    private void applyClick(final int window, final int index, final int button, final ContainerInput action) {
        final InventoryTracker inventory = this.user().get(InventoryTracker.class);
        final Container container = inventory.getCurrentContainer();
        if (container == null || container.javaContainerId() != window || inventory.getPendingCloseContainer() != null) {
            this.unsupported("Click refers to a closed or different menu: " + window);
            return;
        }
        final Slot cursor = this.slot(inventory.getHudContainer(), 0);
        final Slot clicked = index == -999 ? null : this.menuSlot(container, index);
        if (clicked == null && index != -999) {
            this.unsupported("Unsupported menu slot: " + index);
            return;
        }
        final boolean creative = this.user().get(EntityTracker.class).getClientPlayer().javaGameMode() == GameMode.CREATIVE;
        final InventoryClickPlan<Slot> plan = new InventoryClickPlan<>(this::projected, this::capacity);
        if (action != ContainerInput.QUICK_CRAFT) {
            this.dragWindow = -1;
            this.dragSlots.clear();
        }
        switch (action) {
            case PICKUP -> plan.pickup(cursor, clicked, button);
            case QUICK_MOVE -> {
                if (clicked == null) {
                    break;
                }
                final List<Slot> targets = new ArrayList<>();
                if (container.type() == ContainerType.CONTAINER) {
                    if (clicked.container == container) {
                        for (int i = 35; i >= 0; i--) {
                            targets.add(this.slot(inventory.getInventoryContainer(), i < 27 ? i + 9 : i - 27));
                        }
                    } else {
                        for (int i = 0; i < container.size(); i++) {
                            targets.add(this.slot(container, i));
                        }
                    }
                } else {
                    final boolean hotbar = clicked.container == inventory.getInventoryContainer() && clicked.index < 9;
                    final BedrockItem item = this.projected(clicked);
                    final Item java = this.user().get(ItemRewriter.class).javaItem(item.copy());
                    final String equipmentSlot = BedrockProtocol.MAPPINGS.getJavaItemEquipmentSlot(java.identifier());
                    final int armorIndex = equipmentSlot == null ? -1 : switch (equipmentSlot) {
                        case "head" -> 0;
                        case "chest" -> 1;
                        case "legs" -> 2;
                        case "feet" -> 3;
                        default -> -1;
                    };
                    final Slot armor = armorIndex == -1 ? null : this.slot(inventory.getArmorContainer(), armorIndex);
                    final Slot offhand = this.slot(inventory.getOffhandContainer(), 0);
                    if (clicked.container == inventory.getInventoryContainer() && armor != null && this.projected(armor).isEmpty()) {
                        targets.add(armor);
                    } else if (clicked.container == inventory.getInventoryContainer() && "offhand".equals(equipmentSlot) && this.projected(offhand).isEmpty()) {
                        targets.add(offhand);
                    } else if (clicked.container != inventory.getInventoryContainer()) {
                        for (int i = 9; i < 45; i++) {
                            targets.add(this.javaInventorySlot(i));
                        }
                    } else {
                        for (int i = hotbar ? 9 : 0; i < (hotbar ? 36 : 9); i++) {
                            targets.add(this.slot(inventory.getInventoryContainer(), i));
                        }
                    }
                }
                plan.quickMove(clicked, targets);
            }
            case SWAP -> {
                final Slot target = button >= 0 && button < 9 ? this.slot(inventory.getInventoryContainer(), button)
                    : button == 40 ? this.slot(inventory.getOffhandContainer(), 0) : null;
                if (clicked == null || target == null) {
                    this.unsupported("Invalid number-key swap");
                    return;
                }
                plan.swap(clicked, target);
            }
            case THROW -> {
                if (clicked != null && this.projected(cursor).isEmpty()) {
                    plan.drop(clicked, button == 0 ? 1 : this.projected(clicked).amount());
                }
            }
            case PICKUP_ALL -> {
                final List<Slot> sources = this.menuSlots(container);
                if (button == 1) {
                    Collections.reverse(sources);
                }
                plan.collect(cursor, sources);
            }
            case CLONE -> {
                if (creative && clicked != null && this.projected(cursor).isEmpty() && !this.projected(clicked).isEmpty()) {
                    this.cloneItem(clicked, cursor);
                } else {
                    this.resync();
                }
                return;
            }
            case QUICK_CRAFT -> {
                final int phase = button & 3;
                final int mode = button >> 2;
                if (phase == 0 && mode >= 0 && mode <= 2 && (mode != 2 || creative)) {
                    this.dragWindow = window;
                    this.dragMode = mode;
                    this.dragSlots.clear();
                    return;
                }
                if (this.dragWindow != window || this.dragMode != mode) {
                    this.unsupported("Quick-craft sequence has no matching start");
                    return;
                }
                final BedrockItem carried = this.projected(cursor);
                if (phase == 1 && clicked != null && !carried.isEmpty()) {
                    final BedrockItem target = this.projected(clicked);
                    if ((target.isEmpty() || !target.isDifferent(carried)) && target.amount() < this.capacity(clicked, carried)
                        && (mode == 2 || carried.amount() > this.dragSlots.size())) {
                        this.dragSlots.add(clicked);
                    }
                    return;
                } else if (phase == 2) {
                    final List<Slot> targets = new ArrayList<>(this.dragSlots);
                    this.dragSlots.clear();
                    this.dragWindow = -1;
                    if (mode == 2) {
                        for (Slot target : targets) {
                            this.cloneItem(cursor, target);
                        }
                        return;
                    }
                    plan.distribute(cursor, targets, mode == 1);
                } else {
                    this.unsupported("Invalid quick-craft phase");
                    return;
                }
            }
        }
        this.submitPlan(plan);
    }

    private void cloneItem(final Slot source, final Slot destination) {
        final BedrockItem item = this.projected(source);
        final Item java = this.user().get(ItemRewriter.class).javaItem(item);
        java.setAmount(this.capacity(destination, item));
        this.applyCreativeChange(destination, java, Set.of(source));
    }

    public void dropSelected(final boolean all) {
        this.withInventory(() -> {
            final InventoryTracker inventory = this.user().get(InventoryTracker.class);
            final Slot selected = this.slot(inventory.getInventoryContainer(), inventory.getInventoryContainer().getSelectedHotbarSlot());
            final InventoryClickPlan<Slot> plan = new InventoryClickPlan<>(this::projected, this::capacity);
            plan.drop(selected, all ? this.projected(selected).amount() : 1);
            this.submitPlan(plan);
        });
    }

    private void submitPlan(final InventoryClickPlan<Slot> plan) {
        if (plan.operations().isEmpty()) {
            this.resync();
            return;
        }
        this.submit(plan.after(), Set.of(), id -> {
            final List<ItemStackRequest.Action> wire = new ArrayList<>();
            for (var operation : plan.operations()) {
                if (operation instanceof InventoryClickPlan.Transfer<Slot> move) {
                    wire.add(new ItemStackRequest.Transfer(ItemStackRequestActionType.Place, move.count(), this.wireSlot(move.source()), this.wireSlot(move.destination())));
                } else if (operation instanceof InventoryClickPlan.Swap<Slot> swap) {
                    wire.add(new ItemStackRequest.Swap(this.wireSlot(swap.source()), this.wireSlot(swap.destination())));
                } else if (operation instanceof InventoryClickPlan.Drop<Slot> drop) {
                    wire.add(new ItemStackRequest.Drop(drop.count(), this.wireSlot(drop.source()), false));
                }
            }
            return wire;
        });
    }

    public void unsupported(final String reason) {
        ViaBedrock.getPlatform().getLogger().warning("Unsupported inventory operation: " + reason);
        this.resync();
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
        this.user().get(SelectedItemActions.class).drain();
        if (!this.state.isPending()) {
            final List<Runnable> settled = new ArrayList<>(this.settled);
            this.settled.clear();
            settled.forEach(Runnable::run);
        }
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
