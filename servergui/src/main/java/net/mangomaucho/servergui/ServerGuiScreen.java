package net.mangomaucho.servergui;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.ServerList;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ServerGUI: a main page with Servers and Chat,
 * a Servers page (your favorites, 5 per page) and a Chat page (faces + speech bubbles + a big message box).
 */
public class ServerGuiScreen extends Screen {
    private enum Page { MAIN, SERVERS, CHAT }

    private static final int TITLE_COLOR = 0x6EE0C0;     // same light green-blue as Cosmetic Selector
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFAAAAAA;
    private static final int RED = 0xFFE02020;
    private static final int BUTTON_W = 260;
    private static final int PER_PAGE = 5;
    private static final Identifier UNKNOWN_SERVER = Identifier.ofVanilla("textures/misc/unknown_server.png");
    private static final Map<String, Identifier> FAVICONS = new HashMap<>();

    private final Screen parent;
    private Page page = Page.MAIN;
    private int serverPage = 0;
    private int chatScroll = 0;           // how many pixels scrolled up from the newest message
    private String notice = null;         // a short message under the title

    private record Icon(ItemStack stack, int x, int y) {}
    private record Favicon(Identifier id, int x, int y) {}
    private final List<Icon> icons = new ArrayList<>();
    private final List<Favicon> favicons = new ArrayList<>();
    private ButtonWidget chatButton;
    private TextFieldWidget messageBox;

    public ServerGuiScreen(Screen parent) {
        super(Text.literal("ServerGUI"));
        this.parent = parent;
    }

    public boolean isChatOpen() {
        return page == Page.CHAT;
    }

    @Override
    protected void init() {
        icons.clear();
        favicons.clear();
        chatButton = null;
        messageBox = null;
        switch (page) {
            case MAIN -> initMain();
            case SERVERS -> initServers();
            case CHAT -> initChat();
        }
    }

    private void open(Page p) {
        page = p;
        notice = null;
        if (p == Page.CHAT) {
            ChatLog.markRead();
            chatScroll = 0;
        }
        clearAndInit();
    }

    // ---------- main page ----------

