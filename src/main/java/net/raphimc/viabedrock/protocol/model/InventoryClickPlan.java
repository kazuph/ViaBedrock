/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package net.raphimc.viabedrock.protocol.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToIntBiFunction;

/** Java menu semantics expressed as conserved transfers before writing any packets. */
public final class InventoryClickPlan<K> {

    private final Function<K, BedrockItem> read;
    private final ToIntBiFunction<K, BedrockItem> capacity;
    private final Map<K, BedrockItem> after = new LinkedHashMap<>();
    private final List<Operation<K>> operations = new ArrayList<>();

    public InventoryClickPlan(final Function<K, BedrockItem> read, final ToIntBiFunction<K, BedrockItem> capacity) {
        this.read = read;
        this.capacity = capacity;
    }

    public BedrockItem item(final K slot) {
        return this.after.getOrDefault(slot, this.read.apply(slot)).copy();
    }

    public void pickup(final K cursor, final K clicked, final int button) {
        if (button != 0 && button != 1) {
            throw new IllegalArgumentException("Pickup button must be left or right");
        }
        final BedrockItem carried = this.item(cursor);
        if (clicked == null) {
            this.drop(cursor, button == 0 ? carried.amount() : 1);
            return;
        }
        final BedrockItem target = this.item(clicked);
        if (carried.isEmpty()) {
            this.transfer(clicked, cursor, button == 0 ? target.amount() : (target.amount() + 1) / 2);
        } else if (target.isEmpty() || !target.isDifferent(carried)) {
            this.transfer(cursor, clicked, button == 0 ? carried.amount() : 1);
        } else {
            this.swap(cursor, clicked);
        }
    }

    public void transfer(final K source, final K destination, final int requested) {
        if (source.equals(destination) || requested <= 0) {
            return;
        }
        final BedrockItem from = this.item(source);
        final BedrockItem to = this.item(destination);
        if (from.isEmpty() || !to.isEmpty() && from.isDifferent(to)) {
            return;
        }
        final int count = Math.min(requested, Math.min(from.amount(), this.capacity.applyAsInt(destination, from) - (to.isEmpty() ? 0 : to.amount())));
        if (count <= 0) {
            return;
        }
        final BedrockItem moved = from.copy();
        moved.setAmount((to.isEmpty() ? 0 : to.amount()) + count);
        from.setAmount(from.amount() - count);
        this.after.put(source, from.isEmpty() ? BedrockItem.empty() : from);
        this.after.put(destination, moved);
        this.operations.add(new Transfer<>(source, destination, count));
    }

    public void swap(final K source, final K destination) {
        if (source.equals(destination)) {
            return;
        }
        final BedrockItem from = this.item(source);
        final BedrockItem to = this.item(destination);
        if (from.isEmpty() && to.isEmpty()
            || !from.isEmpty() && from.amount() > this.capacity.applyAsInt(destination, from)
            || !to.isEmpty() && to.amount() > this.capacity.applyAsInt(source, to)) {
            return;
        }
        if (from.isEmpty()) {
            this.transfer(destination, source, to.amount());
            return;
        } else if (to.isEmpty()) {
            this.transfer(source, destination, from.amount());
            return;
        }
        this.after.put(source, to);
        this.after.put(destination, from);
        this.operations.add(new Swap<>(source, destination));
    }

    public void drop(final K source, final int requested) {
        final BedrockItem from = this.item(source);
        final int count = Math.min(from.amount(), requested);
        if (from.isEmpty() || count <= 0) {
            return;
        }
        from.setAmount(from.amount() - count);
        this.after.put(source, from.isEmpty() ? BedrockItem.empty() : from);
        this.operations.add(new Drop<>(source, count));
    }

