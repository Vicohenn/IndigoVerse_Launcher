package indigo.spawn.mixin;

import indigo.spawn.Protecao;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.explosion.Explosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Explosões (creeper, TNT, etc.) não destroem blocos dentro das áreas protegidas. */
@Mixin(Explosion.class)
public abstract class ExplosionMixin {
    @Shadow @Final private World world;

    @Shadow public abstract List<BlockPos> getToDestroy();

    @Inject(method = "affectWorld", at = @At("HEAD"))
    private void indigospawn$proteger(boolean particles, CallbackInfo ci) {
        if (world.isClient) return;
        getToDestroy().removeIf(p -> Protecao.areaEm(world, p) != null);
    }
}
