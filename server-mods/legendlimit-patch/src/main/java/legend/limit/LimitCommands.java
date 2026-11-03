package legend.limit;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
        // /adicionar <jogador> <item> <1-5>: libera uma rodada nova de tentativas (máx. 2 por jogador e item)
        dispatcher.register(literal("adicionar")
                .requires(src -> src.hasPermissionLevel(2))
                .then(argument("jogador", GameProfileArgumentType.gameProfile())
                        .then(argument("item", IdentifierArgumentType.identifier())
                                .suggests((c, b) -> net.minecraft.command.CommandSource.suggestMatching(LimitConfig.get().limits.keySet(), b))
                                .then(argument("tentativas", IntegerArgumentType.integer(1, 5))
                                        .executes(ctx -> adicionar(ctx, IntegerArgumentType.getInteger(ctx, "tentativas")))))));

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
            String desc = Limites.descricao(p.getId(), item, data);
            ctx.getSource().sendFeedback(() -> Text.literal(" - " + item + ": " + usados + "/" + limite + desc), false);
        }
        return 1;
    }

    private static int adicionar(CommandContext<ServerCommandSource> ctx, int tentativas) throws CommandSyntaxException {
        GameProfile p = umJogador(ctx);
        if (p == null) return 0;
        String item = IdentifierArgumentType.getIdentifier(ctx, "item").toString();
        LimitConfig cfg = LimitConfig.get();
        if (!cfg.limits.containsKey(item)) {
            ctx.getSource().sendError(Text.literal("Esse item não é limitado pelo legendlimit: " + item));
            return 0;
        }
        UsageData data = UsageData.get(ctx.getSource().getServer());
        int feitas = data.getAdicionados(p.getId(), item);
        if (feitas >= cfg.maxAdicionar) {
            ctx.getSource().sendError(Text.literal(p.getName() + " já recebeu as " + cfg.maxAdicionar + " rodadas extras de " + item + "."));
            return 0;
        }
        if (!Limites.rodadaEncerrada(p.getId(), item, data)) {
            Integer lim = Limites.limite(p.getId(), item, data);
            ctx.getSource().sendError(Text.literal(p.getName() + " ainda tem tentativas nesta rodada ("
                    + data.getUses(p.getId(), item) + "/" + lim + "). Libere a próxima só quando ela acabar."));
            return 0;
        }
        data.novaRodada(p.getId(), item, tentativas);
        int rodada = feitas + 2;
        ctx.getSource().sendFeedback(() -> Text.literal("Rodada " + rodada + " liberada para " + p.getName() + ": "
                + item + " 0/" + tentativas + " (extras usados: " + (feitas + 1) + "/" + cfg.maxAdicionar + ")."), true);
        var online = ctx.getSource().getServer().getPlayerManager().getPlayer(p.getId());
        if (online != null) {
            Limites.avisar(online, "Você recebeu uma nova rodada de " + tentativas + " tentativa(s) de invocação (0/" + tentativas + ").");
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