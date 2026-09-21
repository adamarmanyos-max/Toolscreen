package dev.toolscreen.mobile.mixin;

import dev.toolscreen.mobile.ToolscreenMobile;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Polls the mode-cycle key once per client tick.
 *
 * <p>Polling rather than using Fabric API's key-binding helper keeps this mod's
 * dependencies to loader + yarn, so there is no Fabric API version to pin
 * against 1.16.1 and one less jar to move onto the device.
 *
 * <p>On iOS the key is pressed by an Amethyst on-screen button: its custom
 * control system emits GLFW key codes, so a plain key code doubles as a touch
 * binding. That is the replacement for Toolscreen's global hotkeys, which have
 * no iOS equivalent.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {

    @Unique
    private boolean toolscreen$toggleWasDown;

    @Inject(method = "tick", at = @At("HEAD"))
    private void toolscreen$pollModeKey(CallbackInfo ci) {
        MinecraftClient client = (MinecraftClient) (Object) this;
        if (client.getWindow() == null) return;

        boolean down = InputUtil.isKeyPressed(client.getWindow().getHandle(), ToolscreenMobile.toggleKey());

        // Edge-triggered: cycle once per press, not once per tick held.
        if (down && !this.toolscreen$toggleWasDown) {
            ToolscreenMobile.cycleMode();
            // Forces Minecraft to resize its render target against the new
            // reported dimensions; without this the change lands only on the
            // next genuine resize.
            client.onResolutionChanged();
        }
        this.toolscreen$toggleWasDown = down;
    }
}
