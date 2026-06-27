package dev.mappywall.client;

import dev.mappywall.core.RouteStep;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class MapOpenController {
    private static final int HOTBAR_CONTAINER_OFFSET = 36;
    private final InventoryMapScanner scanner;
    private int cooldownTicks;

    public MapOpenController(InventoryMapScanner scanner) {
        this.scanner = scanner;
    }

    public Optional<Integer> tryOpenMapAtTarget(Minecraft client, RouteStep target) {
        if (cooldownTicks > 0) {
            cooldownTicks--;
            return Optional.empty();
        }

        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null) {
            return Optional.empty();
        }

        int hotbarSlot = scanner.findHotbarEmptyMap(player);
        if (hotbarSlot < 0) {
            int inventorySlot = scanner.findInventoryEmptyMap(player);
            if (inventorySlot >= 0) {
                moveInventoryMapToHotbar(client, inventorySlot, player.getInventory().selected);
                cooldownTicks = 8;
                return Optional.empty();
            }
            player.displayClientMessage(Component.literal("MappyWall needs an empty map for "
                    + target.wallPos().column() + ", " + target.wallPos().row()).withStyle(ChatFormatting.YELLOW), false);
            cooldownTicks = 40;
            return Optional.empty();
        }

        player.getInventory().selected = hotbarSlot;
        ItemStack before = player.getMainHandItem();
        if (!before.is(Items.MAP)) {
            cooldownTicks = 8;
            return Optional.empty();
        }

        client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        cooldownTicks = 20;

        ItemStack after = player.getMainHandItem();
        if (after.is(Items.FILLED_MAP)) {
            Integer mapId = InventoryMapIds.readMapId(after);
            return mapId == null ? Optional.empty() : Optional.of(mapId);
        }
        return Optional.empty();
    }

    private void moveInventoryMapToHotbar(Minecraft client, int inventorySlot, int hotbarSlot) {
        if (client.gameMode == null || client.player == null) {
            return;
        }
        int containerSlot = inventorySlot < 9 ? HOTBAR_CONTAINER_OFFSET + inventorySlot : inventorySlot;
        client.gameMode.handleInventoryMouseClick(
                client.player.containerMenu.containerId,
                containerSlot,
                hotbarSlot,
                ClickType.SWAP,
                client.player
        );
    }
}

