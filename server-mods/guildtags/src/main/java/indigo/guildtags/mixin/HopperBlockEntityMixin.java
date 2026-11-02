package indigo.guildtags.mixin;

import indigo.guildtags.Restricoes;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Funis não puxam itens restritos de suportes de poção, panelas do Cobblemon e máquinas de TM. */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {
    @Inject(method = "canExtract", at = @At("HEAD"), cancellable = true)
    private static void guildtags$canExtract(Inventory hopperInventory, Inventory fromInventory, ItemStack stack,
                                             int slot, Direction facing, CallbackInfoReturnable<Boolean> cir) {
        if (Restricoes.bloqueiaFunil(fromInventory, stack)) cir.setReturnValue(false);
    }
}
