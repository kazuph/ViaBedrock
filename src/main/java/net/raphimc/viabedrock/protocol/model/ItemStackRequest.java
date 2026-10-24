/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package net.raphimc.viabedrock.protocol.model;

import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ItemStackRequestActionType;

import java.util.List;

public record ItemStackRequest(int id, List<Action> actions) {

    public sealed interface Action permits Transfer, Swap, Drop, Destroy, CraftCreative {
        ItemStackRequestActionType type();
    }

    public record Slot(FullContainerName container, int slot, int netId) {
    }

    public record Transfer(ItemStackRequestActionType type, int count, Slot source, Slot destination) implements Action {
        public Transfer {
            if (type != ItemStackRequestActionType.Take && type != ItemStackRequestActionType.Place) {
                throw new IllegalArgumentException("Not a transfer action: " + type);
            }
        }
    }

    public record Swap(Slot source, Slot destination) implements Action {
        @Override
        public ItemStackRequestActionType type() {
            return ItemStackRequestActionType.Swap;
        }
    }

    public record Drop(int count, Slot source, boolean randomly) implements Action {
        @Override
        public ItemStackRequestActionType type() {
            return ItemStackRequestActionType.Drop;
        }
    }

    public record Destroy(int count, Slot source) implements Action {
        @Override
        public ItemStackRequestActionType type() {
            return ItemStackRequestActionType.Destroy;
        }
    }

    public record CraftCreative(int creativeNetId, int count) implements Action {
        @Override
        public ItemStackRequestActionType type() {
            return ItemStackRequestActionType.CraftCreative;
        }
    }

}
