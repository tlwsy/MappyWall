package dev.mappywall.client;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapId;

public final class InventoryMapIds {
    private InventoryMapIds() {
    }

    public static Integer readMapId(ItemStack stack) {
        MapId mapId = stack.get(DataComponents.MAP_ID);
        return mapId == null ? null : mapId.id();
    }
}

