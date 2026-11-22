package net.mangomaucho.cosmeticselector.mixin;

import net.mangomaucho.cosmeticselector.CosmeticManager;
import net.minecraft.client.item.ItemModelManager;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ItemModelManager.class)
public class ItemModelManagerMixin {
    // Every item drawn anywhere (hand, hotbar, inventory, dropped, item frames) goes through update().
    @ModifyVariable(method = "update", at = @At("HEAD"), argsOnly = true)
    private ItemStack cosmeticSelector$swapModel(ItemStack stack) {
        return CosmeticManager.apply(stack);
    }
}
