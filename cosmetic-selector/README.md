# Cosmetic Selector

A client-side Fabric mod for Minecraft **1.21.11**. Press **K** to open a menu that lists every item your resource packs have cosmetics for. Click an item to see a big preview and a grid of all its looks, then click a look to use it. Your picks are saved in `config/cosmetic_selector.json`.

The swap only happens on your screen (hand, hotbar, inventory, dropped items, item frames). It works on any server, and nobody else sees it.

Needs: Fabric Loader + Fabric API.

---

## 1. Build the .jar (free, nothing to install)

1. Make a free GitHub account and create a **new repository** (for example `cosmetic-selector`).
2. Click **Add file → Upload files** and drag in **everything inside this folder**, including the hidden `.github` folder. Then click **Commit**.
   - Tip: on Mac the `.github` folder is hidden. Press `Cmd + Shift + .` in Finder to show it.
3. Open the **Actions** tab. A job called **Build mod** starts on its own and takes about 3–5 minutes.
4. When it shows a green check, click it. Under **Artifacts**, download **cosmetic-selector-jar**.
5. Unzip it and use `cosmetic-selector-1.0.0+mc1.21.11.jar`. Ignore the `-sources` jar.
6. Put the jar and Fabric API in `.minecraft/mods`.

(If you use IntelliJ IDEA, you can also open the folder and run the Gradle `build` task instead.)

## 2. Try it

Turn on `Cosmetics-Example-Pack.zip` in Resource Packs, join a world, and press **K**. The example pack only uses vanilla textures, so it works with no art. It gives the diamond sword a golden, netherite or wooden look, and has a few more examples.

## 3. Add your own looks to a pack

Each look is one file at this path:

```
assets/cosmetics/items/<item name>/<look name>.json
```

Example: a gold-handle diamond sword in MangoMaucho Pack.

**`assets/cosmetics/items/diamond_sword/gold_handle.json`** (what the mod finds)
```json
{ "model": { "type": "minecraft:model", "model": "cosmetics:item/diamond_sword/gold_handle" } }
```

**`assets/cosmetics/models/item/diamond_sword/gold_handle.json`** (the model)
```json
{ "parent": "minecraft:item/handheld", "textures": { "layer0": "cosmetics:item/diamond_sword/gold_handle" } }
```

**`assets/cosmetics/textures/item/diamond_sword/gold_handle.png`** (your texture)

- The look's name in the menu comes from the file name: `gold_handle` → "Gold Handle".
- For a custom name, add `"cosmetic.cosmetics.diamond_sword.gold_handle": "Mango Gold"` to `assets/cosmetics/lang/en_us.json`.
- Use `"parent": "minecraft:item/generated"` for items that aren't tools (totems, apples and so on).
- Animated textures work: add the usual `.png.mcmeta` next to the png.
- For items from other mods, use `assets/cosmetics/items/<modid>/<item>/<look>.json`.
- After you change the pack, click **Reload packs** in the menu.

Limit: this changes how items look. Worn armor on your body uses a different system, so it doesn't change.

## 4. Put it on Modrinth

1. Go to modrinth.com → **Create a project** → type **Mod**. Name: *Cosmetic Selector*.
2. Upload `icon.png` from `src/main/resources/assets/cosmetic_selector/` as the icon, and paste this README as the description.
3. **Versions → Create version**: upload the jar, set Loader **Fabric**, Game version **1.21.11**, Environment **Client-side only**, and add **Fabric API** as a *required* dependency.
4. You can upload the example pack as its own Modrinth **Resource Pack** project and link the two.
