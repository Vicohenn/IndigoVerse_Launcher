package indigo.vip.mixin;

import indigo.vip.Conforto;
import org.pokesplash.gts.config.Config;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.UUID;

/** Taxa do GTS por nível de apoio de quem vende (1º UUID de transferFunds/revertFundTransfer). */
@Mixin(targets = "org.pokesplash.gts.api.GtsAPI", remap = false)
public class GtsTaxaMixin {
    @Redirect(method = {"transferFunds", "revertFundTransfer"}, require = 0,
            at = @At(value = "INVOKE", target = "Lorg/pokesplash/gts/config/Config;getTaxRate()D"))
    private static double indigovip$taxa(Config cfg, UUID vendedor, UUID comprador, double preco) {
        return Conforto.taxaGts(vendedor, cfg.getTaxRate());
    }
}
