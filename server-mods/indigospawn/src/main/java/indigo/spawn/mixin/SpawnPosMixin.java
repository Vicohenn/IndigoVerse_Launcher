package indigo.spawn.mixin;

import indigo.spawn.IndigoSpawnMod;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * O Minecraft procura o bloco mais alto perto do spawn do mundo (o telhado da casa) para colocar
 * jogadores novos e quem morre sem cama. Aqui ele passa a usar o ponto exato do indigospawn.json.
 */
@Mixin(ServerPlayerEntity.class)
public abstract class SpawnPosMixin {
    @Inject(method = "getWorldSpawnPos", at = @At("HEAD"), cancellable = true)
    private void indigospawn$spawnExato(ServerWorld world, BlockPos basePos, CallbackInfoReturnable<BlockPos> cir) {
        BlockPos exato = IndigoSpawnMod.spawnExato(world);
        if (exato != null) cir.setReturnValue(exato);
    }
}
