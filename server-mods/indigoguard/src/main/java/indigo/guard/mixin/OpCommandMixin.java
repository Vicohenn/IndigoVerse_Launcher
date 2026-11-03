package indigo.guard.mixin;

import com.mojang.authlib.GameProfile;
import indigo.guard.OpWatch;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.dedicated.command.OpCommand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;

/** Vê quem usa /op e para quem. */
@Mixin(OpCommand.class)
public abstract class OpCommandMixin {
    @Inject(method = "op", at = @At("HEAD"))
    private static void indigoguard$aoDarOp(ServerCommandSource source, Collection<GameProfile> targets,
                                           CallbackInfoReturnable<Integer> cir) {
        try {
            OpWatch.comandoOp(source, targets);
        } catch (Throwable ignored) { }
    }
}
