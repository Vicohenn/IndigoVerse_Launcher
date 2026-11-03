package indigo.guard;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Fala com o Discord pela API REST usando o token do bot.
 *  - alertas no canal de logs (com @staff e 4 botões quando for o caso)
 *  - lê o canal de ações, onde o bot de doações coloca a decisão da staff, e executa no servidor
 */
public final class Discord {
    private static final String API = "https://discord.com/api/v10";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final ScheduledExecutorService EXEC = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "IndigoGuard-Discord");
        t.setDaemon(true);
        return t;
    });

    private static final java.util.Set<String> PROCESSADOS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private Discord() {}

    public static void iniciar(MinecraftServer server) {
        EXEC.scheduleWithFixedDelay(() -> lerAcoes(server), 10, 5, TimeUnit.SECONDS);
    }

    public static void parar() {
        EXEC.shutdownNow();
    }

    private static boolean pronto(String canal) {
        return !GuardConfig.get().token().isEmpty() && canal != null && !canal.isBlank();
    }

    // ---------------- alertas ----------------

    public static void enviarAlerta(String texto, boolean comBotoes, UUID suspeito) {
        GuardConfig cfg = GuardConfig.get();
        if (!pronto(cfg.canalLogs)) {
            IndigoGuardMod.LOGGER.warn("IndigoGuard: Discord não configurado; alerta só no log do servidor.");
            return;
        }
        JsonObject corpo = new JsonObject();
        String mencao = comBotoes && cfg.cargoStaff != null && !cfg.cargoStaff.isBlank() ? "<@&" + cfg.cargoStaff.trim() + ">\n" : "";
        corpo.addProperty("content", corta(mencao + texto, 1990));
        JsonObject allowed = new JsonObject();
        JsonArray roles = new JsonArray();
        if (!mencao.isEmpty()) roles.add(cfg.cargoStaff.trim());
        allowed.add("parse", new JsonArray());
        allowed.add("roles", roles);
        corpo.add("allowed_mentions", allowed);
        if (comBotoes && suspeito != null) {
            JsonArray linha = new JsonArray();
            linha.add(botao("Banir", "ig:ban:" + suspeito, 4));
            linha.add(botao("Banir x tempo", "ig:tempban:" + suspeito, 4));
            linha.add(botao("Kick", "ig:kick:" + suspeito, 2));
            linha.add(botao("Ignorar", "ig:ignore:" + suspeito, 2));
            JsonObject row = new JsonObject();
            row.addProperty("type", 1);
            row.add("components", linha);
            JsonArray comps = new JsonArray();
            comps.add(row);
            corpo.add("components", comps);
        }
        EXEC.execute(() -> enviar("POST", "/channels/" + cfg.canalLogs.trim() + "/messages", corpo.toString()));
    }

    private static JsonObject botao(String rotulo, String id, int estilo) {
        JsonObject b = new JsonObject();
        b.addProperty("type", 2);
        b.addProperty("style", estilo);
        b.addProperty("label", rotulo);
        b.addProperty("custom_id", id);
        return b;
    }

    // ---------------- ações da staff ----------------

    /** Mensagens do canal de ações que começam com "🛡️ ACAO" e ainda não foram executadas. */
    private static void lerAcoes(MinecraftServer server) {
        GuardConfig cfg = GuardConfig.get();
        if (!pronto(cfg.canalAcoes)) return;
        try {
            String resp = enviar("GET", "/channels/" + cfg.canalAcoes.trim() + "/messages?limit=25", null);
            if (resp == null) return;
            JsonArray msgs = JsonParser.parseString(resp).getAsJsonArray();
            for (JsonElement el : msgs) {
                JsonObject m = el.getAsJsonObject();
                String conteudo = m.get("content").getAsString();
                if (!conteudo.startsWith("🛡️ ACAO")) continue;
                int i = conteudo.indexOf('{'), f = conteudo.lastIndexOf('}');
                if (i < 0 || f < i) continue;
                JsonObject acao = JsonParser.parseString(conteudo.substring(i, f + 1)).getAsJsonObject();
                String id = m.get("id").getAsString();
                if (!PROCESSADOS.add(id)) continue;
                server.execute(() -> {
                    String resultado = Acoes.executar(server, acao);
                    EXEC.execute(() -> {
                        JsonObject edit = new JsonObject();
                        edit.addProperty("content", corta(resultado + "\n```json\n" + acao + "\n```", 1990));
                        enviar("PATCH", "/channels/" + cfg.canalAcoes.trim() + "/messages/" + id, edit.toString());
                    });
                });
            }
        } catch (Exception e) {
            IndigoGuardMod.LOGGER.debug("IndigoGuard: erro lendo ações: {}", e.toString());
        }
    }

    // ---------------- HTTP ----------------

    private static String enviar(String metodo, String caminho, String json) {
        for (int tentativa = 0; tentativa < 3; tentativa++) {
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(API + caminho))
                        .timeout(Duration.ofSeconds(15))
                        .header("Authorization", "Bot " + GuardConfig.get().token())
                        .header("User-Agent", "DiscordBot (indigoguard, 1.0)");
                if (json == null) b.method(metodo, HttpRequest.BodyPublishers.noBody());
                else b.header("Content-Type", "application/json").method(metodo, HttpRequest.BodyPublishers.ofString(json));
                HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 429) {
                    double espera = 1.0;
                    try { espera = JsonParser.parseString(r.body()).getAsJsonObject().get("retry_after").getAsDouble(); } catch (Exception ignored) { }
                    Thread.sleep((long) (espera * 1000) + 250);
                    continue;
                }
                if (r.statusCode() >= 300) {
                    IndigoGuardMod.LOGGER.warn("IndigoGuard: Discord respondeu {} em {}: {}", r.statusCode(), caminho, corta(r.body(), 300));
                    return null;
                }
                return r.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                IndigoGuardMod.LOGGER.warn("IndigoGuard: falha falando com o Discord: {}", e.toString());
            }
        }
        return null;
    }

    static String corta(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
