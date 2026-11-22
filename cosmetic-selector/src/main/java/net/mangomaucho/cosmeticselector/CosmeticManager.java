package net.mangomaucho.cosmeticselector;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;

/** Swaps an item's model for the picked cosmetic right before it is drawn. Nothing changes on the server. */
public final class CosmeticManager {
    /** Set while the menu draws the "Default" preview so the real default look shows. */
    public static boolean bypass = false;

    private CosmeticManager() {}

    public static ItemStack apply(ItemStack stack) {
        if (bypass || stack == null || stack.isEmpty()) return stack;

        Identifier picked = CosmeticConfig.get(stack.getItem());
        if (picked == null || !CosmeticRegistry.isValid(picked)) return stack;

        // Leave items alone if a server/datapack already gave them a custom model.
        Identifier current = stack.get(DataComponentTypes.ITEM_MODEL);
        Identifier normal = stack.getItem().getComponents().get(DataComponentTypes.ITEM_MODEL);
        if (current != null && !current.equals(normal)) return stack;

        ItemStack copy = stack.copy();
        copy.set(DataComponentTypes.ITEM_MODEL, picked);
        return copy;
    }
}
