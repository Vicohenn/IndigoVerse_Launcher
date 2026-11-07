package indigo.ranked;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Configuração, regulamentos e dados da temporada (tudo em config/indigoranked/). */
public final class Dados {
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    public static final class Jogador {
        public String nick = "";
        public int pl;
        public int partidas, vitorias, derrotas;
        public int posicionamentoRestante;
        public boolean emPromocao;
        public int protecao;
        public int seriesFalhas;
        public boolean banido;
        public boolean liberado;
        public String ultimoOponenteSerie = "";
        /** Horário (ms) das partidas dos últimos 7 dias, para a inatividade. */
        public List<Long> partidasRecentes = new ArrayList<>();
        /** Partidas de hoje contra cada adversário: "data|uuid" -> quantidade. */
        public Map<String, Integer> contraHoje = new HashMap<>();
        public long dinheiroPendente;
        public List<String> comandosPendentes = new ArrayList<>();
        public int partidasTemporada;
    }

    public static final class Temporada {
        public int numero = 1;
        public String regulamento = "kanto_classico";
        public long inicio = System.currentTimeMillis();
        public long ultimaInatividade = System.currentTimeMillis();
    }

    public static final class Estado {
        public Temporada temporada = new Temporada();
        public Map<String, Jogador> jogadores = new LinkedHashMap<>();
        /** "uuidA|uuidB" (ordenados) -> horários dos confrontos da última semana. */
        public Map<String, List<Long>> confrontos = new HashMap<>();
    }

    static Config config = new Config();
    static Estado estado = new Estado();
    static Regulamento regulamento = new Regulamento();
    private static boolean sujo;

    private Dados() {}

    static Path pasta() { return FabricLoader.getInstance().getConfigDir().resolve("indigoranked"); }
    static Path pastaRegulamentos() { return pasta().resolve("regulamentos"); }

    static void carregar() {
        try {
            Files.createDirectories(pastaRegulamentos());
            Path c = pasta().resolve("config.json");
            if (Files.exists(c)) try (Reader r = Files.newBufferedReader(c, StandardCharsets.UTF_8)) {
                Config lido = GSON.fromJson(r, Config.class);
                if (lido != null) config = lido;
            }
            salvarConfig();
            Path d = pasta().resolve("dados.json");
            if (Files.exists(d)) try (Reader r = Files.newBufferedReader(d, StandardCharsets.UTF_8)) {
                Estado lido = GSON.fromJson(r, Estado.class);
                if (lido != null) estado = lido;
            }
            if (estado.temporada == null) estado.temporada = new Temporada();
            if (estado.jogadores == null) estado.jogadores = new LinkedHashMap<>();
            if (estado.confrontos == null) estado.confrontos = new HashMap<>();
            criarExemplos();
            carregarRegulamento();
        } catch (Exception e) {
            IndigoRankedMod.LOGGER.error("Erro carregando a ranqueada", e);
        }
    }

    private static void criarExemplos() throws Exception {
        try (var s = Files.list(pastaRegulamentos())) { if (s.findAny().isPresent()) return; }
        Map<String, Regulamento> ex = new TreeMap<>();
        ex.put("kanto_classico", Regulamento.exemplo("Kanto Clássico",
                "Singles. Só Pokémon da Pokédex de Kanto, sem lendários e míticos.", "singles", 3, 6, 1, 151,
                List.of("legendary", "mythical", "ultra_beast", "paradox"), 0, "Mega Evolução"));
        ex.put("reg_h", Regulamento.exemplo("Inspirado na Reg. H",
                "Duplas. Sem lendários, míticos, Ultra Beasts e Paradoxos.", "doubles", 4, 6, 0, 0,
                List.of("legendary", "mythical", "ultra_beast", "paradox"), 0, "Terastal"));
        ex.put("reg_g", Regulamento.exemplo("Inspirado na Reg. G",
                "Duplas. Até 1 lendário restrito por time, sem míticos.", "doubles", 4, 6, 0, 0,
                List.of("mythical"), 1, "Terastal"));
        ex.put("livre_mestre", Regulamento.exemplo("Livre de Mestre",
                "Singles. Até 2 lendários restritos, sem míticos.", "singles", 3, 6, 0, 0,
                List.of("mythical"), 2, "Dynamax"));
        for (var e : ex.entrySet())
            Files.writeString(pastaRegulamentos().resolve(e.getKey() + ".json"), GSON.toJson(e.getValue()), StandardCharsets.UTF_8);
    }

