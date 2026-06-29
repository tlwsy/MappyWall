package dev.mappywall.client;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Identifier;
import net.minecraft.world.HeightLimitView;

final class MinecraftCompat {
    private static final String CATEGORY_TRANSLATION_KEY = "key.categories.mappywall.controls";

    private MinecraftCompat() {
    }

    @SuppressWarnings("unchecked")
    static List<ItemStack> mainStacks(PlayerInventory inventory) {
        try {
            Method method = PlayerInventory.class.getMethod("getMainStacks");
            return (List<ItemStack>) method.invoke(inventory);
        } catch (NoSuchMethodException ignored) {
            return mainStacksField(inventory);
        } catch (IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException("Could not read player inventory main stacks.", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ItemStack> mainStacksField(PlayerInventory inventory) {
        try {
            Field field = PlayerInventory.class.getField("main");
            return (List<ItemStack>) field.get(inventory);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not read player inventory main stacks.", exception);
        }
    }

    static int selectedSlot(PlayerInventory inventory) {
        try {
            Method method = PlayerInventory.class.getMethod("getSelectedSlot");
            return (int) method.invoke(inventory);
        } catch (NoSuchMethodException ignored) {
            return selectedSlotField(inventory);
        } catch (IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException("Could not read selected hotbar slot.", exception);
        }
    }

    private static int selectedSlotField(PlayerInventory inventory) {
        try {
            Field field = PlayerInventory.class.getField("selectedSlot");
            return field.getInt(inventory);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not read selected hotbar slot.", exception);
        }
    }

    static void setSelectedSlot(PlayerInventory inventory, int slot) {
        try {
            Method method = PlayerInventory.class.getMethod("setSelectedSlot", int.class);
            method.invoke(inventory, slot);
        } catch (NoSuchMethodException ignored) {
            setSelectedSlotField(inventory, slot);
        } catch (IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException("Could not set selected hotbar slot.", exception);
        }
    }

    private static void setSelectedSlotField(PlayerInventory inventory, int slot) {
        try {
            Field field = PlayerInventory.class.getField("selectedSlot");
            field.setInt(inventory, slot);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not set selected hotbar slot.", exception);
        }
    }

    static int topYInclusive(HeightLimitView world) {
        return world.getBottomY() + world.getHeight() - 1;
    }

    static KeyBinding createKeyBinding(String translationKey, int keyCode) {
        try {
            Class<?> categoryClass = Class.forName("net.minecraft.client.option.KeyBinding$Category");
            Object category = categoryClass
                    .getMethod("create", Identifier.class)
                    .invoke(null, Identifier.of(MappyWallClient.MOD_ID, "controls"));
            Constructor<KeyBinding> constructor = KeyBinding.class.getConstructor(
                    String.class,
                    InputUtil.Type.class,
                    int.class,
                    categoryClass
            );
            return constructor.newInstance(translationKey, InputUtil.Type.KEYSYM, keyCode, category);
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
            return createLegacyKeyBinding(translationKey, keyCode);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException("Could not create key binding.", exception);
        }
    }

    private static KeyBinding createLegacyKeyBinding(String translationKey, int keyCode) {
        try {
            Constructor<KeyBinding> constructor = KeyBinding.class.getConstructor(
                    String.class,
                    InputUtil.Type.class,
                    int.class,
                    String.class
            );
            return constructor.newInstance(translationKey, InputUtil.Type.KEYSYM, keyCode, CATEGORY_TRANSLATION_KEY);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not create key binding.", exception);
        }
    }

    static boolean isGliding(ClientPlayerEntity player) {
        Boolean gliding = invokeBoolean(player, "isGliding");
        if (gliding != null) {
            return gliding;
        }
        Boolean fallFlying = invokeBoolean(player, "isFallFlying");
        return fallFlying != null && fallFlying;
    }

    static void startGliding(ClientPlayerEntity player) {
        invokeVoid(player, "startGliding");
    }

    static void sendLookAndOnGround(ClientPlayerEntity player, float yaw, float pitch) {
        try {
            Constructor<PlayerMoveC2SPacket.LookAndOnGround> constructor =
                    PlayerMoveC2SPacket.LookAndOnGround.class.getConstructor(
                            float.class,
                            float.class,
                            boolean.class,
                            boolean.class
                    );
            player.networkHandler.sendPacket(constructor.newInstance(
                    yaw,
                    pitch,
                    player.isOnGround(),
                    player.horizontalCollision
            ));
        } catch (NoSuchMethodException ignored) {
            sendLegacyLookAndOnGround(player, yaw, pitch);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not send look packet.", exception);
        }
    }

    private static void sendLegacyLookAndOnGround(ClientPlayerEntity player, float yaw, float pitch) {
        try {
            Constructor<PlayerMoveC2SPacket.LookAndOnGround> constructor =
                    PlayerMoveC2SPacket.LookAndOnGround.class.getConstructor(float.class, float.class, boolean.class);
            player.networkHandler.sendPacket(constructor.newInstance(yaw, pitch, player.isOnGround()));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not send look packet.", exception);
        }
    }

    static void sendPlayerInput(
            ClientPlayerEntity player,
            boolean forward,
            boolean back,
            boolean left,
            boolean right,
            boolean jump,
            boolean sneak,
            boolean sprint
    ) {
        try {
            Class<?> inputClass = Class.forName("net.minecraft.util.PlayerInput");
            Constructor<?> inputConstructor = inputClass.getConstructor(
                    boolean.class,
                    boolean.class,
                    boolean.class,
                    boolean.class,
                    boolean.class,
                    boolean.class,
                    boolean.class
            );
            Object input = inputConstructor.newInstance(forward, back, left, right, jump, sneak, sprint);
            Constructor<PlayerInputC2SPacket> packetConstructor = PlayerInputC2SPacket.class.getConstructor(inputClass);
            player.networkHandler.sendPacket(packetConstructor.newInstance(input));
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
            sendLegacyPlayerInput(player, forward, back, left, right, jump, sneak);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not send player input packet.", exception);
        }
    }

    private static void sendLegacyPlayerInput(
            ClientPlayerEntity player,
            boolean forward,
            boolean back,
            boolean left,
            boolean right,
            boolean jump,
            boolean sneak
    ) {
        try {
            Constructor<PlayerInputC2SPacket> constructor = PlayerInputC2SPacket.class.getConstructor(
                    float.class,
                    float.class,
                    boolean.class,
                    boolean.class
            );
            float sideways = left == right ? 0.0F : left ? 1.0F : -1.0F;
            float forwardValue = forward == back ? 0.0F : forward ? 1.0F : -1.0F;
            Packet<?> packet = constructor.newInstance(sideways, forwardValue, jump, sneak);
            player.networkHandler.sendPacket(packet);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not send player input packet.", exception);
        }
    }

    static void setBoatControls(Entity boat, boolean pressingLeft, boolean pressingRight, boolean pressingForward) {
        invokeVoid(boat, "setInputs", false, false, pressingForward, false);
        invokeVoid(boat, "setPaddlesMoving", pressingLeft, pressingRight);
    }

    private static Boolean invokeBoolean(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return (Boolean) method.invoke(target);
        } catch (NoSuchMethodException ignored) {
            return null;
        } catch (IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException("Could not call " + methodName + ".", exception);
        }
    }

    private static void invokeVoid(Object target, String methodName, Object... args) {
        Method method = findMethod(target.getClass(), methodName, args.length);
        if (method == null) {
            return;
        }
        try {
            method.invoke(target, args);
        } catch (IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException("Could not call " + methodName + ".", exception);
        }
    }

    private static Method findMethod(Class<?> type, String methodName, int parameterCount) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(methodName) && method.getParameterCount() == parameterCount) {
                return method;
            }
        }
        return null;
    }
}
