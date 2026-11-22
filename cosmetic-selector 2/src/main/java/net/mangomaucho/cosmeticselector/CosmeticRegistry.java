package net.mangomaucho.cosmeticselector;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

import java.util.*;

/**
 * Finds cosmetic variants inside the enabled resource packs.
 *
 * A variant is any item model definition at
 *   assets/cosmetics/items/<item>/<variant>.json
 * e.g. assets/cosmetics/items/diamond_sword/gold_handle.json
 * For items from other mods use assets/cosmetics/items/<namespace>/<item>/<variant>.json
 */
public final class CosmeticRegistry {
    public static final String NAMESPACE = "cosmetics";

    public record Variant(Identifier modelId, String name) {}

    private static Map<Item, List<Variant>> variants = new LinkedHashMap<>();
    private static Set<Identifier> validModels = new HashSet<>();
    private static boolean scanned = false;

    private CosmeticRegistry() {}

    public static void rescan() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        ResourceManager rm = client.getResourceManager();
        if (rm == null) return;

        Map<Identifier, Resource> found;
        try {
            found = rm.findResources("items",
                    id -> id.getNamespace().equals(NAMESPACE) && id.getPath().endsWith(".json"));
        } catch (Exception e) {
            return;
        }

        TreeMap<String, Item> itemsById = new TreeMap<>();
        Map<Item, List<Variant>> result = new HashMap<>();
        Set<Identifier> models = new HashSet<>();

        for (Identifier fileId : found.keySet()) {
            String path = fileId.getPath();                                   // items/diamond_sword/gold.json
            String rel = path.substring("items/".length(), path.length() - ".json".length()); // diamond_sword/gold
            int slash = rel.lastIndexOf('/');
            if (slash <= 0) continue;

            String itemPart = rel.substring(0, slash);
            String variantPart = rel.substring(slash + 1);

            Identifier itemId;
            int inner = itemPart.indexOf('/');
            if (inner > 0) {
                itemId = Identifier.tryParse(itemPart.substring(0, inner) + ":" + itemPart.substring(inner + 1));
            } else {
                itemId = Identifier.tryParse("minecraft:" + itemPart);
            }
            if (itemId == null || !Registries.ITEM.containsId(itemId)) continue;

            Item item = Registries.ITEM.get(itemId);
            Identifier modelId = Identifier.of(NAMESPACE, rel);
            models.add(modelId);
            itemsById.put(itemId.toString(), item);
            result.computeIfAbsent(item, k -> new ArrayList<>())
                    .add(new Variant(modelId, displayName(rel, variantPart)));
        }

        Map<Item, List<Variant>> ordered = new LinkedHashMap<>();
        for (Item item : itemsById.values()) {
            List<Variant> list = result.get(item);
            // normal Minecraft first, then packs in folder order
            list.sort(Comparator.comparing((Variant v) -> !v.modelId().getPath().endsWith("/" + CosmeticPackBuilder.VANILLA))
                    .thenComparing(v -> v.modelId().getPath()));
            ordered.put(item, Collections.unmodifiableList(list));
        }

        variants = ordered;
        validModels = models;
        scanned = true;
    }

    private static String displayName(String rel, String variantPart) {
        String key = "cosmetic." + NAMESPACE + "." + rel.replace('/', '.');
        if (I18n.hasTranslation(key)) return I18n.translate(key);
        StringBuilder sb = new StringBuilder();
        for (String word : variantPart.split("[_\\-]")) {
            if (word.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }

    private static void ensureScanned() {
        if (!scanned) rescan();
    }

    public static List<Item> items() {
        ensureScanned();
        return new ArrayList<>(variants.keySet());
    }

    public static List<Variant> variantsFor(Item item) {
        ensureScanned();
        return variants.getOrDefault(item, List.of());
    }

    public static boolean isValid(Identifier modelId) {
        ensureScanned();
        return validModels.contains(modelId);
    }

    public static String nameOf(Identifier modelId) {
        for (List<Variant> list : variants.values()) {
            for (Variant v : list) if (v.modelId().equals(modelId)) return v.name();
        }
        return modelId.getPath();
    }
}
