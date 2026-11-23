package net.mangomaucho.servergui;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.ServerList;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.MutableText;
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

    private static final int TITLE_BLUE = 0x3A7DFF;      // title fades from blue...
    private static final int TITLE_GREEN = 0x3DDC6A;     // ...to green
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFAAAAAA;
    private static final int RED = 0xFFE02020;
    private static final int BUTTON_W = 260;
    private static final int PER_PAGE = 5;
    private static final Identifier UNKNOWN_SERVER = Identifier.ofVanilla("textures/misc/unknown_server.png");
    private static final Identifier CHAT_ICON = Identifier.of("servergui", "textures/gui/chat_icon.png");
    private static final Map<String, Identifier> FAVICONS = new HashMap<>();

    private final Screen parent;
    private Page page = Page.MAIN;
    private int serverPage = 0;
    private int chatScroll = 0;           // how many pixels scrolled up from the newest message
    private String notice = null;         // a short message under the title

    private record Icon(ItemStack stack, Identifier texture, int x, int y) {}
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
        int y = 72;
        addIconButton(x, y, "Servers", new ItemStack(Items.COMPASS), null, b -> { serverPage = 0; open(Page.SERVERS); });
        chatButton = addIconButton(x, y + 26, "Chat", ItemStack.EMPTY, CHAT_ICON, b -> open(Page.CHAT));
        chatButton.active = client != null && client.getNetworkHandler() != null;

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                .dimensions(width / 2 - 100, height - 28, 200, 20).build());
    }

    /** A wide button with a small picture just left of its name. */
    private ButtonWidget addIconButton(int x, int y, String label, ItemStack icon, Identifier texture, ButtonWidget.PressAction action) {
        ButtonWidget b = ButtonWidget.builder(Text.literal(label), action).dimensions(x, y, BUTTON_W, 20).build();
        addDrawableChild(b);
        int textW = textRenderer.getWidth(label);
        icons.add(new Icon(icon, texture, x + BUTTON_W / 2 - textW / 2 - 20, y + 2));
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
        int top = 62;
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
        if (messageBox == null || client == null || client.player == null) return;
        ClientPlayNetworkHandler handler = client.player.networkHandler;
        String msg = messageBox.getText().strip().replaceAll("\\s+", " ");
        if (handler == null || msg.isEmpty()) return;
        if (msg.length() > 256) msg = msg.substring(0, 256);
        // same steps as Minecraft's own chat box
        client.inGameHud.getChatHud().addToMessageHistory(msg);
        if (msg.startsWith("/")) handler.sendChatCommand(msg.substring(1));
        else handler.sendChatMessage(msg);
        messageBox.setText("");
        chatScroll = 0;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (page == Page.CHAT && input.isEnter()) {   // Enter sends, like normal chat
            send();
            return true;
        }
        return super.keyPressed(input);
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
        int areaTop = 44;
        int areaBottom = height - 60;
        int areaW = Math.min(width - 40, 360);
        int left = width / 2 - areaW / 2;
        int face = 16;
        int bubbleLeft = left + face + 8;
        int bubbleMax = areaW - face - 12;

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
            List<OrderedText> lines = textRenderer.wrapLines(Text.literal(e.text()), bubbleMax - 12);
            int bubbleW = 12;
            for (OrderedText line : lines) bubbleW = Math.max(bubbleW, textRenderer.getWidth(line) + 12);
            int bubbleH = lines.size() * 10 + 7;
            int nameH = e.name() != null ? 10 : 0;
            int blockH = nameH + bubbleH + 6;
            y -= blockH;
            if (y + blockH < areaTop) continue;

            int by = y + nameH;
            // face next to the bottom of the bubble
            int faceY = by + bubbleH - face;
            drawFace(context, handler, e.name(), left, faceY, face);

            if (e.name() != null) {
                context.drawTextWithShadow(textRenderer, Text.literal(e.name()), bubbleLeft + 3, y, 0xFFFFE070);
            }
            drawBubble(context, bubbleLeft, by, bubbleW, bubbleH, faceY + face / 2);
            int ty = by + 4;
            for (OrderedText line : lines) {
                context.drawText(textRenderer, line, bubbleLeft + 6, ty, 0xFF1E1E1E, false);
                ty += 10;
            }
        }
        context.disableScissor();
    }

    /** The player's face: from the tab list if they're there, otherwise downloaded by name. Server messages get the server's logo. */
    private void drawFace(DrawContext context, ClientPlayNetworkHandler handler, String name, int x, int y, int size) {
        if (name != null) {
            PlayerListEntry player = null;
            if (handler != null) {
                player = handler.getPlayerListEntry(name);
                if (player == null && name.length() > 1) player = handler.getPlayerListEntry(name.replaceAll("^[.*~]+", ""));
            }
            if (player != null) {
                PlayerSkinDrawer.draw(context, player.getSkinTextures(), x, y, size);
                return;
            }
            Identifier downloaded = Faces.get(name);
            if (downloaded != null) {
                context.drawTexture(RenderPipelines.GUI_TEXTURED, downloaded, x, y, 0f, 0f, size, size, size, size);
                return;
            }
            PlayerSkinDrawer.draw(context, net.minecraft.client.util.DefaultSkinHelper.getSteve(), x, y, size);
            return;
        }
        // a message from the server itself: show the server's logo
        ServerInfo server = client != null ? client.getCurrentServerEntry() : null;
        Identifier logo = server != null ? favicon(server) : UNKNOWN_SERVER;
        context.drawTexture(RenderPipelines.GUI_TEXTURED, logo, x, y, 0f, 0f, size, size, size, size);
    }

    /** A rounded speech bubble with a little tail pointing left at the face. */
    private static void drawBubble(DrawContext c, int x, int y, int w, int h, int tailY) {
        int border = 0xFF2B2B2B;
        int fill = 0xFFF4F4F4;
        int shade = 0xFFD9D9D9;
        // outline with rounded corners
        c.fill(x + 2, y, x + w - 2, y + h, border);
        c.fill(x, y + 2, x + w, y + h - 2, border);
        c.fill(x + 1, y + 1, x + w - 1, y + h - 1, border);
        // inside
        c.fill(x + 2, y + 1, x + w - 2, y + h - 1, fill);
        c.fill(x + 1, y + 2, x + w - 1, y + h - 2, fill);
        c.fill(x + 2, y + h - 2, x + w - 2, y + h - 1, shade);   // soft bottom edge
        // tail
        int ty = Math.max(y + 3, Math.min(tailY - 2, y + h - 6));
        for (int t = 0; t < 4; t++) {
            c.fill(x - 1 - t, ty + t, x, ty + t + 1, border);
            c.fill(x - 1 - t, ty + 4 - t, x, ty + 5 - t, border);
        }
        for (int t = 0; t < 3; t++) {
            c.fill(x - t, ty + t + 1, x + 1, ty + 4 - t, fill);
        }
    }

    // ---------- drawing ----------

    /** Text that fades from blue to green, one letter at a time. */
    private static Text gradient(String text) {
        MutableText out = Text.empty();
        int n = Math.max(1, text.length() - 1);
        for (int i = 0; i < text.length(); i++) {
            float t = i / (float) n;
            int r = Math.round(((TITLE_BLUE >> 16) & 0xFF) * (1 - t) + ((TITLE_GREEN >> 16) & 0xFF) * t);
            int g = Math.round(((TITLE_BLUE >> 8) & 0xFF) * (1 - t) + ((TITLE_GREEN >> 8) & 0xFF) * t);
            int b = Math.round((TITLE_BLUE & 0xFF) * (1 - t) + (TITLE_GREEN & 0xFF) * t);
            int color = (r << 16) | (g << 8) | b;
            out.append(Text.literal(String.valueOf(text.charAt(i))).styled(st -> st.withBold(true).withColor(color)));
        }
        return out;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks);

        // big bold title, blue to green
        String titleText = switch (page) {
            case MAIN -> "ServerGUI";
            case SERVERS -> "Servers";
            case CHAT -> "Chat";
        };
        context.getMatrices().pushMatrix();
        context.getMatrices().translate(width / 2f, 12);
        context.getMatrices().scale(2f, 2f);
        context.drawCenteredTextWithShadow(textRenderer, gradient(titleText), 0, 0, WHITE);
        context.getMatrices().popMatrix();

        if (notice != null) context.drawCenteredTextWithShadow(textRenderer, Text.literal(notice), width / 2, 36, 0xFFFFB000);

        for (Icon icon : icons) {
            if (icon.texture() != null) {
                context.drawTexture(RenderPipelines.GUI_TEXTURED, icon.texture(), icon.x(), icon.y(), 0f, 0f, 16, 16, 16, 16);
            } else {
                context.drawItem(icon.stack(), icon.x(), icon.y());
            }
        }
        for (Favicon f : favicons) {
            context.drawTexture(RenderPipelines.GUI_TEXTURED, f.id(), f.x(), f.y(), 0f, 0f, 16, 16, 16, 16);
        }

        // small red dot on Chat when someone said something new
        if (page == Page.MAIN && chatButton != null && ChatLog.hasUnread()) {
            int dx = chatButton.getX() + chatButton.getWidth() - 10;
            int dy = chatButton.getY() + 4;
            context.fill(dx + 1, dy, dx + 4, dy + 5, RED);
            context.fill(dx, dy + 1, dx + 5, dy + 4, RED);
        }

        if (page == Page.CHAT) renderChat(context);
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }
}
