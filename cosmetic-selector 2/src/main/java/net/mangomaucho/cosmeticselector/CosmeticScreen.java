package net.mangomaucho.cosmeticselector;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Page 1: Swords, Pickaxes, Axes, Spears, Shovels, Hoes, Trident, Mace, Ender Pearl, Shield, Totem.
 * Page 2 (tools only): the materials, wooden to netherite.
 * Page 3: every look for that item, pictures only. Click one to use it, click it again to go back to normal.
 */
public class CosmeticScreen extends Screen {
    private static final int GOLD = 0xFFFFB000;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFAAAAAA;
    private static final int ARMY_GREEN = 0x6EE0C0;   // light green-blue for the big title
    private static final int TOP = 62;                // where the buttons start, under the big title

    private static final int TILE = 44;
    private static final int CELL = 50;
    private static final int ROW = 22;
    private static final int BUTTON_W = 150;

    private static final String[] TOOL_TITLES = {"Swords", "Pickaxes", "Axes", "Spears", "Shovels", "Hoes"};
    private static final String[] SINGLE_TITLES = {"Trident", "Mace", "Ender Pearl", "Shield", "Totem"};
    private static final String[] HUD_TITLES = {"Hearts", "Hunger", "XP Bar"};

    private final Screen parent;

    /** Which tool group is open (index into TOOLS), or -1. */
    private int group = -1;
    /** Which item's looks are open, or null. */
    private Item selectedItem = null;
    /** True while the Choose Theme page is open. */
    private boolean themePage = false;
    /** Which HUD section's looks are open ("hearts", "hunger", "xp"), or null. */
    private String hudSection = null;
    private int lookPage = 0;

    /** Either an item (stack) or a HUD picture (sprite) to draw. */
    private record Icon(ItemStack stack, Identifier sprite, int x, int y, float scale, int w, int h) {
        static Icon item(ItemStack stack, int x, int y, float scale) { return new Icon(stack, null, x, y, scale, 16, 16); }
        static Icon picture(Identifier sprite, int x, int y, int w, int h) { return new Icon(ItemStack.EMPTY, sprite, x, y, 1f, w, h); }
    }
    private record Box(int x, int y, int w, int h) {}

    private final List<Icon> icons = new ArrayList<>();
    private final List<Box> highlights = new ArrayList<>();
    private Text heading = Text.empty();

    public CosmeticScreen(Screen parent) {
        super(Text.translatable("screen.cosmetic_selector.title"));
        this.parent = parent;
    }

    private static Item item(String id) {
        return Registries.ITEM.get(Identifier.of("minecraft", id));
    }

    private static boolean hasLooks(Item item) {
        return item != Items.AIR && !CosmeticRegistry.variantsFor(item).isEmpty();
    }

    private static List<Item> groupItems(int g) {
        List<Item> list = new ArrayList<>();
        for (String m : CosmeticPackBuilder.MATS) {
            Item it = item(m + "_" + CosmeticPackBuilder.TOOLS[g]);
            if (it != Items.AIR) list.add(it);
        }
        return list;
    }

