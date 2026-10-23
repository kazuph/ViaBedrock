package net.raphimc.viabedrock.protocol.model;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerEnumName;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ItemStackRequestActionType;
import net.raphimc.viabedrock.protocol.types.inventory.ItemStackRequestType;
import net.raphimc.viabedrock.protocol.types.inventory.ItemStackResponseType;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class ItemStackProtocolTest {

    @Test
    public void creativeRequestHasUnionTagsAndFixedWidthSlotNetworkIds() {
        final ByteBuf bytes = Unpooled.buffer();
        try {
            new ItemStackRequestType().write(bytes, new ItemStackRequest(-1, List.of(
                new ItemStackRequest.CraftCreative(1, 64),
                new ItemStackRequest.Transfer(ItemStackRequestActionType.Place, 64,
                    new ItemStackRequest.Slot(new FullContainerName(ContainerEnumName.CreatedOutputContainer, null), 50, -1),
                    new ItemStackRequest.Slot(new FullContainerName(ContainerEnumName.CombinedHotbarAndInventoryContainer, null), 1, 0)))));
            assertEquals("01020e0e01400101403c0032ffffffff0c00010000000000ffffffff", ByteBufUtil.hexDump(bytes));
        } finally {
            bytes.release();
        }
    }

    @Test
    public void rejectionWithoutSlotsDoesNotConsumeFollowingResponse() {
        final ByteBuf bytes = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump("0c0300000500"));
        try {
            final var type = new ItemStackResponseType();
            final var rejected = type.read(bytes);
            assertEquals(12, rejected.result());
            assertEquals(-2, rejected.requestId());
            assertTrue(rejected.containers().isEmpty());
            final var next = type.read(bytes);
            assertEquals(-3, next.requestId());
            assertEquals(0, next.result());
            assertFalse(bytes.isReadable());
        } finally {
            bytes.release();
        }
    }

    @Test
    public void responseDecodesOptionalNetIdFilteredNameAndDurability() {
        final ByteBuf bytes = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump("000101010c000101014001f601000000"));
        try {
            final var response = new ItemStackResponseType().read(bytes);
            final var container = response.containers().get(0);
            assertEquals(ContainerEnumName.CombinedHotbarAndInventoryContainer, container.name().name());
            final var slot = container.slots().get(0);
            assertEquals(1, slot.slot());
            assertEquals(64, slot.count());
            assertEquals(123, slot.netId());
            assertNull(slot.filteredCustomName());
            assertFalse(bytes.isReadable());
        } finally {
            bytes.release();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void unsupportedTransferTypeIsNotSubstituted() {
        new ItemStackRequest.Transfer(ItemStackRequestActionType.Destroy, 1, null, null);
    }

}
