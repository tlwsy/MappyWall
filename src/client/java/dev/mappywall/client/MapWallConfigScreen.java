package dev.mappywall.client;

import dev.mappywall.core.RunMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public final class MapWallConfigScreen extends Screen {
    private final MappyWallRuntime runtime;
    private int scale;
    private int wallWidth = 2;
    private int wallHeight = 2;
    private RunMode mode = RunMode.MANUAL;

    public MapWallConfigScreen(MappyWallRuntime runtime) {
        super(Text.translatable("screen.mappywall.config.title"));
        this.runtime = runtime;
        this.scale = runtime.defaultScale();
    }

    @Override
    protected void init() {
        int left = this.width / 2 - 100;
        int y = this.height / 2 - 72;

        addDrawableChild(ButtonWidget.builder(label("screen.mappywall.scale", scale), button -> {
            scale = (scale + 1) % 5;
            button.setMessage(label("screen.mappywall.scale", scale));
        }).dimensions(left, y, 200, 20).build());

        addDrawableChild(ButtonWidget.builder(label("screen.mappywall.width", wallWidth), button -> {
            wallWidth = clampDimension(wallWidth + 1);
            button.setMessage(label("screen.mappywall.width", wallWidth));
        }).dimensions(left, y + 24, 98, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("-"), button -> {
            wallWidth = clampDimension(wallWidth - 1);
            rebuildWidgets();
        }).dimensions(left + 102, y + 24, 20, 20).build());

        addDrawableChild(ButtonWidget.builder(label("screen.mappywall.height", wallHeight), button -> {
            wallHeight = clampDimension(wallHeight + 1);
            button.setMessage(label("screen.mappywall.height", wallHeight));
        }).dimensions(left, y + 48, 98, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("-"), button -> {
            wallHeight = clampDimension(wallHeight - 1);
            rebuildWidgets();
        }).dimensions(left + 102, y + 48, 20, 20).build());

        ButtonWidget modeButton = ButtonWidget.builder(Text.literal("Mode: " + mode.name()), button ->
                MinecraftClient.getInstance().player.sendMessage(Text.translatable("message.mappywall.auto_disabled"), false)
        ).dimensions(left, y + 72, 200, 20).build();
        modeButton.active = false;
        addDrawableChild(modeButton);

        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.mappywall.start"), button -> {
            runtime.startManualRun(MinecraftClient.getInstance(), scale, wallWidth, wallHeight);
            close();
        }).dimensions(left, y + 104, 200, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.mappywall.close"), button -> close())
                .dimensions(left, y + 128, 200, 20)
                .build());
    }

    @Override
    public void render(DrawContext graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, this.height / 2 - 104, 0xFFFFFFFF);
        graphics.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("screen.mappywall.auto_locked"), this.width / 2, this.height / 2 + 8, 0xFFAAAAAA);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void rebuildWidgets() {
        clearChildren();
        init();
    }

    private Text label(String key, int value) {
        return Text.translatable(key).append(": " + value);
    }

    private int clampDimension(int value) {
        return Math.max(1, Math.min(64, value));
    }
}
