package indigo.vip;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * IndigoVIP
 *   /vip                              mostra o seu apoio
 *   /vip ver <nick>                   (OP)
 *   /vip dar <nick> <nivel> <dias>    (OP) dias = 0 deixa permanente
 *   /vip remover <nick>               (OP)
 *   /vip lista                        (OP)
 *   /vip recarregar                   (OP)
 */
public final class IndigoVipMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("indigovip");
    private static int ticks;

    private static final SuggestionProvider<ServerCommandSource> NIVEIS = (c, b) ->
            CommandSource.suggestMatching(VipConfig.get().niveis.stream().map(n -> n.id), b);
    private static final SuggestionProvider<ServerCommandSource> JOGADORES = (c, b) ->
            CommandSource.suggestMatching(c.getSource().getServer().getPlayerNames(), b);
    private static final com.mojang.brigadier.suggestion.SuggestionProvider<ServerCommandSource> CARGOS = (c, b) ->
            CommandSource.suggestMatching(VipConfig.get().cargosStaff.stream().map(x -> x.id).toList(), b);

    @Override
    public void onInitialize() {
        VipConfig.load();
        Apoiadores.load();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            Fila.criarPastas();
            Tags.criarTimes(server);
            for (String nick : Apoiadores.todos().values().stream().map(r -> r.nick).toList()) Tags.aplicar(server, nick);
            Entrega.verificarExpirados(server);
            DiscordFila.iniciar(server);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> DiscordFila.parar());

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            ticks++;
            if (ticks % 100 == 0) Fila.processar(server);                 // a cada 5 s
            if (ticks % 1200 == 0) Entrega.verificarExpirados(server);    // a cada 1 min
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity p = handler.player;
            String nick = p.getGameProfile().getName();
            Tags.aplicar(server, nick);
            Apoiadores.Registro r = Apoiadores.de(nick);
            if (Apoiadores.ativo(r) && r.expiraEm != 0 && r.expiraEm - System.currentTimeMillis() < 3 * Entrega.DIA) {
                p.sendMessage(Text.literal("§eSeu apoio termina " + Entrega.validade(r.expiraEm) + ". Obrigado por apoiar o IndigoVerse!"));
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> dispatcher.register(
                literal("vip")
                        .executes(IndigoVipMod::meu)
                        .then(literal("ver").requires(s -> s.hasPermissionLevel(2))
                                .then(argument("nick", StringArgumentType.word()).suggests(JOGADORES)
                                        .executes(c -> mostrar(c, StringArgumentType.getString(c, "nick")))))
                        .then(literal("dar").requires(s -> s.hasPermissionLevel(2))
                                .then(argument("nick", StringArgumentType.word()).suggests(JOGADORES)
                                        .then(argument("nivel", StringArgumentType.word()).suggests(NIVEIS)
                                                .then(argument("dias", IntegerArgumentType.integer(0, 3650))
                                                        .executes(IndigoVipMod::dar)))))
                        .then(literal("remover").requires(s -> s.hasPermissionLevel(2))
                                .then(argument("nick", StringArgumentType.word()).suggests(JOGADORES)
                                        .executes(c -> {
                                            String nick = StringArgumentType.getString(c, "nick");
                                            boolean ok = Entrega.removerVip(c.getSource().getServer(), nick, "removido pela staff");
                                            if (ok) c.getSource().sendFeedback(() -> Text.literal("§aApoio de " + nick + " removido."), true);
                                            else c.getSource().sendError(Text.literal(nick + " não tem apoio registrado."));
                                            return ok ? 1 : 0;
                                        })))
                        .then(literal("lista").requires(s -> s.hasPermissionLevel(2)).executes(IndigoVipMod::lista))
                        .then(literal("recarregar").requires(s -> s.hasPermissionLevel(2)).executes(c -> {
                            VipConfig.load();
                            Tags.criarTimes(c.getSource().getServer());
                            DiscordFila.iniciar(c.getSource().getServer());
                            c.getSource().sendFeedback(() -> Text.literal("§aIndigoVIP recarregado."), true);
                            return 1;
                        }))));

        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> dispatcher.register(
                literal("cargo").requires(s -> s.hasPermissionLevel(3))
                        .then(literal("definir")
                                .then(argument("nick", StringArgumentType.word()).suggests(JOGADORES)
                                        .then(argument("cargo", StringArgumentType.word()).suggests(CARGOS)
                                                .executes(IndigoVipMod::definirCargo))))
                        .then(literal("remover")
                                .then(argument("nick", StringArgumentType.word()).suggests(JOGADORES)
                                        .executes(IndigoVipMod::removerCargo)))
                        .then(literal("lista").executes(IndigoVipMod::listaCargos))));

        LOGGER.info("IndigoVIP carregado: {} níveis, {} apoiadores.", VipConfig.get().niveis.size(), Apoiadores.todos().size());
    }

    private static int meu(CommandContext<ServerCommandSource> c) {
        if (!(c.getSource().getEntity() instanceof ServerPlayerEntity p)) {
            c.getSource().sendError(Text.literal("Use /vip ver <nick> no console."));
            return 0;
        }
        return mostrar(c, p.getGameProfile().getName());
    }

    private static int mostrar(CommandContext<ServerCommandSource> c, String nick) {
        Apoiadores.Registro r = Apoiadores.de(nick);
        VipConfig.Nivel n = Apoiadores.ativo(r) ? VipConfig.get().nivel(r.nivel) : null;
        String msg = n == null
                ? "§7" + nick + " não tem apoio ativo. Apoie o servidor pelo Discord!"
                : "§f" + nick + "§7: " + Tags.cor(n.prefixo) + "§7" + Entrega.validade(r.expiraEm);
        c.getSource().sendFeedback(() -> Text.literal(msg), false);
        return 1;
    }

    private static int dar(CommandContext<ServerCommandSource> c) {
        String nick = StringArgumentType.getString(c, "nick");
        String nivel = StringArgumentType.getString(c, "nivel");
        int dias = IntegerArgumentType.getInteger(c, "dias");
        if (!nick.matches("[A-Za-z0-9_]{3,16}")) { c.getSource().sendError(Text.literal("Nick inválido.")); return 0; }
        if (VipConfig.get().nivel(nivel) == null) { c.getSource().sendError(Text.literal("Nível inexistente.")); return 0; }
        String res = Entrega.aplicarVip(c.getSource().getServer(), nick, nivel, dias);
        c.getSource().sendFeedback(() -> Text.literal("§a" + nick + ": " + res), true);
        return 1;
    }

    private static int lista(CommandContext<ServerCommandSource> c) {
        StringBuilder sb = new StringBuilder("§6§lApoiadores§r");
        Apoiadores.todos().values().stream()
                .filter(Apoiadores::ativo)
                .sorted(Comparator.comparingInt((Apoiadores.Registro r) -> {
                    VipConfig.Nivel n = VipConfig.get().nivel(r.nivel);
                    return n == null ? 0 : -n.ordem;
                }).thenComparing(r -> r.nick.toLowerCase()))
                .forEach(r -> {
                    VipConfig.Nivel n = VipConfig.get().nivel(r.nivel);
                    sb.append("\n").append(Tags.cor(n == null ? r.nivel : n.prefixo)).append("§f").append(r.nick)
                      .append(" §7").append(Entrega.validade(r.expiraEm));
                });
        c.getSource().sendFeedback(() -> Text.literal(sb.toString()), false);
        return 1;
    }

    private static int definirCargo(CommandContext<ServerCommandSource> c) {
        String nick = StringArgumentType.getString(c, "nick");
        String id = StringArgumentType.getString(c, "cargo");
        if (!nick.matches("[A-Za-z0-9_]{3,16}")) { c.getSource().sendError(Text.literal("Nick inválido.")); return 0; }
        VipConfig.Cargo cargo = VipConfig.get().cargo(id);
        if (cargo == null) { c.getSource().sendError(Text.literal("Cargo inexistente. Use: dono, adm, mod ou aux.")); return 0; }
        VipConfig.get().staff.keySet().removeIf(k -> k.equalsIgnoreCase(nick));
        VipConfig.get().staff.put(nick, cargo.id);
        VipConfig.save();
        Tags.aplicar(c.getSource().getServer(), nick);
        c.getSource().sendFeedback(() -> Text.literal("§a" + nick + " agora é §f" + nick + Tags.cor(cargo.sufixo)), true);
        return 1;
    }

    private static int removerCargo(CommandContext<ServerCommandSource> c) {
        String nick = StringArgumentType.getString(c, "nick");
        boolean ok = VipConfig.get().staff.keySet().removeIf(k -> k.equalsIgnoreCase(nick));
        if (!ok) { c.getSource().sendError(Text.literal(nick + " não tem cargo de staff.")); return 0; }
        VipConfig.save();
        Tags.aplicar(c.getSource().getServer(), nick);
        c.getSource().sendFeedback(() -> Text.literal("§aCargo de " + nick + " removido."), true);
        return 1;
    }

    private static int listaCargos(CommandContext<ServerCommandSource> c) {
        StringBuilder sb = new StringBuilder("§6§lStaff§r");
        VipConfig.get().staff.entrySet().stream()
                .sorted(Comparator.comparingInt((java.util.Map.Entry<String, String> e) -> {
                    VipConfig.Cargo x = VipConfig.get().cargo(e.getValue());
                    return x == null ? 99 : x.ordem;
                }).thenComparing(e -> e.getKey().toLowerCase()))
                .forEach(e -> {
                    VipConfig.Cargo x = VipConfig.get().cargo(e.getValue());
                    sb.append("\n§f").append(e.getKey()).append(Tags.cor(x == null ? " (" + e.getValue() + "?)" : x.sufixo));
                });
        if (VipConfig.get().staff.isEmpty()) sb.append("\n§7Ninguém ainda. Use /cargo definir <nick> <dono|adm|mod|aux>.");
        c.getSource().sendFeedback(() -> Text.literal(sb.toString()), false);
        return 1;
    }
}