    private void initMain() {
        int x = width / 2 - BUTTON_W / 2;
        int y = 54;
        addIconButton(x, y, "Servers", new ItemStack(Items.COMPASS), b -> { serverPage = 0; open(Page.SERVERS); });
        chatButton = addIconButton(x, y + 26, "Chat", new ItemStack(Items.WRITABLE_BOOK), b -> open(Page.CHAT));
        chatButton.active = client != null && client.getNetworkHandler() != null;

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                .dimensions(width / 2 - 100, height - 28, 200, 20).build());
    }

    /** A wide button with a small picture just left of its name. */
    private ButtonWidget addIconButton(int x, int y, String label, ItemStack icon, ButtonWidget.PressAction action) {
        ButtonWidget b = ButtonWidget.builder(Text.literal(label), action).dimensions(x, y, BUTTON_W, 20).build();
        addDrawableChild(b);
        int textW = textRenderer.getWidth(label);
        icons.add(new Icon(icon, x + BUTTON_W / 2 - textW / 2 - 20, y + 2));
        return b;
    }

    // ---------- servers page ----------

    private void initServers() {
        List<ServerInfo> servers = new ArrayList<>();
        try {
            ServerList list = new ServerList(client);
            list.loadFile();
            for (int i = 0; i < list.size(); i++) servers.add(list.get(i));   // top favorite first
        } catch (Exception e) {
            notice = "Couldn't read your server list.";
        }

        int pages = Math.max(1, (servers.size() + PER_PAGE - 1) / PER_PAGE);
        serverPage = Math.max(0, Math.min(serverPage, pages - 1));
        int x = width / 2 - BUTTON_W / 2;
        int top = 48;
        int rowH = 26;

        int start = serverPage * PER_PAGE;
        for (int i = start; i < Math.min(servers.size(), start + PER_PAGE); i++) {
            ServerInfo info = servers.get(i);
            int y = top + (i - start) * rowH;
            addDrawableChild(ButtonWidget.builder(Text.literal(info.name), b -> join(info))
                    .dimensions(x, y, BUTTON_W, 22).build());
            favicons.add(new Favicon(favicon(info), x + 3, y + 3));
        }
        if (servers.isEmpty() && notice == null) notice = "No servers yet. Add some in Multiplayer.";

        // arrows on the sides for the other pages
        int midY = top + (PER_PAGE * rowH) / 2 - 12;
        ButtonWidget prev = ButtonWidget.builder(Text.literal("<"), b -> { serverPage--; clearAndInit(); })
                .dimensions(x - 28, midY, 22, 22).build();
        ButtonWidget next = ButtonWidget.builder(Text.literal(">"), b -> { serverPage++; clearAndInit(); })
                .dimensions(x + BUTTON_W + 6, midY, 22, 22).build();
        prev.active = serverPage > 0;
        next.active = serverPage < pages - 1;
        addDrawableChild(prev);
        addDrawableChild(next);

        addDrawableChild(ButtonWidget.builder(Text.literal("< Back"), b -> open(Page.MAIN))
                .dimensions(width / 2 - 100, height - 28, 200, 20).build());
    }

    private void join(ServerInfo info) {
        if (client == null) return;
        if (client.world != null) {
            notice = "Leave this world first (Esc > Disconnect), then pick a server.";
            return;
        }
        ConnectScreen.connect(this, client, ServerAddress.parse(info.address), info, false, null);
    }

    /** The server's own logo (saved the last time the server list pinged it), or the "unknown server" picture. */
    private Identifier favicon(ServerInfo info) {
        byte[] png = info.getFavicon();
        if (png == null || client == null) return UNKNOWN_SERVER;
        String key = info.address + "#" + java.util.Arrays.hashCode(png);
        Identifier cached = FAVICONS.get(key);
        if (cached != null) return cached;
        try {
            NativeImage image = NativeImage.read(png);
            Identifier id = Identifier.of("servergui", "favicon/" + Integer.toHexString(key.hashCode()));
            client.getTextureManager().registerTexture(id, new NativeImageBackedTexture(() -> "servergui favicon", image));
            FAVICONS.put(key, id);
            return id;
        } catch (Exception e) {
            return UNKNOWN_SERVER;
        }
    }

    // ---------- chat page ----------

    private void initChat() {
        int boxW = Math.min(width - 40, 360);
        int x = width / 2 - boxW / 2;
        int y = height - 52;
        messageBox = new TextFieldWidget(textRenderer, x, y, boxW - 64, 20, Text.literal("Message"));
        messageBox.setMaxLength(256);
        messageBox.setPlaceholder(Text.literal("Type a message...").withColor(0xFF808080));
        addDrawableChild(messageBox);
        setInitialFocus(messageBox);
        addDrawableChild(ButtonWidget.builder(Text.literal("Send"), b -> send())
                .dimensions(x + boxW - 60, y, 60, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("< Back"), b -> open(Page.MAIN))
                .dimensions(width / 2 - 100, height - 28, 200, 20).build());
    }

    private void send() {
        if (messageBox == null || client == null) return;
        ClientPlayNetworkHandler handler = client.getNetworkHandler();
        String msg = messageBox.getText().strip();
        if (handler == null || msg.isEmpty()) return;
        if (msg.startsWith("/")) handler.sendChatCommand(msg.substring(1));
        else handler.sendChatMessage(msg);
        messageBox.setText("");
        chatScroll = 0;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (page == Page.CHAT) {
            chatScroll = Math.max(0, chatScroll + (int) (verticalAmount * 20));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    /** Draws the messages: face, name, and a speech bubble. Newest at the bottom. */
    private void renderChat(DrawContext context) {
        int areaTop = 40;
        int areaBottom = height - 60;
        int areaW = Math.min(width - 40, 360);
        int left = width / 2 - areaW / 2;
        int bubbleMax = areaW - 40;

        List<ChatLog.Entry> entries = ChatLog.entries();
        if (entries.isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("No messages yet."), width / 2, areaTop + 20, GRAY);
            return;
        }

        context.enableScissor(left - 4, areaTop, left + areaW + 4, areaBottom);
        int y = areaBottom + chatScroll;
        ClientPlayNetworkHandler handler = client != null ? client.getNetworkHandler() : null;
        for (int i = entries.size() - 1; i >= 0 && y > areaTop; i--) {
            ChatLog.Entry e = entries.get(i);
            List<OrderedText> lines = textRenderer.wrapLines(Text.literal(e.text()), bubbleMax - 10);
            int bubbleW = 10;
            for (OrderedText line : lines) bubbleW = Math.max(bubbleW, textRenderer.getWidth(line) + 10);
            int bubbleH = lines.size() * 10 + 6;
            int blockH = 11 + bubbleH + 6;   // name + bubble + gap
            y -= blockH;
            if (y + blockH < areaTop) continue;

            // face
            int faceX = left;
            int faceY = y + 11;
            PlayerListEntry player = (e.name() != null && handler != null) ? handler.getPlayerListEntry(e.name()) : null;
            if (player != null) {
                PlayerSkinDrawer.draw(context, player.getSkinTextures(), faceX, faceY, 24);
            } else {
                context.fill(faceX, faceY, faceX + 24, faceY + 24, 0xFF3A3A3A);
                context.drawItem(new ItemStack(e.name() == null ? Items.COMMAND_BLOCK : Items.PLAYER_HEAD), faceX + 4, faceY + 4);
            }

            // name
            int bx = left + 32;
            context.drawTextWithShadow(textRenderer, Text.literal(e.name() != null ? e.name() : "Server"),
                    bx + 2, y, e.name() != null ? 0xFFFFE070 : GRAY);

            // speech bubble with a little tail pointing at the face
            int by = y + 11;
            context.fill(bx - 1, by - 1, bx + bubbleW + 1, by + bubbleH + 1, 0xFF2A2A2A);
            context.fill(bx, by, bx + bubbleW, by + bubbleH, 0xFFF2F2F2);
            for (int t = 0; t < 4; t++) {
                context.fill(bx - 1 - t, by + 6 + t, bx, by + 7 + t, 0xFF2A2A2A);
                context.fill(bx - t, by + 7 + t, bx, by + 8 + t, 0xFFF2F2F2);
            }
            int ty = by + 4;
            for (OrderedText line : lines) {
                context.drawText(textRenderer, line, bx + 5, ty, 0xFF202020, false);
                ty += 10;
            }
        }
        context.disableScissor();
    }

    // ---------- drawing ----------

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks);

        // big bold title
        String titleText = switch (page) {
            case MAIN -> "ServerGUI";
            case SERVERS -> "Servers";
            case CHAT -> "Chat";
        };
        Text big = Text.literal(titleText).styled(st -> st.withBold(true).withColor(TITLE_COLOR));
        context.getMatrices().pushMatrix();
        context.getMatrices().translate(width / 2f, 12);
        context.getMatrices().scale(2f, 2f);
        context.drawCenteredTextWithShadow(textRenderer, big, 0, 0, 0xFF000000 | TITLE_COLOR);
        context.getMatrices().popMatrix();

        if (notice != null) context.drawCenteredTextWithShadow(textRenderer, Text.literal(notice), width / 2, 34, 0xFFFFB000);

        for (Icon icon : icons) context.drawItem(icon.stack(), icon.x(), icon.y());
        for (Favicon f : favicons) {
            context.drawTexture(RenderPipelines.GUI_TEXTURED, f.id(), f.x(), f.y(), 0f, 0f, 16, 16, 16, 16);
        }

        // red dot on Chat when someone said something new
        if (page == Page.MAIN && chatButton != null && ChatLog.hasUnread()) {
            int dx = chatButton.getX() + chatButton.getWidth() - 12;
            int dy = chatButton.getY() + 6;
            context.fill(dx + 1, dy, dx + 7, dy + 8, RED);
            context.fill(dx, dy + 1, dx + 8, dy + 7, RED);
        }

        if (page == Page.CHAT) renderChat(context);
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }
}
