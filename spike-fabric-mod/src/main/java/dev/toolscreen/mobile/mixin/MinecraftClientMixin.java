package dev.toolscreen.mobile.mixin;

import dev.toolscreen.mobile.Measurement;
import dev.toolscreen.mobile.ToolscreenScreen;
import dev.toolscreen.mobile.ToolscreenMobile;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Polls the mode-cycle, measurement-report and settings-menu keys once per tick.
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

    @Unique
    private boolean toolscreen$reportWasDown;

    @Unique
    private boolean toolscreen$menuWasDown;

    @Inject(method = "tick", at = @At("HEAD"))
    private void toolscreen$pollModeKey(CallbackInfo ci) {
        MinecraftClient client = (MinecraftClient) (Object) this;
        if (client.getWindow() == null) return;

        // Nothing fires while a screen is open. These are plain key codes rather
        // than key bindings, so without this they would also trigger from the
        // chat box and from this mod's own menu - a comma typed in chat would
        // reopen it, and the toggle would cycle modes mid-sentence.
        if (client.currentScreen != null) {
            this.toolscreen$toggleWasDown = false;
            this.toolscreen$reportWasDown = false;
            this.toolscreen$menuWasDown = false;
            return;
        }

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

        boolean report = InputUtil.isKeyPressed(client.getWindow().getHandle(),
                ToolscreenMobile.reportKey());
        if (report && !this.toolscreen$reportWasDown) {
            Measurement.report(client);
        }
        this.toolscreen$reportWasDown = report;

        boolean menu = InputUtil.isKeyPressed(client.getWindow().getHandle(),
                ToolscreenMobile.menuKey());
        if (menu && !this.toolscreen$menuWasDown) {
            client.openScreen(new ToolscreenScreen());
        }
        this.toolscreen$menuWasDown = menu;
    }
}
