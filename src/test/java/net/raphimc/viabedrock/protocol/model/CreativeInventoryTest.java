package net.raphimc.viabedrock.protocol.model;

import com.viaversion.viaversion.api.minecraft.data.StructuredData;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataContainer;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.minecraft.item.StructuredItem;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectOpenHashMap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.raphimc.viabedrock.protocol.types.item.BedrockItemType;
import org.junit.Test;
import com.viaversion.nbt.tag.CompoundTag;

import java.util.Map;

import static org.junit.Assert.*;

public class CreativeInventoryTest {

    @Test
    public void explicitVanillaDefaultDamageMatchesButChangedDamageDoesNot() {
        final CreativeInventory inventory = new CreativeInventory(Map.of(1050, 0));
        inventory.add(11, new BedrockItem(300), new StructuredItem(1050, 1));
        final Item undamaged = new StructuredItem(1050, 1, new StructuredDataContainer(Map.of(
            StructuredDataKey.DAMAGE, StructuredData.of(StructuredDataKey.DAMAGE, 0, 3))));
        assertNotNull(inventory.match(undamaged));
        final Item damaged = new StructuredItem(1050, 1, new StructuredDataContainer(Map.of(
            StructuredDataKey.DAMAGE, StructuredData.of(StructuredDataKey.DAMAGE, 7, 3))));
        assertNull(inventory.match(damaged));
        final CreativeInventory noDefault = new CreativeInventory(Map.of());
        noDefault.add(11, new BedrockItem(300), new StructuredItem(1050, 1));
        assertNull(noDefault.match(undamaged));
    }

    @Test
    public void countCanChangeButComponentsMustMatch() {
        final CreativeInventory inventory = new CreativeInventory(Map.of(1050, 0));
        final Item damaged = new StructuredItem(1050, 1, new StructuredDataContainer(Map.of(
            StructuredDataKey.DAMAGE, StructuredData.of(StructuredDataKey.DAMAGE, 7, 3))));
        inventory.add(11, new BedrockItem(300), damaged);
        assertNotNull(inventory.match(damaged.copy()));
        final Item differentCount = damaged.copy();
        differentCount.setAmount(2);
        assertNotNull(inventory.match(differentCount));
        assertNull(inventory.match(new StructuredItem(1050, 1)));
        assertNull(inventory.match(new StructuredItem(1, 1)));
    }

    @Test
    public void aliasedJavaItemsDoNotSelectTheWrongBedrockVariant() {
        final CreativeInventory inventory = new CreativeInventory(Map.of(1050, 0));
        final Item java = new StructuredItem(1, 1);
        final BedrockItem first = new BedrockItem(5);
        first.setBlockRuntimeId(100);
        final BedrockItem second = first.copy();
        second.setBlockRuntimeId(101);
        inventory.add(1, first, java);
        inventory.add(2, second, java);
        assertNull(inventory.match(java));
    }

    @Test
    public void emptyCreativeGroupIconConsumesTheCompleteDescriptor() {
        final ByteBuf bytes = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump("00000000000a00000000000000000000"));
        try {
            final BedrockItem item = new BedrockItemType(355, new Int2ObjectOpenHashMap<>(), false).read(bytes);
            assertTrue(item.isEmpty());
            assertFalse(bytes.isReadable());
        } finally {
            bytes.release();
        }
    }

    @Test
    public void nativeNamedItemsCompareTextSemanticsWithoutChangingCatalogMatching() {
        final CreativeInventory inventory = new CreativeInventory(Map.of());
        final CompoundTag hex = new CompoundTag();
        hex.putString("text", "Named book");
        hex.putString("color", "#ff55ff");
        hex.putByte("bold", (byte) 1);
        final CompoundTag named = hex.copy();
        named.putString("color", "light_purple");
        final Item left = new StructuredItem(1146, 1, new StructuredDataContainer(Map.of(
            StructuredDataKey.CUSTOM_NAME, StructuredData.of(StructuredDataKey.CUSTOM_NAME, hex, 6))));
        final Item right = new StructuredItem(1146, 1, new StructuredDataContainer(Map.of(
            StructuredDataKey.CUSTOM_NAME, StructuredData.of(StructuredDataKey.CUSTOM_NAME, named, 6))));
        assertTrue(inventory.sameItem(left, right));
        named.putString("text", "Different book");
        assertFalse(inventory.sameItem(left, right));
        assertNull(inventory.match(left));
    }

}
