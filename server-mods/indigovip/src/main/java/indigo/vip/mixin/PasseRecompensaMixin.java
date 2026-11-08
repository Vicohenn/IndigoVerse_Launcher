package indigo.vip.mixin;

import com.fredrickvonburger.trainerpass.rewards.RewardDefinition;
import indigo.vip.PassePokemon;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Pokémon configurados do Trainer Pass vão direto para o time/PC, com IVs perfeitos aleatórios. */
@Mixin(targets = "com.fredrickvonburger.trainerpass.rewards.RewardDistributor", remap = false)
public class PasseRecompensaMixin {
    @Inject(method = "grantPokemon", at = @At("HEAD"), cancellable = true, require = 0)
    private void indigovip$pokemon(ServerPlayerEntity player, RewardDefinition r, CallbackInfoReturnable<Boolean> cir) {
        if (r == null) return;
        if (PassePokemon.entregar(player, r.species, r.level, r.shiny)) cir.setReturnValue(true);
    }
}
