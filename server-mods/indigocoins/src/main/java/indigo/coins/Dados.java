package indigo.coins;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tudo que é salvo em config/indigocoins/. */
public final class Dados {
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static final class Moeda {
        /** UUID do dono atual. */
        public String dono;
        public String nick;
        /** raspadinha / drop / admin */
        public String origem;
        public long criadaEm;
        /** != 0 quando foi gasta na loja (o ID nunca é reaproveitado). */
        public long gastaEm;
    }

    public static final class Rodada {
        public int rodada = 1;
        /** Número premiado da rodada (12 dígitos). Secreto: só aparece no relatório. */
        public String numero;
        public long inicio;
        public int bilhetesLidos;
    }

    public static final class Resultado {
        public int rodada;
        public String numero;
        public long inicio, fim;
        public int bilhetesLidos;
        public String vencedor = "";
        public String vencedorUuid = "";
        public int recompensa;
        public List<String> moedas = new ArrayList<>();
        /** "premiada" ou "24h" */
        public String motivo;
    }

    static Config config = new Config();
    /** id (12 dígitos) -> moeda */
    static Map<String, Moeda> moedas = new LinkedHashMap<>();
    static Rodada rodada = new Rodada();
    static List<Resultado> historico = new ArrayList<>();
    /** Raspadinhas a entregar para quem estava offline (nick em minúsculas -> quantidade). */
    static Map<String, Integer> pendentes = new LinkedHashMap<>();
    /** Amulet Coins a entregar para quem estava offline. */
    static Map<String, Integer> amuletosPendentes = new LinkedHashMap<>();

    private Dados() {}

    static Path pasta() { return FabricLoader.getInstance().getConfigDir().resolve("indigocoins"); }

    static void carregar() {
        try {
            Files.createDirectories(pasta());
            Config c = ler("config.json", Config.class);
            config = c != null ? c : new Config();
            salvar("config.json", config);   // grava campos novos
            Map<String, Moeda> m = ler("moedas.json", new TypeToken<LinkedHashMap<String, Moeda>>() {}.getType());
            moedas = m != null ? m : new LinkedHashMap<>();
            Rodada r = ler("sorteio.json", Rodada.class);
            rodada = r != null ? r : null;
            List<Resultado> h = ler("historico.json", new TypeToken<ArrayList<Resultado>>() {}.getType());
            historico = h != null ? h : new ArrayList<>();
            Map<String, Integer> pe = ler("raspadinhas-pendentes.json", new TypeToken<LinkedHashMap<String, Integer>>() {}.getType());
            pendentes = pe != null ? pe : new LinkedHashMap<>();
            Map<String, Integer> ap = ler("amuletos-pendentes.json", new TypeToken<LinkedHashMap<String, Integer>>() {}.getType());
            amuletosPendentes = ap != null ? ap : new LinkedHashMap<>();
        } catch (Exception e) {
            IndigoCoinsMod.LOGGER.error("Erro carregando dados do IndigoCoins", e);
        }
    }

    static <T> T lerPublico(String nome, Type tipo) throws Exception { return ler(nome, tipo); }

    @SuppressWarnings("unchecked")
    private static <T> T ler(String nome, Type tipo) throws Exception {
        Path f = pasta().resolve(nome);
        if (!Files.exists(f)) return null;
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            return (T) GSON.fromJson(r, tipo);
        }
    }

    static void salvar(String nome, Object obj) {
        try {
            Files.createDirectories(pasta());
            Path tmp = pasta().resolve(nome + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(obj, w);
            }
            Files.move(tmp, pasta().resolve(nome), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            IndigoCoinsMod.LOGGER.error("Erro salvando {}", nome, e);
        }
    }

    static void salvarMoedas() { salvar("moedas.json", moedas); }
    static void salvarRodada() { salvar("sorteio.json", rodada); }
    static void salvarHistorico() { salvar("historico.json", historico); }
    static void salvarPendentes() { salvar("raspadinhas-pendentes.json", pendentes); salvar("amuletos-pendentes.json", amuletosPendentes); }

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Registro de tudo (criação, venda, gasto) para auditoria. */
    static void log(String linha) {
        try {
            Files.writeString(pasta().resolve("transacoes.log"), LocalDateTime.now().format(FMT) + " " + linha + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            IndigoCoinsMod.LOGGER.error("Erro no transacoes.log", e);
        }
        IndigoCoinsMod.LOGGER.info(linha);
    }
}
