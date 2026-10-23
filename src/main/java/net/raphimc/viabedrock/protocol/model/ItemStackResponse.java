/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package net.raphimc.viabedrock.protocol.model;

import java.util.List;

public record ItemStackResponse(int result, int requestId, List<Container> containers) {

    public record Container(FullContainerName name, List<Slot> slots) {
    }

    public record Slot(int slot, int hotbarSlot, int count, int netId, String customName, String filteredCustomName, int durabilityCorrection) {
    }

}
