package dev.mappywall.client;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class NavigationSettingsScreen extends Screen {
    private final MappyWallRuntime runtime;
    private final Screen parent;
    private boolean breakingEnabled;
    private AutoNavigationConfig.ListMode listMode;
    private Set<String> blockIds;
    private EditBox blockListField;
    private Component status;

    NavigationSettingsScreen(MappyWallRuntime runtime, Screen parent) {
        super(Component.translatable("screen.mappywall.navigation.title"));
        this.runtime = runtime;
        this.parent = parent;
        load(runtime.aggressiveNavigationConfig());
    }

    @Override
    protected void init() {
        int left = this.width / 2 - 160;
        int y = Math.max(28, this.height / 2 - 86);

        addRenderableWidget(Button.builder(breakingLabel(), button -> {
            breakingEnabled = !breakingEnabled;
            button.setMessage(breakingLabel());
        }).bounds(left, y, 156, 20)
                .tooltip(Tooltip.create(Component.translatable("screen.mappywall.navigation.breaking_tooltip")))
                .build());

        addRenderableWidget(Button.builder(listModeLabel(), button -> {
            listMode = listMode == AutoNavigationConfig.ListMode.WHITELIST
                    ? AutoNavigationConfig.ListMode.BLACKLIST
                    : AutoNavigationConfig.ListMode.WHITELIST;
            button.setMessage(listModeLabel());
        }).bounds(left + 164, y, 156, 20)
                .tooltip(Tooltip.create(Component.translatable("screen.mappywall.navigation.list_mode_tooltip")))
                .build());

        blockListField = new EditBox(
                this.font,
                left,
                y + 42,
                320,
                20,
                Component.translatable("screen.mappywall.navigation.block_list")
        );
        blockListField.setMaxLength(4096);
        blockListField.setValue(formatIds(blockIds));
        addRenderableWidget(blockListField);

        addRenderableWidget(Button.builder(Component.translatable("screen.mappywall.navigation.save"), button -> {
            Set<String> parsed = parseIds(blockListField.getValue());
            if (runtime.updateAggressiveBreakingConfig(breakingEnabled, listMode, parsed)) {
                blockIds = parsed;
                status = Component.translatable("screen.mappywall.navigation.saved").withStyle(ChatFormatting.GREEN);
            } else {
                status = Component.translatable("screen.mappywall.navigation.save_failed").withStyle(ChatFormatting.RED);
            }
        }).bounds(left, y + 72, 100, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("screen.mappywall.navigation.reset"), button -> {
            if (runtime.resetAggressiveBreakingConfig()) {
                load(runtime.aggressiveNavigationConfig());
                blockListField.setValue(formatIds(blockIds));
                clearWidgets();
                init();
                status = Component.translatable("screen.mappywall.navigation.reset_done").withStyle(ChatFormatting.GREEN);
            } else {
                status = Component.translatable("screen.mappywall.navigation.save_failed").withStyle(ChatFormatting.RED);
            }
        }).bounds(left + 110, y + 72, 100, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("screen.mappywall.close"), button -> onClose())
                .bounds(left + 220, y + 72, 100, 20)
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int y = Math.max(28, this.height / 2 - 86);
        graphics.centeredText(this.font, this.title, this.width / 2, y - 24, 0xFFFFFFFF);
        graphics.text(
                this.font,
                Component.translatable("screen.mappywall.navigation.block_list"),
                this.width / 2 - 160,
                y + 29,
                0xFFFFFFFF,
                true
        );
        graphics.centeredText(
                this.font,
                Component.translatable("screen.mappywall.navigation.priority_note").withStyle(ChatFormatting.GRAY),
                this.width / 2,
                y + 102,
                0xFFFFFFFF
        );
        if (status != null) {
            graphics.centeredText(this.font, status, this.width / 2, y + 116, 0xFFFFFFFF);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    private void load(AutoNavigationConfig config) {
        breakingEnabled = config.blockBreakingEnabled();
        listMode = config.breakListMode();
        blockIds = config.breakBlocks();
    }

    private Component breakingLabel() {
        return Component.translatable("screen.mappywall.navigation.breaking")
                .append(": ")
                .append(Component.translatable(breakingEnabled
                        ? "screen.mappywall.navigation.enabled"
                        : "screen.mappywall.navigation.disabled"));
    }

    private Component listModeLabel() {
        return Component.translatable("screen.mappywall.navigation.list_mode")
                .append(": ")
                .append(Component.translatable(listMode == AutoNavigationConfig.ListMode.WHITELIST
                        ? "screen.mappywall.navigation.whitelist"
                        : "screen.mappywall.navigation.blacklist"));
    }

    private Set<String> parseIds(String value) {
        LinkedHashSet<String> parsed = new LinkedHashSet<>();
        for (String raw : value.split("[\\s,;]+")) {
            String id = raw.trim().toLowerCase(Locale.ROOT);
            if (id.isEmpty()) {
                continue;
            }
            if (!id.contains(":")) {
                id = "minecraft:" + id;
            }
            if (id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                parsed.add(id);
            }
        }
        return Set.copyOf(parsed);
    }

    private String formatIds(Set<String> ids) {
        return ids.stream().sorted().collect(Collectors.joining(", "));
    }
}
