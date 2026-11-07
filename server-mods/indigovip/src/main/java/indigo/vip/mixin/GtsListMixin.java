package indigo.vip.mixin;

import com.mojang.brigadier.context.CommandContext;
import indigo.vip.Conforto;
import net.minecraft.server.command.ServerCommandSource;
import org.pokesplash.gts.config.Config;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Vagas no GTS por nível de apoio: troca o limite fixo da config pelo limite do jogador. */
@Mixin(targets = "org.pokesplash.gts.command.subcommand.List", remap = false)
public class GtsListMixin {
    @Redirect(method = {"run", "runPokemon", "runItem"}, require = 0,
            at = @At(value = "INVOKE", target = "Lorg/pokesplash/gts/config/Config;getMaxListingsPerPlayer()I"))
    private int indigovip$limite(Config cfg, CommandContext<ServerCommandSource> ctx) {
        return Conforto.limiteGts(ctx.getSource().getPlayer(), cfg.getMaxListingsPerPlayer());
    }
}
