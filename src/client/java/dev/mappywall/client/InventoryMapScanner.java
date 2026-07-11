package dev.mappywall.client;

import dev.mappywall.core.ObservedMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private static final int COVERAGE_REFRESH_TICKS = 5;

    private final Map<Integer, CoverageSnapshot> coverageByMapId = new LinkedHashMap<>();
    private Object cachedLevel;
    private long cachedGameTime = Long.MIN_VALUE;
    private List<ObservedMap> cachedObservedMaps = List.of();

    public int countEmptyMaps(LocalPlayer player) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (stack.is(Items.MAP)) {
                count += stack.getCount();
            }
        }
        if (player.getOffhandItem().is(Items.MAP)) {
            count += player.getOffhandItem().getCount();
        }
        return count;
    }

    public int findHotbarEmptyMap(LocalPlayer player) {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getNonEquipmentItems().get(slot);
            if (stack.is(Items.MAP) && stack.getCount() == 1) {
                return slot;
            }
        }
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().getNonEquipmentItems().get(slot).is(Items.MAP)) {
                return slot;
            }
        }
        return -1;
    }

    public int findInventoryEmptyMap(LocalPlayer player) {
        for (int slot = 9; slot < player.getInventory().getNonEquipmentItems().size(); slot++) {
            ItemStack stack = player.getInventory().getNonEquipmentItems().get(slot);
            if (stack.is(Items.MAP) && stack.getCount() == 1) {
                return slot;
            }
        }
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
            addFilledMapId(ids, stack);
        }
        addFilledMapId(ids, player.getOffhandItem());
        return ids;
    }

    public List<ObservedMap> scanFilledMaps(Minecraft client) {
        if (client.level == null || client.player == null) {
            clearCache();
            return List.of();
        }

        long gameTime = client.level.getGameTime();
        if (cachedLevel == client.level && cachedGameTime == gameTime) {
            return cachedObservedMaps;
        }

        Map<Integer, ItemStack> uniqueStacks = new LinkedHashMap<>();
        for (ItemStack stack : client.player.getInventory().getNonEquipmentItems()) {
            addUniqueFilledMap(uniqueStacks, stack);
        }
        addUniqueFilledMap(uniqueStacks, client.player.getOffhandItem());

        List<ObservedMap> observed = new ArrayList<>(uniqueStacks.size());
        for (Map.Entry<Integer, ItemStack> entry : uniqueStacks.entrySet()) {
            observeFilledMap(client, entry.getValue()).ifPresent(observed::add);
        }
        cachedLevel = client.level;
        cachedGameTime = gameTime;
        cachedObservedMaps = List.copyOf(observed);
        return cachedObservedMaps;
    }

    public Optional<ObservedMap> observeFilledMap(Minecraft client, ItemStack stack) {
        if (client.level == null || !stack.is(Items.FILLED_MAP)) {
            return Optional.empty();
        }
        if (cachedLevel != null && cachedLevel != client.level) {
            clearCache();
        }
        MapId mapId = stack.get(DataComponents.MAP_ID);
        if (mapId == null) {
            return Optional.empty();
        }
        MapItemSavedData data = MapItem.getSavedData(mapId, client.level);
        if (data == null) {
            return Optional.empty();
        }

        long gameTime = client.level.getGameTime();
        CoverageSnapshot coverage = coverageByMapId.get(mapId.id());
        if (coverage == null
                || coverage.data() != data
                || gameTime - coverage.measuredAtGameTime() >= COVERAGE_REFRESH_TICKS) {
            coverage = new CoverageSnapshot(data, gameTime, exploredFraction(data));
            coverageByMapId.put(mapId.id(), coverage);
        }
        return Optional.of(new ObservedMap(
                mapId.id(),
                data.dimension.identifier().toString(),
                data.scale,
                data.centerX,
                data.centerZ,
                coverage.exploredFraction()
        ));
    }

    public boolean hasEmptyInventorySlot(LocalPlayer player) {
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public int findInventoryFilledMap(LocalPlayer player, int mapId) {
        for (int slot = 0; slot < player.getInventory().getNonEquipmentItems().size(); slot++) {
            if (InventoryMapIds.isFilledMapWithId(player.getInventory().getItem(slot), mapId)) {
                return slot;
            }
        }
        return -1;
    }

    public void clearCache() {
        cachedLevel = null;
        cachedGameTime = Long.MIN_VALUE;
        cachedObservedMaps = List.of();
        coverageByMapId.clear();
    }

    public void invalidateInventorySnapshot() {
        cachedGameTime = Long.MIN_VALUE;
        cachedObservedMaps = List.of();
    }

    private void addFilledMapId(Set<Integer> ids, ItemStack stack) {
        if (!stack.is(Items.FILLED_MAP)) {
            return;
        }
        Integer mapId = InventoryMapIds.readMapId(stack);
        if (mapId != null) {
            ids.add(mapId);
        }
    }

    private void addUniqueFilledMap(Map<Integer, ItemStack> stacks, ItemStack stack) {
        if (!stack.is(Items.FILLED_MAP)) {
            return;
        }
        Integer mapId = InventoryMapIds.readMapId(stack);
        if (mapId != null) {
            stacks.putIfAbsent(mapId, stack);
        }
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

    private record CoverageSnapshot(
            MapItemSavedData data,
            long measuredAtGameTime,
            double exploredFraction
    ) {
    }
}
