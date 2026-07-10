package dev.mappywall.client;

import dev.mappywall.core.AutomationStyle;
import dev.mappywall.core.PostOpenMode;
import dev.mappywall.core.RunMode;
import dev.mappywall.core.WallAnchorMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

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
    private EditBox widthField;
    private EditBox heightField;
    private Button automationStyleButton;
    private Button postOpenButton;

    public MapWallConfigScreen(MappyWallRuntime runtime) {
        super(Component.translatable("screen.mappywall.config.title"));
        this.runtime = runtime;
        this.scale = runtime.defaultScale();
    }

    @Override
    protected void init() {
        int left = this.width / 2 - 100;
        int y = Math.max(8, this.height / 2 - 120);

        addRenderableWidget(Button.builder(label("screen.mappywall.scale", scale), button -> {
            scale = (scale + 1) % 5;
            button.setMessage(label("screen.mappywall.scale", scale));
        }).bounds(left, y, 200, 20).build());

        addRenderableWidget(Button.builder(Component.literal("-"), button -> setWidthValue(readWidthValue() - 1))
                .bounds(left + 84, y + 24, 20, 20)
                .build());

        widthField = dimensionField(label("screen.mappywall.width", wallWidth), wallWidth, left + 108, y + 24);
        addRenderableWidget(widthField);

        addRenderableWidget(Button.builder(Component.literal("+"), button -> setWidthValue(readWidthValue() + 1))
                .bounds(left + 180, y + 24, 20, 20)
                .build());

        addRenderableWidget(Button.builder(Component.literal("-"), button -> setHeightValue(readHeightValue() - 1))
                .bounds(left + 84, y + 48, 20, 20)
                .build());

        heightField = dimensionField(label("screen.mappywall.height", wallHeight), wallHeight, left + 108, y + 48);
        addRenderableWidget(heightField);

        addRenderableWidget(Button.builder(Component.literal("+"), button -> setHeightValue(readHeightValue() + 1))
                .bounds(left + 180, y + 48, 20, 20)
                .build());

        addRenderableWidget(Button.builder(anchorLabel(), button -> {
            anchorMode = anchorMode == WallAnchorMode.FIRST_REGION ? WallAnchorMode.CENTER : WallAnchorMode.FIRST_REGION;
            button.setMessage(anchorLabel());
        }).bounds(left, y + 72, 200, 20).build());

        addRenderableWidget(Button.builder(columnDirectionLabel(), button -> {
            columnStepX = -columnStepX;
            button.setMessage(columnDirectionLabel());
        }).bounds(left, y + 96, 98, 20).build());

        addRenderableWidget(Button.builder(rowDirectionLabel(), button -> {
            rowStepZ = -rowStepZ;
            button.setMessage(rowDirectionLabel());
        }).bounds(left + 102, y + 96, 98, 20).build());

        addRenderableWidget(Button.builder(modeLabel(), button -> {
            mode = nextMode(mode);
            button.setMessage(modeLabel());
            updateModeDependentControls();
        }).bounds(left, y + 120, 98, 20).build());

        automationStyleButton = addRenderableWidget(Button.builder(automationStyleLabel(), button -> {
            automationStyle = automationStyle == AutomationStyle.NORMAL
                    ? AutomationStyle.AGGRESSIVE
                    : AutomationStyle.NORMAL;
            button.setMessage(automationStyleLabel());
        }).bounds(left + 102, y + 120, 98, 20)
                .tooltip(Tooltip.create(Component.translatable("screen.mappywall.automation_style_tooltip")))
                .build());

        postOpenButton = addRenderableWidget(Button.builder(postOpenLabel(), button -> {
            postOpenMode = postOpenMode == PostOpenMode.OPEN_FIRST
                    ? PostOpenMode.FILL_AFTER_OPEN
                    : PostOpenMode.OPEN_FIRST;
            button.setMessage(postOpenLabel());
        }).bounds(left, y + 144, 200, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("screen.mappywall.start"), button -> {
            wallWidth = readWidthValue();
            wallHeight = readHeightValue();
            runtime.startRun(
                    Minecraft.getInstance(),
                    scale,
                    wallWidth,
                    wallHeight,
                    mode,
                    anchorMode,
                    columnStepX,
                    rowStepZ,
                    mode.isAutomatic() ? postOpenMode : PostOpenMode.OPEN_FIRST,
                    mode.isAutomatic() ? automationStyle : AutomationStyle.NORMAL
            );
            onClose();
        }).bounds(left, y + 168, 200, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("screen.mappywall.close"), button -> onClose())
                .bounds(left, y + 192, 200, 20)
                .build());
        updateModeDependentControls();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int y = Math.max(8, this.height / 2 - 120);
        graphics.centeredText(this.font, this.title, this.width / 2, y - 18, 0xFFFFFFFF);
        if (this.height >= 258) {
            graphics.centeredText(this.font, Component.translatable("screen.mappywall.auto_walk_note"), this.width / 2, y + 218, 0xFFAAAAAA);
        }
        if (this.height >= 270) {
            graphics.centeredText(this.font, Component.translatable("screen.mappywall.scale_note"), this.width / 2, y + 230, 0xFFAAAAAA);
        }
        graphics.text(this.font, Component.translatable("screen.mappywall.width"), this.width / 2 - 100, y + 30, 0xFFFFFFFF, true);
        graphics.text(this.font, Component.translatable("screen.mappywall.height"), this.width / 2 - 100, y + 54, 0xFFFFFFFF, true);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private Component label(String key, int value) {
        return Component.translatable(key).append(": " + value);
    }

    private Component modeLabel() {
        return Component.translatable("screen.mappywall.mode_short").append(": ").append(Component.translatable(modeKey(mode)));
    }

    private Component automationStyleLabel() {
        String key = automationStyle == AutomationStyle.NORMAL
                ? "screen.mappywall.automation_style_normal"
                : "screen.mappywall.automation_style_aggressive";
        return Component.translatable("screen.mappywall.automation_style_short").append(": ").append(Component.translatable(key));
    }

    private Component postOpenLabel() {
        String key = postOpenMode == PostOpenMode.OPEN_FIRST
                ? "screen.mappywall.post_open_open_first"
                : "screen.mappywall.post_open_fill_after_open";
        return Component.translatable("screen.mappywall.post_open").append(": ").append(Component.translatable(key));
    }

    private void updateModeDependentControls() {
        boolean automatic = mode.isAutomatic();
        if (automationStyleButton != null) {
            automationStyleButton.active = automatic;
            automationStyleButton.setMessage(automationStyleLabel());
        }
        if (postOpenButton != null) {
            postOpenButton.active = automatic;
            postOpenButton.setMessage(postOpenLabel());
        }
    }

    private Component anchorLabel() {
        String key = anchorMode == WallAnchorMode.FIRST_REGION
                ? "screen.mappywall.anchor_first_region"
                : "screen.mappywall.anchor_center";
        return Component.translatable("screen.mappywall.anchor").append(": ").append(Component.translatable(key));
    }

    private Component columnDirectionLabel() {
        String key = columnStepX > 0 ? "screen.mappywall.direction_east" : "screen.mappywall.direction_west";
        return Component.translatable("screen.mappywall.columns").append(": ").append(Component.translatable(key));
    }

    private Component rowDirectionLabel() {
        String key = rowStepZ > 0 ? "screen.mappywall.direction_south" : "screen.mappywall.direction_north";
        return Component.translatable("screen.mappywall.rows").append(": ").append(Component.translatable(key));
    }

    private EditBox dimensionField(Component label, int value, int x, int y) {
        EditBox field = new EditBox(this.font, x, y, 68, 20, label);
        field.setMaxLength(2);
        field.setValue(Integer.toString(value));
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

    private int readDimension(EditBox field, int fallback) {
        if (field == null || field.getValue().isBlank()) {
            return fallback;
        }
        try {
            return clampDimension(Integer.parseInt(field.getValue()));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private void setWidthValue(int value) {
        wallWidth = clampDimension(value);
        if (widthField != null) {
            widthField.setValue(Integer.toString(wallWidth));
        }
    }

    private void setHeightValue(int value) {
        wallHeight = clampDimension(value);
        if (heightField != null) {
            heightField.setValue(Integer.toString(wallHeight));
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
