package net.mangomaucho.servergui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gets a player's face by name from Mojang (for players who aren't in the tab list, like on big servers).
 * Each name is looked up once; the face is cut out of their skin (face + hat layer).
 */
public final class Faces {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final Map<String, Identifier> READY = new ConcurrentHashMap<>();
    private static final Set<String> STARTED = ConcurrentHashMap.newKeySet();

    private Faces() {}

    /** The face texture (8x8) if we have it; starts downloading it if not. */
    public static Identifier get(String name) {
        if (name == null) return null;
        String clean = name.replaceAll("^[.*~]+", "");               // Bedrock players often start with "."
        if (!clean.matches("[A-Za-z0-9_]{1,16}")) return null;
        String key = clean.toLowerCase(Locale.ROOT);
        Identifier id = READY.get(key);
        if (id != null) return id;
        if (STARTED.add(key)) CompletableFuture.runAsync(() -> download(clean, key));
        return null;
    }

    private static void download(String name, String key) {
        try {
            String profile = fetchText("https://api.mojang.com/users/profiles/minecraft/" + name);
            if (profile == null) return;
            String uuid = JsonParser.parseString(profile).getAsJsonObject().get("id").getAsString();

            String session = fetchText("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid);
            if (session == null) return;
            String value = JsonParser.parseString(session).getAsJsonObject()
                    .getAsJsonArray("properties").get(0).getAsJsonObject().get("value").getAsString();
            JsonObject textures = JsonParser.parseString(new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("textures");
            if (textures == null || !textures.has("SKIN")) return;
            String skinUrl = textures.getAsJsonObject("SKIN").get("url").getAsString();

            HttpResponse<byte[]> skin = HTTP.send(HttpRequest.newBuilder(URI.create(skinUrl))
                    .timeout(Duration.ofSeconds(8)).build(), HttpResponse.BodyHandlers.ofByteArray());
            if (skin.statusCode() != 200) return;
            byte[] png = skin.body();

            MinecraftClient client = MinecraftClient.getInstance();
            client.execute(() -> makeFace(client, key, png));
        } catch (Exception ignored) {
            // no internet, unknown name, Bedrock player, etc: just no face
        }
    }

    private static String fetchText(String url) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8)).build(), HttpResponse.BodyHandlers.ofString());
        return r.statusCode() == 200 ? r.body() : null;
    }

    /** Cuts the 8x8 face (plus the hat layer on top) out of a skin. Runs on the game thread. */
    private static void makeFace(MinecraftClient client, String key, byte[] png) {
        try (NativeImage skin = NativeImage.read(png)) {
            NativeImage face = new NativeImage(8, 8, true);
            for (int x = 0; x < 8; x++) {
                for (int y = 0; y < 8; y++) {
                    int color = skin.getColorArgb(8 + x, 8 + y) | 0xFF000000;
                    if (skin.getWidth() >= 48) {
                        int hat = skin.getColorArgb(40 + x, 8 + y);
                        if ((hat >>> 24) > 0) color = hat | 0xFF000000;
                    }
                    face.setColorArgb(x, y, color);
                }
            }
            Identifier id = Identifier.of("servergui", "face/" + key);
            client.getTextureManager().registerTexture(id, new NativeImageBackedTexture(() -> "servergui face " + key, face));
            READY.put(key, id);
        } catch (Exception ignored) {
        }
    }
}
