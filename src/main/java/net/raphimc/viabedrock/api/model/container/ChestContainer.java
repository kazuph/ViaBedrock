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
package net.raphimc.viabedrock.api.model.container;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.libs.mcstructs.text.TextComponent;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import net.raphimc.viabedrock.protocol.data.generated.bedrock.CustomBlockTags;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;

public class ChestContainer extends Container {

    public ChestContainer(final UserConnection user, final byte containerId, final TextComponent title, final BlockPosition position) {
        // The initial content packet is authoritative for single versus double chest size.
        super(user, containerId, ContainerType.CONTAINER, title, position, 0, CustomBlockTags.CHEST, CustomBlockTags.TRAPPED_CHEST);
    }

    @Override
    public boolean setItems(final BedrockItem[] items) {
        if (this.size() == 0) {
            if (items.length != 27 && items.length != 54) {
                return false;
            }
            this.items = BedrockItem.emptyArray(items.length);
        }
        return super.setItems(items);
    }

    public String javaMenuName() {
        if (this.size() == 27) {
            return "minecraft:generic_9x3";
        }
        if (this.size() == 54) {
            return "minecraft:generic_9x6";
        }
        throw new IllegalStateException("Chest content has not supplied a supported size");
    }

    @Override
    public Item[] getJavaItems() {
        final Item[] chest = super.getJavaItems();
        final Container inventory = this.user.get(InventoryTracker.class).getInventoryContainer();
        final Item[] combined = new Item[chest.length + inventory.size()];
        System.arraycopy(chest, 0, combined, 0, chest.length);
        for (int i = 0; i < 27; i++) {
            combined[chest.length + i] = inventory.getJavaItem(i + 9);
        }
        for (int i = 0; i < 9; i++) {
            combined[chest.length + 27 + i] = inventory.getJavaItem(i);
        }
        return combined;
    }

}
