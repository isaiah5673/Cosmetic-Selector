package net.mangomaucho.servergui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;

/**
 * Waits a few seconds before rejoining the server, because many servers block you
 * ("reconnecting too quickly") if you come back right after leaving.
 */
public class ReconnectScreen extends Screen {
    private static final int WAIT_TICKS = 6 * 20;   // 6 seconds

    private final ServerInfo server;
    private int ticksLeft = WAIT_TICKS;

    public ReconnectScreen(ServerInfo server) {
        super(Text.literal("Reconnecting"));
        this.server = server;
    }

    @Override
    protected void init() {
        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), b -> {
            if (client != null) client.setScreen(new MultiplayerScreen(new TitleScreen(false)));
        }).dimensions(width / 2 - 100, height / 2 + 30, 200, 20).build());
    }

    @Override
    public void tick() {
        if (--ticksLeft <= 0 && client != null) {
            ConnectScreen.connect(new MultiplayerScreen(new TitleScreen(false)), client,
                    ServerAddress.parse(server.address), server, false, null);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks);
        int seconds = Math.max(1, (ticksLeft + 19) / 20);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("Reconnecting to " + server.name + "..."),
                width / 2, height / 2 - 20, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("Joining in " + seconds + " second" + (seconds == 1 ? "" : "s")),
                width / 2, height / 2 - 6, 0xFFAAAAAA);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
