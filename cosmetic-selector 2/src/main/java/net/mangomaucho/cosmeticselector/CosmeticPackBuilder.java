package net.mangomaucho.cosmeticselector;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads normal Minecraft plus every pack in the resourcepacks folder and builds the looks
 * (kept inside the mod's config folder and loaded by the mod, so nothing shows in the pack list).
 * Each item gets one look per pack that changes it (vanilla first). Looks that look exactly
 * the same as another one are left out.
 */
public final class CosmeticPackBuilder {
    /** Old folder from earlier versions; removed so it no longer shows in the pack list. */
    private static final String OLD_PACK_NAME = "Cosmetic Looks (auto)";
    public static final String VANILLA = "minecraft";

    /** A whole-pack theme: which look each item uses for that pack. */
    public record Theme(String name, Map<String, String> lookForItem) {}

    private static volatile List<Theme> themes = List.of();
    private static volatile boolean built = false;

    public static List<Theme> themes() {
        ensureBuilt();
        return themes;
    }

    public static Path outputDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("cosmetic_selector").resolve("looks");
    }

    public static void ensureBuilt() {
        if (!built) build();
    }

    public static final String[] MATS = {"wooden", "stone", "copper", "iron", "golden", "diamond", "netherite"};
    public static final String[] TOOLS = {"sword", "pickaxe", "axe", "spear", "shovel", "hoe"};
    public static final String[] SINGLES = {"trident", "mace", "ender_pearl", "shield", "totem_of_undying"};

    private static final Gson GSON = new Gson();
    private static final List<String> FLAT = new ArrayList<>();
    static {
        for (String t : TOOLS) for (String m : MATS) FLAT.add(m + "_" + t);
        FLAT.add("mace");
        FLAT.add("totem_of_undying");
        FLAT.add("ender_pearl");
    }

    private CosmeticPackBuilder() {}

    /** Reads a file from a pack, or returns null if the pack doesn't have it. */
    interface PackReader extends AutoCloseable {
        byte[] read(String path) throws IOException;
        @Override void close() throws IOException;
    }

    /** One finished look: files to write, plus a fingerprint used to drop repeats. */
    private static final class Look {
        final Map<String, byte[]> files = new LinkedHashMap<>();
        final ByteArrayOutputStream fingerprint = new ByteArrayOutputStream();
        void add(String rel, byte[] data) { files.put(rel, data); }
        void add(String rel, String text) { files.put(rel, text.getBytes(StandardCharsets.UTF_8)); }
        void print(byte[] data) { fingerprint.writeBytes(data); fingerprint.write(0); }
        /** Adds a texture by what it looks like, so the same picture saved differently still matches. */
        void printImage(byte[] png, byte[] mcmeta) {
            try {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
                if (img == null) { print(png); return; }
                int w = img.getWidth(), h = img.getHeight();
                int[] px = img.getRGB(0, 0, w, h, null, 0, w);
                java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(8 + px.length * 4);
                buf.putInt(w).putInt(h);
                for (int p : px) buf.putInt((p >>> 24) == 0 ? 0 : p); // all see-through pixels count as the same
                print(buf.array());
                if (h > w && mcmeta != null) print(mcmeta); // only animations care about the .mcmeta
            } catch (Exception e) {
                print(png);
            }
        }
        String key() {
            try {
                byte[] d = MessageDigest.getInstance("SHA-1").digest(fingerprint.toByteArray());
                return HexFormat.of().formatHex(d);
            } catch (Exception e) {
                return Arrays.toString(fingerprint.toByteArray());
            }
        }
    }

    public static synchronized void build() {
        built = true;
        Path packsDir = FabricLoader.getInstance().getGameDir().resolve("resourcepacks");
        Path out = outputDir();
        List<Theme> newThemes = new ArrayList<>();
        try {
            Files.createDirectories(packsDir);
            deleteTree(packsDir.resolve(OLD_PACK_NAME));
            deleteTree(out.resolve("assets"));
            Files.createDirectories(out);

            PackReader vanilla = classpathReader();
            Map<String, Map<String, String>> seen = new HashMap<>();   // item -> fingerprint -> look that has it
            Set<String> usedSlugs = new HashSet<>();
            usedSlugs.add(VANILLA);

            // normal Minecraft first, so it's always the first look and packs that match it are skipped
            Map<String, String> vanillaTheme = new LinkedHashMap<>();
            addPack(vanilla, vanilla, VANILLA, out, seen, vanillaTheme);
            newThemes.add(new Theme("Default", vanillaTheme));

            List<Path> packs = new ArrayList<>();
            try (Stream<Path> s = Files.list(packsDir)) {
                s.sorted().forEach(packs::add);
            }
            int count = 0;
            for (Path p : packs) {
                String file = p.getFileName().toString();
                if (file.equals(OLD_PACK_NAME) || file.startsWith(".")) continue;
                boolean isZip = Files.isRegularFile(p) && file.toLowerCase(Locale.ROOT).endsWith(".zip");
                if (!isZip && !Files.isDirectory(p)) continue;

                String slug = slugFor(file, usedSlugs);
                try (PackReader reader = isZip ? zipReader(p) : dirReader(p)) {
                    if (reader == null) continue;
                    Map<String, String> theme = new LinkedHashMap<>();
                    if (addPack(reader, vanilla, slug, out, seen, theme)) {
                        usedSlugs.add(slug);
                        count++;
                        // items this pack doesn't change use the normal look in its theme
                        for (Map.Entry<String, String> v : vanillaTheme.entrySet()) theme.putIfAbsent(v.getKey(), v.getValue());
                        newThemes.add(new Theme(file.replaceAll("(?i)\\.zip$", ""), theme));
                    }
                } catch (Exception e) {
                    System.err.println("[Cosmetic Selector] Skipped pack " + file + ": " + e);
                }
            }

            String mcmeta = "{\"pack\":{\"description\":\"Made by Cosmetic Selector from " + count
                    + " packs\",\"min_format\":65,\"max_format\":999}}";
            Files.writeString(out.resolve("pack.mcmeta"), mcmeta, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Cosmetic Selector] Could not build looks: " + e);
        }
        themes = List.copyOf(newThemes);
    }

    /** Adds every new look this pack has. Returns true if it added at least one. */
    private static boolean addPack(PackReader r, PackReader vanilla, String s, Path out,
                                   Map<String, Map<String, String>> seen, Map<String, String> theme) throws IOException {
        boolean any = false;
        for (String item : FLAT) {
            any |= save(out, seen, item, s, flatLook(r, vanilla, s, item), theme);
        }
        any |= save(out, seen, "trident", s, tridentLook(r, s), theme);
        any |= save(out, seen, "shield", s, shieldLook(r, s), theme);
        return any;
    }

    private static boolean save(Path out, Map<String, Map<String, String>> seen, String item, String slug,
                                Look look, Map<String, String> theme) throws IOException {
        if (look == null) return false;
        Map<String, String> known = seen.computeIfAbsent(item, k -> new HashMap<>());
        String same = known.get(look.key());
        if (same != null) {           // looks exactly like one we already have: reuse it, don't repeat it
            theme.put(item, same);
            return !same.equals(VANILLA);
        }
        known.put(look.key(), slug);
        theme.put(item, slug);
        for (Map.Entry<String, byte[]> e : look.files.entrySet()) {
            Path p = out.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.write(p, e.getValue());
        }
        return true;
    }

    // ---------- normal items: copy Minecraft's own item file, swap in this pack's textures ----------

    private static Look flatLook(PackReader r, PackReader vanilla, String s, String item) throws IOException {
        JsonObject def = readJson(vanilla, "assets/minecraft/items/" + item + ".json");
        if (def == null) {
            def = JsonParser.parseString("{\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/" + item + "\"}}").getAsJsonObject();
        }
        Set<String> modelIds = new LinkedHashSet<>();
        collectModels(def, modelIds);

        Look look = new Look();
        Map<String, String> renamed = new HashMap<>();
        for (String modelId : modelIds) {
            String path = stripItemPrefix(modelId);
            if (path == null) continue;
            Map<String, String> textures = inheritedTextures(vanilla, path, 0);
            if (textures.isEmpty()) textures = Map.of("layer0", "minecraft:item/" + path);

            JsonObject overrides = new JsonObject();
            for (Map.Entry<String, String> t : textures.entrySet()) {
                String texPath = stripItemPrefix(t.getValue());
                if (texPath == null) continue;
                String src = "assets/minecraft/textures/item/" + texPath + ".png";
                byte[] png = r.read(src);
                if (png == null) continue;
                String dst = "assets/cosmetics/textures/item/" + s + "/" + texPath + ".png";
                look.add(dst, png);
                byte[] meta = r.read(src + ".mcmeta");
                if (meta != null) look.add(dst + ".mcmeta", meta);
                look.print(t.getKey().getBytes(StandardCharsets.UTF_8));
                look.printImage(png, meta);
                overrides.addProperty(t.getKey(), "cosmetics:item/" + s + "/" + texPath);
            }
            if (overrides.size() == 0) continue;
            JsonObject model = new JsonObject();
            model.addProperty("parent", "minecraft:item/" + path);
            model.add("textures", overrides);
            look.add("assets/cosmetics/models/item/" + s + "/" + path + ".json", GSON.toJson(model));
            renamed.put(modelId, "cosmetics:item/" + s + "/" + path);
        }
        if (renamed.isEmpty()) return null; // this pack doesn't change this item

        JsonObject copy = def.deepCopy();
        renameModels(copy, renamed);
        look.add("assets/cosmetics/items/" + item + "/" + s + ".json", GSON.toJson(copy));
        return look;
    }

    /** Finds every "model" reference in an item file. */
    private static void collectModels(JsonElement e, Set<String> out) {
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("type") && o.get("type").isJsonPrimitive() && o.get("type").getAsString().endsWith("model")
                    && o.has("model") && o.get("model").isJsonPrimitive()) {
                out.add(o.get("model").getAsString());
            }
            for (Map.Entry<String, JsonElement> en : o.entrySet()) collectModels(en.getValue(), out);
        } else if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) collectModels(x, out);
        }
    }

    private static void renameModels(JsonElement e, Map<String, String> renamed) {
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("model") && o.get("model").isJsonPrimitive()) {
                String to = renamed.get(o.get("model").getAsString());
                if (to != null) o.addProperty("model", to);
            }
            for (Map.Entry<String, JsonElement> en : o.entrySet()) renameModels(en.getValue(), renamed);
        } else if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            for (JsonElement x : a) renameModels(x, renamed);
        }
    }

    /** Texture slots of a vanilla item model, including ones it gets from its parents. */
    private static Map<String, String> inheritedTextures(PackReader vanilla, String path, int depth) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        if (depth > 4) return result;
        JsonObject model = readJson(vanilla, "assets/minecraft/models/item/" + path + ".json");
        if (model == null) return result;
        if (model.has("parent")) {
            String parent = stripItemPrefix(model.get("parent").getAsString());
            if (parent != null) result.putAll(inheritedTextures(vanilla, parent, depth + 1));
        }
        if (model.has("textures") && model.get("textures").isJsonObject()) {
            for (Map.Entry<String, JsonElement> t : model.getAsJsonObject("textures").entrySet()) {
                if (t.getValue().isJsonPrimitive()) result.put(t.getKey(), t.getValue().getAsString());
            }
        }
        result.values().removeIf(v -> v.startsWith("#"));
        return result;
    }

    /** "minecraft:item/foo" or "item/foo" -> "foo"; anything else -> null. */
    private static String stripItemPrefix(String id) {
        if (id.startsWith("minecraft:")) id = id.substring("minecraft:".length());
        return id.startsWith("item/") ? id.substring("item/".length()) : null;
    }

    // ---------- trident and shield: rebuilt in 3D from the pack's textures ----------

    private static Look tridentLook(PackReader r, String s) throws IOException {
        String tex = "assets/minecraft/textures/";
        Look look = new Look();
        boolean icon = copyTex(r, tex + "item/trident.png", s, "trident", look);
        boolean model3d = copyTex(r, tex + "entity/trident.png", s, "trident_3d", look);
        if (!icon && !model3d) return null;
        String iconTex = icon ? "cosmetics:item/" + s + "/trident" : "minecraft:item/trident";
        look.add("assets/cosmetics/models/item/" + s + "/trident.json",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"" + iconTex + "\"}}");
        String hand = "minecraft:item/trident";
        String throwing = "minecraft:item/trident";
        if (model3d) {
            String t = "cosmetics:item/" + s + "/trident_3d";
            look.add("assets/cosmetics/models/item/" + s + "/trident_in_hand.json", ModelTemplates.TRIDENT_IN_HAND.replace("__TEX__", t));
            look.add("assets/cosmetics/models/item/" + s + "/trident_throwing.json", ModelTemplates.TRIDENT_THROWING.replace("__TEX__", t));
            hand = "cosmetics:item/" + s + "/trident_in_hand";
            throwing = "cosmetics:item/" + s + "/trident_throwing";
        }
        look.add("assets/cosmetics/items/trident/" + s + ".json",
                "{\"model\":{\"type\":\"minecraft:select\",\"property\":\"minecraft:display_context\","
                + "\"cases\":[{\"when\":[\"gui\",\"ground\",\"fixed\"],\"model\":{\"type\":\"minecraft:model\",\"model\":\"cosmetics:item/" + s + "/trident\"}}],"
                + "\"fallback\":{\"type\":\"minecraft:condition\",\"property\":\"minecraft:using_item\","
                + "\"on_false\":{\"type\":\"minecraft:model\",\"model\":\"" + hand + "\"},"
                + "\"on_true\":{\"type\":\"minecraft:model\",\"model\":\"" + throwing + "\"}}}}");
        return look;
    }

    private static Look shieldLook(PackReader r, String s) throws IOException {
        String tex = "assets/minecraft/textures/";
        Look look = new Look();
        boolean found = copyTex(r, tex + "entity/shield/shield_base_nopattern.png", s, "shield_3d", look)
                || copyTex(r, tex + "entity/shield_base_nopattern.png", s, "shield_3d", look);
        if (!found) return null;
        String t = "cosmetics:item/" + s + "/shield_3d";
        look.add("assets/cosmetics/models/item/" + s + "/shield.json", ModelTemplates.SHIELD.replace("__TEX__", t));
        look.add("assets/cosmetics/models/item/" + s + "/shield_blocking.json", ModelTemplates.SHIELD_BLOCKING.replace("__TEX__", t));
        look.add("assets/cosmetics/items/shield/" + s + ".json",
                "{\"model\":{\"type\":\"minecraft:condition\",\"property\":\"minecraft:using_item\","
                + "\"on_false\":{\"type\":\"minecraft:model\",\"model\":\"cosmetics:item/" + s + "/shield\"},"
                + "\"on_true\":{\"type\":\"minecraft:model\",\"model\":\"cosmetics:item/" + s + "/shield_blocking\"}}}");
        return look;
    }

    private static boolean copyTex(PackReader r, String src, String s, String name, Look look) throws IOException {
        byte[] png = r.read(src);
        if (png == null) return false;
        String dst = "assets/cosmetics/textures/item/" + s + "/" + name + ".png";
        look.add(dst, png);
        byte[] meta = r.read(src + ".mcmeta");
        if (meta != null) look.add(dst + ".mcmeta", meta);
        look.print(name.getBytes(StandardCharsets.UTF_8));
        look.printImage(png, meta);
        return true;
    }

    // ---------- helpers ----------

    private static JsonObject readJson(PackReader r, String path) {
        try {
            byte[] b = r.read(path);
            if (b == null) return null;
            JsonElement e = JsonParser.parseString(new String(b, StandardCharsets.UTF_8));
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String slugFor(String file, Set<String> used) {
        String s = file.replaceAll("(?i)\\.zip$", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (s.isEmpty()) s = "pack";
        String base = s;
        for (int i = 2; used.contains(s); i++) s = base + "_" + i;
        return s;
    }

    /** Normal Minecraft's files, read from the game itself. */
    static PackReader classpathReader() {
        ClassLoader cl = CosmeticPackBuilder.class.getClassLoader();
        return new PackReader() {
            public byte[] read(String path) throws IOException {
                try (InputStream in = cl.getResourceAsStream(path)) {
                    return in == null ? null : in.readAllBytes();
                }
            }
            public void close() {}
        };
    }

    private static PackReader zipReader(Path p) throws IOException {
        ZipFile zip = new ZipFile(p.toFile());
        String root = null;
        Enumeration<? extends ZipEntry> e = zip.entries();
        while (e.hasMoreElements()) {
            String n = e.nextElement().getName();
            if (n.startsWith("__MACOSX")) continue;
            if (n.endsWith("pack.mcmeta")) {
                String r = n.substring(0, n.length() - "pack.mcmeta".length());
                if (root == null || r.length() < root.length()) root = r;
            }
        }
        if (root == null) { zip.close(); return null; }
        final String prefix = root;
        return new PackReader() {
            public byte[] read(String path) throws IOException {
                ZipEntry entry = zip.getEntry(prefix + path);
                if (entry == null) return null;
                try (InputStream in = zip.getInputStream(entry)) { return in.readAllBytes(); }
            }
            public void close() throws IOException { zip.close(); }
        };
    }

    private static PackReader dirReader(Path p) {
        Path root = p;
        if (!Files.exists(root.resolve("pack.mcmeta"))) {
            // some unzipped packs have one extra folder inside
            try (Stream<Path> s = Files.list(p)) {
                root = s.filter(d -> Files.exists(d.resolve("pack.mcmeta"))).findFirst().orElse(null);
            } catch (IOException e) {
                root = null;
            }
            if (root == null) return null;
        }
        final Path base = root;
        return new PackReader() {
            public byte[] read(String path) throws IOException {
                Path f = base.resolve(path);
                return Files.isRegularFile(f) ? Files.readAllBytes(f) : null;
            }
            public void close() {}
        };
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            List<Path> all = s.sorted(Comparator.reverseOrder()).toList();
            for (Path x : all) Files.deleteIfExists(x);
        }
    }
}
