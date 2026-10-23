package net.raphimc.viabedrock.protocol.model;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public class InventoryRequestStateTest {

    private static BedrockItem stack(final int count, final int netId) {
        final BedrockItem item = new BedrockItem(1);
        item.setAmount(count);
        item.setNetId(netId);
        return item;
    }

    @Test
    public void rejectionRemovesOnlyDependentOperations() {
        final Map<String, BedrockItem> confirmed = new HashMap<>();
        confirmed.put("inventory", stack(64, 10));
        confirmed.put("cursor", BedrockItem.empty());
        confirmed.put("chest", stack(4, 20));
        final InventoryRequestState<String> state = new InventoryRequestState<>(confirmed::get, confirmed::put);
        final var split = state.submit(Map.of("inventory", stack(32, 10), "cursor", stack(32, 0)), Set.of());
        final var drop = state.submit(Map.of("cursor", BedrockItem.empty()), Set.of());
        final var independent = state.submit(Map.of("chest", stack(3, 20)), Set.of());
        assertEquals(64, confirmed.get("inventory").amount());
        assertEquals(32, state.projected("inventory").amount());
        assertEquals(Set.of(split.id(), independent.id()), state.takeReady().requests().stream().map(InventoryRequestState.Request::id).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of(split.id(), drop.id()), state.reject(split.id()));
        assertNotNull(state.get(independent.id()));
        assertEquals(64, state.projected("inventory").amount());
        assertTrue(state.projected("cursor").isEmpty());
        assertTrue(state.complete(independent.id(), Map.of("chest", stack(3, 21))));
        assertEquals(Integer.valueOf(21), confirmed.get("chest").netId());
        assertFalse(state.isPending());
    }

    @Test
    public void dependentOperationWaitsForServerConfirmation() {
        final Map<String, BedrockItem> confirmed = new HashMap<>(Map.of("slot", stack(64, 10)));
        final InventoryRequestState<String> state = new InventoryRequestState<>(confirmed::get, confirmed::put);
        final var first = state.submit(Map.of("slot", stack(32, 10)), Set.of());
        final var next = state.submit(Map.of("slot", stack(16, 10)), Set.of());
        assertEquals(-1, first.id());
        assertEquals(-3, next.id());
        assertEquals(first.id(), state.takeReady().requests().get(0).id());
        assertTrue(state.takeReady().requests().isEmpty());
        assertTrue(state.complete(first.id(), Map.of("slot", stack(32, 11))));
        assertEquals(next.id(), state.takeReady().requests().get(0).id());
        assertEquals(Integer.valueOf(11), confirmed.get("slot").netId());
    }

    @Test
    public void serverCorrectionInvalidatesStaleDependentInput() {
        final Map<String, BedrockItem> confirmed = new HashMap<>(Map.of("slot", stack(64, 10)));
        final InventoryRequestState<String> state = new InventoryRequestState<>(confirmed::get, confirmed::put);
        final var first = state.submit(Map.of("slot", stack(32, 10)), Set.of());
        final var next = state.submit(Map.of("slot", stack(16, 10)), Set.of());
        state.takeReady();
        state.complete(first.id(), Map.of("slot", stack(30, 11)));
        final var ready = state.takeReady();
        assertTrue(ready.requests().isEmpty());
        assertEquals(Set.of(next.id()), ready.rejected());
        assertEquals(30, state.projected("slot").amount());
    }

    @Test
    public void newerInventoryContentIsNotOverwrittenByAcknowledgementWithoutSlots() {
        final Map<String, BedrockItem> confirmed = new HashMap<>(Map.of("slot", stack(64, 10)));
        final InventoryRequestState<String> state = new InventoryRequestState<>(confirmed::get, confirmed::put);
        final var request = state.submit(Map.of("slot", stack(32, 10)), Set.of());
        state.takeReady();
        confirmed.put("slot", stack(31, 12));
        state.observed("slot");
        assertTrue(state.complete(request.id(), Map.of()));
        assertEquals(31, confirmed.get("slot").amount());
        assertEquals(Integer.valueOf(12), confirmed.get("slot").netId());
        assertFalse(state.complete(request.id(), Map.of("slot", stack(99, 0))));
    }

    @Test
    public void aLaterWriteDoesNotOvertakeAnEarlierReadOfTheSameSlot() {
        final Map<String, BedrockItem> confirmed = new HashMap<>(Map.of("source", stack(64, 10), "destination", BedrockItem.empty()));
        final InventoryRequestState<String> state = new InventoryRequestState<>(confirmed::get, confirmed::put);
        final var clone = state.submit(Map.of("destination", stack(64, 0)), Set.of("source"));
        final var drop = state.submit(Map.of("source", stack(63, 10)), Set.of());
        assertTrue(drop.dependencies().contains(clone.id()));
        assertEquals(clone.id(), state.takeReady().requests().get(0).id());
        assertTrue(state.takeReady().requests().isEmpty());
    }

}
