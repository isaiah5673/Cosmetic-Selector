package net.mangomaucho.cosmeticselector.mixin;

import net.mangomaucho.cosmeticselector.CosmeticPackBuilder;
import net.minecraft.resource.DirectoryResourcePack;
import net.minecraft.resource.ReloadableResourceManagerImpl;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourcePackInfo;
import net.minecraft.resource.ResourcePackSource;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Adds the looks the mod built to the game's resources every time they load.
 * It never shows up in the Resource Packs screen, and it can't be turned off by accident.
 */
@Mixin(ReloadableResourceManagerImpl.class)
public class ReloadableResourceManagerImplMixin {
    @ModifyVariable(method = "reload", at = @At("HEAD"), argsOnly = true)
    private List<ResourcePack> cosmeticSelector$addLooks(List<ResourcePack> packs) {
        try {
            CosmeticPackBuilder.ensureBuilt();
            ResourcePackInfo info = new ResourcePackInfo("cosmetic_selector_looks",
                    Text.literal("Cosmetic Selector"), ResourcePackSource.BUILTIN, Optional.empty());
            List<ResourcePack> withLooks = new ArrayList<>(packs);
            withLooks.add(new DirectoryResourcePack(info, CosmeticPackBuilder.outputDir()));
            return withLooks;
        } catch (Exception e) {
            System.err.println("[Cosmetic Selector] Could not add looks: " + e);
            return packs;
        }
    }
}
