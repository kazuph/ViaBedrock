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

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.IntTag;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.minecraft.blockentity.BlockEntity;
import net.raphimc.viabedrock.api.chunk.BedrockBlockEntity;
import net.raphimc.viabedrock.api.chunk.BlockEntityWithBlockState;
import net.raphimc.viabedrock.api.model.BlockState;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.storage.ChunkTracker;

/**
 * Bedrock stores a large chest as two chests which reference each other with pairx/pairz. Java expresses it with the chest type (left/right).
 */
public class ChestBlockEntityRewriter extends LootableContainerBlockEntityRewriter {

    @Override
    public BlockEntity toJava(final UserConnection user, final BedrockBlockEntity bedrockBlockEntity) {
        final BlockEntity javaBlockEntity = super.toJava(user, bedrockBlockEntity);
        final CompoundTag bedrockTag = bedrockBlockEntity.tag();
        if (!(bedrockTag.get("pairx") instanceof IntTag pairX) || !(bedrockTag.get("pairz") instanceof IntTag pairZ)) {
            return javaBlockEntity;
        }

        final BlockPosition position = bedrockBlockEntity.position();
        final int javaBlockStateId = user.get(ChunkTracker.class).getJavaBlockState(position);
        final BlockState javaBlockState = BedrockProtocol.MAPPINGS.getJavaBlockStates().inverse().get(javaBlockStateId);
        if (javaBlockState == null) {
            return javaBlockEntity;
        }
        final BlockState pairedBlockState = pairedBlockState(javaBlockState, pairX.asInt() - position.x(), pairZ.asInt() - position.z());
        if (pairedBlockState == javaBlockState) {
            return javaBlockEntity;
        }
        final Integer pairedBlockStateId = BedrockProtocol.MAPPINGS.getJavaBlockStates().get(pairedBlockState);
        if (pairedBlockStateId == null) {
            return javaBlockEntity;
        }

        return new BlockEntityWithBlockState(javaBlockEntity, pairedBlockStateId);
    }

    /**
     * @param offsetX X offset from this chest to the other half
     * @param offsetZ Z offset from this chest to the other half
     * @return The block state with the Java chest type of this half, or the given state if the other half is not directly beside it
     */
    public static BlockState pairedBlockState(final BlockState single, final int offsetX, final int offsetZ) {
        final String facing = single.properties().get("facing");
        if (facing == null || Math.abs(offsetX) + Math.abs(offsetZ) != 1) {
            return single;
        }

        // Java: the other half of a LEFT chest is in the clockwise direction of its facing, the other half of a RIGHT chest is counterclockwise
        final int[] clockwise = switch (facing) {
            case "north" -> new int[]{1, 0};
            case "east" -> new int[]{0, 1};
            case "south" -> new int[]{-1, 0};
            case "west" -> new int[]{0, -1};
            default -> null;
        };
        if (clockwise == null) {
            return single;
        }
        if (offsetX == clockwise[0] && offsetZ == clockwise[1]) {
            return single.replaceProperty("type", "left");
        } else if (offsetX == -clockwise[0] && offsetZ == -clockwise[1]) {
            return single.replaceProperty("type", "right");
        }
        return single;
    }

}
