package dev.mappywall.client;

import dev.mappywall.core.RouteStep;
import dev.mappywall.core.ObservedMap;
import java.util.Optional;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

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

    public MapOpenAttempt tryOpenMapInRegion(Minecraft client, RouteStep target) {
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
                return MapOpenAttempt.pause(Component.translatable("message.mappywall.open_unverified"));
            }
            return MapOpenAttempt.none();
        }

        if (cooldownTicks > 0) {
            cooldownTicks--;
            return MapOpenAttempt.none();
        }

        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null) {
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
                moveInventoryMapToHotbar(client, inventorySlot, player.getInventory().getSelectedSlot());
                cooldownTicks = 8;
                return MapOpenAttempt.none();
            }
            player.sendSystemMessage(Component.translatable(
                    "message.mappywall.needs_empty_map",
                    target.wallPos().column() + 1,
                    target.wallPos().row() + 1
            ).withStyle(ChatFormatting.YELLOW));
            cooldownTicks = 40;
            return MapOpenAttempt.none();
        }

        player.getInventory().setSelectedSlot(hotbarSlot);
        ItemStack before = player.getMainHandItem();
        if (!before.is(Items.MAP)) {
            cooldownTicks = 8;
            return MapOpenAttempt.none();
        }

        Set<Integer> knownMapIds = currentFilledMapIds(client);
        client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        pendingOpening = new PendingOpening(target.region().signature(), knownMapIds, hotbarSlot, OPEN_WAIT_TICKS);
        cooldownTicks = 4;
        return MapOpenAttempt.none();
    }

    private Optional<Integer> findCompletedOpening(Minecraft client, RouteStep target) {
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

    private Set<Integer> currentFilledMapIds(Minecraft client) {
        if (client.player == null) {
            return Set.of();
        }
        return scanner.scanFilledMapIds(client.player);
    }

    private void moveInventoryMapToHotbar(Minecraft client, int inventorySlot, int hotbarSlot) {
        if (client.gameMode == null || client.player == null) {
            return;
        }
        int containerSlot = inventorySlot < 9 ? HOTBAR_CONTAINER_OFFSET + inventorySlot : inventorySlot;
        client.gameMode.handleContainerInput(
                client.player.containerMenu.containerId,
                containerSlot,
                hotbarSlot,
                ContainerInput.SWAP,
                client.player
        );
    }

    private Integer readPendingSlotMapId(Minecraft client) {
        if (client.player == null || pendingOpening == null) {
            return null;
        }
        ItemStack stack = client.player.getInventory().getNonEquipmentItems().get(pendingOpening.hotbarSlot());
        if (!stack.is(Items.FILLED_MAP)) {
            return null;
        }
        return InventoryMapIds.readMapId(stack);
    }

    private record PendingOpening(String regionSignature, Set<Integer> knownMapIds, int hotbarSlot, int ticksRemaining) {
        PendingOpening tick() {
            return new PendingOpening(regionSignature, knownMapIds, hotbarSlot, ticksRemaining - 1);
        }
    }

    public record MapOpenAttempt(Integer openedMapId, Component pauseMessage) {
        static MapOpenAttempt none() {
            return new MapOpenAttempt(null, null);
        }

        static MapOpenAttempt opened(int mapId) {
            return new MapOpenAttempt(mapId, null);
        }

        static MapOpenAttempt pause(Component message) {
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
