package indigo.vip.mixin;

import com.mojang.authlib.GameProfile;
import indigo.vip.Beneficios;
import net.minecraft.server.PlayerManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Fila prioritária: com o servidor cheio, apoiadores entram nas vagas extras do nível deles. */
@Mixin(PlayerManager.class)
public abstract class PlayerManagerMixin {
    @Inject(method = "canBypassPlayerLimit", at = @At("HEAD"), cancellable = true)
    private void indigovip$prioridade(GameProfile profile, CallbackInfoReturnable<Boolean> cir) {
        PlayerManager pm = (PlayerManager) (Object) this;
        if (Beneficios.podeUsarVagaExtra(profile.getName(), pm.getCurrentPlayerCount(), pm.getMaxPlayerCount())) {
            cir.setReturnValue(true);
        }
    }
}
