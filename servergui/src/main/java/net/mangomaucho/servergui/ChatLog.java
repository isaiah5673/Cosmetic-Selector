package net.mangomaucho.servergui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Keeps the last chat messages, with who said them, for the Chat page. */
public final class ChatLog {
    /** name == null means a server message (no player). */
    public record Entry(String name, String text) {}

    private static final int MAX = 200;
    private static final List<Entry> entries = new ArrayList<>();
    private static boolean unread = false;
    private static boolean inWorld = false;

    private ChatLog() {}

    public static synchronized List<Entry> entries() { return new ArrayList<>(entries); }
    public static boolean hasUnread() { return unread; }
    public static void markRead() { unread = false; }

    /** A normal player chat message. */
    static void addChat(Text message, Object senderProfile) {
        String line = message.getString();
        String name = senderProfile != null ? Profiles.name(senderProfile) : null;
        if (name == null) name = findSpeaker(line);
        add(name, name != null ? stripName(line, name) : line);
    }

    /** A server/system message. Many servers send player chat this way, so look for a player name at the start. */
    static void addGame(Text message) {
        String line = message.getString();
        String name = findSpeaker(line);
        add(name, name != null ? stripName(line, name) : line);
    }

    private static synchronized void add(String name, String text) {
        inWorld = true;
        if (text == null || text.isBlank()) return;
        entries.add(new Entry(name, text.strip()));
        while (entries.size() > MAX) entries.remove(0);
        MinecraftClient client = MinecraftClient.getInstance();
        String me = client.player != null ? client.player.getName().getString() : null;
        boolean chatOpen = client.currentScreen instanceof ServerGuiScreen s && s.isChatOpen();
        if (!chatOpen && (name == null || !name.equals(me))) unread = true;
    }

    /** Called every tick while not in a world: start fresh next time you join. */
    static synchronized void clearIfLeft() {
        if (!inWorld) return;
        inWorld = false;
        entries.clear();
        unread = false;
    }

    /** Finds an online player whose name is at the start of the line, like "<Steve> hi" or "[VIP] Steve: hi". */
    private static String findSpeaker(String line) {
        ClientPlayNetworkHandler handler = MinecraftClient.getInstance().getNetworkHandler();
        if (handler == null) return null;
        Collection<PlayerListEntry> players = handler.getPlayerList();
        String head = line.length() > 48 ? line.substring(0, 48) : line;
        String best = null;
        int bestAt = Integer.MAX_VALUE;
        for (PlayerListEntry p : players) {
            String n = Profiles.name(p.getProfile());
            if (n == null || n.isEmpty()) continue;
            int at = head.indexOf(n);
            if (at < 0) continue;
            int after = at + n.length();
            // the name must be followed by something like ">", ":", "»" or "]" to count as the speaker
            if (after < head.length() && ">:»]|-".indexOf(head.charAt(after)) < 0 && head.charAt(after) != ' ') continue;
            if (at < bestAt || (at == bestAt && best != null && n.length() > best.length())) {
                best = n;
                bestAt = at;
            }
        }
        return best;
    }

    /** "<Steve> hello" -> "hello" */
    private static String stripName(String line, String name) {
        int at = line.indexOf(name);
        if (at < 0) return line;
        String rest = line.substring(at + name.length());
        int i = 0;
        while (i < rest.length() && (">:»]|- ".indexOf(rest.charAt(i)) >= 0)) i++;
        return rest.substring(i);
    }
}
