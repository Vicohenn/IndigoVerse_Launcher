package legend.limit;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.GameProfileArgumentType;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.Collection;
import java.util.Map;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class LimitCommands {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher,
                                CommandRegistryAccess registryAccess,
                                CommandManager.RegistrationEnvironment environment) {
        dispatcher.register(literal("legendlimit")
                .requires(src -> src.hasPermissionLevel(2))
                .then(literal("ver")
                        .then(argument("jogador", GameProfileArgumentType.gameProfile())
                                .executes(LimitCommands::ver)))
                .then(literal("resetar")
                        .then(argument("jogador", GameProfileArgumentType.gameProfile())
                                .executes(ctx -> resetar(ctx, null))
                                .then(argument("item", IdentifierArgumentType.identifier())
                                        .executes(ctx -> resetar(ctx,
                                                IdentifierArgumentType.getIdentifier(ctx, "item"))))))
                .then(literal("recarregar")
                        .executes(ctx -> {
                            LimitConfig.load();
                            ctx.getSource().sendFeedback(() -> Text.literal("Configuração recarregada."), true);
                            return 1;
                        })));
    }

    private static GameProfile umJogador(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        Collection<GameProfile> perfis = GameProfileArgumentType.getProfileArgument(ctx, "jogador");
        if (perfis.size() != 1) {
            ctx.getSource().sendError(Text.literal("Selecione exatamente um jogador."));
            return null;
        }
        return perfis.iterator().next();
    }

    private static int ver(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        GameProfile p = umJogador(ctx);
        if (p == null) return 0;
        UsageData data = UsageData.get(ctx.getSource().getServer());
        ctx.getSource().sendFeedback(() -> Text.literal("Usos de " + p.getName() + ":"), false);
        for (String item : LimitConfig.get().limits.keySet()) {
            int usados = data.getUses(p.getId(), item);
            Integer limite = Limites.limite(p.getId(), item, data);
            int extras = data.getExtras(p.getId(), item);
            String cap = LimitConfig.get().itemSpecies.containsKey(item)
                    ? (Limites.capturouItem(p.getId(), item) ? " [capturado]" : " [não capturado]") : "";
            ctx.getSource().sendFeedback(
                    () -> Text.literal(" - " + item + ": " + usados + "/" + limite + cap + (extras > 0 ? " (+" + extras + " comprados)" : "")), false);
        }
        return 1;
    }

    private static int resetar(CommandContext<ServerCommandSource> ctx, Identifier item) throws CommandSyntaxException {
        GameProfile p = umJogador(ctx);
        if (p == null) return 0;
        UsageData data = UsageData.get(ctx.getSource().getServer());
        if (item == null) data.reset(p.getId());
        else data.reset(p.getId(), item.toString());
        ctx.getSource().sendFeedback(
                () -> Text.literal("Contagem de " + p.getName() + " resetada."), true);
        return 1;
    }
}