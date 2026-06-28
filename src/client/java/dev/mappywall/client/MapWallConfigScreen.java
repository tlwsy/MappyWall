package dev.mappywall.client;

import dev.mappywall.core.RunMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public final class MapWallConfigScreen extends Screen {
    private final MappyWallRuntime runtime;
    private int scale;
    private int wallWidth = 2;
    private int wallHeight = 2;
    private RunMode mode = RunMode.MANUAL;
    private TextFieldWidget widthField;
    private TextFieldWidget heightField;

    public MapWallConfigScreen(MappyWallRuntime runtime) {
        super(Text.translatable("screen.mappywall.config.title"));
        this.runtime = runtime;
        this.scale = runtime.defaultScale();
    }

    @Override
    protected void init() {
        int left = this.width / 2 - 100;
        int y = Math.max(24, this.height / 2 - 92);

        addDrawableChild(ButtonWidget.builder(label("screen.mappywall.scale", scale), button -> {
            scale = (scale + 1) % 5;
            button.setMessage(label("screen.mappywall.scale", scale));
        }).dimensions(left, y, 200, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("-"), button -> setWidthValue(readWidthValue() - 1))
                .dimensions(left + 84, y + 24, 20, 20)
                .build());

        widthField = dimensionField(label("screen.mappywall.width", wallWidth), wallWidth, left + 108, y + 24);
        addDrawableChild(widthField);

        addDrawableChild(ButtonWidget.builder(Text.literal("+"), button -> setWidthValue(readWidthValue() + 1))
                .dimensions(left + 180, y + 24, 20, 20)
                .build());

        addDrawableChild(ButtonWidget.builder(Text.literal("-"), button -> setHeightValue(readHeightValue() - 1))
                .dimensions(left + 84, y + 48, 20, 20)
                .build());

        heightField = dimensionField(label("screen.mappywall.height", wallHeight), wallHeight, left + 108, y + 48);
        addDrawableChild(heightField);

        addDrawableChild(ButtonWidget.builder(Text.literal("+"), button -> setHeightValue(readHeightValue() + 1))
                .dimensions(left + 180, y + 48, 20, 20)
                .build());

        addDrawableChild(ButtonWidget.builder(modeLabel(), button -> {
            mode = mode == RunMode.MANUAL ? RunMode.AUTO_WALK : RunMode.MANUAL;
            button.setMessage(modeLabel());
        }).dimensions(left, y + 72, 200, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.mappywall.start"), button -> {
            wallWidth = readWidthValue();
            wallHeight = readHeightValue();
            runtime.startRun(MinecraftClient.getInstance(), scale, wallWidth, wallHeight, mode);
            close();
        }).dimensions(left, y + 104, 200, 20).build());

        ButtonWidget pauseButton = ButtonWidget.builder(Text.translatable("screen.mappywall.pause_resume"), button ->
                runtime.togglePause(MinecraftClient.getInstance())
        ).dimensions(left, y + 128, 200, 20).build();
        pauseButton.active = runtime.hasActiveProject();
        addDrawableChild(pauseButton);

        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.mappywall.close"), button -> close())
                .dimensions(left, y + 152, 200, 20)
                .build());
    }

    @Override
    public void render(DrawContext graphics, int mouseX, int mouseY, float partialTick) {
        int y = Math.max(24, this.height / 2 - 92);
        graphics.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, y - 18, 0xFFFFFFFF);
        graphics.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("screen.mappywall.auto_walk_note"), this.width / 2, y + 82, 0xFFAAAAAA);
        if (this.height >= 230) {
            graphics.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("screen.mappywall.scale_note"), this.width / 2, y + 178, 0xFFAAAAAA);
        }
        graphics.drawTextWithShadow(this.textRenderer, Text.translatable("screen.mappywall.width"), this.width / 2 - 100, y + 30, 0xFFFFFFFF);
        graphics.drawTextWithShadow(this.textRenderer, Text.translatable("screen.mappywall.height"), this.width / 2 - 100, y + 54, 0xFFFFFFFF);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private Text label(String key, int value) {
        return Text.translatable(key).append(": " + value);
    }

    private Text modeLabel() {
        return Text.translatable("screen.mappywall.mode").append(": " + mode.name());
    }

    private TextFieldWidget dimensionField(Text label, int value, int x, int y) {
        TextFieldWidget field = new TextFieldWidget(this.textRenderer, x, y, 68, 20, label);
        field.setMaxLength(2);
        field.setText(Integer.toString(value));
        field.setTextPredicate(text -> text.isEmpty() || text.chars().allMatch(Character::isDigit));
        return field;
    }

    private int readWidthValue() {
        wallWidth = readDimension(widthField, wallWidth);
        setWidthValue(wallWidth);
        return wallWidth;
    }

    private int readHeightValue() {
        wallHeight = readDimension(heightField, wallHeight);
        setHeightValue(wallHeight);
        return wallHeight;
    }

    private int readDimension(TextFieldWidget field, int fallback) {
        if (field == null || field.getText().isBlank()) {
            return fallback;
        }
        try {
            return clampDimension(Integer.parseInt(field.getText()));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private void setWidthValue(int value) {
        wallWidth = clampDimension(value);
        if (widthField != null) {
            widthField.setText(Integer.toString(wallWidth));
        }
    }

    private void setHeightValue(int value) {
        wallHeight = clampDimension(value);
        if (heightField != null) {
            heightField.setText(Integer.toString(wallHeight));
        }
    }

    private int clampDimension(int value) {
        return Math.max(1, Math.min(64, value));
    }
}
