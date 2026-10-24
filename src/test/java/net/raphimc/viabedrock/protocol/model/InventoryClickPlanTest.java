package net.raphimc.viabedrock.protocol.model;

import org.junit.Test;
import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.IntTag;
import com.viaversion.nbt.tag.StringTag;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class InventoryClickPlanTest {

    private static BedrockItem item(final int id, final int count) {
        final BedrockItem item = new BedrockItem(id);
        item.setAmount(count);
        return item;
    }

    private static InventoryClickPlan<String> plan(final Map<String, BedrockItem> slots) {
        return new InventoryClickPlan<>(slots::get, (slot, item) -> 64);
    }

    @Test
    public void rightPickupAndPlacementConserveEverySupportedStackCount() {
        for (int count = 1; count <= 64; count++) {
            final var plan = plan(Map.of("slot", item(1, count), "cursor", BedrockItem.empty()));
            plan.pickup("cursor", "slot", 1);
            assertEquals((count + 1) / 2, plan.item("cursor").amount());
            assertEquals(count, plan.item("slot").amount() + plan.item("cursor").amount());
            plan.pickup("cursor", "slot", 1);
            assertEquals(count, plan.item("slot").amount() + plan.item("cursor").amount());
        }
    }

    @Test
    public void quickMoveFillsMatchingPartialStacksBeforeEmptySlotsAndPreservesRemainder() {
        final var plan = plan(Map.of("source", item(1, 64), "partial", item(1, 60), "empty", BedrockItem.empty(), "other", item(2, 63)));
        plan.quickMove("source", List.of("empty", "other", "partial"));
        assertEquals(64, plan.item("partial").amount());
        assertEquals(60, plan.item("empty").amount());
        assertTrue(plan.item("source").isEmpty());
        assertEquals(2, plan.item("other").identifier());
        assertEquals(63, plan.item("other").amount());
    }

    @Test
    public void swappingWithAnEmptySlotUsesTransfersInBothDirections() {
        final var plan = plan(Map.of("source", item(1, 32), "destination", BedrockItem.empty()));
        plan.swap("source", "destination");
        assertTrue(plan.item("source").isEmpty());
        assertEquals(32, plan.item("destination").amount());
        assertTrue(plan.operations().get(0) instanceof InventoryClickPlan.Transfer<?>);
        plan.swap("source", "destination");
        assertEquals(32, plan.item("source").amount());
        assertTrue(plan.item("destination").isEmpty());
        assertTrue(plan.operations().get(1) instanceof InventoryClickPlan.Transfer<?>);
    }

    @Test
    public void fullTargetsAndDifferentKindsDoNotConsumeItems() {
        final var plan = plan(Map.of("source", item(1, 64), "full", item(1, 64), "other", item(2, 63)));
        plan.quickMove("source", List.of("full", "other"));
        assertEquals(64, plan.item("source").amount());
        assertTrue(plan.operations().isEmpty());
        plan.swap("source", "other");
        assertEquals(2, plan.item("source").identifier());
        assertEquals(1, plan.item("other").identifier());
    }

    @Test
    public void dragUsesTheOriginalCountAndLeavesUnevenRemainderOnCursor() {
        final var plan = plan(Map.of("cursor", item(1, 32), "a", BedrockItem.empty(), "b", BedrockItem.empty(), "c", BedrockItem.empty()));
        plan.distribute("cursor", List.of("a", "b", "c"), false);
        assertEquals(10, plan.item("a").amount());
        assertEquals(10, plan.item("b").amount());
        assertEquals(10, plan.item("c").amount());
        assertEquals(2, plan.item("cursor").amount());
    }

    @Test
    public void doubleClickCollectAndOutsideDropPreserveTheirDifferentCountRules() {
        final Map<String, BedrockItem> items = new HashMap<>(Map.of("cursor", item(1, 10), "a", item(1, 32), "b", item(1, 31), "different", item(2, 8)));
        final var plan = plan(items);
        plan.collect("cursor", List.of("a", "b", "different"));
        assertEquals(64, plan.item("cursor").amount());
        assertEquals(9, plan.item("b").amount());
        assertEquals(8, plan.item("different").amount());
        plan.pickup("cursor", null, 1);
        assertEquals(63, plan.item("cursor").amount());
        plan.pickup("cursor", null, 0);
        assertTrue(plan.item("cursor").isEmpty());
        final var drop = (InventoryClickPlan.Drop<String>) plan.operations().get(plan.operations().size() - 1);
        assertEquals(63, drop.count());
    }

    @Test
    public void numberSwapPreservesDamageNameAndNetworkIdentity() {
        final BedrockItem sword = item(300, 1);
        sword.setNetId(41);
        final CompoundTag tag = new CompoundTag();
        tag.put("Damage", new IntTag(17));
        final CompoundTag display = new CompoundTag();
        display.put("Name", new StringTag("Named sword"));
        tag.put("display", display);
        sword.setTag(tag);
        final BedrockItem glass = item(20, 32);
        glass.setNetId(42);
        final var plan = plan(Map.of("main", sword, "hotbar", glass));
        plan.swap("main", "hotbar");
        assertEquals(1, plan.operations().size());
        assertTrue(plan.operations().get(0) instanceof InventoryClickPlan.Swap<?>);
        assertEquals(tag, plan.item("hotbar").tag());
        assertEquals(Integer.valueOf(41), plan.item("hotbar").netId());
        assertEquals(Integer.valueOf(42), plan.item("main").netId());
        assertEquals(32, plan.item("main").amount());
    }

    @Test
    public void nativeShiftMergeUsesOnlyConservedTransfers() {
        final var plan = plan(Map.of("main", item(1, 64), "hotbar", item(1, 32), "empty", BedrockItem.empty(), "cursor", BedrockItem.empty()));
        assertTrue(plan.reconcile(Map.of("main", BedrockItem.empty(), "hotbar", item(1, 64), "empty", item(1, 32), "cursor", BedrockItem.empty()), "cursor"));
        assertTrue(plan.item("main").isEmpty());
        assertEquals(64, plan.item("hotbar").amount());
        assertEquals(32, plan.item("empty").amount());
        assertTrue(plan.item("cursor").isEmpty());
        assertEquals(2, plan.operations().size());
        assertTrue(plan.operations().stream().allMatch(operation -> operation instanceof InventoryClickPlan.Transfer<?>));
    }

    @Test
    public void nativeImagesRejectCreationAndStaleExchanges() {
        final var plan = plan(Map.of("a", item(1, 64), "b", item(1, 32), "c", BedrockItem.empty(), "cursor", BedrockItem.empty()));
        assertFalse(plan.reconcile(Map.of("a", BedrockItem.empty(), "b", item(1, 64), "c", item(1, 64), "cursor", BedrockItem.empty()), "cursor"));
        assertTrue(plan.operations().isEmpty());
        final var stale = plan(Map.of("a", item(2, 16), "b", item(3, 32), "cursor", BedrockItem.empty()));
        assertFalse(stale.reconcile(Map.of("a", item(3, 32), "b", item(1, 1), "cursor", BedrockItem.empty()), "cursor"));
        assertTrue(stale.operations().isEmpty());
    }

    @Test
    public void wholeReciprocalStacksSwapAndCyclesUseCursorTransfers() {
        final var swap = plan(Map.of("a", item(1, 1), "b", item(2, 32), "cursor", BedrockItem.empty()));
        assertTrue(swap.reconcile(Map.of("a", item(2, 32), "b", item(1, 1), "cursor", BedrockItem.empty()), "cursor"));
        assertEquals(1, swap.operations().size());
        assertTrue(swap.operations().get(0) instanceof InventoryClickPlan.Swap<?>);
        final var cycle = plan(Map.of("a", item(1, 1), "b", item(2, 32), "c", item(3, 16), "cursor", BedrockItem.empty()));
        assertTrue(cycle.reconcile(Map.of("a", item(2, 32), "b", item(3, 16), "c", item(1, 1), "cursor", BedrockItem.empty()), "cursor"));
        assertTrue(cycle.item("cursor").isEmpty());
        assertTrue(cycle.operations().stream().allMatch(operation -> operation instanceof InventoryClickPlan.Transfer<?>));
    }

}
