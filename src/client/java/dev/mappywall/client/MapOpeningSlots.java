package dev.mappywall.client;

import dev.mappywall.core.PendingMapOpening;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

final class MapOpeningSlots {
    static final int INVALID_SLOT = -2;

    private MapOpeningSlots() {
    }

    static int expectedOutputSlot(LocalPlayer player, InteractionHand hand) {
        ItemStack source = hand == InteractionHand.OFF_HAND
                ? player.getOffhandItem()
                : player.getMainHandItem();
        if (!player.hasInfiniteMaterials() && source.getCount() == 1) {
            return hand == InteractionHand.OFF_HAND
                    ? PendingMapOpening.OFFHAND_SLOT
                    : player.getInventory().getSelectedSlot();
        }
        for (int slot = 0; slot < player.getInventory().getNonEquipmentItems().size(); slot++) {
            if (player.getInventory().getNonEquipmentItems().get(slot).isEmpty()) {
                return slot;
            }
        }
        return INVALID_SLOT;
    }

    static ItemStack stackAt(LocalPlayer player, int slot) {
        if (slot == PendingMapOpening.OFFHAND_SLOT) {
            return player.getOffhandItem();
        }
        if (slot < 0 || slot >= player.getInventory().getNonEquipmentItems().size()) {
            return ItemStack.EMPTY;
        }
        return player.getInventory().getNonEquipmentItems().get(slot);
    }
}
