package indigo.vip;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Lê os pedidos que o bot de doações posta num canal privado do Discord ("📦 FILA" + JSON),
 * entrega no jogo e edita a mensagem para "✅ ENTREGUE" ou "⚠️ ERRO". Assim o bot pode rodar
 * numa hospedagem separada e o servidor pega os pedidos quando estiver ligado.
 */
public final class DiscordFila {
    private static final String API = "https://discord.com/api/v10";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static ScheduledExecutorService exec;

    private DiscordFila() {}

    private record Item(String msgId, JsonObject pedido, String bloco) {}

    public static void iniciar(MinecraftServer server) {
        VipConfig c = VipConfig.get();
        if (c.discordToken == null || c.discordToken.isBlank() || c.discordCanalFila == null || c.discordCanalFila.isBlank()) {
            IndigoVipMod.LOGGER.info("Fila do Discord desligada (sem discordToken/discordCanalFila); usando só a pasta.");
            return;
        }
        parar();
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "IndigoVIP-DiscordFila");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleWithFixedDelay(() -> {
            try {
                ler(server);
            } catch (Exception e) {
                IndigoVipMod.LOGGER.warn("Fila do Discord: {}", e.toString());
            }
        }, 10, 10, TimeUnit.SECONDS);
        IndigoVipMod.LOGGER.info("Fila do Discord ligada (canal {}).", c.discordCanalFila);
    }

    public static void parar() {
        if (exec != null) exec.shutdownNow();
        exec = null;
    }

    private static HttpRequest.Builder req(String path) {
        return HttpRequest.newBuilder(URI.create(API + path))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bot " + VipConfig.get().discordToken.trim())
                .header("User-Agent", "DiscordBot (https://github.com/Vicohenn/IndigoVerse_Launcher, 1.0) IndigoVIP");
    }

    private static void ler(MinecraftServer server) throws Exception {
        String canal = VipConfig.get().discordCanalFila.trim();
        HttpResponse<String> r = HTTP.send(req("/channels/" + canal + "/messages?limit=100").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            IndigoVipMod.LOGGER.warn("Fila do Discord: HTTP {} ao ler o canal", r.statusCode());
            return;
        }
        List<Item> itens = new ArrayList<>();
        JsonArray arr = JsonParser.parseString(r.body()).getAsJsonArray();
        for (JsonElement el : arr) {
            JsonObject m = el.getAsJsonObject();
            String conteudo = m.has("content") ? m.get("content").getAsString() : "";
            if (!conteudo.startsWith("📦 FILA")) continue;
            int a = conteudo.indexOf("```json"), b = conteudo.lastIndexOf("```");
            if (a < 0 || b <= a) continue;
            String json = conteudo.substring(a + 7, b).trim();
            try {
                itens.add(new Item(m.get("id").getAsString(), JsonParser.parseString(json).getAsJsonObject(), json));
            } catch (Exception e) {
                IndigoVipMod.LOGGER.warn("Fila do Discord: mensagem {} com JSON inválido", m.get("id").getAsString());
            }
        }
        if (itens.isEmpty()) return;
        // mais antigos primeiro
        java.util.Collections.reverse(itens);
        server.execute(() -> {
            for (Item it : itens) {
                Fila.Resultado res = Fila.processarPedido(server, it.pedido());
                if (res.estado() == Fila.Estado.ESPERA) continue;
                String quando = new SimpleDateFormat("dd/MM HH:mm").format(new Date());
                String novo = (res.estado() == Fila.Estado.ENTREGUE ? "✅ ENTREGUE " + quando + " — " : "⚠️ ERRO " + quando + " — ")
                        + res.texto() + "\n```json\n" + it.bloco() + "\n```";
                if (novo.length() > 1900) novo = novo.substring(0, 1900);
                editar(it.msgId(), novo);
            }
        });
    }

    private static void editar(String msgId, String conteudo) {
        if (exec == null) return;
        exec.execute(() -> {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("content", conteudo);
                HttpResponse<String> r = HTTP.send(req("/channels/" + VipConfig.get().discordCanalFila.trim() + "/messages/" + msgId)
                                .header("Content-Type", "application/json")
                                .method("PATCH", HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() >= 300) IndigoVipMod.LOGGER.warn("Fila do Discord: HTTP {} ao marcar {}", r.statusCode(), msgId);
            } catch (Exception e) {
                IndigoVipMod.LOGGER.warn("Fila do Discord: falha ao marcar {}: {}", msgId, e.toString());
            }
        });
    }
}
