package dev.toolscreen.mobile.mixin;

import dev.toolscreen.mobile.ToolscreenMobile;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Slows the view while measuring, by scaling the look movement itself.
 *
 * <p>Minecraft's sensitivity option goes through a cubic curve with a floor -
 * {@code (s * 0.6 + 0.2)^3 * 8} - so from a normal setting of 30% even 0% only
 * slows the view to about a third. An earlier version adjusted that option and
 * appeared to do nothing, because it barely could. Scaling the movement where
 * it is applied has no floor and is linear: an aim speed of 0.25 is exactly a
 * quarter of the player's normal speed, whatever that is. It also never writes
 * to options.txt, so nothing can outlive the measuring mode.
 *
 * <p>Target verified from the remapped 1.16.1 jar's bytecode: {@code updateMouse}
 * calls {@code ClientPlayerEntity.changeLookDirection(DD)V} once.
 * {@code require = 0}: a failure costs the slow aim, not the launch, and
 * {@code ToolscreenMobile.lookScale} logs the first time it takes effect so a
 * silent failure is still visible.
 */
@Mixin(Mouse.class)
public abstract class MouseMixin {

    @Redirect(
            method = "updateMouse()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/network/ClientPlayerEntity;changeLookDirection(DD)V"),
            require = 0)
    private void toolscreen$scaleLook(ClientPlayerEntity player, double yaw, double pitch) {
        // getInstance rather than a shadowed field: a wrong @Shadow name
        // compiles cleanly and only fails when the game loads.
        double scale = ToolscreenMobile.lookScale(MinecraftClient.getInstance());
        player.changeLookDirection(yaw * scale, pitch * scale);
    }
}
