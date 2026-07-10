package dev.mappywall.client;

import dev.mappywall.core.ObservedMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

public final class InventoryMapScanner {
    public int countEmptyMaps(LocalPlayer player) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (stack.is(Items.MAP)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    public int findHotbarEmptyMap(LocalPlayer player) {
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().getNonEquipmentItems().get(slot).is(Items.MAP)) {
                return slot;
            }
        }
        return -1;
    }

    public int findInventoryEmptyMap(LocalPlayer player) {
        for (int slot = 9; slot < player.getInventory().getNonEquipmentItems().size(); slot++) {
            if (player.getInventory().getNonEquipmentItems().get(slot).is(Items.MAP)) {
                return slot;
            }
        }
        return -1;
    }

    public Set<Integer> scanFilledMapIds(LocalPlayer player) {
        Set<Integer> ids = new HashSet<>();
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (!stack.is(Items.FILLED_MAP)) {
                continue;
            }

            Integer mapId = InventoryMapIds.readMapId(stack);
            if (mapId != null) {
                ids.add(mapId);
            }
        }
        return ids;
    }

    public List<ObservedMap> scanFilledMaps(Minecraft client) {
        List<ObservedMap> observed = new ArrayList<>();
        if (client.level == null || client.player == null) {
            return observed;
        }

        String dimension = client.level.dimension().identifier().toString();
        for (ItemStack stack : client.player.getInventory().getNonEquipmentItems()) {
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

            observed.add(new ObservedMap(
                    mapId.id(),
                    dimension,
                    data.scale,
                    data.centerX,
                    data.centerZ,
                    exploredFraction(data)
            ));
        }
        return observed;
    }

    private double exploredFraction(MapItemSavedData data) {
        if (data.colors.length == 0) {
            return -1.0;
        }
        int explored = 0;
        for (byte color : data.colors) {
            if ((color & 0xFF) != 0) {
                explored++;
            }
        }
        return explored / (double) data.colors.length;
    }
}
