package dev.mappywall.client;

import dev.mappywall.core.RouteStep;
import dev.mappywall.core.ObservedMap;
import dev.mappywall.core.MapRegion;
import dev.mappywall.core.MapRegionMath;
import dev.mappywall.core.OpenedMapIdResolver;
import java.util.Optional;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class MapOpenController {
    private static final int OPEN_WAIT_TICKS = 120;
    private static final int WRONG_REGION_RETRY_COOLDOWN_TICKS = 10;
    private final InventoryMapScanner scanner;
    private final OpenedMapIdResolver openedMapIdResolver = new OpenedMapIdResolver();
    private int cooldownTicks;
    private PendingOpening pendingOpening;
    private String stableOpeningRegion;
    private int stableOpeningTicks;

    public MapOpenController(InventoryMapScanner scanner) {
        this.scanner = scanner;
    }

    public void reset() {
        cooldownTicks = 0;
        pendingOpening = null;
        stableOpeningRegion = null;
        stableOpeningTicks = 0;
    }

    public MapOpenAttempt tryOpenMapInRegion(Minecraft client, RouteStep target) {
        if (pendingOpening != null) {
            PendingOpening pending = pendingOpening;
            if (!pending.regionSignature().equals(target.region().signature())) {
                pendingOpening = null;
                resetStableOpeningPosition();
                cooldownTicks = Math.max(cooldownTicks, 4);
                return MapOpenAttempt.none();
            }
            Optional<Integer> completed = findCompletedOpening(client, target);
            if (completed.isPresent()) {
                pendingOpening = null;
                cooldownTicks = 10;
                return MapOpenAttempt.opened(completed.get());
            }
            Optional<Integer> rejected = findRejectedOpening(client, target);
            if (rejected.isPresent()) {
                pendingOpening = null;
                resetStableOpeningPosition();
                cooldownTicks = WRONG_REGION_RETRY_COOLDOWN_TICKS;
                scanner.invalidateInventorySnapshot();
                if (client.player != null) {
                    client.player.sendSystemMessage(Component.translatable(
                            "message.mappywall.open_wrong_region_retry",
                            rejected.get()
                    ).withStyle(ChatFormatting.YELLOW));
                }
                return MapOpenAttempt.none();
            }
            PendingOpening nextPending = pending.tick();
            pendingOpening = nextPending;
            if (nextPending.ticksRemaining() <= 0) {
                pendingOpening = null;
                resetStableOpeningPosition();
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
        if (player == null
                || client.gameMode == null
                || !hasStableOpeningPosition(client, target)
                || !isSafeInventoryContext(client, player)) {
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

        int expectedOutputSlot = MapOpeningSlots.expectedOutputSlot(player, openingHand);
        if (expectedOutputSlot == MapOpeningSlots.INVALID_SLOT) {
            cooldownTicks = 40;
            return MapOpenAttempt.none();
        }
        Set<Integer> knownMapIds = currentFilledMapIds(client);
        InteractionResult useResult = client.gameMode.useItem(player, openingHand);
        if (!useResult.consumesAction()) {
            cooldownTicks = 8;
            return MapOpenAttempt.none();
        }
        scanner.invalidateInventorySnapshot();
        pendingOpening = new PendingOpening(
                target.region().signature(),
                knownMapIds,
                expectedOutputSlot,
                OPEN_WAIT_TICKS
        );
        cooldownTicks = 4;
        return MapOpenAttempt.none();
    }

    private Optional<Integer> findCompletedOpening(Minecraft client, RouteStep target) {
        Integer selectedMapId = readPendingSlotMapId(client);
        Optional<Integer> openedMapId = openedMapIdResolver.resolve(
                pendingOpening.knownMapIds(),
                currentFilledMapIds(client),
                selectedMapId
        );
        if (openedMapId.isEmpty()) {
            return Optional.empty();
        }

        // Vanilla does not transmit map center coordinates to clients. Most
        // client-side MapItemSavedData objects therefore contain placeholder 0,0
        // centers. A future reliable observation may veto the captured position,
        // but an ordinary placeholder must not reject a newly allocated id.
        Optional<ObservedMap> reliableObservation = scanner.scanFilledMaps(client).stream()
                .filter(observed -> observed.mapId() == openedMapId.get())
                .filter(ObservedMap::regionReliable)
                .findFirst();
        if (reliableObservation.isPresent() && !matchesOpeningTarget(reliableObservation.get(), target)) {
            return Optional.empty();
        }
        return openedMapId;
    }

    private Optional<Integer> findRejectedOpening(Minecraft client, RouteStep target) {
        Optional<Integer> openedMapId = openedMapIdResolver.resolve(
                pendingOpening.knownMapIds(),
                currentFilledMapIds(client),
                readPendingSlotMapId(client)
        );
        if (openedMapId.isEmpty()) {
            return Optional.empty();
        }
        return scanner.scanFilledMaps(client).stream()
                .filter(observed -> observed.mapId() == openedMapId.get())
                .filter(ObservedMap::regionReliable)
                .filter(observed -> !matchesOpeningTarget(observed, target))
                .map(ObservedMap::mapId)
                .findFirst();
    }

    /**
     * Mirrors vanilla empty-map centering: first derive the scale-0 map created at
     * the player's position, then project that map center into the route scale.
     */
    public boolean canOpenAtCurrentPosition(Minecraft client, RouteStep target) {
        if (client.player == null || client.level == null) {
            return false;
        }
        String dimension = client.level.dimension().identifier().toString();
        if (!dimension.equals(target.region().dimension())) {
            return false;
        }
        return positionMapsToTarget(dimension, client.player.getX(), client.player.getZ(), target);
    }

    private boolean hasStableOpeningPosition(Minecraft client, RouteStep target) {
        if (client.player == null || client.level == null || !isSafeInventoryContext(client, client.player)) {
            resetStableOpeningPosition();
            return false;
        }
        String dimension = client.level.dimension().identifier().toString();
        double predictedX = client.player.getX() + client.player.getDeltaMovement().x * 3.0;
        double predictedZ = client.player.getZ() + client.player.getDeltaMovement().z * 3.0;
        boolean safeNow = positionMapsToTarget(
                dimension,
                client.player.getX(),
                client.player.getZ(),
                target
        );
        boolean safePredicted = positionMapsToTarget(dimension, predictedX, predictedZ, target);
        String signature = target.region().signature();
        if (!safeNow || !safePredicted) {
            resetStableOpeningPosition();
            return false;
        }
        if (signature.equals(stableOpeningRegion)) {
            stableOpeningTicks++;
        } else {
            stableOpeningRegion = signature;
            stableOpeningTicks = 1;
        }
        return stableOpeningTicks >= 3;
    }

    private void resetStableOpeningPosition() {
        stableOpeningRegion = null;
        stableOpeningTicks = 0;
    }

    private boolean positionMapsToTarget(String dimension, double x, double z, RouteStep target) {
        if (!dimension.equals(target.region().dimension())) {
            return false;
        }
        MapRegion scaleZeroRegion = MapRegionMath.regionForBlock(dimension, 0, x, z);
        MapRegion projected = MapRegionMath.regionForBlock(
                dimension,
                target.region().scale(),
                scaleZeroRegion.centerX(),
                scaleZeroRegion.centerZ()
        );
        return projected.signature().equals(target.region().signature());
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
        ItemStack stack = MapOpeningSlots.stackAt(client.player, pendingOpening.expectedOutputSlot());
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
            int expectedOutputSlot,
            int ticksRemaining
    ) {
        PendingOpening tick() {
            return new PendingOpening(regionSignature, knownMapIds, expectedOutputSlot, ticksRemaining - 1);
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
