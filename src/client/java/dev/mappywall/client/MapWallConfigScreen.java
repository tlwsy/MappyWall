package dev.mappywall.client;

import dev.mappywall.core.AutomationStyle;
import dev.mappywall.core.PostOpenMode;
import dev.mappywall.core.RunMode;
import dev.mappywall.core.WallAnchorMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public final class MapWallConfigScreen extends Screen {
    private final MappyWallRuntime runtime;
    private int scale;
    private int wallWidth = 2;
    private int wallHeight = 2;
    private RunMode mode = RunMode.MANUAL;
    private PostOpenMode postOpenMode = PostOpenMode.OPEN_FIRST;
    private AutomationStyle automationStyle = AutomationStyle.NORMAL;
    private WallAnchorMode anchorMode = WallAnchorMode.FIRST_REGION;
    private int columnStepX = 1;
    private int rowStepZ = 1;
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
        int y = Math.max(8, this.height / 2 - 120);

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

        addDrawableChild(ButtonWidget.builder(anchorLabel(), button -> {
            anchorMode = anchorMode == WallAnchorMode.FIRST_REGION ? WallAnchorMode.CENTER : WallAnchorMode.FIRST_REGION;
            button.setMessage(anchorLabel());
        }).dimensions(left, y + 72, 200, 20).build());

        addDrawableChild(ButtonWidget.builder(columnDirectionLabel(), button -> {
            columnStepX = -columnStepX;
            button.setMessage(columnDirectionLabel());
        }).dimensions(left, y + 96, 98, 20).build());

        addDrawableChild(ButtonWidget.builder(rowDirectionLabel(), button -> {
            rowStepZ = -rowStepZ;
            button.setMessage(rowDirectionLabel());
        }).dimensions(left + 102, y + 96, 98, 20).build());

        addDrawableChild(ButtonWidget.builder(modeLabel(), button -> {
            mode = nextMode(mode);
            button.setMessage(modeLabel());
        }).dimensions(left, y + 120, 98, 20).build());

        addDrawableChild(ButtonWidget.builder(automationStyleLabel(), button -> {
            automationStyle = automationStyle == AutomationStyle.NORMAL
                    ? AutomationStyle.AGGRESSIVE
                    : AutomationStyle.NORMAL;
            button.setMessage(automationStyleLabel());
        }).dimensions(left + 102, y + 120, 98, 20)
                .tooltip(Tooltip.of(Text.translatable("screen.mappywall.automation_style_tooltip")))
                .build());

        addDrawableChild(ButtonWidget.builder(postOpenLabel(), button -> {
            postOpenMode = postOpenMode == PostOpenMode.OPEN_FIRST
                    ? PostOpenMode.FILL_AFTER_OPEN
                    : PostOpenMode.OPEN_FIRST;
            button.setMessage(postOpenLabel());
        }).dimensions(left, y + 144, 200, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.mappywall.start"), button -> {
            wallWidth = readWidthValue();
            wallHeight = readHeightValue();
            runtime.startRun(
                    MinecraftClient.getInstance(),
                    scale,
                    wallWidth,
                    wallHeight,
                    mode,
                    anchorMode,
                    columnStepX,
                    rowStepZ,
                    postOpenMode,
                    automationStyle
            );
            close();
        }).dimensions(left, y + 168, 200, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.mappywall.close"), button -> close())
                .dimensions(left, y + 192, 200, 20)
                .build());
    }

    @Override
    public void render(DrawContext graphics, int mouseX, int mouseY, float partialTick) {
        int y = Math.max(8, this.height / 2 - 120);
        graphics.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, y - 18, 0xFFFFFFFF);
        if (this.height >= 258) {
            graphics.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("screen.mappywall.auto_walk_note"), this.width / 2, y + 218, 0xFFAAAAAA);
        }
        if (this.height >= 270) {
            graphics.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("screen.mappywall.scale_note"), this.width / 2, y + 230, 0xFFAAAAAA);
        }
        graphics.drawTextWithShadow(this.textRenderer, Text.translatable("screen.mappywall.width"), this.width / 2 - 100, y + 30, 0xFFFFFFFF);
        graphics.drawTextWithShadow(this.textRenderer, Text.translatable("screen.mappywall.height"), this.width / 2 - 100, y + 54, 0xFFFFFFFF);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private Text label(String key, int value) {
        return Text.translatable(key).append(": " + value);
    }

    private Text modeLabel() {
        return Text.translatable("screen.mappywall.mode_short").append(": ").append(Text.translatable(modeKey(mode)));
    }

    private Text automationStyleLabel() {
        String key = automationStyle == AutomationStyle.NORMAL
                ? "screen.mappywall.automation_style_normal"
                : "screen.mappywall.automation_style_aggressive";
        return Text.translatable("screen.mappywall.automation_style_short").append(": ").append(Text.translatable(key));
    }

    private Text postOpenLabel() {
        String key = postOpenMode == PostOpenMode.OPEN_FIRST
                ? "screen.mappywall.post_open_open_first"
                : "screen.mappywall.post_open_fill_after_open";
        return Text.translatable("screen.mappywall.post_open").append(": ").append(Text.translatable(key));
    }

    private Text anchorLabel() {
        String key = anchorMode == WallAnchorMode.FIRST_REGION
                ? "screen.mappywall.anchor_first_region"
                : "screen.mappywall.anchor_center";
        return Text.translatable("screen.mappywall.anchor").append(": ").append(Text.translatable(key));
    }

    private Text columnDirectionLabel() {
        String key = columnStepX > 0 ? "screen.mappywall.direction_east" : "screen.mappywall.direction_west";
        return Text.translatable("screen.mappywall.columns").append(": ").append(Text.translatable(key));
    }

    private Text rowDirectionLabel() {
        String key = rowStepZ > 0 ? "screen.mappywall.direction_south" : "screen.mappywall.direction_north";
        return Text.translatable("screen.mappywall.rows").append(": ").append(Text.translatable(key));
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

    private RunMode nextMode(RunMode current) {
        return switch (current) {
            case MANUAL -> RunMode.AUTO_WALK;
            case AUTO_WALK -> RunMode.AUTO_ELYTRA;
            case AUTO_ELYTRA -> RunMode.MANUAL;
        };
    }

    private String modeKey(RunMode current) {
        return switch (current) {
            case MANUAL -> "screen.mappywall.mode_manual";
            case AUTO_WALK -> "screen.mappywall.mode_auto_walk";
            case AUTO_ELYTRA -> "screen.mappywall.mode_auto_elytra";
        };
    }
}
