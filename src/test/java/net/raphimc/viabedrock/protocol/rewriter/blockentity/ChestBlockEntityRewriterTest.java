/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.protocol.rewriter.blockentity;

import net.raphimc.viabedrock.api.model.BlockState;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class ChestBlockEntityRewriterTest {

    private static BlockState chest(final String facing) {
        return new BlockState("minecraft", "chest", Map.of("facing", facing, "type", "single", "waterlogged", "false"));
    }

    @Test
    public void northFacingHalfWithPartnerToTheEastIsLeft() {
        // Observed on the copy server: chests at x=242 and x=243, both facing north; 242 has pairx=243, 243 has pairx=242
        assertEquals("left", ChestBlockEntityRewriter.pairedBlockState(chest("north"), 1, 0).properties().get("type"));
        assertEquals("right", ChestBlockEntityRewriter.pairedBlockState(chest("north"), -1, 0).properties().get("type"));
    }

    @Test
    public void everyFacingUsesTheClockwiseRule() {
        assertEquals("left", ChestBlockEntityRewriter.pairedBlockState(chest("east"), 0, 1).properties().get("type"));
        assertEquals("right", ChestBlockEntityRewriter.pairedBlockState(chest("east"), 0, -1).properties().get("type"));
        assertEquals("left", ChestBlockEntityRewriter.pairedBlockState(chest("south"), -1, 0).properties().get("type"));
        assertEquals("right", ChestBlockEntityRewriter.pairedBlockState(chest("south"), 1, 0).properties().get("type"));
        assertEquals("left", ChestBlockEntityRewriter.pairedBlockState(chest("west"), 0, -1).properties().get("type"));
        assertEquals("right", ChestBlockEntityRewriter.pairedBlockState(chest("west"), 0, 1).properties().get("type"));
    }

    @Test
    public void otherPropertiesAreKept() {
        final BlockState paired = ChestBlockEntityRewriter.pairedBlockState(chest("north"), 1, 0);
        assertEquals("north", paired.properties().get("facing"));
        assertEquals("false", paired.properties().get("waterlogged"));
    }

    @Test
    public void partnerThatIsNotBesideTheChestLeavesItSingle() {
        final BlockState single = chest("north");
        assertSame(single, ChestBlockEntityRewriter.pairedBlockState(single, 0, 0));
        assertSame(single, ChestBlockEntityRewriter.pairedBlockState(single, 2, 0));
        assertSame(single, ChestBlockEntityRewriter.pairedBlockState(single, 1, 1));
        assertSame(single, ChestBlockEntityRewriter.pairedBlockState(single, 0, 1));
    }

}
