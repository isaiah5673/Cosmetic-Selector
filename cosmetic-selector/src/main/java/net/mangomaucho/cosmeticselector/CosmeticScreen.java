package net.mangomaucho.cosmeticselector;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Left side: every item that has cosmetics in your packs.
 * Right side: a big preview plus a grid of every look for the chosen item. Click one to use it.
 */
public class CosmeticScreen extends Screen {
    private static final int GOLD = 0xFFFFB000;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFAAAAAA;

    private static final int TILE = 44;
    private static final int CELL_W = 56;
    private static final int CELL_H = 58;

    private final Screen parent;

    private List<Item> items = List.of();
    private Item selectedItem;
    private int itemPage = 0;
    private int variantPage = 0;

    private record Icon(ItemStack stack, int x, int y, float scale, boolean showDefault) {}
    private record Label(Text text, int centerX, int y, int color) {}
    private record Box(int x, int y, int w, int h) {}

    private final List<Icon> icons = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();
    private final List<Box> highlights = new ArrayList<>();

    private int previewX, previewY;

    public CosmeticScreen(Screen parent) {
        super(Text.translatable("screen.cosmetic_selector.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        CosmeticRegistry.rescan();
        items = CosmeticRegistry.items();
        if (selectedItem == null || !items.contains(selectedItem)) {
            selectedItem = items.isEmpty() ? null : items.get(0);
            variantPage = 0;
        }

        icons.clear();
        labels.clear();
        highlights.clear();

        int top = 30;
        int bottom = height - 34;

        // ---------- Left: item list ----------
        int listX = 10;
        int listW = Math.min(150, width / 3);
        int rowH = 22;
        int listBottom = bottom - 24;
        int perPage = Math.max(1, (listBottom - top) / rowH);
        int itemPages = Math.max(1, (items.size() + perPage - 1) / perPage);
        itemPage = Math.max(0, Math.min(itemPage, itemPages - 1));

        int start = itemPage * perPage;
        for (int i = start; i < Math.min(items.size(), start + perPage); i++) {
            Item item = items.get(i);
            int y = top + (i - start) * rowH;
            ItemStack stack = new ItemStack(item);
            String name = textRenderer.trimToWidth(stack.getName().getString(), listW - 30);
            addDrawableChild(ButtonWidget.builder(Text.literal(name), b -> {
                selectedItem = item;
                variantPage = 0;
                clearAndInit();
            }).dimensions(listX + 22, y, listW - 22, 20).build());
            icons.add(new Icon(stack, listX + 2, y + 2, 1f, false)); // shows the look you have on now
            if (item == selectedItem) highlights.add(new Box(listX, y, listW, 20));
        }

        if (itemPages > 1) {
            int y = listBottom + 2;
            ButtonWidget prev = ButtonWidget.builder(Text.literal("<"), b -> { itemPage--; clearAndInit(); })
                    .dimensions(listX, y, 20, 20).build();
            ButtonWidget next = ButtonWidget.builder(Text.literal(">"), b -> { itemPage++; clearAndInit(); })
                    .dimensions(listX + listW - 20, y, 20, 20).build();
            prev.active = itemPage > 0;
            next.active = itemPage < itemPages - 1;
            addDrawableChild(prev);
            addDrawableChild(next);
            labels.add(new Label(Text.literal((itemPage + 1) + " / " + itemPages), listX + listW / 2, y + 6, GRAY));
        }

        // ---------- Right: preview + variant grid ----------
        int rx = listX + listW + 16;
        int rw = width - rx - 10;

        if (selectedItem != null) {
            previewX = rx;
            previewY = top;
            icons.add(new Icon(new ItemStack(selectedItem), rx + 4, top + 4, 3f, false)); // 48px preview
            Identifier current = CosmeticConfig.get(selectedItem);
            String currentName = current == null ? "Default" : CosmeticRegistry.nameOf(current);
            labels.add(new Label(new ItemStack(selectedItem).getName(), -1, top + 12, WHITE));
            labels.add(new Label(Text.literal("Using: " + currentName), -1, top + 26, GOLD));

            List<CosmeticRegistry.Variant> variants = CosmeticRegistry.variantsFor(selectedItem);
            List<CosmeticRegistry.Variant> options = new ArrayList<>();
            options.add(null); // "Default"
            options.addAll(variants);

            int gridTop = top + 64;
            int cols = Math.max(1, rw / CELL_W);
            int rows = Math.max(1, (bottom - 24 - gridTop) / CELL_H);
            int per = cols * rows;
            int pages = Math.max(1, (options.size() + per - 1) / per);
            variantPage = Math.max(0, Math.min(variantPage, pages - 1));

            int vStart = variantPage * per;
            for (int i = vStart; i < Math.min(options.size(), vStart + per); i++) {
                CosmeticRegistry.Variant v = options.get(i);
                int idx = i - vStart;
                int cx = rx + (idx % cols) * CELL_W;
                int cy = gridTop + (idx / cols) * CELL_H;

                String name = v == null ? "Default" : v.name();
                Identifier modelId = v == null ? null : v.modelId();

                addDrawableChild(ButtonWidget.builder(Text.empty(), b -> {
                            CosmeticConfig.set(selectedItem, modelId);
                            clearAndInit();
                        })
                        .dimensions(cx, cy, TILE, TILE)
                        .tooltip(Tooltip.of(Text.literal(name)))
                        .build());

                ItemStack preview = new ItemStack(selectedItem);
                if (modelId != null) preview.set(DataComponentTypes.ITEM_MODEL, modelId);
                icons.add(new Icon(preview, cx + (TILE - 32) / 2, cy + (TILE - 32) / 2, 2f, v == null));

                labels.add(new Label(Text.literal(textRenderer.trimToWidth(name, CELL_W - 2)),
                        cx + TILE / 2, cy + TILE + 3, WHITE));

                boolean picked = (modelId == null && current == null) || (modelId != null && modelId.equals(current));
                if (picked) highlights.add(new Box(cx - 1, cy - 1, TILE + 2, TILE + 2));
            }

            if (pages > 1) {
                int y = bottom - 22;
                ButtonWidget prev = ButtonWidget.builder(Text.literal("<"), b -> { variantPage--; clearAndInit(); })
                        .dimensions(rx, y, 20, 20).build();
                ButtonWidget next = ButtonWidget.builder(Text.literal(">"), b -> { variantPage++; clearAndInit(); })
                        .dimensions(rx + 70, y, 20, 20).build();
                prev.active = variantPage > 0;
                next.active = variantPage < pages - 1;
                addDrawableChild(prev);
                addDrawableChild(next);
                labels.add(new Label(Text.literal((variantPage + 1) + " / " + pages), rx + 45, y + 6, GRAY));
            }
        }

        // ---------- Bottom buttons ----------
        addDrawableChild(ButtonWidget.builder(Text.literal("Reload packs"), b -> {
            if (client != null) client.reloadResources();
        }).dimensions(width / 2 - 154, height - 28, 150, 20).build());
        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, b -> close())
                .dimensions(width / 2 + 4, height - 28, 150, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks); // background + buttons

        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 10, WHITE);

        if (items.isEmpty()) {
            int y = height / 2 - 30;
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("No cosmetics found in your resource packs."), width / 2, y, GOLD);
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("Put item model files here in a pack:"), width / 2, y + 16, WHITE);
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("assets/cosmetics/items/<item>/<look>.json"), width / 2, y + 28, GRAY);
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("then turn the pack on and press Reload packs."), width / 2, y + 44, WHITE);
        }

        for (Box b : highlights) {
            context.fill(b.x(), b.y(), b.x() + b.w(), b.y() + 1, GOLD);
            context.fill(b.x(), b.y() + b.h() - 1, b.x() + b.w(), b.y() + b.h(), GOLD);
            context.fill(b.x(), b.y(), b.x() + 1, b.y() + b.h(), GOLD);
            context.fill(b.x() + b.w() - 1, b.y(), b.x() + b.w(), b.y() + b.h(), GOLD);
        }

        if (selectedItem != null) {
            context.fill(previewX, previewY, previewX + 56, previewY + 56, 0x66000000);
        }

        for (Icon icon : icons) {
            CosmeticManager.bypass = icon.showDefault();
            try {
                context.getMatrices().pushMatrix();
                context.getMatrices().translate(icon.x(), icon.y());
                context.getMatrices().scale(icon.scale(), icon.scale());
                context.drawItem(icon.stack(), 0, 0);
                context.getMatrices().popMatrix();
            } finally {
                CosmeticManager.bypass = false;
            }
        }

        for (Label l : labels) {
            if (l.centerX() < 0) {
                context.drawTextWithShadow(textRenderer, l.text(), previewX + 64, l.y(), l.color());
            } else {
                context.drawCenteredTextWithShadow(textRenderer, l.text(), l.centerX(), l.y(), l.color());
            }
        }
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }
}
