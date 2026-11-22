package net.mangomaucho.cosmeticselector;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/** Saves which cosmetic is picked for each item in config/cosmetic_selector.json */
public final class CosmeticConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("cosmetic_selector.json");

    private static final Map<Item, Identifier> selections = new HashMap<>();

    private CosmeticConfig() {}

    public static void load() {
        selections.clear();
        if (!Files.exists(FILE)) return;
        try (Reader reader = Files.newBufferedReader(FILE)) {
            Map<String, String> raw = GSON.fromJson(reader, new TypeToken<Map<String, String>>() {}.getType());
            if (raw == null) return;
            raw.forEach((itemKey, modelKey) -> {
                Identifier itemId = Identifier.tryParse(itemKey);
                Identifier modelId = Identifier.tryParse(modelKey);
                if (itemId != null && modelId != null && Registries.ITEM.containsId(itemId)) {
                    selections.put(Registries.ITEM.get(itemId), modelId);
                }
            });
        } catch (Exception e) {
            System.err.println("[Cosmetic Selector] Could not read config: " + e);
        }
    }

    public static void save() {
        Map<String, String> raw = new TreeMap<>();
        selections.forEach((item, model) -> raw.put(Registries.ITEM.getId(item).toString(), model.toString()));
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE)) {
                GSON.toJson(raw, writer);
            }
        } catch (Exception e) {
            System.err.println("[Cosmetic Selector] Could not save config: " + e);
        }
    }

    public static Identifier get(Item item) {
        return selections.get(item);
    }

    /** modelId == null means "use the default look". */
    public static void set(Item item, Identifier modelId) {
        if (modelId == null) selections.remove(item);
        else selections.put(item, modelId);
        save();
    }
}
