package indigo.coins;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.GameProfileArgumentType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Jogadores:
 *   /indigocoins                                   suas coins (IDs de 12 dígitos)
 *   /indigocoins vender <jogador> <qtd> <preço>    oferece coins por CobbleDollars
 *   /indigocoins aceitar|recusar <jogador>
 *   /indigocoins loja  |  /indigocoins comprar <item>
 *   /indigocoins ver <id>                          de quem é a coin
 *   /raspadinha                                    rodada atual
 *   /raspadinha comprar [qtd]                      compra raspadinhas com CobbleDollars
 * NPC / staff (permissão alta):
 *   /raspadinha conferir <jogador>                 raspa as raspadinhas do inventário (botão do NPC)
 *   /raspadinha dar <jogador> <qtd>                dá raspadinhas (VIP)
 *   /raspadinha admin ver | reiniciar
 *   /indigocoins admin dar <jogador> <qtd|sorteio> | remover <id> | recarregar
 */
public final class IndigoCoinsMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("indigocoins");
    private static long ticks;
    private static final java.util.Map<java.util.UUID, Long> aguardandoLogin = new java.util.concurrent.ConcurrentHashMap<>();
    private static java.lang.reflect.Method metodoAuth;
    private static boolean semEasyAuth;

    /** EasyAuth esconde o inventário até o login; sem EasyAuth = sempre autenticado. */
    private static boolean autenticado(ServerPlayerEntity p) {
        if (semEasyAuth) return true;
        try {
            if (metodoAuth == null) {
                for (java.lang.reflect.Method m : p.getClass().getMethods())
                    if (m.getParameterCount() == 0 && m.getReturnType() == boolean.class && m.getName().toLowerCase().contains("isauthenticated")) { metodoAuth = m; break; }
                if (metodoAuth == null) { semEasyAuth = true; return true; }
            }
            return (boolean) metodoAuth.invoke(p);
        } catch (Exception e) {
            semEasyAuth = true;
            return true;
        }
    }

    @Override
    public void onInitialize() {
        Dados.carregar();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            Raspadinha.garantirRodada(server);
            Raspadinha.relatorio();
            RctDrops.registrar(server);
        });
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((h, sender, server) -> {
            if (Dados.pendentes.containsKey(h.player.getGameProfile().getName().toLowerCase(Locale.ROOT)))
                aguardandoLogin.put(h.player.getUuid(), ticks + 20L * 300);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (ticks % 20 == 0 && !aguardandoLogin.isEmpty()) {
                aguardandoLogin.entrySet().removeIf(e -> {
                    ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
                    if (p == null) return true;
                    if (!autenticado(p) && e.getValue() > ticks) return false;   // espera o login do EasyAuth
                    Integer q = Dados.pendentes.remove(p.getGameProfile().getName().toLowerCase(Locale.ROOT));
                    if (q != null && q > 0) { Dados.salvarPendentes(); entregar(p, q); }
                    return true;
                });
            }
            if (++ticks % 1200 == 0) Raspadinha.garantirRodada(server);   // confere as 24 h a cada minuto
        });
        CommandRegistrationCallback.EVENT.register((d, reg, env) -> {
            d.register(literal("indigocoins")
                    .executes(IndigoCoinsMod::minhas)
                    .then(literal("vender").then(argument("jogador", EntityArgumentType.player())
                            .then(argument("quantidade", IntegerArgumentType.integer(1, 100))
                                    .then(argument("preco", LongArgumentType.longArg(1))
                                            .executes(c -> Vendas.oferecer(c.getSource().getPlayerOrThrow(), EntityArgumentType.getPlayer(c, "jogador"),
                                                    IntegerArgumentType.getInteger(c, "quantidade"), LongArgumentType.getLong(c, "preco")))))))
                    .then(literal("aceitar").then(argument("jogador", EntityArgumentType.player())
                            .executes(c -> Vendas.aceitar(c.getSource().getServer(), c.getSource().getPlayerOrThrow(), EntityArgumentType.getPlayer(c, "jogador")))))
                    .then(literal("recusar").then(argument("jogador", EntityArgumentType.player())
                            .executes(c -> Vendas.recusar(c.getSource().getPlayerOrThrow(), EntityArgumentType.getPlayer(c, "jogador")))))
                    .then(literal("loja").executes(IndigoCoinsMod::loja))
                    .then(literal("comprar").then(argument("item", StringArgumentType.word())
                            .suggests((c, b) -> CommandSource.suggestMatching(Dados.config.loja.stream().map(i -> i.id), b))
                            .executes(IndigoCoinsMod::comprar)))
                    .then(literal("ver").then(argument("id", StringArgumentType.word()).executes(IndigoCoinsMod::ver)))
                    .then(literal("admin").requires(s -> s.hasPermissionLevel(3))
                            .then(literal("dar").then(argument("jogador", GameProfileArgumentType.gameProfile())
                                    .then(argument("quantidade", IntegerArgumentType.integer(1, 100)).executes(c -> adminDar(c, false)))
                                    .then(literal("sorteio").executes(c -> adminDar(c, true)))))
                            .then(literal("remover").then(argument("id", StringArgumentType.word()).executes(IndigoCoinsMod::adminRemover)))
                            .then(literal("recarregar").executes(c -> {
                                Dados.carregar();
                                c.getSource().sendFeedback(() -> Text.literal("§aIndigoCoins recarregado."), true);
                                return 1;
                            }))));

            d.register(literal("raspadinha")
                    .executes(IndigoCoinsMod::infoRaspadinha)
                    .then(literal("comprar")
                            .executes(c -> comprarRaspadinha(c, 1))
                            .then(argument("quantidade", IntegerArgumentType.integer(1, 64))
                                    .executes(c -> comprarRaspadinha(c, IntegerArgumentType.getInteger(c, "quantidade")))))
                    .then(literal("npccomprar").requires(s -> s.hasPermissionLevel(Dados.config.permissaoConferir))
                            .then(argument("jogador", EntityArgumentType.player())
                                    .executes(c -> npcComprar(EntityArgumentType.getPlayer(c, "jogador"), Integer.MAX_VALUE))
                                    .then(argument("quantidade", IntegerArgumentType.integer(1, 640))
                                            .executes(c -> npcComprar(EntityArgumentType.getPlayer(c, "jogador"), IntegerArgumentType.getInteger(c, "quantidade"))))))
                    .then(literal("conferir").requires(s -> s.hasPermissionLevel(Dados.config.permissaoConferir))
                            .then(argument("jogador", EntityArgumentType.player())
                                    .executes(c -> Raspadinha.conferir(c.getSource().getServer(), EntityArgumentType.getPlayer(c, "jogador")))))
                    .then(literal("dar").requires(s -> s.hasPermissionLevel(2))
                            .then(argument("nick", StringArgumentType.word())
                                    .suggests((c, b) -> CommandSource.suggestMatching(c.getSource().getServer().getPlayerNames(), b))
                                    .then(argument("quantidade", IntegerArgumentType.integer(1, 640)).executes(c -> {
                                        String nick = StringArgumentType.getString(c, "nick");
                                        int q = IntegerArgumentType.getInteger(c, "quantidade");
                                        ServerPlayerEntity p = c.getSource().getServer().getPlayerManager().getPlayer(nick);
                                        if (p != null) entregar(p, q);
                                        else {
                                            Dados.pendentes.merge(nick.toLowerCase(Locale.ROOT), q, Integer::sum);
                                            Dados.salvarPendentes();
                                        }
                                        Dados.log("DAR " + q + " raspadinha(s) para " + nick + (p == null ? " (entrega quando entrar)" : ""));
                                        c.getSource().sendFeedback(() -> Text.literal("§a" + q + " raspadinha(s) para " + nick + (p == null ? " (entrega quando entrar)" : "")), true);
                                        return 1;
                                    }))))
                    .then(literal("admin").requires(s -> s.hasPermissionLevel(3))
                            .then(literal("ver").executes(c -> {
                                Raspadinha.garantirRodada(c.getSource().getServer());
                                Dados.Rodada r = Dados.rodada;
                                c.getSource().sendFeedback(() -> Text.literal("§eRodada " + r.rodada + ": número premiado §f" + r.numero
                                        + "§e, " + r.bilhetesLidos + " bilhetes conferidos."), false);
                                return 1;
                            }))
                            .then(literal("reiniciar").executes(c -> {
                                Dados.rodada.inicio = 0;    // força o fim por tempo
                                Raspadinha.garantirRodada(c.getSource().getServer());
                                c.getSource().sendFeedback(() -> Text.literal("§aNova rodada: " + Dados.rodada.rodada), true);
                                return 1;
                            }))));
        });
        LOGGER.info("IndigoCoins carregado: {} coins registradas.", Dados.moedas.size());
    }

    static void entregar(ServerPlayerEntity p, int q) {
        Raspadinha.dar(p, q);
        p.sendMessage(Text.literal("§6Você recebeu §f" + q + "§6 raspadinha(s)! Confira com o Sr. Fujiro Nakombi."));
    }

    // ---------------- coins ----------------

    private static int minhas(CommandContext<ServerCommandSource> c) throws CommandSyntaxException {
        ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
        List<String> ids = Moedas.doJogador(p.getUuid());
        String msg = ids.isEmpty()
                ? "§7Você não tem Indigo Coins. Elas vêm da raspadinha e, raramente, de treinadores derrotados."
                : "§bVocê tem " + ids.size() + " Indigo Coin" + (ids.size() > 1 ? "s" : "") + "§7: §f" + String.join("§7, §f", ids)
                  + "\n§7Gaste em §e/indigocoins loja§7 ou venda com §e/indigocoins vender <jogador> <qtd> <preço>";
        c.getSource().sendFeedback(() -> Text.literal(msg), false);
        return ids.size();
    }

    private static int ver(CommandContext<ServerCommandSource> c) {
        String id = StringArgumentType.getString(c, "id");
        Dados.Moeda m = Dados.moedas.get(id);
        String msg = m == null ? "§cA coin " + id + " não existe."
                : m.gastaEm != 0 ? "§7A coin " + id + " já foi gasta."
                : "§bCoin " + id + "§7: pertence a §f" + m.nick + "§7 (origem: " + m.origem + ")";
        c.getSource().sendFeedback(() -> Text.literal(msg), false);
        return m == null ? 0 : 1;
    }

    private static int loja(CommandContext<ServerCommandSource> c) {
        StringBuilder sb = new StringBuilder("§6§lLoja de Indigo Coins");
        for (Config.ItemLoja i : Dados.config.loja)
            sb.append("\n§e").append(i.nome).append(" §7— §b").append(i.custo).append(" coin").append(i.custo > 1 ? "s" : "")
              .append(" §8(/indigocoins comprar ").append(i.id).append(")");
        c.getSource().sendFeedback(() -> Text.literal(sb.toString()), false);
        return 1;
    }

    private static int comprar(CommandContext<ServerCommandSource> c) throws CommandSyntaxException {
        ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
        String id = StringArgumentType.getString(c, "item").toLowerCase(Locale.ROOT);
        Config.ItemLoja item = Dados.config.loja.stream().filter(i -> i.id.equalsIgnoreCase(id)).findFirst().orElse(null);
        if (item == null) { c.getSource().sendError(Text.literal("Item inexistente. Veja /indigocoins loja")); return 0; }
        // validação: só conta coins cujo dono registrado é este jogador
        List<String> minhas = Moedas.doJogador(p.getUuid());
        if (minhas.size() < item.custo) {
            c.getSource().sendError(Text.literal(item.nome + " custa " + item.custo + " Indigo Coins; você tem " + minhas.size() + "."));
            return 0;
        }
        long agora = System.currentTimeMillis();
        for (String m : minhas.subList(0, item.custo)) {
            Dados.moedas.get(m).gastaEm = agora;
            Dados.log("GASTA " + m + " por " + p.getGameProfile().getName() + " em " + item.id);
        }
        Dados.salvarMoedas();
        MinecraftServer server = c.getSource().getServer();
        for (String cmd : item.comandos)
            server.getCommandManager().executeWithPrefix(server.getCommandSource(), cmd.replace("{nick}", p.getGameProfile().getName()));
        c.getSource().sendFeedback(() -> Text.literal("§aVocê trocou " + item.custo + " Indigo Coin(s) por §f" + item.nome + "§a!"), false);
        return 1;
    }

    private static int adminDar(CommandContext<ServerCommandSource> c, boolean sorteio) throws CommandSyntaxException {
        Collection<GameProfile> perfis = GameProfileArgumentType.getProfileArgument(c, "jogador");
        int q = sorteio ? Moedas.quantidadePremio() : IntegerArgumentType.getInteger(c, "quantidade");
        for (GameProfile g : perfis) {
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < q; i++) ids.add(Moedas.criar(g.getId(), g.getName(), "admin", null));
            ServerPlayerEntity p = c.getSource().getServer().getPlayerManager().getPlayer(g.getId());
            if (p != null) p.sendMessage(Text.literal("§6Você recebeu §b" + q + " Indigo Coin" + (q > 1 ? "s" : "") + "§6! §8/indigocoins"));
            c.getSource().sendFeedback(() -> Text.literal("§a" + q + " coin(s) para " + g.getName() + ": " + String.join(", ", ids)), true);
        }
        return q;
    }

    private static int adminRemover(CommandContext<ServerCommandSource> c) {
        String id = StringArgumentType.getString(c, "id");
        Dados.Moeda m = Dados.moedas.get(id);
        if (m == null || m.gastaEm != 0) { c.getSource().sendError(Text.literal("Coin inexistente ou já gasta.")); return 0; }
        m.gastaEm = System.currentTimeMillis();
        m.origem = m.origem + " (removida pela staff)";
        Dados.salvarMoedas();
        Dados.log("REMOVIDA " + id + " dono=" + m.nick + " por " + c.getSource().getName());
        c.getSource().sendFeedback(() -> Text.literal("§eCoin " + id + " de " + m.nick + " removida."), true);
        return 1;
    }

    // ---------------- raspadinha ----------------

    private static int infoRaspadinha(CommandContext<ServerCommandSource> c) {
        Raspadinha.garantirRodada(c.getSource().getServer());
        Dados.Rodada r = Dados.rodada;
        long resta = Math.max(0, r.inicio + Dados.config.horasPorRodada * 3_600_000L - System.currentTimeMillis());
        String preco = Dados.config.precoRaspadinha > 0 ? "§e/raspadinha comprar§7 (" + Economia.fmt(Dados.config.precoRaspadinha) + " CobbleDollars)" : "na loja";
        c.getSource().sendFeedback(() -> Text.literal("§6§lRaspadinha IndigoVerse §7— rodada " + r.rodada
                + "\n§7O número premiado muda em §f" + (resta / 3_600_000) + "h" + String.format("%02d", (resta / 60_000) % 60)
                + "§7 ou assim que alguém ganhar. Bilhetes conferidos nesta rodada: §f" + r.bilhetesLidos
                + "\n§7Compre: " + preco + "§7 • confira com o §fSr. Fujiro Nakombi§7."), false);
        return 1;
    }

    /**
     * Botão do NPC: compra raspadinhas para o jogador até onde o saldo dele dá.
     * Sem quantidade = o máximo possível (limitado a 640 por clique); com quantidade = até aquele número.
     */
    private static int npcComprar(ServerPlayerEntity p, int pedido) {
        long preco = Dados.config.precoRaspadinha;
        if (preco <= 0) { p.sendMessage(Text.literal("§cA venda de raspadinhas está desativada.")); return 0; }
        BigInteger saldo = Economia.saldo(p);
        if (saldo == null) { p.sendMessage(Text.literal("§cCobbleDollars indisponível, tente de novo.")); return 0; }
        long cabe = saldo.divide(BigInteger.valueOf(preco)).min(BigInteger.valueOf(640)).longValue();
        int qtd = (int) Math.min(cabe, pedido);
        if (qtd <= 0) {
            p.sendMessage(Text.literal("§cCada raspadinha custa " + Economia.fmt(preco) + " CobbleDollars e você tem " + Economia.fmt(saldo.longValue()) + "."));
            return 0;
        }
        BigInteger total = BigInteger.valueOf(preco).multiply(BigInteger.valueOf(qtd));
        if (!Economia.definir(p, saldo.subtract(total))) { p.sendMessage(Text.literal("§cFalha no pagamento.")); return 0; }
        Raspadinha.dar(p, qtd);
        Dados.log("COMPRA(NPC) " + p.getGameProfile().getName() + " " + qtd + " raspadinha(s) por " + total);
        p.sendMessage(Text.literal("§aVocê comprou §f" + qtd + "§a raspadinha" + (qtd > 1 ? "s" : "") + " por §f" + Economia.fmt(total.longValue())
                + "§a CobbleDollars." + (qtd < pedido && pedido != Integer.MAX_VALUE ? " §7(seu saldo só dava para " + qtd + ")" : "")));
        return qtd;
    }

    private static int comprarRaspadinha(CommandContext<ServerCommandSource> c, int qtd) throws CommandSyntaxException {
        ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
        long preco = Dados.config.precoRaspadinha;
        if (preco <= 0) { c.getSource().sendError(Text.literal("Raspadinhas são vendidas na loja do NPC.")); return 0; }
        BigInteger total = BigInteger.valueOf(preco).multiply(BigInteger.valueOf(qtd));
        BigInteger saldo = Economia.saldo(p);
        if (saldo == null) { c.getSource().sendError(Text.literal("CobbleDollars indisponível.")); return 0; }
        if (saldo.compareTo(total) < 0) {
            c.getSource().sendError(Text.literal(qtd + " raspadinha(s) custam " + Economia.fmt(total.longValue()) + " CobbleDollars."));
            return 0;
        }
        if (!Economia.definir(p, saldo.subtract(total))) { c.getSource().sendError(Text.literal("Falha no pagamento.")); return 0; }
        Raspadinha.dar(p, qtd);
        Dados.log("COMPRA " + p.getGameProfile().getName() + " " + qtd + " raspadinha(s) por " + total);
        c.getSource().sendFeedback(() -> Text.literal("§aVocê comprou " + qtd + " raspadinha(s). Confira com o Sr. Fujiro Nakombi!"), false);
        return qtd;
    }
}
