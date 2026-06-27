package dev.mappywall.client;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.item.ItemStack;

public final class InventoryMapIds {
    private InventoryMapIds() {
    }

    public static Integer readMapId(ItemStack stack) {
        MapIdComponent mapId = stack.get(DataComponentTypes.MAP_ID);
        return mapId == null ? null : mapId.id();
    }
}
