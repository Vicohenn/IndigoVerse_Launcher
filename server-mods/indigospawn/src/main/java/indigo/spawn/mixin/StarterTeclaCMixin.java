package indigo.spawn.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A tecla C manda o pacote RequestStarterScreen. O servidor passa a ignorar esse pedido:
 * a escolha de inicial só abre pelo NPC (comando /openstarterscreen, que não usa esse pacote).
 * Assim, quem fala com o NPC e fecha a tela sem escolher não consegue reabrir com o C.
 */
@Mixin(targets = "com.cobblemon.mod.common.net.serverhandling.starter.RequestStarterScreenHandler", remap = false)
public abstract class StarterTeclaCMixin {
    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, remap = false)
    private void indigospawn$bloquearTeclaC(CallbackInfo ci) {
        ci.cancel();
    }
}
