package dev.mappywall.client;

import dev.mappywall.core.ObservedMap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.item.MapItem;

public final class InventoryMapScanner {
    public int countEmptyMaps(LocalPlayer player) {
        int count = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(Items.MAP)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    public int findHotbarEmptyMap(LocalPlayer player) {
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().items.get(slot).is(Items.MAP)) {
                return slot;
            }
        }
        return -1;
    }

    public int findInventoryEmptyMap(LocalPlayer player) {
        for (int slot = 9; slot < player.getInventory().items.size(); slot++) {
            if (player.getInventory().items.get(slot).is(Items.MAP)) {
                return slot;
            }
        }
        return -1;
    }

    public List<ObservedMap> scanFilledMaps(Minecraft client) {
        List<ObservedMap> observed = new ArrayList<>();
        if (client.level == null || client.player == null) {
            return observed;
        }

        String dimension = client.level.dimension().location().toString();
        for (ItemStack stack : client.player.getInventory().items) {
            if (!stack.is(Items.FILLED_MAP)) {
                continue;
            }

            MapId mapId = stack.get(DataComponents.MAP_ID);
            if (mapId == null) {
                continue;
            }

            MapItemSavedData data = MapItem.getSavedData(mapId, client.level);
            if (data == null) {
                continue;
            }

            observed.add(new ObservedMap(mapId.id(), dimension, data.scale, data.centerX, data.centerZ));
        }
        return observed;
    }
}

