package dev.mappywall.client;

import dev.mappywall.core.ProjectStatus;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class MapWallTasksScreen extends Screen {
    private static final int MAX_VISIBLE_TASKS = 5;

    private final MappyWallRuntime runtime;
    private List<MappyWallRuntime.ProjectListItem> tasks = List.of();
    private int taskOffset;

    public MapWallTasksScreen(MappyWallRuntime runtime) {
        super(Component.translatable("screen.mappywall.tasks.title"));
        this.runtime = runtime;
    }

    @Override
    protected void init() {
        reloadTasks();
        int left = Math.max(8, this.width / 2 - 176);
        int y = Math.max(24, this.height / 2 - 104);

        addRenderableWidget(Button.builder(Component.translatable("screen.mappywall.new_task"), button ->
                runtime.openNewProjectScreen(Minecraft.getInstance())
        ).bounds(left, y, 96, 20).build());

        Button pauseButton = Button.builder(Component.translatable("screen.mappywall.pause_resume"), button -> {
            runtime.togglePause(Minecraft.getInstance());
            refresh();
        }).bounds(left + 104, y, 96, 20).build();
        pauseButton.active = runtime.hasActiveProject();
        addRenderableWidget(pauseButton);

        Button stopButton = Button.builder(Component.translatable("screen.mappywall.stop_hide"), button -> {
            runtime.stopActiveProject(Minecraft.getInstance());
            refresh();
        }).bounds(left + 208, y, 144, 20).build();
        stopButton.active = runtime.hasActiveProject();
        addRenderableWidget(stopButton);

        int taskY = y + 42;
        int visibleTasks = visibleTaskCount(taskY);
        taskOffset = clampTaskOffset(taskOffset, visibleTasks);
        Button previousButton = Button.builder(Component.literal("<"), button -> {
            taskOffset = Math.max(0, taskOffset - visibleTasks);
            refresh();
        }).bounds(left + 264, y + 22, 40, 18).build();
        previousButton.active = taskOffset > 0;
        addRenderableWidget(previousButton);

        Button nextButton = Button.builder(Component.literal(">"), button -> {
            taskOffset = Math.min(maxTaskOffset(visibleTasks), taskOffset + visibleTasks);
            refresh();
        }).bounds(left + 312, y + 22, 40, 18).build();
        nextButton.active = taskOffset + visibleTasks < tasks.size();
        addRenderableWidget(nextButton);

        int rowsOnPage = Math.min(visibleTasks, tasks.size() - taskOffset);
        for (int i = 0; i < rowsOnPage; i++) {
            MappyWallRuntime.ProjectListItem task = tasks.get(taskOffset + i);
            boolean completed = task.status() == ProjectStatus.COMPLETE;
            Component actionText = completed
                    ? Component.translatable("screen.mappywall.print_order")
                    : Component.translatable("screen.mappywall.activate");
            Button actionButton = Button.builder(actionText, button -> {
                if (completed) {
                    runtime.printHangingOrder(Minecraft.getInstance(), task.id());
                    return;
                }
                runtime.activateProject(Minecraft.getInstance(), task.id());
                refresh();
            }).bounds(left + 232, taskY + i * 32, 56, 20).build();
            actionButton.active = completed || !task.active();
            addRenderableWidget(actionButton);

            Button deleteButton = Button.builder(Component.translatable("screen.mappywall.delete"), button -> {
                runtime.deleteProject(Minecraft.getInstance(), task.id());
                refresh();
            }).bounds(left + 296, taskY + i * 32, 56, 20).build();
            deleteButton.active = !task.active();
            addRenderableWidget(deleteButton);
        }

        addRenderableWidget(Button.builder(Component.translatable("screen.mappywall.close"), button -> onClose())
                .bounds(this.width / 2 - 100, closeButtonY(y), 200, 20)
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int left = Math.max(8, this.width / 2 - 176);
        int y = Math.max(24, this.height / 2 - 104);
        graphics.centeredText(this.font, this.title, this.width / 2, y - 24, 0xFFFFFFFF);
        graphics.text(this.font,
                Component.translatable("screen.mappywall.tasks.count").append(": " + tasks.size()),
                left,
                y + 28,
                0xFFFFFFFF,
                true);

        if (tasks.isEmpty()) {
            graphics.text(this.font,
                    Component.translatable("screen.mappywall.tasks.empty").withStyle(ChatFormatting.GRAY),
                    left,
                    y + 60,
                    0xFFFFFFFF,
                    true);
        }

        int visibleTasks = visibleTaskCount(y + 42);
        int rowsOnPage = Math.min(visibleTasks, tasks.size() - taskOffset);
        for (int i = 0; i < rowsOnPage; i++) {
            MappyWallRuntime.ProjectListItem task = tasks.get(taskOffset + i);
            int rowY = y + 42 + i * 32;
            Component headline = Component.literal(shortId(task.id()) + "  "
                    + task.width() + "x" + task.height()
                    + " S" + task.scale()
                    + "  " + task.completedSteps() + "/" + task.totalSteps()
                    + "  " + localizedPostOpenMode(task)
                    + "  " + localizedAutomationStyle(task)
                    + "  " + task.status().name());
            if (task.active()) {
                headline = headline.copy().withStyle(ChatFormatting.AQUA);
            }
            graphics.text(this.font, headline, left, rowY, 0xFFFFFFFF, true);
            graphics.text(this.font,
                    Component.translatable("screen.mappywall.current_step").append(": " + task.targetText()).withStyle(ChatFormatting.GRAY),
                    left,
                    rowY + 11,
                    0xFFFFFFFF,
                    true);
        }

        if (tasks.size() > visibleTasks) {
            graphics.text(this.font,
                    Component.translatable("screen.mappywall.tasks.more").append(": "
                            + (taskOffset + 1) + "-" + (taskOffset + rowsOnPage) + "/" + tasks.size()),
                    left,
                    y + 42 + visibleTasks * 32,
                    0xFFFFFFFF,
                    true);
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void refresh() {
        clearWidgets();
        init();
    }

    private void reloadTasks() {
        tasks = runtime.listProjects(Minecraft.getInstance());
    }

    private int visibleTaskCount(int taskY) {
        int closeY = closeButtonY(Math.max(24, this.height / 2 - 104));
        int availableRows = Math.max(1, (closeY - taskY - 10) / 32);
        return Math.min(MAX_VISIBLE_TASKS, availableRows);
    }

    private int clampTaskOffset(int requestedOffset, int pageSize) {
        return Math.max(0, Math.min(requestedOffset, maxTaskOffset(pageSize)));
    }

    private int maxTaskOffset(int pageSize) {
        if (tasks.size() <= pageSize) {
            return 0;
        }
        return ((tasks.size() - 1) / pageSize) * pageSize;
    }

    private int closeButtonY(int y) {
        return Math.min(this.height - 24, Math.max(y + 92, this.height - 28));
    }

    private String shortId(String id) {
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    private String localizedPostOpenMode(MappyWallRuntime.ProjectListItem task) {
        String key = switch (task.postOpenMode()) {
            case OPEN_FIRST -> "screen.mappywall.post_open_open_first";
            case FILL_AFTER_OPEN -> "screen.mappywall.post_open_fill_after_open";
        };
        return Component.translatable(key).getString();
    }

    private String localizedAutomationStyle(MappyWallRuntime.ProjectListItem task) {
        String key = switch (task.automationStyle()) {
            case NORMAL -> "screen.mappywall.automation_style_normal";
            case AGGRESSIVE -> "screen.mappywall.automation_style_aggressive";
        };
        return Component.translatable(key).getString();
    }
}
