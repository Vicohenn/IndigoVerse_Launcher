package indigo.vip;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Fila de entregas gravada pelo bot do Discord.
 *
 *   doacoes/pendentes/<id>.json   pedidos pagos esperando entrega
 *   doacoes/entregues/<id>.json   já entregues (também evita entregar duas vezes)
 *   doacoes/erros/<id>.json       pedidos inválidos, para a staff olhar
 *
 * Formato: {"id":"mp-123","nick":"Ash","tipo":"vip","nivel":"gym_leader","dias":30}
 *          {"id":"mp-124","nick":"Ash","tipo":"trainer_pass"}
 *          {"id":"mp-125-estorno","nick":"Ash","tipo":"revogar"}
 */
public final class Fila {
    private static final Pattern NICK = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    private Fila() {}

    private static Path base() {
        return FabricLoader.getInstance().getGameDir().resolve(VipConfig.get().pastaFila);
    }

    public static void criarPastas() {
        try {
            Files.createDirectories(base().resolve("pendentes"));
            Files.createDirectories(base().resolve("entregues"));
            Files.createDirectories(base().resolve("erros"));
        } catch (Exception e) {
            IndigoVipMod.LOGGER.error("Não consegui criar a pasta da fila: {}", e.toString());
        }
    }

    public static void processar(MinecraftServer server) {
        Path pend = base().resolve("pendentes");
        if (!Files.isDirectory(pend)) return;
        List<Path> arquivos = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(pend, "*.json")) {
            for (Path p : ds) arquivos.add(p);
        } catch (Exception e) {
            return;
        }
        arquivos.sort(null);
        for (Path arq : arquivos) {
            try {
                processarArquivo(server, arq);
            } catch (Exception e) {
                IndigoVipMod.LOGGER.error("Erro no pedido {}: {}", arq.getFileName(), e.toString());
                mover(arq, "erros", e.toString());
            }
        }
    }

    /** Resultado de um pedido: ENTREGUE, ERRO ou ESPERA (jogador offline). */
    public enum Estado { ENTREGUE, ERRO, ESPERA }
    public record Resultado(Estado estado, String texto) {}

    private static void processarArquivo(MinecraftServer server, Path arq) throws Exception {
        JsonObject o;
        try (Reader r = Files.newBufferedReader(arq, StandardCharsets.UTF_8)) {
            o = JsonParser.parseReader(r).getAsJsonObject();
        }
        if (!o.has("id")) o.addProperty("id", arq.getFileName().toString().replace(".json", ""));
        Resultado res = processarPedido(server, o);
        switch (res.estado()) {
            case ESPERA -> { }
            case ERRO -> mover(arq, "erros", res.texto());
            case ENTREGUE -> Files.deleteIfExists(arq);
        }
    }

    /** Processa um pedido (vindo da pasta ou do canal do Discord). Deve rodar na thread do servidor. */
    public static Resultado processarPedido(MinecraftServer server, JsonObject o) {
        try {
            String id = texto(o, "id", "");
            String nick = texto(o, "nick", "");
            String tipo = texto(o, "tipo", "").toLowerCase();

            if (!id.matches("[A-Za-z0-9_.-]{1,80}") || !NICK.matcher(nick).matches()) {
                return new Resultado(Estado.ERRO, "id ou nick inválido (" + nick + ")");
            }
            Path entregue = base().resolve("entregues").resolve(id + ".json");
            if (Files.exists(entregue)) {
                JsonObject antes = JsonParser.parseString(Files.readString(entregue, StandardCharsets.UTF_8)).getAsJsonObject();
                return new Resultado(Estado.ENTREGUE, texto(antes, "resultado", "já entregue"));
            }

            String resultado;
            switch (tipo) {
                case "vip" -> {
                    String nivel = texto(o, "nivel", "");
                    if (VipConfig.get().nivel(nivel) == null) return new Resultado(Estado.ERRO, "nível inexistente: " + nivel);
                    int dias = o.has("dias") ? o.get("dias").getAsInt() : 30;
                    resultado = Entrega.aplicarVip(server, nick, nivel, dias);
                }
                case "revogar" -> {
                    boolean tinha = Entrega.removerVip(server, nick, "pagamento estornado");
                    resultado = tinha ? "apoio removido" : "não tinha apoio ativo";
                }
                default -> {
                    VipConfig.Produto prod = VipConfig.get().produtos.get(tipo);
                    if (prod == null) return new Resultado(Estado.ERRO, "produto desconhecido: " + tipo);
                    ServerPlayerEntity p = server.getPlayerManager().getPlayer(nick);
                    if (prod.precisaOnline && p == null) return new Resultado(Estado.ESPERA, "esperando " + nick + " entrar");
                    Entrega.rodar(server, prod.comandos, nick);
                    if (p != null && prod.mensagemAoJogador != null && !prod.mensagemAoJogador.isBlank()) {
                        p.sendMessage(Text.literal(Tags.cor(prod.mensagemAoJogador)));
                    }
                    resultado = prod.nome + " entregue";
                }
            }
            IndigoVipMod.LOGGER.info("Pedido {} entregue para {}: {}", id, nick, resultado);
            o.addProperty("resultado", resultado);
            o.addProperty("entregueEm", System.currentTimeMillis());
            Files.createDirectories(entregue.getParent());
            Files.writeString(entregue, VipConfig.GSON.toJson(o), StandardCharsets.UTF_8);
            return new Resultado(Estado.ENTREGUE, resultado);
        } catch (Exception e) {
            IndigoVipMod.LOGGER.error("Erro no pedido {}: {}", o, e.toString());
            return new Resultado(Estado.ERRO, e.toString());
        }
    }

    static String texto(JsonObject o, String k, String padrao) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString().trim() : padrao;
    }

    private static void mover(Path arq, String pasta, String motivo) {
        try {
            Path destino = base().resolve(pasta).resolve(arq.getFileName());
            Files.move(arq, destino, StandardCopyOption.REPLACE_EXISTING);
            if (motivo != null) Files.writeString(destino.resolveSibling(arq.getFileName() + ".motivo.txt"), motivo, StandardCharsets.UTF_8);
        } catch (Exception e) {
            IndigoVipMod.LOGGER.error("Não consegui mover {}: {}", arq.getFileName(), e.toString());
        }
    }
}
