package dev.mappywall.client;

import dev.mappywall.core.RouteStep;
import dev.mappywall.core.ObservedMap;
import java.util.HashSet;
import java.util.List;
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
    private static final int OPEN_WAIT_TICKS = 80;
    private final InventoryMapScanner scanner;
    private int cooldownTicks;
    private PendingOpening pendingOpening;

    public MapOpenController(InventoryMapScanner scanner) {
        this.scanner = scanner;
    }

    public void reset() {
        cooldownTicks = 0;
        pendingOpening = null;
    }

    public Optional<Integer> tryOpenMapInRegion(MinecraftClient client, RouteStep target) {
        if (pendingOpening != null) {
            PendingOpening pending = pendingOpening;
            Optional<Integer> completed = findCompletedOpening(client, target);
            if (completed.isPresent()) {
                pendingOpening = null;
                cooldownTicks = 10;
                return completed;
            }
            if (pendingOpening == null) {
                cooldownTicks = 10;
                return Optional.empty();
            }
            PendingOpening nextPending = pending.tick();
            pendingOpening = nextPending;
            if (nextPending.ticksRemaining() <= 0) {
                pendingOpening = null;
                cooldownTicks = 20;
            }
            return Optional.empty();
        }

        if (cooldownTicks > 0) {
            cooldownTicks--;
            return Optional.empty();
        }

        ClientPlayerEntity player = client.player;
        if (player == null || client.interactionManager == null) {
            return Optional.empty();
        }

        int hotbarSlot = scanner.findHotbarEmptyMap(player);
        if (hotbarSlot < 0) {
            int inventorySlot = scanner.findInventoryEmptyMap(player);
            if (inventorySlot >= 0) {
                moveInventoryMapToHotbar(client, inventorySlot, player.getInventory().getSelectedSlot());
                cooldownTicks = 8;
                return Optional.empty();
            }
            player.sendMessage(Text.literal("MappyWall needs an empty map for "
                    + target.wallPos().column() + ", " + target.wallPos().row()).formatted(Formatting.YELLOW), false);
            cooldownTicks = 40;
            return Optional.empty();
        }

        player.getInventory().setSelectedSlot(hotbarSlot);
        ItemStack before = player.getMainHandStack();
        if (!before.isOf(Items.MAP)) {
            cooldownTicks = 8;
            return Optional.empty();
        }

        Set<Integer> knownMapIds = currentFilledMapIds(client);
        client.interactionManager.interactItem(player, Hand.MAIN_HAND);
        pendingOpening = new PendingOpening(target.region().signature(), knownMapIds, OPEN_WAIT_TICKS);
        cooldownTicks = 4;
        return Optional.empty();
    }

    private Optional<Integer> findCompletedOpening(MinecraftClient client, RouteStep target) {
        if (!pendingOpening.regionSignature().equals(target.region().signature())) {
            pendingOpening = null;
            return Optional.empty();
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
        List<ObservedMap> observedMaps = scanner.scanFilledMaps(client);
        Set<Integer> mapIds = new HashSet<>(observedMaps.size());
        for (ObservedMap observed : observedMaps) {
            mapIds.add(observed.mapId());
        }
        return mapIds;
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

    private record PendingOpening(String regionSignature, Set<Integer> knownMapIds, int ticksRemaining) {
        PendingOpening tick() {
            return new PendingOpening(regionSignature, knownMapIds, ticksRemaining - 1);
        }
    }
}