    @Override
    protected void init() {
        CosmeticRegistry.rescan();
        icons.clear();
        highlights.clear();

        if (themePage) initThemes();
        else if (hudSection != null) initHudLooks();
        else if (selectedItem != null) initLooks();
        else if (group >= 0) initGroup();
        else initMain();

        // bottom row
        int y = height - 28;
        boolean canGoBack = themePage || hudSection != null || selectedItem != null || group >= 0;
        ButtonWidget back = ButtonWidget.builder(Text.literal("< Back"), b -> goBack())
                .dimensions(width / 2 - 154, y, 98, 20).build();
        back.active = canGoBack;
        addDrawableChild(back);
        addDrawableChild(ButtonWidget.builder(Text.literal("Reload packs"), b -> {
            CosmeticPackBuilder.build();
            if (client != null) client.reloadResources();
        }).dimensions(width / 2 - 50, y, 100, 20).build());
        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, b -> close())
                .dimensions(width / 2 + 56, y, 98, 20).build());
    }

    private void goBack() {
        if (themePage) {
            themePage = false;
        } else if (hudSection != null) {
            hudSection = null;
        } else if (selectedItem != null) {
            selectedItem = null;   // back to the material list (or the main page for single items)
        } else {
            group = -1;
        }
        lookPage = 0;
        clearAndInit();
    }

    // ---------- page 1 ----------

    private void initMain() {
        heading = title;
        int top = TOP;
        int leftX = width / 2 - BUTTON_W - 6;
        int rightX = width / 2 + 6;

        for (int g = 0; g < CosmeticPackBuilder.TOOLS.length; g++) {
            final int index = g;
            List<Item> items = groupItems(g);
            boolean any = items.stream().anyMatch(CosmeticScreen::hasLooks);
            Item iconItem = items.isEmpty() ? Items.BARRIER : items.get(Math.min(5, items.size() - 1));
            addRow(leftX, top + g * ROW, TOOL_TITLES[g], iconItem, any, () -> { group = index; });
        }
        for (int i = 0; i < CosmeticPackBuilder.SINGLES.length; i++) {
            Item it = item(CosmeticPackBuilder.SINGLES[i]);
            addRow(rightX, top + i * ROW, SINGLE_TITLES[i], it, hasLooks(it), () -> { selectedItem = it; });
        }
        // hearts, hunger, XP bar (below the items on the right)
        for (int h = 0; h < CosmeticPackBuilder.HUD_SECTIONS.length; h++) {
            String sec = CosmeticPackBuilder.HUD_SECTIONS[h];
            int y = top + (CosmeticPackBuilder.SINGLES.length + h) * ROW;
            ButtonWidget button = ButtonWidget.builder(Text.literal(HUD_TITLES[h]), b -> {
                hudSection = sec;
                lookPage = 0;
                clearAndInit();
            }).dimensions(rightX + 22, y, BUTTON_W - 22, 20).build();
            button.active = !CosmeticPackBuilder.hudLooks(sec).isEmpty();
            addDrawableChild(button);
            Identifier icon = Identifier.of("minecraft", hudIconSprite(sec));
            if (sec.equals("xp")) icons.add(Icon.picture(icon, rightX + 1, y + 8, 18, 4));
            else icons.add(Icon.picture(icon, rightX + 3, y + 5, 11, 11));
        }

        // switch everything to one pack at once
        int rowsUsed = Math.max(CosmeticPackBuilder.TOOLS.length, CosmeticPackBuilder.SINGLES.length + CosmeticPackBuilder.HUD_SECTIONS.length);
        int themeY = top + rowsUsed * ROW + 8;
        addDrawableChild(ButtonWidget.builder(Text.literal("Choose Theme"), b -> {
            themePage = true;
            lookPage = 0;
            clearAndInit();
        }).dimensions(width / 2 - 100, themeY, 200, 20).build());
    }

    // ---------- Choose Theme page ----------

    private void initThemes() {
        heading = Text.literal("Choose Theme");
        List<CosmeticPackBuilder.Theme> themes = CosmeticPackBuilder.themes();
        int top = TOP;
        int perPage = Math.max(1, (height - 34 - 26 - top) / ROW);
        int pages = Math.max(1, (themes.size() + perPage - 1) / perPage);
        lookPage = Math.max(0, Math.min(lookPage, pages - 1));
        int start = lookPage * perPage;
        int w = 220;
        for (int i = start; i < Math.min(themes.size(), start + perPage); i++) {
            CosmeticPackBuilder.Theme theme = themes.get(i);
            String label = "Choose theme for " + theme.name();
            addDrawableChild(ButtonWidget.builder(Text.literal(textRenderer.trimToWidth(label, w - 10)), b -> {
                applyTheme(theme);
                themePage = false;
                clearAndInit();
            }).dimensions(width / 2 - w / 2, top + (i - start) * ROW, w, 20).build());
        }
        addPageButtons(pages, height - 56);
    }

    private static String hudIconSprite(String sec) {
        return switch (sec) {
            case "hearts" -> "hud/heart/full";
            case "hunger" -> "hud/food_full";
            default -> "hud/experience_bar_progress";
        };
    }

    // ---------- hearts / hunger / XP bar looks: pictures only ----------

    private void initHudLooks() {
        int index = java.util.Arrays.asList(CosmeticPackBuilder.HUD_SECTIONS).indexOf(hudSection);
        heading = Text.literal(index >= 0 ? HUD_TITLES[index] : hudSection);
        List<CosmeticPackBuilder.HudLook> looks = CosmeticPackBuilder.hudLooks(hudSection);
        String current = HudLooks.get(hudSection);

        int top = TOP;
        int bottom = height - 34;
        int areaW = Math.min(width - 20, 8 * CELL);
        int cols = Math.max(1, areaW / CELL);
        int rows = Math.max(1, (bottom - 24 - top) / CELL);
        int per = cols * rows;
        int pages = Math.max(1, (looks.size() + per - 1) / per);
        lookPage = Math.max(0, Math.min(lookPage, pages - 1));
        int shown = Math.min(cols, looks.size());
        int startX = width / 2 - (shown * CELL) / 2 + (CELL - TILE) / 2;

        int start = lookPage * per;
        for (int i = start; i < Math.min(looks.size(), start + per); i++) {
            CosmeticPackBuilder.HudLook look = looks.get(i);
            int idx = i - start;
            int cx = startX + (idx % cols) * CELL;
            int cy = top + (idx / cols) * CELL;
            String section = hudSection;
            addDrawableChild(ButtonWidget.builder(Text.empty(), b -> {
                // click the one you're using again to go back to your normal pack's look
                HudLooks.set(section, look.slug().equals(HudLooks.get(section)) ? null : look.slug());
                clearAndInit();
            }).dimensions(cx, cy, TILE, TILE).build());

            Identifier sprite = HudLooks.sprite(look.slug(), look.preview());
            if (look.preview().startsWith("hud/experience_bar")) {
                // empty bar on top, full bar underneath
                if (look.sprites().contains("hud/experience_bar_background"))
                    icons.add(Icon.picture(HudLooks.sprite(look.slug(), "hud/experience_bar_background"),
                            cx + 4, cy + TILE / 2 - 8, TILE - 8, 6));
                icons.add(Icon.picture(sprite, cx + 4, cy + TILE / 2 + 2, TILE - 8, 6));
            } else {
                icons.add(Icon.picture(sprite, cx + (TILE - 27) / 2, cy + (TILE - 27) / 2, 27, 27));
            }
            if (look.slug().equals(current)) highlights.add(new Box(cx - 1, cy - 1, TILE + 2, TILE + 2));
        }
        addPageButtons(pages, bottom - 22);
    }

    private static void applyTheme(CosmeticPackBuilder.Theme theme) {
        for (Map.Entry<String, String> e : theme.lookForItem().entrySet()) {
            if (e.getKey().startsWith("hud_")) {
                HudLooks.set(e.getKey().substring("hud_".length()), e.getValue());
                continue;
            }
            Item it = item(e.getKey());
            if (it == Items.AIR) continue;
            Identifier model = Identifier.of("cosmetics", e.getKey() + "/" + e.getValue());
            if (CosmeticRegistry.isValid(model)) CosmeticConfig.set(it, model);
        }
    }

    private void addPageButtons(int pages, int y) {
        if (pages <= 1) return;
        ButtonWidget prev = ButtonWidget.builder(Text.literal("<"), b -> { lookPage--; clearAndInit(); })
                .dimensions(width / 2 - 45, y, 20, 20).build();
        ButtonWidget next = ButtonWidget.builder(Text.literal(">"), b -> { lookPage++; clearAndInit(); })
                .dimensions(width / 2 + 25, y, 20, 20).build();
        prev.active = lookPage > 0;
        next.active = lookPage < pages - 1;
        addDrawableChild(prev);
        addDrawableChild(next);
    }

    // ---------- page 2 ----------

    private void initGroup() {
        heading = Text.literal(TOOL_TITLES[group]);
        int top = TOP;
        int x = width / 2 - BUTTON_W / 2;
        List<Item> items = groupItems(group);
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            addRow(x, top + i * ROW, new ItemStack(it).getName().getString(), it, hasLooks(it), () -> { selectedItem = it; });
        }
    }

    private void addRow(int x, int y, String label, Item iconItem, boolean active, Runnable open) {
        ButtonWidget button = ButtonWidget.builder(Text.literal(label), b -> {
            open.run();
            lookPage = 0;
            clearAndInit();
        }).dimensions(x + 22, y, BUTTON_W - 22, 20).build();
        button.active = active;
        addDrawableChild(button);
        icons.add(Icon.item(new ItemStack(iconItem), x + 2, y + 2, 1f));
    }

    // ---------- page 3: pictures only ----------

    private void initLooks() {
        heading = new ItemStack(selectedItem).getName();
        List<CosmeticRegistry.Variant> looks = CosmeticRegistry.variantsFor(selectedItem);
        Identifier current = CosmeticConfig.get(selectedItem);

        int top = TOP;
        int bottom = height - 34;
        int areaW = Math.min(width - 20, 8 * CELL);
        int cols = Math.max(1, areaW / CELL);
        int rows = Math.max(1, (bottom - 24 - top) / CELL);
        int per = cols * rows;
        int pages = Math.max(1, (looks.size() + per - 1) / per);
        lookPage = Math.max(0, Math.min(lookPage, pages - 1));

        int shown = Math.min(cols, looks.size());
        int startX = width / 2 - (shown * CELL) / 2 + (CELL - TILE) / 2;

        int start = lookPage * per;
        for (int i = start; i < Math.min(looks.size(), start + per); i++) {
            Identifier modelId = looks.get(i).modelId();
            int idx = i - start;
            int cx = startX + (idx % cols) * CELL;
            int cy = top + (idx / cols) * CELL;

            addDrawableChild(ButtonWidget.builder(Text.empty(), b -> {
                // click the one you're using again to go back to your normal pack's look
                CosmeticConfig.set(selectedItem, modelId.equals(CosmeticConfig.get(selectedItem)) ? null : modelId);
                clearAndInit();
            }).dimensions(cx, cy, TILE, TILE).build());

            ItemStack preview = new ItemStack(selectedItem);
            preview.set(DataComponentTypes.ITEM_MODEL, modelId);
            icons.add(Icon.item(preview, cx + (TILE - 32) / 2, cy + (TILE - 32) / 2, 2f));

            if (modelId.equals(current)) highlights.add(new Box(cx - 1, cy - 1, TILE + 2, TILE + 2));
        }

        addPageButtons(pages, bottom - 22);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks); // background + buttons

        // big bold green title
        Text big = heading.copy().styled(st -> st.withBold(true).withColor(ARMY_GREEN));
        context.getMatrices().pushMatrix();
        context.getMatrices().translate(width / 2f, 14);
        context.getMatrices().scale(2f, 2f);
        context.drawCenteredTextWithShadow(textRenderer, big, 0, 0, 0xFF000000 | ARMY_GREEN);
        context.getMatrices().popMatrix();

        if (CosmeticRegistry.items().isEmpty()) {
            int y = height / 2 - 20;
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("No looks found yet."), width / 2, y, GOLD);
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("Put resource packs in your resourcepacks folder,"), width / 2, y + 16, WHITE);
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("then press Reload packs."), width / 2, y + 28, GRAY);
        }

        for (Box b : highlights) {
            context.fill(b.x(), b.y(), b.x() + b.w(), b.y() + 1, GOLD);
            context.fill(b.x(), b.y() + b.h() - 1, b.x() + b.w(), b.y() + b.h(), GOLD);
            context.fill(b.x(), b.y(), b.x() + 1, b.y() + b.h(), GOLD);
            context.fill(b.x() + b.w() - 1, b.y(), b.x() + b.w(), b.y() + b.h(), GOLD);
        }

        for (Icon icon : icons) {
            if (icon.sprite() != null) {
                context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, icon.sprite(), icon.x(), icon.y(), icon.w(), icon.h());
                continue;
            }
            context.getMatrices().pushMatrix();
            context.getMatrices().translate(icon.x(), icon.y());
            context.getMatrices().scale(icon.scale(), icon.scale());
            context.drawItem(icon.stack(), 0, 0);
            context.getMatrices().popMatrix();
        }
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }
}
