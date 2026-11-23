package net.mangomaucho.cosmeticselector.mixin;

import net.mangomaucho.cosmeticselector.HudLooks;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Swaps the hearts, hunger and XP bar pictures for the ones you picked. */
@Mixin(DrawContext.class)
public class DrawContextMixin {
    @ModifyVariable(method = {
            "drawGuiTexture(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIII)V",
            "drawGuiTexture(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIIII)V",
            "drawGuiTexture(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIIIIIII)V",
            "drawGuiTexture(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIIIIIIII)V"
    }, at = @At("HEAD"), argsOnly = true, require = 0)
    private Identifier cosmeticSelector$hudLook(Identifier sprite) {
        return HudLooks.remap(sprite);
    }
}
