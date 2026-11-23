package net.mangomaucho.servergui;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class ServerGuiClient implements ClientModInitializer {
    public static KeyBinding openKey;
    private boolean menuKeyWasDown = false;

    @Override
    public void onInitializeClient() {
        // N by default; change it in Options > Controls > Key Binds
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.servergui.open",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_N,
                KeyBinding.Category.MISC));

        // remember chat so it can be shown with faces and bubbles
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->
                ChatLog.addChat(message, sender));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) ChatLog.addGame(message);
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // in game: the normal key binding
            while (openKey.wasPressed()) {
                if (client.currentScreen == null) client.setScreen(new ServerGuiScreen(null));
            }
            // on the title screen and server list, key bindings don't fire, so check the key directly
            boolean onMenu = client.currentScreen instanceof TitleScreen || client.currentScreen instanceof MultiplayerScreen;
            boolean down = onMenu && isMenuKeyDown(client);
            if (down && !menuKeyWasDown) client.setScreen(new ServerGuiScreen(client.currentScreen));
            menuKeyWasDown = down;

            if (client.world == null) ChatLog.clearIfLeft();
        });
    }

    private static boolean isMenuKeyDown(MinecraftClient client) {
        InputUtil.Key key = KeyBindingHelper.getBoundKeyOf(openKey);
        if (key.getCategory() != InputUtil.Type.KEYSYM || key.getCode() < 0) return false;
        return GLFW.glfwGetKey(client.getWindow().getHandle(), key.getCode()) == GLFW.GLFW_PRESS;
    }
}
