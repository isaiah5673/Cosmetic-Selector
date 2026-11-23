package net.mangomaucho.servergui;

/** Reads a player's name from a GameProfile, whichever way this Minecraft version names it. */
final class Profiles {
    private Profiles() {}

    static String name(Object profile) {
        if (profile == null) return null;
        for (String method : new String[]{"name", "getName"}) {
            try {
                Object v = profile.getClass().getMethod(method).invoke(profile);
                if (v instanceof String s) return s;
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
