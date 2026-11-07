package indigo.ranked;

import indigo.ranked.Dados.Jogador;
import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Fila, pareamento, partidas MD1 e séries de promoção MD3. */
final class Partidas {
    private Partidas() {}

    static final Map<UUID, Long> fila = new LinkedHashMap<>();
    static final List<Partida> ativas = new ArrayList<>();
    /** "desafiante|alvo" -> expira em (ms). */
    static final Map<String, Long> desafios = new HashMap<>();

    static final class Partida {
        UUID a, b;
        String nickA, nickB;
        int plA0, plB0;
        boolean serie, promoA, promoB, desafio;
        int vitA, vitB, jogo;
        Object batalha;
        UUID batalhaId;
        long inicioJogo, proximoInicio, fimSemResultado;
        Map<UUID, List<String>> trava = new HashMap<>();
        ServerBossBar barra;
        boolean curto;

        boolean tem(UUID u) { return a.equals(u) || b.equals(u); }
        UUID outro(UUID u) { return a.equals(u) ? b : a; }
        String nick(UUID u) { return a.equals(u) ? nickA : nickB; }
    }

    static long agora() { return System.currentTimeMillis(); }

    static Partida partidaDe(UUID u) {
        for (Partida p : ativas) if (p.tem(u)) return p;
        return null;
    }

    // ---------------- arena ----------------

    static boolean arenaPronta() {
        Config c = Dados.config;
        return c.arenaCanto1 != null && c.arenaCanto2 != null && c.arenaCanto1.length == 3 && c.arenaCanto2.length == 3;
    }

    static boolean naArena(ServerPlayerEntity p) {
        if (!arenaPronta()) return false;
        Config c = Dados.config;
        if (!p.getWorld().getRegistryKey().getValue().toString().equals(c.arenaDimensao)) return false;
        int[] a = c.arenaCanto1, b = c.arenaCanto2;
        double x = p.getX(), y = p.getY(), z = p.getZ();
        return x >= Math.min(a[0], b[0]) && x < Math.max(a[0], b[0]) + 1
                && y >= Math.min(a[1], b[1]) - 1 && y < Math.max(a[1], b[1]) + 2
                && z >= Math.min(a[2], b[2]) && z < Math.max(a[2], b[2]) + 1;
    }

    static void levar(ServerPlayerEntity p, Config.Ponto pt) {
        if (pt == null) return;
        ServerWorld w = p.getServer().getWorld(RegistryKey.of(RegistryKeys.WORLD, Identifier.of(Dados.config.arenaDimensao)));
        if (w != null) p.teleport(w, pt.x, pt.y, pt.z, pt.yaw, 0f);
    }

    // ---------------- requisitos e time ----------------

