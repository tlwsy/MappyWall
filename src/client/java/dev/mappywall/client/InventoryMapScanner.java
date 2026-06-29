package dev.mappywall.client;

import dev.mappywall.core.ObservedMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
        for (ItemStack stack : MinecraftCompat.mainStacks(player.getInventory())) {
            if (stack.isOf(Items.MAP)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    public int findHotbarEmptyMap(ClientPlayerEntity player) {
        List<ItemStack> stacks = MinecraftCompat.mainStacks(player.getInventory());
        for (int slot = 0; slot < 9; slot++) {
            if (stacks.get(slot).isOf(Items.MAP)) {
                return slot;
            }
        }
        return -1;
    }

    public int findInventoryEmptyMap(ClientPlayerEntity player) {
        List<ItemStack> stacks = MinecraftCompat.mainStacks(player.getInventory());
        for (int slot = 9; slot < stacks.size(); slot++) {
            if (stacks.get(slot).isOf(Items.MAP)) {
                return slot;
            }
        }
        return -1;
    }

    public Set<Integer> scanFilledMapIds(ClientPlayerEntity player) {
        Set<Integer> ids = new HashSet<>();
        for (ItemStack stack : MinecraftCompat.mainStacks(player.getInventory())) {
            if (!stack.isOf(Items.FILLED_MAP)) {
                continue;
            }

            Integer mapId = InventoryMapIds.readMapId(stack);
            if (mapId != null) {
                ids.add(mapId);
            }
        }
        return ids;
    }

    public List<ObservedMap> scanFilledMaps(MinecraftClient client) {
        List<ObservedMap> observed = new ArrayList<>();
        if (client.world == null || client.player == null) {
            return observed;
        }

        String dimension = client.world.getRegistryKey().getValue().toString();
        for (ItemStack stack : MinecraftCompat.mainStacks(client.player.getInventory())) {
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

    private double exploredFraction(MapState data) {
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
