package indigo.dados;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static net.minecraft.server.command.CommandManager.literal;

/**
 * IndigoDados: manda dados do servidor para um banco TiDB Serverless (MySQL) em segundo plano.
 * Nada roda na thread do jogo: as gravações vão para uma fila e uma thread separada envia.
 * Config: config/indigodados.json (os dados de acesso ao banco você mesmo cola lá).
 */
public final class IndigoDadosMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("indigodados");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static final class Config {
        public boolean ativo = false;
        public String host = "";
        public int porta = 4000;
        public String banco = "indigoverse";
        public String usuario = "";
        public String senha = "";
        /** De quantos em quantos minutos manda saldos, coins, raspadinha e ranqueada. */
        public int intervaloMinutos = 10;
    }

    private interface Tarefa { void rodar(Connection c) throws Exception; }

    private static Config cfg = new Config();
    private static ExecutorService fila;
    private static final AtomicInteger pendentes = new AtomicInteger();
    private static final Map<UUID, Long> entradas = new ConcurrentHashMap<>();
    private static volatile String ultimoErro = "";
    private static volatile long ultimoEnvio;
    private static Connection conexao;
    private static long ticks;

    @Override
    public void onInitialize() {
        carregarConfig();
        fila = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "IndigoDados"); t.setDaemon(true); return t; });
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            if (!pronto()) { LOGGER.info("IndigoDados desligado (preencha config/indigodados.json e coloque \"ativo\": true)."); return; }
            enviar(IndigoDadosMod::criarTabelas);
            LOGGER.info("IndigoDados ligado: enviando para {}:{}/{}.", cfg.host, cfg.porta, cfg.banco);
        });
        ServerPlayConnectionEvents.JOIN.register((h, sender, s) -> {
            ServerPlayerEntity p = h.player;
            long agora = System.currentTimeMillis();
            entradas.put(p.getUuid(), agora);
            String uuid = p.getUuidAsString(), nick = p.getGameProfile().getName();
            enviar(c -> {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO jogadores (uuid, nick, primeiro_login, ultimo_login, minutos_total) VALUES (?,?,?,?,0) "
                        + "ON DUPLICATE KEY UPDATE nick=VALUES(nick), ultimo_login=VALUES(ultimo_login)")) {
                    ps.setString(1, uuid); ps.setString(2, nick); ps.setTimestamp(3, new Timestamp(agora)); ps.setTimestamp(4, new Timestamp(agora));
                    ps.executeUpdate();
                }
            });
        });
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> fecharSessao(h.player));
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) fecharSessao(p);
            fila.shutdown();
            try { fila.awaitTermination(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            try { if (conexao != null) conexao.close(); } catch (Exception ignored) {}
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            ticks++;
            if (ticks % (20L * 60 * Math.max(1, cfg.intervaloMinutos)) == 0) sincronizar(s);
        });
        CommandRegistrationCallback.EVENT.register((d, reg, env) -> d.register(literal("indigodados").requires(x -> x.hasPermissionLevel(3))
                .executes(c -> {
                    String st = !cfg.ativo ? "desligado" : !pronto() ? "config incompleta" : "ligado";
                    String ult = ultimoEnvio == 0 ? "nunca" : ((System.currentTimeMillis() - ultimoEnvio) / 1000) + " s atrás";
                    c.getSource().sendFeedback(() -> Text.literal("§6IndigoDados: §f" + st + " §7| último envio: §f" + ult + " §7| na fila: §f"
                            + pendentes.get() + (ultimoErro.isEmpty() ? "" : "\n§cÚltimo erro: " + ultimoErro)), false);
                    return 1;
                })
                .then(literal("recarregar").executes(c -> {
                    carregarConfig();
                    synchronized (IndigoDadosMod.class) { try { if (conexao != null) conexao.close(); } catch (Exception ignored) {} conexao = null; }
                    if (pronto()) enviar(IndigoDadosMod::criarTabelas);
                    c.getSource().sendFeedback(() -> Text.literal("§aConfig recarregada (" + (pronto() ? "ligado" : "desligado") + ")."), false);
                    return 1;
                }))
                .then(literal("enviar").executes(c -> {
                    if (!pronto()) { c.getSource().sendError(Text.literal("IndigoDados está desligado ou sem dados de acesso.")); return 0; }
                    sincronizar(c.getSource().getServer());
                    c.getSource().sendFeedback(() -> Text.literal("§aEnvio iniciado. Veja o resultado com /indigodados."), false);
                    return 1;
                }))));
        LOGGER.info("IndigoDados carregado.");
    }

    // ---------------- config e conexão ----------------

    private static Path arquivo() { return FabricLoader.getInstance().getConfigDir().resolve("indigodados.json"); }

    private static void carregarConfig() {
        try {
            if (Files.exists(arquivo())) {
                Config c = GSON.fromJson(Files.readString(arquivo(), StandardCharsets.UTF_8), Config.class);
                if (c != null) cfg = c;
            }
            Files.writeString(arquivo(), GSON.toJson(cfg), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.error("Erro na config do IndigoDados: {}", e.toString());
        }
    }

    private static boolean pronto() {
        return cfg.ativo && !cfg.host.isBlank() && !cfg.usuario.isBlank();
    }

    private static synchronized Connection conectar() throws Exception {
        if (conexao != null && conexao.isValid(5)) return conexao;
        String url = "jdbc:mysql://" + cfg.host + ":" + cfg.porta + "/" + cfg.banco
                + "?sslMode=VERIFY_IDENTITY&enabledTLSProtocols=TLSv1.2,TLSv1.3&useUnicode=true&characterEncoding=UTF-8"
                + "&connectTimeout=10000&socketTimeout=30000&createDatabaseIfNotExist=true";
        Properties pr = new Properties();
        pr.setProperty("user", cfg.usuario);
        pr.setProperty("password", cfg.senha);
        conexao = new com.mysql.cj.jdbc.Driver().connect(url, pr);
        return conexao;
    }

    private static void enviar(Tarefa t) {
        if (!pronto() || fila.isShutdown()) return;
        if (pendentes.get() > 5000) return;   // banco fora do ar há muito tempo: não acumula sem limite
        pendentes.incrementAndGet();
        fila.submit(() -> {
            try {
                Connection c = conectar();
                t.rodar(c);
                ultimoEnvio = System.currentTimeMillis();
                ultimoErro = "";
            } catch (Exception e) {
                String m = e.getMessage() == null ? e.toString() : e.getMessage();
                if (!m.equals(ultimoErro)) LOGGER.warn("IndigoDados: falha ao enviar: {}", m);
                ultimoErro = m;
                synchronized (IndigoDadosMod.class) { conexao = null; }
            } finally {
                pendentes.decrementAndGet();
            }
        });
    }

    private static void criarTabelas(Connection c) throws Exception {
        String[] sql = {
                "CREATE TABLE IF NOT EXISTS jogadores (uuid CHAR(36) PRIMARY KEY, nick VARCHAR(32), primeiro_login DATETIME, ultimo_login DATETIME, minutos_total INT DEFAULT 0)",
                "CREATE TABLE IF NOT EXISTS sessoes (id BIGINT AUTO_INCREMENT PRIMARY KEY, uuid CHAR(36), nick VARCHAR(32), entrada DATETIME, saida DATETIME, minutos INT, INDEX(uuid), INDEX(entrada))",
                "CREATE TABLE IF NOT EXISTS saldos (id BIGINT AUTO_INCREMENT PRIMARY KEY, uuid CHAR(36), nick VARCHAR(32), saldo DECIMAL(30,0), registrado_em DATETIME, INDEX(uuid), INDEX(registrado_em))",
                "CREATE TABLE IF NOT EXISTS indigo_coins (id VARCHAR(16) PRIMARY KEY, dono_uuid CHAR(36), dono_nick VARCHAR(32), origem VARCHAR(64), criada_em DATETIME, gasta_em DATETIME NULL, atualizado_em DATETIME)",
                "CREATE TABLE IF NOT EXISTS raspadinha_rodadas (rodada INT PRIMARY KEY, numero VARCHAR(16), inicio DATETIME, fim DATETIME, bilhetes INT, vencedor VARCHAR(32), vencedor_uuid CHAR(36), recompensa INT, moedas TEXT, motivo VARCHAR(64))",
                "CREATE TABLE IF NOT EXISTS ranqueada_jogadores (uuid CHAR(36) PRIMARY KEY, nick VARCHAR(32), temporada INT, pl INT, vitorias INT, derrotas INT, partidas_temporada INT, atualizado_em DATETIME)",
                "CREATE TABLE IF NOT EXISTS eventos (id BIGINT AUTO_INCREMENT PRIMARY KEY, tipo VARCHAR(32), uuid CHAR(36), nick VARCHAR(32), detalhe TEXT, quando DATETIME, INDEX(tipo), INDEX(quando))",
                "CREATE TABLE IF NOT EXISTS cupons (codigo VARCHAR(32) PRIMARY KEY, descricao VARCHAR(255), desconto DECIMAL(5,2), valido_ate DATETIME, usos_max INT, usos INT DEFAULT 0)"
        };
        try (Statement st = c.createStatement()) { for (String s : sql) st.execute(s); }
        LOGGER.info("IndigoDados: tabelas conferidas no banco.");
    }

    // ---------------- sessões ----------------

    private static void fecharSessao(ServerPlayerEntity p) {
        Long ini = entradas.remove(p.getUuid());
        if (ini == null) return;
        long fim = System.currentTimeMillis();
        int min = (int) ((fim - ini) / 60000);
        String uuid = p.getUuidAsString(), nick = p.getGameProfile().getName();
        enviar(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO sessoes (uuid, nick, entrada, saida, minutos) VALUES (?,?,?,?,?)")) {
                ps.setString(1, uuid); ps.setString(2, nick); ps.setTimestamp(3, new Timestamp(ini)); ps.setTimestamp(4, new Timestamp(fim)); ps.setInt(5, min);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE jogadores SET minutos_total = minutos_total + ?, ultimo_login = ? WHERE uuid = ?")) {
                ps.setInt(1, min); ps.setTimestamp(2, new Timestamp(fim)); ps.setString(3, uuid);
                ps.executeUpdate();
            }
        });
    }

    // ---------------- sincronização periódica ----------------

    private static void sincronizar(MinecraftServer s) {
        if (!pronto()) return;
        // saldos: lidos na thread do jogo, enviados depois
        List<String[]> saldos = new ArrayList<>();
        for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
            BigInteger b = Economia.saldo(p);
            if (b != null) saldos.add(new String[]{p.getUuidAsString(), p.getGameProfile().getName(), b.toString()});
        }
        long agora = System.currentTimeMillis();
        enviar(c -> {
            if (saldos.isEmpty()) return;
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO saldos (uuid, nick, saldo, registrado_em) VALUES (?,?,?,?)")) {
                for (String[] x : saldos) {
                    ps.setString(1, x[0]); ps.setString(2, x[1]); ps.setBigDecimal(3, new java.math.BigDecimal(x[2])); ps.setTimestamp(4, new Timestamp(agora));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
        Path cfgDir = FabricLoader.getInstance().getConfigDir();
        enviar(c -> enviarCoins(c, cfgDir.resolve("indigocoins/moedas.json")));
        enviar(c -> enviarRaspadinha(c, cfgDir.resolve("indigocoins/historico.json")));
        enviar(c -> enviarRanqueada(c, cfgDir.resolve("indigoranked/dados.json")));
    }

    private static JsonElement lerJson(Path p) throws Exception {
        if (!Files.exists(p)) return null;
        return JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8));
    }

    private static String str(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null; }
    private static long lng(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsLong() : 0; }
    private static Timestamp ts(long ms) { return ms <= 0 ? null : new Timestamp(ms); }

    private static void enviarCoins(Connection c, Path p) throws Exception {
        JsonElement e = lerJson(p);
        if (e == null || !e.isJsonObject()) return;
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO indigo_coins (id, dono_uuid, dono_nick, origem, criada_em, gasta_em, atualizado_em) VALUES (?,?,?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE dono_uuid=VALUES(dono_uuid), dono_nick=VALUES(dono_nick), gasta_em=VALUES(gasta_em), atualizado_em=VALUES(atualizado_em)")) {
            Timestamp agora = new Timestamp(System.currentTimeMillis());
            for (Map.Entry<String, JsonElement> m : e.getAsJsonObject().entrySet()) {
                JsonObject o = m.getValue().getAsJsonObject();
                ps.setString(1, m.getKey()); ps.setString(2, str(o, "dono")); ps.setString(3, str(o, "nick")); ps.setString(4, str(o, "origem"));
                ps.setTimestamp(5, ts(lng(o, "criadaEm"))); ps.setTimestamp(6, ts(lng(o, "gastaEm"))); ps.setTimestamp(7, agora);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void enviarRaspadinha(Connection c, Path p) throws Exception {
        JsonElement e = lerJson(p);
        if (e == null || !e.isJsonArray()) return;
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO raspadinha_rodadas (rodada, numero, inicio, fim, bilhetes, vencedor, vencedor_uuid, recompensa, moedas, motivo) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE fim=VALUES(fim), bilhetes=VALUES(bilhetes), vencedor=VALUES(vencedor), vencedor_uuid=VALUES(vencedor_uuid), "
                + "recompensa=VALUES(recompensa), moedas=VALUES(moedas), motivo=VALUES(motivo)")) {
            for (JsonElement x : (JsonArray) e) {
                JsonObject o = x.getAsJsonObject();
                ps.setInt(1, (int) lng(o, "rodada")); ps.setString(2, str(o, "numero")); ps.setTimestamp(3, ts(lng(o, "inicio"))); ps.setTimestamp(4, ts(lng(o, "fim")));
                ps.setInt(5, (int) lng(o, "bilhetesLidos")); ps.setString(6, str(o, "vencedor")); ps.setString(7, str(o, "vencedorUuid"));
                ps.setInt(8, (int) lng(o, "recompensa")); ps.setString(9, o.has("moedas") ? o.get("moedas").toString() : "[]"); ps.setString(10, str(o, "motivo"));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void enviarRanqueada(Connection c, Path p) throws Exception {
        JsonElement e = lerJson(p);
        if (e == null || !e.isJsonObject()) return;
        JsonObject raiz = e.getAsJsonObject();
        int temporada = raiz.has("temporada") ? (int) lng(raiz.getAsJsonObject("temporada"), "numero") : 0;
        if (!raiz.has("jogadores")) return;
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO ranqueada_jogadores (uuid, nick, temporada, pl, vitorias, derrotas, partidas_temporada, atualizado_em) "
                + "VALUES (?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE nick=VALUES(nick), temporada=VALUES(temporada), pl=VALUES(pl), vitorias=VALUES(vitorias), "
                + "derrotas=VALUES(derrotas), partidas_temporada=VALUES(partidas_temporada), atualizado_em=VALUES(atualizado_em)")) {
            Timestamp agora = new Timestamp(System.currentTimeMillis());
            for (Map.Entry<String, JsonElement> m : raiz.getAsJsonObject("jogadores").entrySet()) {
                JsonObject o = m.getValue().getAsJsonObject();
                ps.setString(1, m.getKey()); ps.setString(2, str(o, "nick")); ps.setInt(3, temporada); ps.setInt(4, (int) lng(o, "pl"));
                ps.setInt(5, (int) lng(o, "vitorias")); ps.setInt(6, (int) lng(o, "derrotas")); ps.setInt(7, (int) lng(o, "partidasTemporada")); ps.setTimestamp(8, agora);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}
