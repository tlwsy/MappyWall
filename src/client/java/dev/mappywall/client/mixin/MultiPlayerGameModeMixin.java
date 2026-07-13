package dev.mappywall.client.mixin;

import dev.mappywall.client.MappyWallClient;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
abstract class MultiPlayerGameModeMixin {
    @Unique
    private boolean mappywall$armedOpening;

    @Unique
    private boolean mappywall$armedZoom;

    @Inject(method = "useItem", at = @At("HEAD"), cancellable = true)
    private void mappywall$beforeUseItem(
            Player player,
            InteractionHand hand,
            CallbackInfoReturnable<InteractionResult> callback
    ) {
        mappywall$armedOpening = false;
        boolean hadPendingOpening = MappyWallClient.hasPendingMapOpening(player);
        if (!MappyWallClient.beforeUseItem(player, hand)) {
            callback.setReturnValue(InteractionResult.FAIL);
            return;
        }
        mappywall$armedOpening = !hadPendingOpening && MappyWallClient.hasPendingMapOpening(player);
    }

    @Inject(method = "useItem", at = @At("RETURN"))
    private void mappywall$afterUseItem(
            Player player,
            InteractionHand hand,
            CallbackInfoReturnable<InteractionResult> callback
    ) {
        if (mappywall$armedOpening && !callback.getReturnValue().consumesAction()) {
            MappyWallClient.cancelUnsentMapOpening(player);
        }
        mappywall$armedOpening = false;
    }

    @Inject(method = "handleContainerInput", at = @At("HEAD"), cancellable = true)
    private void mappywall$beforeContainerInput(
            int containerId,
            int slotId,
            int button,
            ContainerInput input,
            Player player,
            CallbackInfo callback
    ) {
        mappywall$armedZoom = false;
        boolean hadPendingZoom = MappyWallClient.hasPendingMapZoom(player);
        if (!MappyWallClient.beforeContainerInput(player, containerId, slotId, button, input)) {
            callback.cancel();
            return;
        }
        mappywall$armedZoom = !hadPendingZoom && MappyWallClient.hasPendingMapZoom(player);
    }

    @Inject(method = "handleContainerInput", at = @At("RETURN"))
    private void mappywall$afterContainerInput(
            int containerId,
            int slotId,
            int button,
            ContainerInput input,
            Player player,
            CallbackInfo callback
    ) {
        if (mappywall$armedZoom) {
            MappyWallClient.afterContainerInput(player);
        }
        mappywall$armedZoom = false;
    }
}
