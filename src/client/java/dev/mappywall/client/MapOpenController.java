package dev.mappywall.client;

import dev.mappywall.core.RouteStep;
import java.util.Optional;
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
    private final InventoryMapScanner scanner;
    private int cooldownTicks;

    public MapOpenController(InventoryMapScanner scanner) {
        this.scanner = scanner;
    }

    public Optional<Integer> tryOpenMapAtTarget(MinecraftClient client, RouteStep target) {
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

        client.interactionManager.interactItem(player, Hand.MAIN_HAND);
        cooldownTicks = 20;

        ItemStack after = player.getMainHandStack();
        if (after.isOf(Items.FILLED_MAP)) {
            Integer mapId = InventoryMapIds.readMapId(after);
            return mapId == null ? Optional.empty() : Optional.of(mapId);
        }
        return Optional.empty();
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
}
