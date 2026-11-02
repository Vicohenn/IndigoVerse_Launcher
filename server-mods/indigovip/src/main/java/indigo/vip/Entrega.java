package indigo.vip;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/** Aplica níveis, roda comandos e cuida das expirações. */
public final class Entrega {
    public static final long DIA = 24L * 60 * 60 * 1000;

    private Entrega() {}

    /**
     * Ativa ou estende um nível.
     * - Mesmo nível ativo: soma os dias.
     * - Nível maior: troca para o novo nível com os dias comprados.
     * - Nível menor que o atual: mantém o atual e soma os dias nele.
     * dias = 0 deixa permanente.
     */
    public static String aplicarVip(MinecraftServer server, String nick, String nivelId, int dias) {
        VipConfig cfg = VipConfig.get();
        VipConfig.Nivel novo = cfg.nivel(nivelId);
        if (novo == null) throw new IllegalArgumentException("nível inexistente: " + nivelId);
        long agora = System.currentTimeMillis();
        long duracao = dias <= 0 ? 0 : dias * DIA;

        Apoiadores.Registro r = Apoiadores.de(nick);
        VipConfig.Nivel atual = Apoiadores.ativo(r) ? cfg.nivel(r.nivel) : null;

        VipConfig.Nivel fica;
        long expira;
        if (atual == null) {
            fica = novo;
            expira = duracao == 0 ? 0 : agora + duracao;
        } else if (atual.ordem == novo.ordem || novo.ordem < atual.ordem) {
            fica = atual;
            expira = (r.expiraEm == 0 || duracao == 0) ? 0 : r.expiraEm + duracao;
        } else {
            fica = novo;
            expira = duracao == 0 ? 0 : agora + duracao;
        }

        boolean trocou = atual == null || atual != fica;
        if (trocou && atual != null) rodar(server, atual.comandosAoExpirar, nick);
        Apoiadores.definir(nick, fica.id, expira);
        Tags.aplicar(server, nick);
        if (trocou) rodar(server, fica.comandosAoAtivar, nick);

        if (trocou && cfg.anunciarNoChat) {
            server.getPlayerManager().broadcast(Text.literal(Tags.cor(cfg.mensagemAnuncio
                    .replace("{nick}", nick).replace("{prefixo}", fica.prefixo.trim()).replace("{nivel}", fica.nome))), false);
        }
        ServerPlayerEntity p = server.getPlayerManager().getPlayer(nick);
        if (p != null) p.sendMessage(Text.literal("§aSeu apoio §f" + fica.nome + "§a está ativo " + validade(expira) + "."));
        return fica.nome + " " + validade(expira);
    }

    public static boolean removerVip(MinecraftServer server, String nick, String motivo) {
        Apoiadores.Registro r = Apoiadores.remover(nick);
        if (r == null) return false;
        VipConfig.Nivel n = VipConfig.get().nivel(r.nivel);
        Tags.aplicar(server, nick);
        if (n != null) rodar(server, n.comandosAoExpirar, nick);
        ServerPlayerEntity p = server.getPlayerManager().getPlayer(nick);
        if (p != null) p.sendMessage(Text.literal("§eSeu apoio " + (n == null ? r.nivel : n.nome) + " terminou" + (motivo == null ? "." : " (" + motivo + ").")));
        IndigoVipMod.LOGGER.info("Apoio de {} removido ({})", nick, motivo);
        return true;
    }

    /** Remove quem já passou da data. Chamado a cada minuto. */
    public static void verificarExpirados(MinecraftServer server) {
        List<String> vencidos = Apoiadores.todos().values().stream()
                .filter(r -> r.expiraEm != 0 && r.expiraEm <= System.currentTimeMillis())
                .map(r -> r.nick).toList();
        for (String nick : vencidos) removerVip(server, nick, "expirou");
    }

    public static void rodar(MinecraftServer server, List<String> comandos, String nick) {
        if (comandos == null) return;
        for (String c : comandos) {
            if (c == null || c.isBlank()) continue;
            String cmd = c.replace("{nick}", nick);
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            try {
                server.getCommandManager().executeWithPrefix(server.getCommandSource().withSilent(), cmd);
            } catch (Exception e) {
                IndigoVipMod.LOGGER.error("Falha no comando '{}': {}", cmd, e.toString());
            }
        }
    }

    public static String validade(long expiraEm) {
        if (expiraEm == 0) return "para sempre";
        return "até " + new SimpleDateFormat("dd/MM/yyyy HH:mm").format(new Date(expiraEm));
    }
}
