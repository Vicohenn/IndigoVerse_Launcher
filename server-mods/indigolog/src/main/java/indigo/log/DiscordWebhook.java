package indigo.log;

import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Envia as linhas de log para o webhook do Discord.
 * As linhas ficam numa fila e são agrupadas (até ~1900 caracteres por mensagem, a cada 3 s),
 * para não estourar o limite do Discord quando muita coisa acontece junto.
 * Tudo roda fora da thread do servidor: o jogo nunca espera o Discord.
 */
public final class DiscordWebhook {
    private static final ConcurrentLinkedQueue<String> FILA = new ConcurrentLinkedQueue<>();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static ScheduledExecutorService executor;
    private static String pendente;
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("dd/MM HH:mm:ss");

    private DiscordWebhook() {}

    public static void start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "IndigoLog-Discord");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(DiscordWebhook::flush, 3, 3, TimeUnit.SECONDS);
    }

    public static String agora() {
        ZoneId zona;
        try { zona = ZoneId.of(LogConfig.get().fusoHorario); } catch (Exception e) { zona = ZoneId.systemDefault(); }
        return ZonedDateTime.now(zona).format(HORA);
    }

    /** Coloca uma linha na fila (também vai para o log do servidor). */
    public static void enviar(String linha) {
        IndigoLogMod.LOGGER.info("[log] {}", linha.replace("**", ""));
        FILA.add(linha);
    }

    public static void flushNow() {
        flush();
    }

    private static synchronized void flush() {
        String url = LogConfig.get().webhookUrl;
        if (url == null || url.isBlank()) { FILA.clear(); return; }
        if (pendente != null) {                                        // mensagem que falhou no ciclo anterior
            if (!post(url, pendente)) return;
            pendente = null;
        }
        while (!FILA.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            while (!FILA.isEmpty() && sb.length() + FILA.peek().length() + 1 < 1900) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(FILA.poll());
            }
            if (sb.length() == 0) sb.append(FILA.poll(), 0, 1900);   // linha gigante: corta
            if (!post(url, sb.toString())) { pendente = sb.toString(); return; }   // tenta de novo no próximo ciclo
        }
    }

    private static boolean post(String url, String conteudo) {
        JsonObject body = new JsonObject();
        body.addProperty("content", conteudo);
        body.addProperty("username", "IndigoVerse Logs");
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new com.google.gson.JsonArray());     // nunca marca @everyone/@here por acidente
        body.add("allowed_mentions", mentions);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 429) {                            // limite do Discord: espera e reenvia
                Thread.sleep(2000);
                return post(url, conteudo);
            }
            if (resp.statusCode() >= 300) {
                IndigoLogMod.LOGGER.warn("Discord respondeu {}: {}", resp.statusCode(), resp.body());
            }
            return true;
        } catch (Exception e) {
            IndigoLogMod.LOGGER.warn("Não consegui enviar log ao Discord: {}", e.toString());
            return false;
        }
    }
}