    static List<String> regulamentosDisponiveis() {
        List<String> out = new ArrayList<>();
        try (var s = Files.list(pastaRegulamentos())) {
            s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json"))
                    .forEach(n -> out.add(n.substring(0, n.length() - 5)));
        } catch (Exception ignored) {}
        out.sort(String::compareTo);
        return out;
    }

    static Regulamento lerRegulamento(String id) {
        Path p = pastaRegulamentos().resolve(id + ".json");
        if (!Files.exists(p)) return null;
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            return GSON.fromJson(r, Regulamento.class);
        } catch (Exception e) {
            IndigoRankedMod.LOGGER.error("Regulamento {} com erro: {}", id, e.toString());
            return null;
        }
    }

    static void carregarRegulamento() {
        Regulamento r = lerRegulamento(estado.temporada.regulamento);
        if (r == null) {
            IndigoRankedMod.LOGGER.warn("Regulamento '{}' não encontrado; usando um vazio.", estado.temporada.regulamento);
            r = new Regulamento();
            r.nome = estado.temporada.regulamento;
        }
        regulamento = r;
    }

    static void salvarConfig() {
        try (Writer w = Files.newBufferedWriter(pasta().resolve("config.json"), StandardCharsets.UTF_8)) {
            GSON.toJson(config, w);
        } catch (Exception e) {
            IndigoRankedMod.LOGGER.error("Erro salvando config da ranqueada", e);
        }
    }

    static void marcar() { sujo = true; }

    static void salvarSeMudou() { if (sujo) salvar(); }

    static void salvar() {
        sujo = false;
        try {
            Path tmp = pasta().resolve("dados.json.tmp");
            Files.writeString(tmp, GSON.toJson(estado), StandardCharsets.UTF_8);
            Files.move(tmp, pasta().resolve("dados.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            IndigoRankedMod.LOGGER.error("Erro salvando dados da ranqueada", e);
        }
    }

    static Jogador jogador(java.util.UUID u, String nick) {
        Jogador j = estado.jogadores.computeIfAbsent(u.toString(), k -> {
            Jogador n = new Jogador();
            n.pl = config.plInicial;
            n.posicionamentoRestante = config.partidasPosicionamento;
            return n;
        });
        if (nick != null) j.nick = nick;
        if (j.partidasRecentes == null) j.partidasRecentes = new ArrayList<>();
        if (j.contraHoje == null) j.contraHoje = new HashMap<>();
        if (j.comandosPendentes == null) j.comandosPendentes = new ArrayList<>();
        return j;
    }

    static String hoje() { return LocalDate.now(FUSO).toString(); }

    // ---------- registro ----------

    static void csv(String... colunas) {
        try {
            Path p = pasta().resolve("partidas.csv");
            boolean novo = !Files.exists(p);
            StringBuilder sb = new StringBuilder();
            if (novo) sb.append('﻿').append("Data;Temporada;Tipo;Jogador A;Jogador B;Vencedor;Placar;Turnos;Segundos;PL A;PL B;Observação\n");
            sb.append(LocalDateTime.now(FUSO).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")));
            for (String c : colunas) sb.append(';').append(c == null ? "" : c.replace(';', ','));
            sb.append('\n');
            Files.writeString(p, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            IndigoRankedMod.LOGGER.warn("Não consegui gravar partidas.csv (aberto no Excel?): {}", e.toString());
        }
    }

    static void discord(String texto) {
        IndigoRankedMod.LOGGER.info("[Ranqueada] {}", texto);
        String url = config.webhookDiscord;
        if (url == null || !url.startsWith("https://")) return;
        try {
            String json = "{\"content\":" + GSON.toJson(texto.length() > 1900 ? texto.substring(0, 1900) : texto) + "}";
            HTTP.sendAsync(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            IndigoRankedMod.LOGGER.warn("Webhook do Discord falhou: {}", e.toString());
        }
    }

    static Map<String, Jogador> jogadores() { return estado.jogadores; }

    static java.lang.reflect.Type tipoLista() { return new TypeToken<List<String>>() {}.getType(); }
}
