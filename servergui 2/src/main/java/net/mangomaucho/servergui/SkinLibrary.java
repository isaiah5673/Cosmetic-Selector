package net.mangomaucho.servergui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Your skins: ones you uploaded here, ones saved in the Minecraft Launcher, and ones saved in the Modrinth App,
 * plus the skin you're wearing now. Default skins are not included.
 */
public final class SkinLibrary {
    public static final class Skin {
        public final String id;        // fingerprint of the picture
        public final byte[] png;
        public final boolean slim;
        public final boolean current;
        private Identifier preview;

        Skin(String id, byte[] png, boolean slim, boolean current) {
            this.id = id;
            this.png = png;
            this.slim = slim;
            this.current = current;
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final Path MY_SKINS = FabricLoader.getInstance().getConfigDir().resolve("servergui").resolve("skins");

    private static volatile List<Skin> skins = List.of();
    private static volatile Skin wearing = null;

    private SkinLibrary() {}

    public static List<Skin> skins() {
        List<Skin> list = new ArrayList<>();
        Skin w = wearing;
        if (w != null) list.add(w);
        for (Skin s : skins) if (w == null || !s.id.equals(w.id)) list.add(s);
        return list;
    }

    /** Looks for skins in every place. Call when the Skins page opens. */
    public static void reload() {
        List<Skin> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        // 1) skins you uploaded with the Upload button
        try {
            Files.createDirectories(MY_SKINS);
            try (Stream<Path> s = Files.list(MY_SKINS)) {
                for (Path p : s.sorted().toList()) {
                    if (p.toString().toLowerCase(Locale.ROOT).endsWith(".png")) add(found, seen, Files.readAllBytes(p), false);
                }
            }
        } catch (Exception ignored) {
        }
        // 2) Minecraft Launcher saved skins
        try {
            Path json = minecraftDir().resolve("launcher_custom_skins.json");
            if (Files.exists(json)) {
                JsonObject root = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
                JsonObject custom = root.getAsJsonObject("customSkins");
                if (custom != null) {
                    for (var e : custom.entrySet()) {
                        JsonObject skin = e.getValue().getAsJsonObject();
                        if (!skin.has("skinImage")) continue;
                        String data = skin.get("skinImage").getAsString();
                        int comma = data.indexOf(',');
                        add(found, seen, Base64.getDecoder().decode(data.substring(comma + 1)), false);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        // 3) Modrinth App saved skins
        try {
            Path db = dataDir().resolve("ModrinthApp").resolve("app.db");
            if (Files.exists(db)) {
                try (SqliteReader reader = new SqliteReader(db)) {
                    for (List<Object> row : reader.readTable("custom_minecraft_skin_textures")) {
                        for (Object v : row) if (v instanceof byte[] png) add(found, seen, png, false);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        skins = found;
        fetchWearing();
    }

    private static void add(List<Skin> list, Set<String> seen, byte[] png, boolean current) {
        Skin s = make(png, current);
        if (s != null && seen.add(s.id)) list.add(s);
    }

    /** Only real skin pictures (64x64 or 64x32). The fingerprint is the pixels, so the same skin saved twice shows once. */
    private static Skin make(byte[] png, boolean current) {
        try (NativeImage img = NativeImage.read(png)) {
            int w = img.getWidth(), h = img.getHeight();
            if (w != 64 || (h != 64 && h != 32)) return null;
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int c = img.getColorArgb(x, y);
                if ((c >>> 24) == 0) c = 0;
                md.update((byte) (c >>> 24)); md.update((byte) (c >>> 16)); md.update((byte) (c >>> 8)); md.update((byte) c);
            }
            return new Skin(HexFormat.of().formatHex(md.digest()), png, h == 64 && isSlim(img), current);
        } catch (Exception e) {
            return null;
        }
    }

    /** Slim (Alex-style) skins leave the outer arm column empty. */
    private static boolean isSlim(NativeImage img) {
        for (int y = 20; y < 32; y++) for (int x = 54; x < 56; x++) {
            if ((img.getColorArgb(x, y) >>> 24) != 0) return false;
        }
        return true;
    }

    /** The skin you're wearing now, from your Minecraft account. */
    private static void fetchWearing() {
        MinecraftClient client = MinecraftClient.getInstance();
        String token = client.getSession().getAccessToken();
        CompletableFuture.runAsync(() -> {
            try {
                HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("https://api.minecraftservices.com/minecraft/profile"))
                        .header("Authorization", "Bearer " + token).timeout(Duration.ofSeconds(8)).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) return;
                JsonArray list = JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonArray("skins");
                if (list == null) return;
                for (JsonElement el : list) {
                    JsonObject s = el.getAsJsonObject();
                    if (!"ACTIVE".equals(s.get("state").getAsString())) continue;
                    HttpResponse<byte[]> img = HTTP.send(HttpRequest.newBuilder(URI.create(s.get("url").getAsString()))
                            .timeout(Duration.ofSeconds(8)).build(), HttpResponse.BodyHandlers.ofByteArray());
                    if (img.statusCode() != 200) return;
                    byte[] png = img.body();
                    client.execute(() -> wearing = make(png, true));
                    return;
                }
            } catch (Exception ignored) {
            }
        });
    }

    /** Copies a skin file into your ServerGUI skins. Returns a short message to show. */
    public static String importFile(Path file) {
        try {
            byte[] png = Files.readAllBytes(file);
            Skin s = make(png, false);
            if (s == null) return "That isn't a Minecraft skin (it must be a 64x64 or 64x32 .png).";
            Files.createDirectories(MY_SKINS);
            Files.write(MY_SKINS.resolve(s.id.substring(0, 12) + ".png"), png);
            reload();
            return "Skin added!";
        } catch (Exception e) {
            return "Couldn't add that skin.";
        }
    }

    /** Puts this skin on your Minecraft account. The message comes back on the game thread. */
    public static void wear(Skin skin, Consumer<String> done) {
        MinecraftClient client = MinecraftClient.getInstance();
        String token = client.getSession().getAccessToken();
        CompletableFuture.runAsync(() -> {
            String message;
            try {
                String boundary = "----ServerGUI" + System.nanoTime();
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"variant\"\r\n\r\n"
                        + (skin.slim ? "slim" : "classic") + "\r\n").getBytes(StandardCharsets.UTF_8));
                body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\n"
                        + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                body.write(skin.png);
                body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("https://api.minecraftservices.com/minecraft/profile/skins"))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .timeout(Duration.ofSeconds(20))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    message = "Skin changed! Rejoin a world or server to see it.";
                    byte[] png = skin.png;
                    client.execute(() -> wearing = make(png, true));
                } else if (r.statusCode() == 401) {
                    message = "Couldn't change skin: please restart Minecraft and log in again.";
                } else if (r.statusCode() == 429) {
                    message = "Too many skin changes. Wait a minute and try again.";
                } else {
                    message = "Couldn't change skin (error " + r.statusCode() + ").";
                }
            } catch (Exception e) {
                message = "Couldn't change skin: no internet?";
            }
            String m = message;
            client.execute(() -> done.accept(m));
        });
    }

    /** A front view of the whole player (16x32 pixels) made from the skin, for the carousel. */
    public static Identifier preview(Skin skin) {
        if (skin.preview != null) return skin.preview;
        MinecraftClient client = MinecraftClient.getInstance();
        try (NativeImage src = NativeImage.read(skin.png)) {
            NativeImage out = new NativeImage(16, 32, true);
            boolean full = src.getHeight() == 64;
            int arm = skin.slim ? 3 : 4;
            // head + hat
            copy(src, out, 8, 8, 8, 8, 4, 0, false, false);
            copy(src, out, 40, 8, 8, 8, 4, 0, true, false);
            // body + jacket
            copy(src, out, 20, 20, 8, 12, 4, 8, false, false);
            if (full) copy(src, out, 20, 36, 8, 12, 4, 8, true, false);
            // right arm (on the left of the picture) + sleeve
            copy(src, out, 44, 20, arm, 12, 4 - arm, 8, false, false);
            if (full) copy(src, out, 44, 36, arm, 12, 4 - arm, 8, true, false);
            // left arm + sleeve (old 64x32 skins mirror the right arm)
            if (full) {
                copy(src, out, 36, 52, arm, 12, 12, 8, false, false);
                copy(src, out, 52, 52, arm, 12, 12, 8, true, false);
            } else {
                copy(src, out, 44, 20, arm, 12, 12, 8, false, true);
            }
            // legs + pants
            copy(src, out, 4, 20, 4, 12, 4, 20, false, false);
            if (full) copy(src, out, 4, 36, 4, 12, 4, 20, true, false);
            if (full) {
                copy(src, out, 20, 52, 4, 12, 8, 20, false, false);
                copy(src, out, 4, 52, 4, 12, 8, 20, true, false);
            } else {
                copy(src, out, 4, 20, 4, 12, 8, 20, false, true);
            }
            Identifier id = Identifier.of("servergui", "skin_preview/" + skin.id.substring(0, 16));
            client.getTextureManager().registerTexture(id, new NativeImageBackedTexture(() -> "servergui skin preview", out));
            skin.preview = id;
            return id;
        } catch (Exception e) {
            return null;
        }
    }

    private static void copy(NativeImage src, NativeImage dst, int sx, int sy, int w, int h, int dx, int dy,
                             boolean overlay, boolean mirror) {
        for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) {
            int c = src.getColorArgb(sx + (mirror ? w - 1 - x : x), sy + y);
            if (overlay) {
                if ((c >>> 24) == 0) continue;
            } else {
                c |= 0xFF000000;
            }
            dst.setColorArgb(dx + x, dy + y, c);
        }
    }

    /** Opens the computer's "choose a file" window (if available). Returns null if cancelled or not available. */
    public static Path chooseFile() {
        try {
            Class<?> dialogs = Class.forName("org.lwjgl.util.tinyfd.TinyFileDialogs");
            Class<?> pointerBuffer = Class.forName("org.lwjgl.PointerBuffer");
            var method = dialogs.getMethod("tinyfd_openFileDialog",
                    CharSequence.class, CharSequence.class, pointerBuffer, CharSequence.class, boolean.class);
            Object result = method.invoke(null, "Choose a skin (.png)", System.getProperty("user.home") + "/", null, "Skin pictures (.png)", false);
            return result == null ? null : Path.of(result.toString());
        } catch (Throwable t) {
            return null;
        }
    }

    private static Path minecraftDir() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home");
        if (os.contains("mac")) return Path.of(home, "Library", "Application Support", "minecraft");
        if (os.contains("win")) return Path.of(System.getenv().getOrDefault("APPDATA", home), ".minecraft");
        return Path.of(home, ".minecraft");
    }

    private static Path dataDir() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home");
        if (os.contains("mac")) return Path.of(home, "Library", "Application Support");
        if (os.contains("win")) return Path.of(System.getenv().getOrDefault("APPDATA", home));
        return Path.of(System.getenv().getOrDefault("XDG_DATA_HOME", home + "/.local/share"));
    }
}
