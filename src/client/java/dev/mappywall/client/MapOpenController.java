package dev.mappywall.client;

import dev.mappywall.core.RouteStep;
import dev.mappywall.core.ObservedMap;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;

public final class MapOpenController {
    private static final int HOTBAR_CONTAINER_OFFSET = 36;
    private static final int OPEN_WAIT_TICKS = 120;
    private final InventoryMapScanner scanner;
    private int cooldownTicks;
    private PendingOpening pendingOpening;
    private String blockedRegionSignature;

    public MapOpenController(InventoryMapScanner scanner) {
        this.scanner = scanner;
    }

    public void reset() {
        cooldownTicks = 0;
        pendingOpening = null;
        blockedRegionSignature = null;
    }

    public MapOpenAttempt tryOpenMapInRegion(MinecraftClient client, RouteStep target) {
        if (pendingOpening != null) {
            PendingOpening pending = pendingOpening;
            Optional<Integer> completed = findCompletedOpening(client, target);
            if (completed.isPresent()) {
                pendingOpening = null;
                cooldownTicks = 10;
                blockedRegionSignature = null;
                return MapOpenAttempt.opened(completed.get());
            }
            if (pendingOpening == null) {
                cooldownTicks = 10;
                return MapOpenAttempt.none();
            }
            PendingOpening nextPending = pending.tick();
            pendingOpening = nextPending;
            if (nextPending.ticksRemaining() <= 0) {
                pendingOpening = null;
                cooldownTicks = 100;
                blockedRegionSignature = pending.regionSignature();
                return MapOpenAttempt.pause(Text.translatable("message.mappywall.open_unverified"));
            }
            return MapOpenAttempt.none();
        }

        if (cooldownTicks > 0) {
            cooldownTicks--;
            return MapOpenAttempt.none();
        }

        ClientPlayerEntity player = client.player;
        if (player == null || client.interactionManager == null) {
            return MapOpenAttempt.none();
        }

        if (target.region().signature().equals(blockedRegionSignature)) {
            cooldownTicks = 20;
            return MapOpenAttempt.none();
        }

        int hotbarSlot = scanner.findHotbarEmptyMap(player);
        if (hotbarSlot < 0) {
            int inventorySlot = scanner.findInventoryEmptyMap(player);
            if (inventorySlot >= 0) {
                moveInventoryMapToHotbar(client, inventorySlot, MinecraftCompat.selectedSlot(player.getInventory()));
                cooldownTicks = 8;
                return MapOpenAttempt.none();
            }
            player.sendMessage(Text.translatable(
                    "message.mappywall.needs_empty_map",
                    target.wallPos().column() + 1,
                    target.wallPos().row() + 1
            ).formatted(Formatting.YELLOW), false);
            cooldownTicks = 40;
            return MapOpenAttempt.none();
        }

        MinecraftCompat.setSelectedSlot(player.getInventory(), hotbarSlot);
        ItemStack before = player.getMainHandStack();
        if (!before.isOf(Items.MAP)) {
            cooldownTicks = 8;
            return MapOpenAttempt.none();
        }

        Set<Integer> knownMapIds = currentFilledMapIds(client);
        client.interactionManager.interactItem(player, Hand.MAIN_HAND);
        pendingOpening = new PendingOpening(target.region().signature(), knownMapIds, hotbarSlot, OPEN_WAIT_TICKS);
        cooldownTicks = 4;
        return MapOpenAttempt.none();
    }

    private Optional<Integer> findCompletedOpening(MinecraftClient client, RouteStep target) {
        if (!pendingOpening.regionSignature().equals(target.region().signature())) {
            pendingOpening = null;
            return Optional.empty();
        }

        Integer selectedMapId = readPendingSlotMapId(client);
        if (selectedMapId != null && !pendingOpening.knownMapIds().contains(selectedMapId)) {
            return Optional.of(selectedMapId);
        }

        Set<Integer> newMapIds = currentFilledMapIds(client);
        newMapIds.removeAll(pendingOpening.knownMapIds());
        if (newMapIds.size() == 1) {
            return Optional.of(newMapIds.iterator().next());
        }

        for (ObservedMap observed : scanner.scanFilledMaps(client)) {
            if (observed.regionSignature().equals(pendingOpening.regionSignature())
                    && !pendingOpening.knownMapIds().contains(observed.mapId())) {
                return Optional.of(observed.mapId());
            }
        }
        return Optional.empty();
    }

    private Set<Integer> currentFilledMapIds(MinecraftClient client) {
        if (client.player == null) {
            return Set.of();
        }
        return scanner.scanFilledMapIds(client.player);
    }

    private void moveInventoryMapToHotbar(MinecraftClient client, int inventorySlot, int hotbarSlot) {
        if (client.interactionManager == null || client.player == null) {
            return;
        }
        int containerSlot = inventorySlot < 9 ? HOTBAR_CONTAINER_OFFSET + inventorySlot : inventorySlot;
        client.interactionManager.clickSlot(
                client.player.currentScreenHandler.syncId,
                containerSlot,
                hotbarSlot,
                SlotActionType.SWAP,
                client.player
        );
    }

    private Integer readPendingSlotMapId(MinecraftClient client) {
        if (client.player == null || pendingOpening == null) {
            return null;
        }
        ItemStack stack = MinecraftCompat.mainStacks(client.player.getInventory()).get(pendingOpening.hotbarSlot());
        if (!stack.isOf(Items.FILLED_MAP)) {
            return null;
        }
        return InventoryMapIds.readMapId(stack);
    }

    private record PendingOpening(String regionSignature, Set<Integer> knownMapIds, int hotbarSlot, int ticksRemaining) {
        PendingOpening tick() {
            return new PendingOpening(regionSignature, knownMapIds, hotbarSlot, ticksRemaining - 1);
        }
    }

    public record MapOpenAttempt(Integer openedMapId, Text pauseMessage) {
        static MapOpenAttempt none() {
            return new MapOpenAttempt(null, null);
        }

        static MapOpenAttempt opened(int mapId) {
            return new MapOpenAttempt(mapId, null);
        }

        static MapOpenAttempt pause(Text message) {
            return new MapOpenAttempt(null, message);
        }

        public Optional<Integer> openedMapIdOptional() {
            return Optional.ofNullable(openedMapId);
        }

        public boolean shouldPause() {
            return pauseMessage != null;
        }
    }
}
