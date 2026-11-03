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
                // usado pela loja/NPC: /legendlimit comprar <jogador> <item> [quantidade]
                .then(literal("comprar")
                        .then(argument("jogador", GameProfileArgumentType.gameProfile())
                                .then(argument("item", IdentifierArgumentType.identifier())
                                        .executes(ctx -> comprar(ctx, 1))
                                        .then(argument("quantidade", IntegerArgumentType.integer(1, 100))
                                                .executes(ctx -> comprar(ctx, IntegerArgumentType.getInteger(ctx, "quantidade")))))))
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

    private static int comprar(CommandContext<ServerCommandSource> ctx, int qtd) throws CommandSyntaxException {
        GameProfile p = umJogador(ctx);
        if (p == null) return 0;
        String item = IdentifierArgumentType.getIdentifier(ctx, "item").toString();
        if (!LimitConfig.get().limits.containsKey(item)) {
            ctx.getSource().sendError(Text.literal("Esse item não é limitado pelo legendlimit: " + item));
            return 0;
        }
        UsageData data = UsageData.get(ctx.getSource().getServer());
        data.addExtras(p.getId(), item, qtd);
        Integer limite = Limites.limite(p.getId(), item, data);
        int usados = data.getUses(p.getId(), item);
        ctx.getSource().sendFeedback(() -> Text.literal("+" + qtd + " uso(s) de " + item + " para " + p.getName()
                + " (" + usados + "/" + limite + ")."), true);
        var online = ctx.getSource().getServer().getPlayerManager().getPlayer(p.getId());
        if (online != null) {
            online.sendMessage(Text.literal("§6Você recebeu " + qtd + " tentativa(s) extra(s) de invocação (" + usados + "/" + limite + ")."), false);
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