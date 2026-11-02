package indigo.guildtags.mixin;

import indigo.guildtags.Restricoes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.slot.Slot;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Impede pegar o resultado de um craft restrito (clique, shift-clique, tecla numérica, Q). */
@Mixin(Slot.class)
public abstract class SlotMixin {
    @Inject(method = "canTakeItems", at = @At("HEAD"), cancellable = true)
    private void guildtags$canTakeItems(PlayerEntity player, CallbackInfoReturnable<Boolean> cir) {
        if (player instanceof ServerPlayerEntity sp && Restricoes.bloqueiaRetirada(sp, (Slot) (Object) this)) {
            cir.setReturnValue(false);
        }
    }
}
