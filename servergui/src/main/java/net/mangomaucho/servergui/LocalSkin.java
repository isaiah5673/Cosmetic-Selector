package net.mangomaucho.servergui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;

/**
 * Shows your new skin to YOU right away, by swapping the picture of the skin Minecraft already loaded for you.
 * (Minecraft keeps your old skin in memory; servers keep their own copy too.)
 */
final class LocalSkin {
    private static byte[] png = null;
    private static Identifier appliedTo = null;
    private static AbstractTexture applied = null;
    private static int ticks = 0;

    private LocalSkin() {}

    static void set(byte[] skinPng) {
        png = skinPng;
        appliedTo = null;
        applied = null;
        apply();
    }

    /** Checked every 2 seconds: after joining a world or when Minecraft reloads your skin, swap it again. */
    static void tick() {
        if (png == null || ++ticks < 40) return;
        ticks = 0;
        apply();
    }

    private static void apply() {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (png == null || client.player == null) return;
            ClientPlayNetworkHandler handler = client.getNetworkHandler();
            if (handler == null) return;
            PlayerListEntry me = handler.getPlayerListEntry(client.player.getUuid());
            if (me == null) return;
            Identifier id = skinPicture(me.getSkinTextures());
            // never touch the built-in default skins (that would change everyone who uses them)
            if (id == null || id.getPath().startsWith("textures/entity/player")) return;
            if (id.equals(appliedTo) && client.getTextureManager().getTexture(id) == applied) return;

            NativeImage image = toModernSkin(NativeImage.read(png));
            NativeImageBackedTexture texture = new NativeImageBackedTexture(() -> "servergui local skin", image);
            client.getTextureManager().registerTexture(id, texture);
            appliedTo = id;
            applied = texture;
        } catch (Throwable ignored) {
        }
    }

    /** Finds the picture used for the skin's body inside SkinTextures. */
    private static Identifier skinPicture(Object skinTextures) throws Exception {
        if (skinTextures == null || !skinTextures.getClass().isRecord()) return null;
        RecordComponent[] parts = skinTextures.getClass().getRecordComponents();
        if (parts.length == 0) return null;
        Object body = parts[0].getAccessor().invoke(skinTextures);   // the first part is the body skin
        if (body == null) return null;
        Identifier any = null;
        for (Method m : body.getClass().getMethods()) {
            if (m.getParameterCount() != 0 || m.getReturnType() != Identifier.class) continue;
            Identifier id = (Identifier) m.invoke(body);
            if (id == null) continue;
            if (id.getPath().endsWith(".png")) return id;   // the texture path, when it has one
            any = id;
        }
        return any;
    }

    /** Old 64x32 skins get their left arm and leg filled in, like Minecraft does. */
    private static NativeImage toModernSkin(NativeImage src) {
        if (src.getHeight() != 32) return src;
        NativeImage out = new NativeImage(64, 64, true);
        for (int x = 0; x < 64; x++) for (int y = 0; y < 32; y++) out.setColorArgb(x, y, src.getColorArgb(x, y));
        // left leg <- right leg (mirrored), left arm <- right arm (mirrored)
        mirror(src, out, 0, 16, 16, 48);
        mirror(src, out, 40, 16, 32, 48);
        src.close();
        return out;
    }

    private static void mirror(NativeImage src, NativeImage out, int sx, int sy, int dx, int dy) {
        for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) {
            out.setColorArgb(dx + x, dy + y, src.getColorArgb(sx + (15 - x), sy + y));
        }
    }
}
