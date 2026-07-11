package dev.mappywall.client;

import dev.mappywall.core.RouteStep;
import dev.mappywall.core.ObservedMap;
import dev.mappywall.core.MapRegion;
import dev.mappywall.core.MapRegionMath;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class MapOpenController {
    private static final int OPEN_WAIT_TICKS = 120;
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

    public MapOpenAttempt tryOpenMapInRegion(Minecraft client, RouteStep target) {
        if (pendingOpening != null) {
            PendingOpening pending = pendingOpening;
            if (!pending.regionSignature().equals(target.region().signature())) {
                pendingOpening = null;
                cooldownTicks = Math.max(cooldownTicks, 4);
                return MapOpenAttempt.none();
            }
            Optional<Integer> completed = findCompletedOpening(client, target);
            if (completed.isPresent()) {
                pendingOpening = null;
                cooldownTicks = 10;
                return MapOpenAttempt.opened(completed.get());
            }
            PendingOpening nextPending = pending.tick();
            pendingOpening = nextPending;
            if (nextPending.ticksRemaining() <= 0) {
                pendingOpening = null;
                cooldownTicks = 40;
                return MapOpenAttempt.pause(Component.translatable("message.mappywall.open_unverified"));
            }
            return MapOpenAttempt.none();
        }

        if (cooldownTicks > 0) {
            cooldownTicks--;
            return MapOpenAttempt.none();
        }

        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null || !isSafeInventoryContext(client, player)) {
            return MapOpenAttempt.none();
        }

        InteractionHand openingHand = InteractionHand.MAIN_HAND;
        int hotbarSlot = scanner.findHotbarEmptyMap(player);
        if (hotbarSlot < 0) {
            int inventorySlot = scanner.findInventoryEmptyMap(player);
            if (inventorySlot >= 0) {
                moveInventoryMapToHotbar(client, inventorySlot, player.getInventory().getSelectedSlot());
                cooldownTicks = 8;
                return MapOpenAttempt.none();
            }
            if (player.getOffhandItem().is(Items.MAP)) {
                openingHand = InteractionHand.OFF_HAND;
            } else {
                player.sendSystemMessage(Component.translatable(
                        "message.mappywall.needs_empty_map",
                        target.wallPos().column() + 1,
                        target.wallPos().row() + 1
                ).withStyle(ChatFormatting.YELLOW));
                cooldownTicks = 40;
                return MapOpenAttempt.none();
            }
        }

        ItemStack before;
        if (openingHand == InteractionHand.MAIN_HAND) {
            player.getInventory().setSelectedSlot(hotbarSlot);
            before = player.getMainHandItem();
        } else {
            before = player.getOffhandItem();
        }
        if (!before.is(Items.MAP)) {
            cooldownTicks = 8;
            return MapOpenAttempt.none();
        }
        if ((before.getCount() > 1 || player.hasInfiniteMaterials())
                && !scanner.hasEmptyInventorySlot(player)) {
            player.sendSystemMessage(Component.translatable("message.mappywall.open_needs_inventory_space")
                    .withStyle(ChatFormatting.YELLOW));
            cooldownTicks = 40;
            return MapOpenAttempt.none();
        }

        Set<Integer> knownMapIds = currentFilledMapIds(client);
        client.gameMode.useItem(player, openingHand);
        scanner.invalidateInventorySnapshot();
        pendingOpening = new PendingOpening(
                target.region().signature(),
                knownMapIds,
                openingHand,
                hotbarSlot,
                OPEN_WAIT_TICKS
        );
        cooldownTicks = 4;
        return MapOpenAttempt.none();
    }

    private Optional<Integer> findCompletedOpening(Minecraft client, RouteStep target) {
        Integer selectedMapId = readPendingSlotMapId(client);
        List<Integer> verifiedCandidates = new ArrayList<>();
        for (ObservedMap observed : scanner.scanFilledMaps(client)) {
            if (!pendingOpening.knownMapIds().contains(observed.mapId())
                    && matchesOpeningTarget(observed, target)) {
                verifiedCandidates.add(observed.mapId());
            }
        }
        if (selectedMapId != null && verifiedCandidates.contains(selectedMapId)) {
            return Optional.of(selectedMapId);
        }
        if (verifiedCandidates.size() == 1) {
            return Optional.of(verifiedCandidates.getFirst());
        }
        Set<Integer> newMapIds = new HashSet<>(currentFilledMapIds(client));
        newMapIds.removeAll(pendingOpening.knownMapIds());
        if (selectedMapId != null && newMapIds.contains(selectedMapId)) {
            return Optional.of(selectedMapId);
        }
        if (newMapIds.size() == 1) {
            return Optional.of(newMapIds.iterator().next());
        }
        return Optional.empty();
    }

    private boolean matchesOpeningTarget(ObservedMap observed, RouteStep target) {
        if (observed.scale() != 0 || !observed.dimension().equals(target.region().dimension())) {
            return false;
        }
        MapRegion projected = MapRegionMath.regionForBlock(
                observed.dimension(),
                target.region().scale(),
                observed.centerX(),
                observed.centerZ()
        );
        return projected.signature().equals(target.region().signature());
    }

    private Set<Integer> currentFilledMapIds(Minecraft client) {
        if (client.player == null) {
            return Set.of();
        }
        return scanner.scanFilledMapIds(client.player);
    }

    private void moveInventoryMapToHotbar(Minecraft client, int inventorySlot, int hotbarSlot) {
        if (client.gameMode == null || client.player == null
                || !isSafeInventoryContext(client, client.player)) {
            return;
        }
        int containerSlot = findPlayerInventoryMenuSlot(client.player, inventorySlot);
        if (containerSlot < 0) {
            return;
        }
        client.gameMode.handleContainerInput(
                client.player.inventoryMenu.containerId,
                containerSlot,
                hotbarSlot,
                ContainerInput.SWAP,
                client.player
        );
        scanner.invalidateInventorySnapshot();
    }

    private Integer readPendingSlotMapId(Minecraft client) {
        if (client.player == null || pendingOpening == null) {
            return null;
        }
        ItemStack stack = pendingOpening.hand() == InteractionHand.OFF_HAND
                ? client.player.getOffhandItem()
                : client.player.getInventory().getNonEquipmentItems().get(pendingOpening.hotbarSlot());
        if (!stack.is(Items.FILLED_MAP)) {
            return null;
        }
        return InventoryMapIds.readMapId(stack);
    }

    private boolean isSafeInventoryContext(Minecraft client, LocalPlayer player) {
        return client.gui.screen() == null && player.containerMenu == player.inventoryMenu;
    }

    private int findPlayerInventoryMenuSlot(LocalPlayer player, int inventorySlot) {
        for (int menuSlot = 0; menuSlot < player.inventoryMenu.slots.size(); menuSlot++) {
            Slot slot = player.inventoryMenu.slots.get(menuSlot);
            if (slot.container == player.getInventory() && slot.getContainerSlot() == inventorySlot) {
                return menuSlot;
            }
        }
        return -1;
    }

    private record PendingOpening(
            String regionSignature,
            Set<Integer> knownMapIds,
            InteractionHand hand,
            int hotbarSlot,
            int ticksRemaining
    ) {
        PendingOpening tick() {
            return new PendingOpening(regionSignature, knownMapIds, hand, hotbarSlot, ticksRemaining - 1);
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
