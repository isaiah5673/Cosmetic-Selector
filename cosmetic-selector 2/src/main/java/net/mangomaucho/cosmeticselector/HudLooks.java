package net.mangomaucho.cosmeticselector;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Hearts, hunger and XP bar looks. Remembers which pack you picked for each,
 * and swaps Minecraft's HUD pictures for that pack's pictures while drawing.
 */
public final class HudLooks {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("cosmetic_selector_hud.json");

    /** section ("hearts", "hunger", "xp") -> pack slug */
    private static final Map<String, String> chosen = new HashMap<>();
    /** Minecraft picture path ("hud/heart/full") -> the picked pack's version of it */
    private static volatile Map<String, Identifier> swap = Map.of();
    private static boolean loaded = false;

    private HudLooks() {}

    public static synchronized String get(String section) {
        load();
        return chosen.get(section);
    }

    /** slug == null means "use whatever your normal packs show". */
    public static synchronized void set(String section, String slug) {
        load();
        if (slug == null) chosen.remove(section);
        else chosen.put(section, slug);
        save();
        refresh();
    }

    /** Called after looks are rebuilt, so the swaps match what exists now. */
    public static synchronized void refresh() {
        load();
        Map<String, Identifier> map = new HashMap<>();
        for (String section : CosmeticPackBuilder.HUD_SECTIONS) {
            String slug = chosen.get(section);
            if (slug == null) continue;
            for (CosmeticPackBuilder.HudLook look : CosmeticPackBuilder.hudLooks(section)) {
                if (!look.slug().equals(slug)) continue;
                for (String sprite : look.sprites()) map.put(sprite, sprite(slug, sprite));
            }
        }
        swap = map;
    }

    public static Identifier sprite(String slug, String sprite) {
        return Identifier.of("cosmetics", slug + "/" + sprite);
    }

    /** Used while drawing: gives back the picked pack's picture, or the same picture if nothing is picked. */
    public static Identifier remap(Identifier id) {
        Map<String, Identifier> map = swap;
        if (map.isEmpty() || id == null || !"minecraft".equals(id.getNamespace())) return id;
        Identifier to = map.get(id.getPath());
        return to != null ? to : id;
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        if (!Files.exists(FILE)) return;
        try (Reader reader = Files.newBufferedReader(FILE)) {
            Map<String, String> raw = GSON.fromJson(reader, new TypeToken<Map<String, String>>() {}.getType());
            if (raw != null) chosen.putAll(raw);
        } catch (Exception e) {
            System.err.println("[Cosmetic Selector] Could not read HUD config: " + e);
        }
    }

    private static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE)) {
                GSON.toJson(new TreeMap<>(chosen), writer);
            }
        } catch (Exception e) {
            System.err.println("[Cosmetic Selector] Could not save HUD config: " + e);
        }
    }
}
