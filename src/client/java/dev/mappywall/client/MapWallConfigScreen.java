package dev.mappywall.client;

import dev.mappywall.core.RunMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class MapWallConfigScreen extends Screen {
    private final MappyWallRuntime runtime;
    private int scale;
    private int wallWidth = 2;
    private int wallHeight = 2;
    private RunMode mode = RunMode.MANUAL;

    public MapWallConfigScreen(MappyWallRuntime runtime) {
        super(Component.translatable("screen.mappywall.config.title"));
        this.runtime = runtime;
        this.scale = runtime.defaultScale();
    }

    @Override
    protected void init() {
        int left = this.width / 2 - 100;
        int y = this.height / 2 - 72;

        addRenderableWidget(Button.builder(label("screen.mappywall.scale", scale), button -> {
            scale = (scale + 1) % 5;
            button.setMessage(label("screen.mappywall.scale", scale));
        }).bounds(left, y, 200, 20).build());

        addRenderableWidget(Button.builder(label("screen.mappywall.width", wallWidth), button -> {
            wallWidth = clampDimension(wallWidth + 1);
            button.setMessage(label("screen.mappywall.width", wallWidth));
        }).bounds(left, y + 24, 98, 20).build());

        addRenderableWidget(Button.builder(Component.literal("-"), button -> {
            wallWidth = clampDimension(wallWidth - 1);
            rebuildWidgets();
        }).bounds(left + 102, y + 24, 20, 20).build());

        addRenderableWidget(Button.builder(label("screen.mappywall.height", wallHeight), button -> {
            wallHeight = clampDimension(wallHeight + 1);
            button.setMessage(label("screen.mappywall.height", wallHeight));
        }).bounds(left, y + 48, 98, 20).build());

        addRenderableWidget(Button.builder(Component.literal("-"), button -> {
            wallHeight = clampDimension(wallHeight - 1);
            rebuildWidgets();
        }).bounds(left + 102, y + 48, 20, 20).build());

        Button modeButton = Button.builder(Component.literal("Mode: " + mode.name()), button ->
                Minecraft.getInstance().player.displayClientMessage(Component.translatable("message.mappywall.auto_disabled"), false)
        ).bounds(left, y + 72, 200, 20).build();
        modeButton.active = false;
        addRenderableWidget(modeButton);

        addRenderableWidget(Button.builder(Component.translatable("screen.mappywall.start"), button -> {
            runtime.startManualRun(Minecraft.getInstance(), scale, wallWidth, wallHeight);
            onClose();
        }).bounds(left, y + 104, 200, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("screen.mappywall.close"), button -> onClose())
                .bounds(left, y + 128, 200, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 104, 0xFFFFFFFF);
        graphics.drawCenteredString(this.font, Component.translatable("screen.mappywall.auto_locked"), this.width / 2, this.height / 2 + 8, 0xFFAAAAAA);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void rebuildWidgets() {
        clearWidgets();
        init();
    }

    private Component label(String key, int value) {
        return Component.translatable(key).append(": " + value);
    }

    private int clampDimension(int value) {
        return Math.max(1, Math.min(64, value));
    }
}

