package net.mangomaucho.cosmeticselector;

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

/**
 * Page 1: Swords, Pickaxes, Axes, Spears, Shovels, Hoes, Trident, Mace, Ender Pearl, Shield, Totem.
 * Page 2 (tools only): the materials, wooden to netherite.
 * Page 3: every look for that item, pictures only. Click one to use it, click it again to go back to normal.
 */
public class CosmeticScreen extends Screen {
    private static final int GOLD = 0xFFFFB000;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFAAAAAA;

    private static final int TILE = 44;
    private static final int CELL = 50;
    private static final int ROW = 22;
    private static final int BUTTON_W = 150;

    private static final String[] TOOL_TITLES = {"Swords", "Pickaxes", "Axes", "Spears", "Shovels", "Hoes"};
    private static final String[] SINGLE_TITLES = {"Trident", "Mace", "Ender Pearl", "Shield", "Totem"};

    private final Screen parent;

    /** Which tool group is open (index into TOOLS), or -1. */
    private int group = -1;
    /** Which item's looks are open, or null. */
    private Item selectedItem = null;
    private int lookPage = 0;

    private record Icon(ItemStack stack, int x, int y, float scale) {}
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

        if (selectedItem != null) initLooks();
        else if (group >= 0) initGroup();
        else initMain();

        // bottom row
        int y = height - 28;
        boolean canGoBack = selectedItem != null || group >= 0;
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
        if (selectedItem != null) {
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
        int top = 34;
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
    }

    // ---------- page 2 ----------

    private void initGroup() {
        heading = Text.literal(TOOL_TITLES[group]);
        int top = 34;
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
        icons.add(new Icon(new ItemStack(iconItem), x + 2, y + 2, 1f));
    }

    // ---------- page 3: pictures only ----------

    private void initLooks() {
        heading = new ItemStack(selectedItem).getName();
        List<CosmeticRegistry.Variant> looks = CosmeticRegistry.variantsFor(selectedItem);
        Identifier current = CosmeticConfig.get(selectedItem);

        int top = 34;
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
            icons.add(new Icon(preview, cx + (TILE - 32) / 2, cy + (TILE - 32) / 2, 2f));

            if (modelId.equals(current)) highlights.add(new Box(cx - 1, cy - 1, TILE + 2, TILE + 2));
        }

        if (pages > 1) {
            int y = bottom - 22;
            ButtonWidget prev = ButtonWidget.builder(Text.literal("<"), b -> { lookPage--; clearAndInit(); })
                    .dimensions(width / 2 - 45, y, 20, 20).build();
            ButtonWidget next = ButtonWidget.builder(Text.literal(">"), b -> { lookPage++; clearAndInit(); })
                    .dimensions(width / 2 + 25, y, 20, 20).build();
            prev.active = lookPage > 0;
            next.active = lookPage < pages - 1;
            addDrawableChild(prev);
            addDrawableChild(next);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks); // background + buttons

        context.drawCenteredTextWithShadow(textRenderer, heading, width / 2, 12, WHITE);

        if (CosmeticRegistry.items().isEmpty()) {
            int y = height / 2 - 30;
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("No looks found yet."), width / 2, y, GOLD);
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("Go to Options > Resource Packs and turn on"), width / 2, y + 16, WHITE);
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(CosmeticPackBuilder.PACK_NAME), width / 2, y + 28, GOLD);
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("New packs show up after you press Reload packs."), width / 2, y + 44, GRAY);
        }

        for (Box b : highlights) {
            context.fill(b.x(), b.y(), b.x() + b.w(), b.y() + 1, GOLD);
            context.fill(b.x(), b.y() + b.h() - 1, b.x() + b.w(), b.y() + b.h(), GOLD);
            context.fill(b.x(), b.y(), b.x() + 1, b.y() + b.h(), GOLD);
            context.fill(b.x() + b.w() - 1, b.y(), b.x() + b.w(), b.y() + b.h(), GOLD);
        }

        for (Icon icon : icons) {
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