    /** Motivo para não poder jogar, ou null. */
    static String impedimento(ServerPlayerEntity p) {
        Config c = Dados.config;
        Jogador j = Dados.jogador(p.getUuid(), p.getGameProfile().getName());
        if (j.banido) return "você está banido da ranqueada nesta temporada.";
        if (j.liberado) return null;
        long horas = p.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.PLAY_TIME)) / 20 / 3600;
        if (horas < c.horasMinimas) return "precisa de " + c.horasMinimas + " horas de jogo (você tem " + horas + ").";
        if (c.conquistaObrigatoria != null && !c.conquistaObrigatoria.isBlank()) {
            AdvancementEntry adv = p.getServer().getAdvancementLoader().get(Identifier.of(c.conquistaObrigatoria));
            if (adv != null && !p.getAdvancementTracker().getProgress(adv).isDone())
                return "precisa vencer a Liga de Kanto antes.";
        }
        if (c.tagObrigatoria != null && !c.tagObrigatoria.isBlank() && !p.getCommandTags().contains(c.tagObrigatoria))
            return "precisa vencer a Liga de Kanto antes.";
        return null;
    }

    /** Problemas do time em relação ao regulamento da temporada (vazio = time válido). */
    static List<String> errosDoTime(List<Cobble.Pk> eq, Regulamento r) {
        List<String> e = new ArrayList<>();
        if (eq.size() < r.minPokemon) e.add("o time precisa de pelo menos " + r.minPokemon + " Pokémon (tem " + eq.size() + ")");
        if (eq.size() > r.maxPokemon) e.add("o time pode ter no máximo " + r.maxPokemon + " Pokémon (tem " + eq.size() + ")");
        int restritos = 0;
        Set<Integer> dex = new HashSet<>();
        Set<String> itens = new HashSet<>();
        for (Cobble.Pk pk : eq) {
            String n = pk.nome();
            boolean restrito = pk.rotulos().contains("restricted");
            if (restrito) restritos++;
            if (!r.especiesPermitidas.isEmpty() && !contem(r.especiesPermitidas, pk.especie())) e.add(n + " não está na lista permitida");
            if (contem(r.especiesProibidas, pk.especie())) e.add(n + " está proibido");
            if (r.pokedexMin > 0 && pk.dex() < r.pokedexMin || r.pokedexMax > 0 && pk.dex() > r.pokedexMax)
                e.add(n + " está fora da Pokédex permitida (" + r.pokedexMin + " a " + r.pokedexMax + ")");
            for (String g : r.gruposProibidos) {
                String gl = g.toLowerCase();
                if (gl.equals("legendary") && restrito && r.maxRestritos > 0) continue;
                if (pk.rotulos().contains(gl)) { e.add(n + " é do grupo proibido: " + nomeGrupo(gl)); break; }
            }
            if (!pk.item().isEmpty() && contem(r.itensProibidos, pk.item())) e.add(n + " segura um item proibido (" + pk.item() + ")");
            for (String g : pk.golpes()) if (contem(r.golpesProibidos, g)) e.add(n + " tem um golpe proibido (" + g + ")");
            if (r.clausulaEspecie && !dex.add(pk.dex())) e.add("espécie repetida: " + n);
            if (r.clausulaItem && !pk.item().isEmpty() && !itens.add(pk.item())) e.add("item repetido: " + pk.item());
        }
        if (restritos > r.maxRestritos) e.add("lendários restritos demais: " + restritos + " (máximo " + r.maxRestritos + ")");
        return e;
    }

    private static boolean contem(List<String> l, String v) {
        if (l == null) return false;
        for (String s : l) if (s.equalsIgnoreCase(v) || s.equalsIgnoreCase(v.replace("cobblemon:", ""))) return true;
        return false;
    }

    static String nomeGrupo(String g) {
        return switch (g) {
            case "legendary" -> "lendários";
            case "mythical" -> "míticos";
            case "ultra_beast" -> "Ultra Beasts";
            case "paradox" -> "Paradoxos";
            case "restricted" -> "lendários restritos";
            default -> g;
        };
    }

    private static List<String> retrato(List<Cobble.Pk> eq) {
        List<String> l = new ArrayList<>();
        for (Cobble.Pk pk : eq) l.add(pk.uuid() + "|" + pk.item());
        l.sort(String::compareTo);
        return l;
    }

    // ---------------- fila ----------------

    static String entrar(ServerPlayerEntity p) {
        if (!Cobble.ok) return "a ranqueada está desligada (Cobblemon não respondeu).";
        if (!arenaPronta()) return "a arena ainda não foi configurada pela staff.";
        if (!naArena(p)) return "entre na Arena Central para jogar.";
        if (fila.containsKey(p.getUuid())) return "você já está na fila.";
        if (partidaDe(p.getUuid()) != null) return "você já está numa partida.";
        if (Cobble.emBatalha(p)) return "termine a batalha atual primeiro.";
        String imp = impedimento(p);
        if (imp != null) return imp;
        List<String> erros = errosDoTime(Cobble.equipe(p), Dados.regulamento);
        if (!erros.isEmpty()) return "seu time não segue o regulamento " + Dados.regulamento.nome + ":\n§c- " + String.join("\n§c- ", erros);
        fila.put(p.getUuid(), agora());
        return null;
    }

    private static int margem(long esperaMs) {
        Config c = Dados.config;
        long passos = esperaMs / 1000 / Math.max(1, c.segundosParaCrescer);
        return (int) Math.min(c.margemMaxima, c.margemInicial + passos * c.margemCrescimento);
    }

    private static boolean compativeis(ServerPlayerEntity pa, ServerPlayerEntity pb, long espera) {
        Config c = Dados.config;
        if (c.bloquearMesmoIp && pa.getIp().equals(pb.getIp())) return false;
        Jogador a = Dados.jogador(pa.getUuid(), null), b = Dados.jogador(pb.getUuid(), null);
        int ra = Pontos.indiceRank(a.pl), rb = Pontos.indiceRank(b.pl);
        if (Math.abs(ra - rb) > c.distanciaMaximaRanks) return false;
        String hoje = Dados.hoje() + "|";
        if (a.contraHoje.getOrDefault(hoje + pb.getUuid(), 0) >= c.pesoMesmoOponente.length) return false;
        if (a.emPromocao || b.emPromocao) {
            if (a.emPromocao && !serieValida(a, ra, b, rb, pb.getUuid())) return false;
            if (b.emPromocao && !serieValida(b, rb, a, ra, pa.getUuid())) return false;
            return true;
        }
        return Math.abs(a.pl - b.pl) <= margem(espera);
    }

    /** Quem está em promoção enfrenta alguém do rank de destino ou outro em promoção no mesmo rank. */
    private static boolean serieValida(Jogador p, int rp, Jogador o, int ro, UUID uo) {
        if (uo.toString().equals(p.ultimoOponenteSerie)) return false;
        return ro == rp + 1 || (o.emPromocao && ro == rp);
    }

    static void tickFila(MinecraftServer server) {
        for (Iterator<Map.Entry<UUID, Long>> it = fila.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Long> e = it.next();
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
            if (p == null) { it.remove(); continue; }
            if (!naArena(p)) {
                it.remove();
                p.sendMessage(Text.literal("§eVocê saiu da Arena Central e deixou a fila da ranqueada."));
            }
        }
        if (fila.size() < 2) return;
        List<UUID> ordem = new ArrayList<>(fila.keySet());
        Set<UUID> usados = new HashSet<>();
        long agora = agora();
        for (int i = 0; i < ordem.size(); i++) {
            UUID ua = ordem.get(i);
            if (usados.contains(ua)) continue;
            for (int k = i + 1; k < ordem.size(); k++) {
                UUID ub = ordem.get(k);
                if (usados.contains(ub)) continue;
                ServerPlayerEntity pa = server.getPlayerManager().getPlayer(ua), pb = server.getPlayerManager().getPlayer(ub);
                if (pa == null || pb == null) continue;
                long espera = Math.min(agora - fila.get(ua), agora - fila.get(ub));
                if (!compativeis(pa, pb, espera)) continue;
                usados.add(ua); usados.add(ub);
                criar(server, pa, pb, false);
                break;
            }
        }
        for (UUID u : usados) fila.remove(u);
    }

    // ---------------- partidas ----------------

    static void criar(MinecraftServer server, ServerPlayerEntity pa, ServerPlayerEntity pb, boolean desafio) {
        Jogador ja = Dados.jogador(pa.getUuid(), pa.getGameProfile().getName());
        Jogador jb = Dados.jogador(pb.getUuid(), pb.getGameProfile().getName());
        Partida m = new Partida();
        m.a = pa.getUuid(); m.b = pb.getUuid();
        m.nickA = pa.getGameProfile().getName(); m.nickB = pb.getGameProfile().getName();
        m.plA0 = ja.pl; m.plB0 = jb.pl;
        m.desafio = desafio;
        m.promoA = !desafio && ja.emPromocao;
        m.promoB = !desafio && jb.emPromocao;
        m.serie = m.promoA || m.promoB;
        if (m.serie) {
            // ajuda contra frustração: depois de X séries perdidas seguidas, começa 1 x 0
            if (m.promoA && ja.seriesFalhas >= Dados.config.seriesFalhasParaAjuda) m.vitA = 1;
            if (m.promoB && jb.seriesFalhas >= Dados.config.seriesFalhasParaAjuda) m.vitB = 1;
            m.trava.put(m.a, retrato(Cobble.equipe(pa)));
            m.trava.put(m.b, retrato(Cobble.equipe(pb)));
        }
        m.proximoInicio = agora() + 1000L * Dados.config.segundosContagem;
        m.barra = new ServerBossBar(Text.empty(), BossBar.Color.YELLOW, BossBar.Style.PROGRESS);
        m.barra.addPlayer(pa); m.barra.addPlayer(pb);
        atualizarBarra(m);
        ativas.add(m);
        levar(pa, Dados.config.ladoA);
        levar(pb, Dados.config.ladoB);
        String tipo = m.serie ? "§d§lSÉRIE DE PROMOÇÃO (MD3)" : "§6§lPartida ranqueada (MD1)";
        for (ServerPlayerEntity p : List.of(pa, pb)) {
            UUID o = m.outro(p.getUuid());
            Jogador jo = Dados.jogador(o, null);
            p.sendMessage(Text.literal(tipo + "§r §7contra §f" + m.nick(o) + " §7(" + Pontos.nomeRank(o, jo) + "§7, " + jo.pl
                    + " PL). Começa em " + Dados.config.segundosContagem + " segundos. Regulamento: §f" + Dados.regulamento.nome));
        }
        if (m.serie) server.getPlayerManager().broadcast(Text.literal("§d⚔ Série de promoção na Arena Central: §f"
                + m.nickA + " §7x §f" + m.nickB + "§d. Venha assistir!"), false);
    }

    static void atualizarBarra(Partida m) {
        if (m.barra == null) return;
        String t = m.serie ? "§dPromoção (MD3): §f" + m.nickA + " §e" + m.vitA + " x " + m.vitB + " §f" + m.nickB
                : "§6Ranqueada: §f" + m.nickA + " §7x §f" + m.nickB;
        m.barra.setName(Text.literal(t));
        m.barra.setPercent(m.serie ? Math.min(1f, (m.vitA + m.vitB) / 3f) : 1f);
    }

    static void tickPartidas(MinecraftServer server) {
        long agora = agora();
        for (Partida m : new ArrayList<>(ativas)) {
            ServerPlayerEntity pa = server.getPlayerManager().getPlayer(m.a), pb = server.getPlayerManager().getPlayer(m.b);
            if (m.batalha == null) {
                if (agora < m.proximoInicio) continue;
                iniciarJogo(server, m, pa, pb);
                continue;
            }
            // batalha em andamento: os dois precisam ficar na arena
            if (pa != null && !naArena(pa)) { encerrarPorSaida(server, m, m.a, "saiu da arena"); continue; }
            if (pb != null && !naArena(pb)) { encerrarPorSaida(server, m, m.b, "saiu da arena"); continue; }
            if (Cobble.terminou(m.batalha)) {
                // terminou sem vencedor (parada pela staff, erro do Cobblemon): anula o jogo
                if (m.fimSemResultado == 0) m.fimSemResultado = agora;
                else if (agora - m.fimSemResultado > 5000) anularJogo(server, m, "a batalha terminou sem vencedor");
            }
        }
    }

    private static void iniciarJogo(MinecraftServer server, Partida m, ServerPlayerEntity pa, ServerPlayerEntity pb) {
        // quem não está apto perde este jogo
        String fA = falhaParaJogar(m, pa, m.a), fB = falhaParaJogar(m, pb, m.b);
        if (fA != null && fB != null) { cancelar(server, m, "nenhum dos dois pôde jogar (" + fA + " / " + fB + ")"); return; }
        if (fA != null) { avisar(server, m, "§c" + m.nickA + " perdeu o jogo: " + fA); registrarJogo(server, m, m.b, true); return; }
        if (fB != null) { avisar(server, m, "§c" + m.nickB + " perdeu o jogo: " + fB); registrarJogo(server, m, m.a, true); return; }
        if (m.jogo > 0) { levar(pa, Dados.config.ladoA); levar(pb, Dados.config.ladoB); }
        Object r = Cobble.iniciarBatalha(pa, pb, Dados.regulamento, Dados.config.nivel);
        if (r instanceof String erro) { cancelar(server, m, erro); return; }
        m.batalha = r;
        m.batalhaId = Cobble.idBatalha(r);
        m.inicioJogo = agora();
        m.fimSemResultado = 0;
        m.jogo++;
        atualizarBarra(m);
    }

    private static String falhaParaJogar(Partida m, ServerPlayerEntity p, UUID u) {
        if (p == null) return "não está online";
        if (!naArena(p)) return "não está na arena";
        if (Cobble.emBatalha(p)) return "já está em outra batalha";
        List<Cobble.Pk> eq = Cobble.equipe(p);
        if (!errosDoTime(eq, Dados.regulamento).isEmpty()) return "time fora do regulamento";
        if (m.serie && m.trava.containsKey(u) && !m.trava.get(u).equals(retrato(eq))) return "trocou o time ou os itens no meio da série";
        return null;
    }

    static void avisar(MinecraftServer server, Partida m, String msg) {
        for (UUID u : List.of(m.a, m.b)) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(u);
            if (p != null) p.sendMessage(Text.literal(msg));
        }
    }

    static void encerrarPorSaida(MinecraftServer server, Partida m, UUID perdedor, String motivo) {
        if (m.batalha != null) Cobble.parar(m.batalha);
        avisar(server, m, "§c" + m.nick(perdedor) + " " + motivo + " e perdeu o jogo.");
        registrarJogo(server, m, m.outro(perdedor), true);
    }

    private static void anularJogo(MinecraftServer server, Partida m, String motivo) {
        m.batalha = null;
        m.batalhaId = null;
        m.jogo = Math.max(0, m.jogo - 1);
        if (!m.serie) { cancelar(server, m, motivo); return; }
        avisar(server, m, "§eJogo anulado (" + motivo + "). Ele será repetido em " + Dados.config.segundosEntreJogosMd3 + " segundos.");
        m.proximoInicio = agora() + 1000L * Dados.config.segundosEntreJogosMd3;
    }

    /** Cancela sem pontos. */
    static void cancelar(MinecraftServer server, Partida m, String motivo) {
        if (m.batalha != null && !Cobble.terminou(m.batalha)) Cobble.parar(m.batalha);
        avisar(server, m, "§ePartida ranqueada cancelada, sem pontos: " + motivo + ".");
        Dados.csv(String.valueOf(Dados.estado.temporada.numero), m.serie ? "MD3" : "MD1", m.nickA, m.nickB, "", m.vitA + "x" + m.vitB, "", "", "", "", "cancelada: " + motivo);
        fechar(m);
    }

    private static void fechar(Partida m) {
        if (m.barra != null) m.barra.clearPlayers();
        ativas.remove(m);
    }

    // ---------------- resultado ----------------

    static void aoVencer(MinecraftServer server, Object ev) {
        Object battle = Cobble.batalhaDoEvento(ev);
        UUID id = battle == null ? null : Cobble.idBatalha(battle);
        if (id == null) return;
        for (Partida m : new ArrayList<>(ativas)) {
            if (!id.equals(m.batalhaId)) continue;
            Set<UUID> venc = Cobble.vencedores(ev);
            UUID v = venc.contains(m.a) ? m.a : venc.contains(m.b) ? m.b : null;
            if (v == null) {
                Set<UUID> perd = Cobble.perdedores(ev);
                v = perd.contains(m.a) ? m.b : perd.contains(m.b) ? m.a : null;
            }
            if (v == null) { anularJogo(server, m, "resultado sem vencedor"); return; }
            registrarJogo(server, m, v, false);
            return;
        }
    }

    static void aoFugir(MinecraftServer server, Object ev) {
        Object battle = Cobble.batalhaDoEvento(ev);
        UUID id = battle == null ? null : Cobble.idBatalha(battle);
        UUID quem = Cobble.quemFugiu(ev);
        if (id == null || quem == null) return;
        for (Partida m : new ArrayList<>(ativas)) {
            if (!id.equals(m.batalhaId) || !m.tem(quem)) continue;
            avisar(server, m, "§c" + m.nick(quem) + " desistiu da batalha.");
            registrarJogo(server, m, m.outro(quem), false);
            return;
        }
    }

    static void aoDesconectar(MinecraftServer server, UUID u) {
        fila.remove(u);
        Partida m = partidaDe(u);
        if (m == null || m.batalha == null) return;
        encerrarPorSaida(server, m, u, "desconectou");
    }

    private static void registrarJogo(MinecraftServer server, Partida m, UUID vencedor, boolean semBatalha) {
        Object battle = m.batalha;
        int turnos = battle == null ? 0 : Cobble.turno(battle);
        long seg = m.inicioJogo == 0 ? 0 : (agora() - m.inicioJogo) / 1000;
        if (semBatalha && battle == null) m.jogo++;
        m.batalha = null;
        m.batalhaId = null;
        if (!semBatalha && (turnos < Dados.config.turnosMinimos || seg < Dados.config.segundosMinimos)) {
            m.curto = true;
            Dados.discord("⚠️ Partida ranqueada muito curta: " + m.nickA + " x " + m.nickB + " (" + turnos + " turnos, " + seg
                    + " s). Vencedor: " + m.nick(vencedor) + ". Pode ser troca de vitória.");
        }
        if (vencedor.equals(m.a)) m.vitA++; else m.vitB++;
        atualizarBarra(m);
        if (m.serie && m.vitA < 2 && m.vitB < 2) {
            avisar(server, m, "§d" + m.nick(vencedor) + " venceu o jogo " + m.jogo + ". Placar: §f" + m.nickA + " §e" + m.vitA + " x "
                    + m.vitB + " §f" + m.nickB + "§d. Próximo jogo em " + Dados.config.segundosEntreJogosMd3 + " segundos.");
            m.proximoInicio = agora() + 1000L * Dados.config.segundosEntreJogosMd3;
            return;
        }
        finalizar(server, m, vencedor, turnos, seg);
    }

    private static void finalizar(MinecraftServer server, Partida m, UUID venc, int turnos, long seg) {
        Jogador ja = Dados.jogador(m.a, m.nickA), jb = Dados.jogador(m.b, m.nickB);
        String rankA0 = Pontos.nomeRank(m.a, ja), rankB0 = Pontos.nomeRank(m.b, jb);
        // partida MD1 curta demais não vale pontos
        boolean vale = !(m.curto && !m.serie);
        int dA = aplicarLado(server, m, m.a, ja, m.b, m.plB0, m.promoA, venc, vale);
        int dB = aplicarLado(server, m, m.b, jb, m.a, m.plA0, m.promoB, venc, vale);
        String placar = m.serie ? m.vitA + "x" + m.vitB : (venc.equals(m.a) ? "1x0" : "0x1");
        for (UUID u : List.of(m.a, m.b)) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(u);
            if (p == null) continue;
            Jogador j = Dados.jogador(u, null);
            int d = u.equals(m.a) ? dA : dB;
            String res = u.equals(venc) ? "§a§lVITÓRIA" : "§c§lDERROTA";
            p.sendMessage(Text.literal(res + "§r §7(" + placar + ") — " + (d >= 0 ? "§a+" : "§c") + d + " PL §7→ §f" + j.pl + " PL §7("
                    + Pontos.nomeRank(u, j) + "§7)" + (vale ? "" : " §8(partida curta demais: sem pontos)")));
            if (j.emPromocao && !(m.serie && (u.equals(m.a) ? m.promoA : m.promoB)))
                p.sendMessage(Text.literal("§d✦ Você chegou ao topo do rank! A próxima partida na fila é uma §lsérie de promoção MD3§r§d."));
        }
        anunciarSubida(server, m.a, ja, rankA0);
        anunciarSubida(server, m.b, jb, rankB0);
        contarConfronto(m);
        Dados.csv(String.valueOf(Dados.estado.temporada.numero), m.serie ? "MD3 promoção" : (m.desafio ? "MD1 desafio" : "MD1"),
                m.nickA, m.nickB, m.nick(venc), placar, String.valueOf(turnos), String.valueOf(seg),
                (dA >= 0 ? "+" : "") + dA, (dB >= 0 ? "+" : "") + dB, vale ? "" : "curta, sem pontos");
        if (Dados.config.resultadosNoDiscord)
            Dados.discord("🏟️ Ranqueada " + (m.serie ? "(promoção MD3)" : "MD1") + ": **" + m.nickA + "** " + placar + " **" + m.nickB
                    + "** — vencedor: " + m.nick(venc) + " (" + (dA >= 0 ? "+" : "") + dA + " / " + (dB >= 0 ? "+" : "") + dB + " PL)");
        Dados.salvar();
        fechar(m);
    }

    private static int aplicarLado(MinecraftServer server, Partida m, UUID u, Jogador j, UUID o, int plO, boolean promo, UUID venc, boolean vale) {
        int antes = j.pl;
        if (m.serie && promo) {
            boolean subiu = u.equals(venc);
            Pontos.contarConfronto(j, o);
            if (subiu) j.vitorias++; else j.derrotas++;
            Pontos.resultadoPromocao(j, subiu, o.toString());
            return j.pl - antes;
        }
        return Pontos.aplicar(j, o, plO, u.equals(venc), vale);
    }

    private static void anunciarSubida(MinecraftServer server, UUID u, Jogador j, String rankAntes) {
        String agoraRank = Pontos.nomeRank(u, j);
        if (agoraRank.equals(rankAntes)) return;
        int antes = -1;
        // só anuncia quando sobe de rank (não de divisão nem quando cai)
        for (Config.Rank r : Pontos.ranks()) if (rankAntes.contains(Pontos.cor(r.cor) + r.nome)) antes = Pontos.ranks().indexOf(r);
        if (antes >= 0 && Pontos.indiceRank(j.pl) > antes)
            server.getPlayerManager().broadcast(Text.literal("§6✦ §f" + j.nick + " §6subiu para " + agoraRank + "§6 na ranqueada!"), false);
    }

    private static void contarConfronto(Partida m) {
        String k = m.a.compareTo(m.b) < 0 ? m.a + "|" + m.b : m.b + "|" + m.a;
        List<Long> l = Dados.estado.confrontos.computeIfAbsent(k, x -> new ArrayList<>());
        long agora = agora();
        l.removeIf(t -> agora - t > 7L * 24 * 3600 * 1000);
        l.add(agora);
        if (l.size() == Dados.config.confrontosSemanaAlerta)
            Dados.discord("⚠️ " + m.nickA + " e " + m.nickB + " já se enfrentaram " + l.size()
                    + " vezes na ranqueada nesta semana. Vale conferir se não é troca de vitórias.");
    }

    // ---------------- desafio direto ----------------

    static String desafiar(MinecraftServer server, ServerPlayerEntity de, ServerPlayerEntity alvo) {
        if (!Dados.config.desafioDireto) return "o desafio direto está desligado.";
        if (de == alvo) return "você não pode se desafiar.";
        String e = podeDuelar(de);
        if (e != null) return e;
        String e2 = podeDuelar(alvo);
        if (e2 != null) return alvo.getGameProfile().getName() + " não pode jogar agora: " + e2;
        Jogador a = Dados.jogador(de.getUuid(), null), b = Dados.jogador(alvo.getUuid(), null);
        if (Pontos.indiceRank(a.pl) != Pontos.indiceRank(b.pl)) return "o desafio direto só vale entre jogadores do mesmo rank.";
        if (a.contraHoje.getOrDefault(Dados.hoje() + "|" + alvo.getUuid(), 0) >= 1) return "vocês já jogaram hoje; o desafio direto vale 1 vez por dia.";
        desafios.put(de.getUuid() + "|" + alvo.getUuid(), agora() + 60_000);
        alvo.sendMessage(Text.literal("§6⚔ " + de.getGameProfile().getName() + " te desafiou para uma partida ranqueada. Use §e/ranqueada aceitar "
                + de.getGameProfile().getName() + "§6 em até 60 segundos."));
        return null;
    }

    static String aceitar(MinecraftServer server, ServerPlayerEntity alvo, ServerPlayerEntity de) {
        Long exp = desafios.remove(de.getUuid() + "|" + alvo.getUuid());
        if (exp == null || exp < agora()) return "não há desafio válido de " + de.getGameProfile().getName() + ".";
        String e = podeDuelar(alvo);
        if (e != null) return e;
        String e2 = podeDuelar(de);
        if (e2 != null) return de.getGameProfile().getName() + " não pode jogar agora: " + e2;
        fila.remove(alvo.getUuid()); fila.remove(de.getUuid());
        criar(server, de, alvo, true);
        return null;
    }

    private static String podeDuelar(ServerPlayerEntity p) {
        if (!arenaPronta() || !naArena(p)) return "precisa estar na Arena Central.";
        if (partidaDe(p.getUuid()) != null) return "já está numa partida.";
        if (Cobble.emBatalha(p)) return "está em outra batalha.";
        String imp = impedimento(p);
        if (imp != null) return imp;
        if (Dados.jogador(p.getUuid(), null).emPromocao) return "está em série de promoção; use a fila (/ranqueada entrar).";
        List<String> erros = errosDoTime(Cobble.equipe(p), Dados.regulamento);
        if (!erros.isEmpty()) return "time fora do regulamento: " + String.join("; ", erros);
        return null;
    }
}
