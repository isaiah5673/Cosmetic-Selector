package net.mangomaucho.cosmeticselector;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class CosmeticSelectorClient implements ClientModInitializer {
    public static final String MOD_ID = "cosmetic_selector";

    private static KeyBinding openKey;
    private int ticks = 0;

    @Override
    public void onInitializeClient() {
        CosmeticConfig.load();

        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.cosmetic_selector.open",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                KeyBinding.Category.MISC));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new CosmeticScreen(null));
                }
            }
            // Re-check the resource packs every 10 seconds so a pack change is noticed.
            if (++ticks >= 200) {
                ticks = 0;
                if (client.getOverlay() == null) {
                    CosmeticRegistry.rescan();
                }
            }
        });
    }
}
