package indigo.ranked;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import indigo.ranked.Dados.Jogador;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * IndigoRanked: ranqueada da Arena Central.
 *  /ranqueada entrar | sair | perfil [nick] | top | regulamento | desafiar <jogador> | aceitar <jogador>
 *  /ranqueada admin arena canto1|canto2|ladoA|ladoB · temporada nova <regulamento> | info · ajustar <nick> <pl>
 *                   banir|desbanir|liberar <nick> · cancelar <nick> · recarregar
 */
public final class IndigoRankedMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("indigoranked");
    private static MinecraftServer server;
    private static long ticks;

    @Override
    public void onInitialize() {
        Dados.carregar();
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            Cobble.iniciar(ev -> s.execute(() -> Partidas.aoVencer(s, ev)), ev -> s.execute(() -> Partidas.aoFugir(s, ev)));
            LOGGER.info("IndigoRanked pronto: temporada {}, regulamento {}, {} jogadores.", Dados.estado.temporada.numero,
                    Dados.regulamento.nome, Dados.jogadores().size());
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            for (Partidas.Partida m : new ArrayList<>(Partidas.ativas)) Partidas.cancelar(s, m, "o servidor está desligando");
            Dados.salvar();
        });
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> Partidas.aoDesconectar(s, h.player.getUuid()));
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            ticks++;
            if (ticks % 20 != 0) return;
            try {
                Partidas.tickFila(s);
                Partidas.tickPartidas(s);
                if (ticks % 600 == 0) entregarRecompensas(s);
                if (ticks % 1200 == 0) { inatividade(); Dados.salvarSeMudou(); }
            } catch (Exception e) {
                LOGGER.error("Erro no ciclo da ranqueada", e);
            }
        });
        CommandRegistrationCallback.EVENT.register((d, reg, env) -> d.register(literal("ranqueada")
                .executes(c -> ajuda(c.getSource()))
                .then(literal("entrar").executes(c -> {
                    ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
                    String erro = Partidas.entrar(p);
                    if (erro != null) return erro(c, erro);
                    Jogador j = Dados.jogador(p.getUuid(), p.getGameProfile().getName());
                    msg(c, "§aVocê entrou na fila da ranqueada (" + Pontos.nomeRank(p.getUuid(), j) + "§a, " + j.pl + " PL)."
                            + (j.emPromocao ? " §dA próxima partida é a sua série de promoção (MD3)." : "")
                            + " §7Fique na Arena Central. /ranqueada sair para sair.");
                    return 1;
                }))
                .then(literal("sair").executes(c -> {
                    ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
                    if (Partidas.fila.remove(p.getUuid()) == null) return erro(c, "você não está na fila.");
                    msg(c, "§eVocê saiu da fila da ranqueada.");
                    return 1;
                }))
                .then(literal("perfil")
                        .executes(c -> perfil(c, c.getSource().getPlayerOrThrow().getUuid()))
                        .then(argument("nick", StringArgumentType.word()).suggests(nicks()).executes(c -> {
                            UUID u = porNick(StringArgumentType.getString(c, "nick"));
                            return u == null ? erro(c, "jogador sem partidas ranqueadas.") : perfil(c, u);
                        })))
                .then(literal("top").executes(IndigoRankedMod::top))
                .then(literal("regulamento").executes(c -> regulamento(c.getSource())))
                .then(literal("desafiar").then(argument("jogador", EntityArgumentType.player()).executes(c -> {
                    ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
                    ServerPlayerEntity alvo = EntityArgumentType.getPlayer(c, "jogador");
                    String erro = Partidas.desafiar(c.getSource().getServer(), p, alvo);
                    if (erro != null) return erro(c, erro);
                    msg(c, "§6Desafio enviado para " + alvo.getGameProfile().getName() + ". Ele tem 60 segundos para aceitar.");
                    return 1;
                })))
                .then(literal("aceitar").then(argument("jogador", EntityArgumentType.player()).executes(c -> {
                    String erro = Partidas.aceitar(c.getSource().getServer(), c.getSource().getPlayerOrThrow(), EntityArgumentType.getPlayer(c, "jogador"));
                    return erro != null ? erro(c, erro) : 1;
                })))
                .then(literal("admin").requires(s -> s.hasPermissionLevel(2))
                        .then(literal("arena")
                                .then(literal("canto1").executes(c -> canto(c, 1)))
                                .then(literal("canto2").executes(c -> canto(c, 2)))
                                .then(literal("ladoA").executes(c -> lado(c, true)))
                                .then(literal("ladoB").executes(c -> lado(c, false))))
                        .then(literal("temporada")
                                .then(literal("info").executes(c -> {
                                    Dados.Temporada t = Dados.estado.temporada;
                                    msg(c, "§6Temporada " + t.numero + " §7| regulamento §f" + t.regulamento + " (" + Dados.regulamento.nome + ")§7 | "
                                            + Dados.jogadores().size() + " jogadores | " + Partidas.fila.size() + " na fila | " + Partidas.ativas.size() + " partidas agora");
                                    return 1;
                                }))
                                .then(literal("nova").then(argument("regulamento", StringArgumentType.word()).suggests(regulamentos())
                                        .executes(c -> novaTemporada(c, StringArgumentType.getString(c, "regulamento"))))))
                        .then(literal("ajustar").then(argument("nick", StringArgumentType.word()).suggests(nicks())
                                .then(argument("pl", IntegerArgumentType.integer(0, 100000)).executes(c -> {
                                    UUID u = porNick(StringArgumentType.getString(c, "nick"));
                                    if (u == null) return erro(c, "jogador não encontrado.");
                                    Jogador j = Dados.jogador(u, null);
                                    j.pl = IntegerArgumentType.getInteger(c, "pl");
                                    j.emPromocao = false;
                                    Dados.salvar();
                                    msg(c, "§a" + j.nick + " agora tem " + j.pl + " PL (" + Pontos.nomeRank(u, j) + "§a).");
                                    return 1;
                                }))))
                        .then(literal("banir").then(argument("nick", StringArgumentType.word()).suggests(nicks()).executes(c -> marcar(c, "banir"))))
                        .then(literal("desbanir").then(argument("nick", StringArgumentType.word()).suggests(nicks()).executes(c -> marcar(c, "desbanir"))))
                        .then(literal("liberar").then(argument("nick", StringArgumentType.word()).executes(c -> marcar(c, "liberar"))))
                        .then(literal("cancelar").then(argument("nick", StringArgumentType.word()).suggests(nicks()).executes(c -> {
                            UUID u = porNick(StringArgumentType.getString(c, "nick"));
                            Partidas.Partida m = u == null ? null : Partidas.partidaDe(u);
                            if (m == null) return erro(c, "esse jogador não está em partida.");
                            Partidas.cancelar(c.getSource().getServer(), m, "cancelada pela staff");
                            msg(c, "§aPartida cancelada sem pontos.");
                            return 1;
                        })))
                        .then(literal("recarregar").executes(c -> {
                            Dados.carregar();
                            msg(c, "§aConfig e regulamento recarregados (" + Dados.regulamento.nome + ").");
                            return 1;
                        })))));
        LOGGER.info("IndigoRanked carregado.");
    }

    // ---------------- comandos ----------------

    private static int ajuda(ServerCommandSource s) {
        s.sendFeedback(() -> Text.literal("§6§lRanqueada IndigoVerse §7— temporada " + Dados.estado.temporada.numero + ", regulamento §f"
                + Dados.regulamento.nome + "\n§e/ranqueada entrar §7— entra na fila (dentro da Arena Central)"
                + "\n§e/ranqueada sair §7— sai da fila\n§e/ranqueada perfil [nick] §7— rank e pontos"
                + "\n§e/ranqueada top §7— melhores da temporada\n§e/ranqueada regulamento §7— regras do time desta temporada"
                + "\n§e/ranqueada desafiar <jogador> §7— desafio direto (mesmo rank, 1 vez por dia)"), false);
        return 1;
    }

    private static int perfil(CommandContext<ServerCommandSource> c, UUID u) {
        Jogador j = Dados.jogador(u, null);
        List<Map.Entry<String, Jogador>> top = ordenados();
        int pos = 0;
        for (int i = 0; i < top.size(); i++) if (top.get(i).getKey().equals(u.toString())) pos = i + 1;
        int prox = Pontos.proximoMin(j.pl);
        StringBuilder sb = new StringBuilder("§6§l" + (j.nick.isEmpty() ? "Jogador" : j.nick) + "§r §7— " + Pontos.nomeRank(u, j)
                + " §7| §f" + j.pl + " PL" + (pos > 0 ? " §7| #" + pos : ""));
        sb.append("\n§7Vitórias §a").append(j.vitorias).append(" §7| Derrotas §c").append(j.derrotas)
                .append(" §7| Partidas na temporada §f").append(j.partidasTemporada);
        if (j.posicionamentoRestante > 0) sb.append("\n§bPartidas de posicionamento restantes: ").append(j.posicionamentoRestante).append(" (pontos em dobro)");
        if (j.emPromocao) sb.append("\n§d✦ Em série de promoção: a próxima partida é MD3.");
        else if (prox > 0) sb.append("\n§7Faltam §f").append(prox - 1 - j.pl).append("§7 PL para a série de promoção.");
        if (j.protecao > 0) sb.append("\n§aProteção contra queda: ").append(j.protecao).append(" partidas.");
        if (j.banido) sb.append("\n§cBanido da ranqueada nesta temporada.");
        c.getSource().sendFeedback(() -> Text.literal(sb.toString()), false);
        return 1;
    }

    private static int top(CommandContext<ServerCommandSource> c) {
        List<Map.Entry<String, Jogador>> l = ordenados();
        StringBuilder sb = new StringBuilder("§6§lTop da ranqueada — temporada " + Dados.estado.temporada.numero);
        for (int i = 0; i < Math.min(10, l.size()); i++) {
            Jogador j = l.get(i).getValue();
            sb.append("\n§e").append(i + 1).append(". §f").append(j.nick).append(" §7— ")
                    .append(Pontos.nomeRank(UUID.fromString(l.get(i).getKey()), j)).append(" §7(").append(j.pl).append(" PL)");
        }
        if (l.isEmpty()) sb.append("\n§7Ninguém jogou ainda nesta temporada.");
        c.getSource().sendFeedback(() -> Text.literal(sb.toString()), false);
        return 1;
    }

    private static List<Map.Entry<String, Jogador>> ordenados() {
        List<Map.Entry<String, Jogador>> l = new ArrayList<>();
        for (Map.Entry<String, Jogador> e : Dados.jogadores().entrySet())
            if (e.getValue().partidasTemporada > 0 && !e.getValue().banido) l.add(e);
        l.sort((x, y) -> Integer.compare(y.getValue().pl, x.getValue().pl));
        return l;
    }

    private static int regulamento(ServerCommandSource s) {
        Regulamento r = Dados.regulamento;
        StringBuilder sb = new StringBuilder("§6§lRegulamento: " + r.nome + "§r\n§7" + r.descricao);
        sb.append("\n§7Formato: §f").append(r.duplas() ? "duplas" : "individual").append(" §7| Time: §f").append(r.minPokemon)
                .append(" a ").append(r.maxPokemon).append(" Pokémon §7| Nível fixo: §f").append(Dados.config.nivel);
        if (r.pokedexMin > 0 || r.pokedexMax > 0) sb.append("\n§7Pokédex: §f").append(r.pokedexMin).append(" a ").append(r.pokedexMax);
        if (!r.gruposProibidos.isEmpty()) {
            List<String> g = new ArrayList<>();
            for (String x : r.gruposProibidos) g.add(Partidas.nomeGrupo(x.toLowerCase()));
            sb.append("\n§7Proibidos: §c").append(String.join(", ", g));
        }
        sb.append("\n§7Lendários restritos por time: §f").append(r.maxRestritos);
        if (r.clausulaEspecie) sb.append("\n§7Um Pokémon de cada espécie.");
        if (r.clausulaItem) sb.append("\n§7Itens sem repetir.");
        if (!r.especiesProibidas.isEmpty()) sb.append("\n§7Espécies proibidas: §c").append(String.join(", ", r.especiesProibidas));
        if (!r.itensProibidos.isEmpty()) sb.append("\n§7Itens proibidos: §c").append(String.join(", ", r.itensProibidos));
        if (!r.golpesProibidos.isEmpty()) sb.append("\n§7Golpes proibidos: §c").append(String.join(", ", r.golpesProibidos));
        if (r.mecanica != null && !r.mecanica.isBlank()) sb.append("\n§7Mecânica da temporada: §d").append(r.mecanica);
        s.sendFeedback(() -> Text.literal(sb.toString()), false);
        return 1;
    }

    private static int canto(CommandContext<ServerCommandSource> c, int n) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
        int[] pos = {p.getBlockX(), p.getBlockY(), p.getBlockZ()};
        if (n == 1) Dados.config.arenaCanto1 = pos; else Dados.config.arenaCanto2 = pos;
        Dados.config.arenaDimensao = p.getWorld().getRegistryKey().getValue().toString();
        Dados.salvarConfig();
        msg(c, "§aCanto " + n + " da arena: " + pos[0] + " " + pos[1] + " " + pos[2]
                + (Partidas.arenaPronta() ? " §7(arena pronta)" : " §7(falta o outro canto)"));
        return 1;
    }

    private static int lado(CommandContext<ServerCommandSource> c, boolean a) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
        Config.Ponto pt = new Config.Ponto(p.getX(), p.getY(), p.getZ(), p.getYaw());
        if (a) Dados.config.ladoA = pt; else Dados.config.ladoB = pt;
        Dados.salvarConfig();
        msg(c, "§aPosição do lado " + (a ? "A" : "B") + " salva.");
        return 1;
    }

    private static int marcar(CommandContext<ServerCommandSource> c, String acao) {
        String nick = StringArgumentType.getString(c, "nick");
        UUID u = porNick(nick);
        if (u == null) {
            ServerPlayerEntity p = c.getSource().getServer().getPlayerManager().getPlayer(nick);
            if (p == null) return erro(c, "jogador não encontrado (precisa estar online ou já ter jogado).");
            u = p.getUuid();
            Dados.jogador(u, p.getGameProfile().getName());
        }
        Jogador j = Dados.jogador(u, null);
        switch (acao) {
            case "banir" -> {
                j.banido = true;
                Partidas.fila.remove(u);
                Dados.discord("🚫 " + j.nick + " foi banido da ranqueada nesta temporada.");
            }
            case "desbanir" -> j.banido = false;
            default -> j.liberado = true;
        }
        Dados.salvar();
        msg(c, "§a" + (j.nick.isEmpty() ? nick : j.nick) + ": " + acao + " feito.");
        return 1;
    }

    // ---------------- temporada ----------------

    private static int novaTemporada(CommandContext<ServerCommandSource> c, String reg) {
        Regulamento r = Dados.lerRegulamento(reg);
        if (r == null) return erro(c, "regulamento '" + reg + "' não existe em config/indigoranked/regulamentos/.");
        MinecraftServer s = c.getSource().getServer();
        for (Partidas.Partida m : new ArrayList<>(Partidas.ativas)) Partidas.cancelar(s, m, "a temporada terminou");
        Partidas.fila.clear();
        // recompensas pelo rank final
        UUID campeao = Pontos.campeao();
        int premiados = 0;
        for (Map.Entry<String, Jogador> e : Dados.jogadores().entrySet()) {
            Jogador j = e.getValue();
            if (j.partidasTemporada == 0 || j.banido) continue;
            String chave = UUID.fromString(e.getKey()).equals(campeao) ? "campeao" : Pontos.rank(j.pl).id;
            Config.Recompensa rec = Dados.config.recompensas.get(chave);
            if (rec != null) {
                j.dinheiroPendente += rec.dinheiro;
                if (rec.comandos != null) j.comandosPendentes.addAll(rec.comandos);
                premiados++;
            }
        }
        String nomeCampeao = campeao == null ? null : Dados.jogador(campeao, null).nick;
        // reset parcial
        int base = Pontos.ranks().size() > 1 ? Pontos.ranks().get(1).min : 300;
        for (Jogador j : Dados.jogadores().values()) {
            if (j.partidasTemporada > 0) j.pl = (j.pl + base) / 2;
            j.emPromocao = false; j.protecao = 0; j.seriesFalhas = 0; j.banido = false;
            j.partidasTemporada = 0; j.ultimoOponenteSerie = "";
            j.contraHoje.clear();
        }
        Dados.Temporada t = Dados.estado.temporada;
        int encerrada = t.numero;
        t.numero++;
        t.regulamento = reg;
        t.inicio = System.currentTimeMillis();
        t.ultimaInatividade = t.inicio;
        Dados.estado.confrontos.clear();
        Dados.carregarRegulamento();
        Dados.salvar();
        entregarRecompensas(s);
        String anuncio = "§6§l✦ Temporada " + encerrada + " da ranqueada encerrada!"
                + (nomeCampeao != null ? " §r§bCampeão Indigo: §f" + nomeCampeao + "§b!" : "")
                + "\n§eComeçou a temporada " + t.numero + " com o regulamento §f" + r.nome + "§e. Use /ranqueada regulamento.";
        s.getPlayerManager().broadcast(Text.literal(anuncio), false);
        Dados.discord("🏆 Temporada " + encerrada + " encerrada" + (nomeCampeao != null ? " — Campeão Indigo: **" + nomeCampeao + "**" : "")
                + ". " + premiados + " jogadores premiados. Nova temporada " + t.numero + ": " + r.nome + ".");
        msg(c, "§aTemporada " + t.numero + " iniciada. " + premiados + " jogadores com recompensa (entregue quando estiverem online).");
        return 1;
    }

    /** Elite Four (último rank) perde pontos se jogar pouco na semana. */
    private static void inatividade() {
        Dados.Temporada t = Dados.estado.temporada;
        long semana = 7L * 24 * 3600 * 1000, agora = System.currentTimeMillis();
        if (agora - t.ultimaInatividade < semana) return;
        t.ultimaInatividade = agora;
        int topo = Pontos.ranks().get(Pontos.ranks().size() - 1).min;
        for (Jogador j : Dados.jogadores().values()) {
            if (j.pl < topo) continue;
            j.partidasRecentes.removeIf(x -> agora - x > semana);
            if (j.partidasRecentes.size() < Dados.config.partidasMinimasSemana)
                j.pl = Math.max(topo, j.pl - Dados.config.perdaInatividade);
        }
        Dados.salvar();
    }

    private static void entregarRecompensas(MinecraftServer s) {
        boolean mudou = false;
        for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
            if (!autenticado(p)) continue;
            Jogador j = Dados.jogadores().get(p.getUuid().toString());
            if (j == null || (j.dinheiroPendente <= 0 && j.comandosPendentes.isEmpty())) continue;
            if (j.dinheiroPendente > 0) {
                BigInteger saldo = Economia.saldo(p);
                if (saldo != null && Economia.definir(p, saldo.add(BigInteger.valueOf(j.dinheiroPendente)))) {
                    p.sendMessage(Text.literal("§6✦ Recompensa da ranqueada: §f" + Economia.fmt(j.dinheiroPendente) + " CobbleDollars§6!"));
                    j.dinheiroPendente = 0;
                    mudou = true;
                }
            }
            for (String cmd : j.comandosPendentes)
                s.getCommandManager().executeWithPrefix(s.getCommandSource(), cmd.replace("{nick}", p.getGameProfile().getName()));
            if (!j.comandosPendentes.isEmpty()) { j.comandosPendentes.clear(); mudou = true; }
        }
        if (mudou) Dados.salvar();
    }

    // ---------------- utilidades ----------------

    private static UUID porNick(String nick) {
        for (Map.Entry<String, Jogador> e : Dados.jogadores().entrySet())
            if (e.getValue().nick.equalsIgnoreCase(nick)) return UUID.fromString(e.getKey());
        return null;
    }

    private static SuggestionProvider<ServerCommandSource> nicks() {
        return (c, b) -> {
            String r = b.getRemaining().toLowerCase(Locale.ROOT);
            for (Jogador j : Dados.jogadores().values()) if (j.nick.toLowerCase(Locale.ROOT).startsWith(r)) b.suggest(j.nick);
            return b.buildFuture();
        };
    }

    private static SuggestionProvider<ServerCommandSource> regulamentos() {
        return (c, b) -> {
            for (String s : Dados.regulamentosDisponiveis()) b.suggest(s);
            return b.buildFuture();
        };
    }

    private static void msg(CommandContext<ServerCommandSource> c, String t) {
        c.getSource().sendFeedback(() -> Text.literal(t), false);
    }

    private static int erro(CommandContext<ServerCommandSource> c, String t) {
        c.getSource().sendError(Text.literal("Ranqueada: " + t));
        return 0;
    }

    private static java.lang.reflect.Method metodoAuth;
    private static boolean semEasyAuth;

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
}
