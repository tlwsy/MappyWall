package dev.mappywall.client;

import dev.mappywall.core.ProjectStatus;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public final class MapWallTasksScreen extends Screen {
    private static final int MAX_VISIBLE_TASKS = 5;

    private final MappyWallRuntime runtime;
    private List<MappyWallRuntime.ProjectListItem> tasks = List.of();

    public MapWallTasksScreen(MappyWallRuntime runtime) {
        super(Text.translatable("screen.mappywall.tasks.title"));
        this.runtime = runtime;
    }

    @Override
    protected void init() {
        reloadTasks();
        int left = Math.max(8, this.width / 2 - 176);
        int y = Math.max(24, this.height / 2 - 104);

        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.mappywall.new_task"), button ->
                runtime.openNewProjectScreen(MinecraftClient.getInstance())
        ).dimensions(left, y, 96, 20).build());

        ButtonWidget pauseButton = ButtonWidget.builder(Text.translatable("screen.mappywall.pause_resume"), button -> {
            runtime.togglePause(MinecraftClient.getInstance());
            refresh();
        }).dimensions(left + 104, y, 96, 20).build();
        pauseButton.active = runtime.hasActiveProject();
        addDrawableChild(pauseButton);

        ButtonWidget stopButton = ButtonWidget.builder(Text.translatable("screen.mappywall.stop_hide"), button -> {
            runtime.stopActiveProject(MinecraftClient.getInstance());
            refresh();
        }).dimensions(left + 208, y, 144, 20).build();
        stopButton.active = runtime.hasActiveProject();
        addDrawableChild(stopButton);

        int taskY = y + 42;
        int visibleTasks = visibleTaskCount(taskY);
        for (int i = 0; i < Math.min(visibleTasks, tasks.size()); i++) {
            MappyWallRuntime.ProjectListItem task = tasks.get(i);
            boolean completed = task.status() == ProjectStatus.COMPLETE;
            Text actionText = completed
                    ? Text.translatable("screen.mappywall.print_order")
                    : Text.translatable("screen.mappywall.activate");
            ButtonWidget actionButton = ButtonWidget.builder(actionText, button -> {
                if (completed) {
                    runtime.printHangingOrder(MinecraftClient.getInstance(), task.id());
                    return;
                }
                runtime.activateProject(MinecraftClient.getInstance(), task.id());
                refresh();
            }).dimensions(left + 232, taskY + i * 32, 56, 20).build();
            actionButton.active = completed || !task.active();
            addDrawableChild(actionButton);

            ButtonWidget deleteButton = ButtonWidget.builder(Text.translatable("screen.mappywall.delete"), button -> {
                runtime.deleteProject(MinecraftClient.getInstance(), task.id());
                refresh();
            }).dimensions(left + 296, taskY + i * 32, 56, 20).build();
            deleteButton.active = !task.active();
            addDrawableChild(deleteButton);
        }

        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.mappywall.close"), button -> close())
                .dimensions(this.width / 2 - 100, closeButtonY(y), 200, 20)
                .build());
    }

    @Override
    public void render(DrawContext graphics, int mouseX, int mouseY, float partialTick) {
        int left = Math.max(8, this.width / 2 - 176);
        int y = Math.max(24, this.height / 2 - 104);
        graphics.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, y - 24, 0xFFFFFFFF);
        graphics.drawTextWithShadow(this.textRenderer,
                Text.translatable("screen.mappywall.tasks.count").append(": " + tasks.size()),
                left,
                y + 28,
                0xFFFFFFFF);

        if (tasks.isEmpty()) {
            graphics.drawTextWithShadow(this.textRenderer,
                    Text.translatable("screen.mappywall.tasks.empty").formatted(Formatting.GRAY),
                    left,
                    y + 60,
                    0xFFFFFFFF);
        }

        int visibleTasks = visibleTaskCount(y + 42);
        for (int i = 0; i < Math.min(visibleTasks, tasks.size()); i++) {
            MappyWallRuntime.ProjectListItem task = tasks.get(i);
            int rowY = y + 42 + i * 32;
            Text headline = Text.literal(shortId(task.id()) + "  "
                    + task.width() + "x" + task.height()
                    + " S" + task.scale()
                    + "  " + task.completedSteps() + "/" + task.totalSteps()
                    + "  " + task.status().name());
            if (task.active()) {
                headline = headline.copy().formatted(Formatting.AQUA);
            }
            graphics.drawTextWithShadow(this.textRenderer, headline, left, rowY, 0xFFFFFFFF);
            graphics.drawTextWithShadow(this.textRenderer,
                    Text.translatable("screen.mappywall.current_step").append(": " + task.targetText()).formatted(Formatting.GRAY),
                    left,
                    rowY + 11,
                    0xFFFFFFFF);
        }

        if (tasks.size() > visibleTasks) {
            graphics.drawTextWithShadow(this.textRenderer,
                    Text.translatable("screen.mappywall.tasks.more").append(": " + (tasks.size() - visibleTasks)),
                    left,
                    y + 42 + visibleTasks * 32,
                    0xFFFFFFFF);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void refresh() {
        clearChildren();
        init();
    }

    private void reloadTasks() {
        tasks = runtime.listProjects(MinecraftClient.getInstance());
    }

    private int visibleTaskCount(int taskY) {
        int closeY = closeButtonY(Math.max(24, this.height / 2 - 104));
        int availableRows = Math.max(1, (closeY - taskY - 10) / 32);
        return Math.min(MAX_VISIBLE_TASKS, availableRows);
    }

    private int closeButtonY(int y) {
        return Math.min(this.height - 24, Math.max(y + 92, this.height - 28));
    }

    private String shortId(String id) {
        return id.length() <= 8 ? id : id.substring(0, 8);
    }
}
