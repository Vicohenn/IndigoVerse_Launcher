package indigo.coins.mixin;

import indigo.coins.Amuleto;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.math.BigInteger;

/**
 * Amulet Coin: dobra o valor que o CobbleDollars dá ao vencer batalhas (NPCs, líderes, Pokémon selvagens).
 * earnCobbleDollars só é usado pelas recompensas de batalha (vender para o banco/loja não passa por aqui).
 */
@Pseudo
@Mixin(targets = "fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt", remap = false)
public abstract class CobbleDollarsGanhoMixin {
    @ModifyVariable(method = "earnCobbleDollars(Lnet/minecraft/class_1657;Ljava/math/BigInteger;Z)Z",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, remap = false)
    private static BigInteger indigocoins$amuleto(BigInteger valor, PlayerEntity jogador) {
        return Amuleto.aplicar(jogador, valor);
    }
}
