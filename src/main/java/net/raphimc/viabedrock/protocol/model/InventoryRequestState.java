/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package net.raphimc.viabedrock.protocol.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Pending operations overlay the server's containers without mutating them. */
public final class InventoryRequestState<K> {

    private final Function<K, BedrockItem> confirmed;
    private final BiConsumer<K, BedrockItem> commit;
    private final Map<K, Long> revisions = new HashMap<>();
    private final Map<Integer, Request<K>> pending = new LinkedHashMap<>();
    private final Map<Integer, Map<K, Long>> sent = new HashMap<>();
    private int nextId = 1;

    public InventoryRequestState(final Function<K, BedrockItem> confirmed, final BiConsumer<K, BedrockItem> commit) {
        this.confirmed = confirmed;
        this.commit = commit;
    }

    public BedrockItem projected(final K slot) {
        BedrockItem item = this.confirmed.apply(slot);
        for (Request<K> request : this.pending.values()) {
            if (request.after.containsKey(slot)) {
                item = request.after.get(slot);
            }
        }
        return item.copy();
    }

    public Request<K> submit(final Map<K, BedrockItem> after, final Set<K> reads) {
        if (after.isEmpty()) {
            throw new IllegalArgumentException("An inventory request must affect a slot");
        }
        final Set<K> touched = new LinkedHashSet<>(reads);
        touched.addAll(after.keySet());
        final Map<K, BedrockItem> before = new LinkedHashMap<>();
        for (K slot : touched) {
            before.put(slot, this.projected(slot));
        }
        final Set<Integer> dependencies = new HashSet<>();
        for (Request<K> earlier : this.pending.values()) {
            if (earlier.after.keySet().stream().anyMatch(touched::contains)) {
                dependencies.add(earlier.id);
            }
        }
        final Map<K, BedrockItem> copiedAfter = new LinkedHashMap<>();
        after.forEach((slot, item) -> copiedAfter.put(slot, item.copy()));
        final int id = this.nextId++;
        if (id <= 0) {
            throw new IllegalStateException("Inventory request identifiers exhausted");
        }
        final Request<K> request = new Request<>(id, Map.copyOf(before), Map.copyOf(copiedAfter), Set.copyOf(dependencies));
        this.pending.put(id, request);
        return request;
    }

    /** Dependent operations are sent only after their inputs have real server net IDs. */
    public Ready<K> takeReady() {
        final List<Request<K>> ready = new ArrayList<>();
        final Set<Integer> rejected = new LinkedHashSet<>();
        for (Request<K> request : new ArrayList<>(this.pending.values())) {
            if (!this.pending.containsKey(request.id) || this.sent.containsKey(request.id)
                || request.dependencies.stream().anyMatch(this.pending::containsKey)) {
                continue;
            }
            if (request.before.entrySet().stream().anyMatch(entry -> !sameStack(entry.getValue(), this.confirmed.apply(entry.getKey())))) {
                rejected.addAll(this.reject(request.id));
                continue;
            }
            final Map<K, Long> versions = new HashMap<>();
            request.after.keySet().forEach(slot -> versions.put(slot, this.revisions.getOrDefault(slot, 0L)));
            this.sent.put(request.id, versions);
            ready.add(request);
        }
        return new Ready<>(List.copyOf(ready), Set.copyOf(rejected));
    }

    public Request<K> get(final int id) {
        return this.pending.get(id);
    }

    public boolean complete(final int id, final Map<K, BedrockItem> corrections) {
        final Request<K> request = this.pending.get(id);
        final Map<K, Long> versions = this.sent.get(id);
        if (request == null || versions == null) {
            return false;
        }
        this.pending.remove(id);
        this.sent.remove(id);
        request.after.forEach((slot, predicted) -> {
            if (corrections.containsKey(slot)) {
                this.commit.accept(slot, corrections.get(slot).copy());
            } else if (this.revisions.getOrDefault(slot, 0L).equals(versions.get(slot))) {
                this.commit.accept(slot, predicted.copy());
            }
        });
        corrections.forEach((slot, item) -> {
            if (!request.after.containsKey(slot)) {
                this.commit.accept(slot, item.copy());
            }
        });
        return true;
    }

    public Set<Integer> reject(final int id) {
        final Set<Integer> rejected = new LinkedHashSet<>();
        if (!this.pending.containsKey(id)) {
            return rejected;
        }
        rejected.add(id);
        for (Request<K> request : this.pending.values()) {
            if (request.dependencies.stream().anyMatch(rejected::contains)) {
                rejected.add(request.id);
            }
        }
        rejected.forEach(this.pending::remove);
        rejected.forEach(this.sent::remove);
        return rejected;
    }

    public void observed(final K slot) {
        this.revisions.merge(slot, 1L, Long::sum);
    }

    public boolean isPending() {
        return !this.pending.isEmpty();
    }

    private static boolean sameStack(final BedrockItem left, final BedrockItem right) {
        return left.isEmpty() && right.isEmpty() || left.amount() == right.amount() && !left.isDifferent(right);
    }

    public record Request<K>(int id, Map<K, BedrockItem> before, Map<K, BedrockItem> after, Set<Integer> dependencies) {
    }

    public record Ready<K>(List<Request<K>> requests, Set<Integer> rejected) {
    }

}
