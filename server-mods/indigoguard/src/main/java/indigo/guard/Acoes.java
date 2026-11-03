package indigo.guard;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.BannedPlayerEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/** Executa no servidor a decisão que a staff tomou nos botões do Discord. */
public final class Acoes {
    private Acoes() {}

    public static String executar(MinecraftServer server, JsonObject a) {
        try {
            String acao = texto(a, "acao").toLowerCase(Locale.ROOT);
            UUID uuid = UUID.fromString(texto(a, "uuid"));
            String nick = texto(a, "nick");
            String staff = texto(a, "staff");
            String motivo = texto(a, "motivo");
            if (motivo.isBlank()) motivo = "IndigoGuard: decisão da staff";
            GameProfile perfil = new GameProfile(uuid, nick.isBlank() ? uuid.toString() : nick);
            ServerPlayerEntity online = server.getPlayerManager().getPlayer(uuid);

            switch (acao) {
                case "kick" -> {
                    if (online == null) return "⚠️ " + nick + " não está online; kick não aplicado.";
                    online.networkHandler.disconnect(Text.literal("Você foi expulso pela staff.\n" + motivo));
                    avisarStaff(server, "§e" + staff + " expulsou " + nick + " (IndigoGuard).");
                    return "✅ ACAO EXECUTADA: kick em " + nick + " (por " + staff + ")";
                }
                case "ban", "tempban" -> {
                    Date fim = null;
                    if (acao.equals("tempban")) {
                        long ms = duracaoMs(texto(a, "duracao"));
                        if (ms <= 0) return "⚠️ Duração inválida: \"" + texto(a, "duracao") + "\" (use 30m, 12h, 7d, 2w).";
                        fim = new Date(System.currentTimeMillis() + ms);
                    }
                    server.getPlayerManager().getUserBanList().add(
                            new BannedPlayerEntry(perfil, new Date(), "IndigoGuard (" + staff + ")", fim, motivo));
                    String ate = fim == null ? "permanente" : "até " + new SimpleDateFormat("dd/MM/yyyy HH:mm").format(fim);
                    if (online != null) {
                        online.networkHandler.disconnect(Text.literal("Você foi banido (" + ate + ").\n" + motivo));
                    }
                    avisarStaff(server, "§c" + staff + " baniu " + nick + " (" + ate + ") pelo IndigoGuard.");
                    return "✅ ACAO EXECUTADA: ban " + ate + " em " + nick + " (por " + staff + ")";
                }
                default -> {
                    return "⚠️ Ação desconhecida: " + acao;
                }
            }
        } catch (Exception e) {
            IndigoGuardMod.LOGGER.warn("IndigoGuard: erro executando ação {}: {}", a, e.toString());
            return "⚠️ Erro executando a ação: " + e.getMessage();
        }
    }

    /** "30m", "12h", "7d", "2w" (também aceita "1d12h"). */
    static long duracaoMs(String s) {
        if (s == null) return -1;
        s = s.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        long total = 0, num = 0;
        boolean temNumero = false;
        for (char c : s.toCharArray()) {
            if (Character.isDigit(c)) { num = num * 10 + (c - '0'); temNumero = true; continue; }
            long mult = switch (c) {
                case 's' -> 1000L;
                case 'm' -> 60_000L;
                case 'h' -> 3_600_000L;
                case 'd' -> 86_400_000L;
                case 'w' -> 7 * 86_400_000L;
                default -> -1;
            };
            if (mult < 0 || !temNumero) return -1;
            total += num * mult;
            num = 0;
            temNumero = false;
        }
        if (temNumero) total += num * 60_000L; // número sozinho = minutos
        return total;
    }

    static void avisarStaff(MinecraftServer server, String msg) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (Alertas.ehStaffOnline(p)) p.sendMessage(Text.literal("§8[§cIndigoGuard§8] " + msg), false);
        }
    }

    private static String texto(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }
}