    public boolean reconcile(final Map<K, BedrockItem> desired, final K cursor) {
        for (K slot : desired.keySet()) {
            final BedrockItem wanted = desired.get(slot);
            if (!wanted.isEmpty() && wanted.amount() > this.capacity.applyAsInt(slot, wanted)) {
                return false;
            }
            final BedrockItem identity = this.item(slot);
            if (!identity.isEmpty()) {
                final int before = desired.keySet().stream().map(this::item).filter(item -> !item.isDifferent(identity)).mapToInt(BedrockItem::amount).sum();
                final int after = desired.values().stream().filter(item -> !item.isDifferent(identity)).mapToInt(BedrockItem::amount).sum();
                if (before != after) {
                    return false;
                }
            }
            if (!wanted.isEmpty() && desired.keySet().stream().map(this::item).noneMatch(item -> !item.isDifferent(wanted))) {
                return false;
            }
        }
        while (desired.entrySet().stream().anyMatch(entry -> !sameStack(this.item(entry.getKey()), entry.getValue()))) {
            final int previous = this.operations.size();
            for (K target : desired.keySet()) {
                final BedrockItem wanted = desired.get(target);
                if (wanted.isEmpty() || sameStack(this.item(target), wanted)) {
                    continue;
                }
                for (K source : desired.keySet()) {
                    if (source.equals(target)) {
                        continue;
                    }
                    final BedrockItem from = this.item(source);
                    final BedrockItem to = this.item(target);
                    if (sameStack(from, wanted) && sameStack(to, desired.get(source))) {
                        this.swap(source, target);
                        break;
                    }
                    if (!from.isEmpty() && !from.isDifferent(wanted) && (to.isEmpty() || !to.isDifferent(wanted))) {
                        final BedrockItem keep = desired.get(source);
                        final int surplus = from.amount() - (!keep.isEmpty() && !keep.isDifferent(from) ? keep.amount() : 0);
                        this.transfer(source, target, Math.min(surplus, wanted.amount() - to.amount()));
                    }
                }
            }
            if (this.operations.size() == previous) {
                if (!this.item(cursor).isEmpty()) {
                    return false;
                }
                final K blocked = desired.keySet().stream().filter(slot -> !slot.equals(cursor)
                    && !this.item(slot).isEmpty() && !sameStack(this.item(slot), desired.get(slot))).findFirst().orElse(null);
                if (blocked == null) {
                    return false;
                }
                final int count = this.item(blocked).amount();
                this.transfer(blocked, cursor, count);
                if (this.item(cursor).amount() != count) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean sameStack(final BedrockItem left, final BedrockItem right) {
        return left.isEmpty() && right.isEmpty() || left.amount() == right.amount() && !left.isDifferent(right);
    }

    public void quickMove(final K source, final List<K> destinations) {
        for (int pass = 0; pass < 2; pass++) {
            for (K target : destinations) {
                final BedrockItem to = this.item(target);
                if ((pass == 0) == !to.isEmpty()) {
                    this.transfer(source, target, this.item(source).amount());
                }
            }
        }
    }

    public void collect(final K cursor, final List<K> sources) {
        final BedrockItem carried = this.item(cursor);
        if (carried.isEmpty()) {
            return;
        }
        for (int pass = 0; pass < 2; pass++) {
            for (K source : sources) {
                final BedrockItem item = this.item(source);
                if (!item.isEmpty() && !item.isDifferent(carried)
                    && (pass == 1 || item.amount() < this.capacity.applyAsInt(source, item))) {
                    this.transfer(source, cursor, item.amount());
                }
            }
        }
    }

    public void distribute(final K cursor, final List<K> destinations, final boolean oneEach) {
        final int count = this.item(cursor).amount();
        if (destinations.isEmpty() || count == 0) {
            return;
        }
        final int each = oneEach ? 1 : count / destinations.size();
        for (K destination : destinations) {
            this.transfer(cursor, destination, each);
        }
    }

    public Map<K, BedrockItem> after() {
        return Map.copyOf(this.after);
    }

    public List<Operation<K>> operations() {
        return List.copyOf(this.operations);
    }

    public sealed interface Operation<K> permits Transfer, Swap, Drop {
    }

    public record Transfer<K>(K source, K destination, int count) implements Operation<K> {
    }

    public record Swap<K>(K source, K destination) implements Operation<K> {
    }

    public record Drop<K>(K source, int count) implements Operation<K> {
    }

}
