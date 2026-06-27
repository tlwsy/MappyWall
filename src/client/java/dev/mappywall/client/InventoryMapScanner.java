package dev.mappywall.client;

import dev.mappywall.core.ObservedMap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.map.MapState;

public final class InventoryMapScanner {
    public int countEmptyMaps(ClientPlayerEntity player) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getMainStacks()) {
            if (stack.isOf(Items.MAP)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    public int findHotbarEmptyMap(ClientPlayerEntity player) {
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().getMainStacks().get(slot).isOf(Items.MAP)) {
                return slot;
            }
        }
        return -1;
    }

    public int findInventoryEmptyMap(ClientPlayerEntity player) {
        for (int slot = 9; slot < player.getInventory().getMainStacks().size(); slot++) {
            if (player.getInventory().getMainStacks().get(slot).isOf(Items.MAP)) {
                return slot;
            }
        }
        return -1;
    }

    public List<ObservedMap> scanFilledMaps(MinecraftClient client) {
        List<ObservedMap> observed = new ArrayList<>();
        if (client.world == null || client.player == null) {
            return observed;
        }

        String dimension = client.world.getRegistryKey().getValue().toString();
        for (ItemStack stack : client.player.getInventory().getMainStacks()) {
            if (!stack.isOf(Items.FILLED_MAP)) {
                continue;
            }

            MapIdComponent mapId = stack.get(DataComponentTypes.MAP_ID);
            if (mapId == null) {
                continue;
            }

            MapState data = FilledMapItem.getMapState(mapId, client.world);
            if (data == null) {
                continue;
            }

            observed.add(new ObservedMap(mapId.id(), dimension, data.scale, data.centerX, data.centerZ));
        }
        return observed;
    }
}
