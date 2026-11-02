package indigo.vip;

import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * As tags usam times do placar (scoreboard): o prefixo aparece no chat, na lista do TAB
 * e em cima da cabeça, sem precisar de nada no cliente.
 * Os times se chamam "ivip<n>_<id>"; quanto maior o nível, mais acima na lista do TAB.
 */
public final class Tags {
    private static final String PREFIXO_TIME = "ivip";

    private Tags() {}

    public static String cor(String s) { return s == null ? "" : s.replace('&', '§'); }

    public static String nomeDoTime(VipConfig.Nivel n) {
        return PREFIXO_TIME + Math.max(0, 9 - n.ordem) + "_" + n.id;
    }

    /** Cria ou atualiza os times de todos os níveis. */
    public static void criarTimes(MinecraftServer server) {
        Scoreboard sb = server.getScoreboard();
        for (VipConfig.Nivel n : VipConfig.get().niveis) {
            Team t = sb.getTeam(nomeDoTime(n));
            if (t == null) t = sb.addTeam(nomeDoTime(n));
            t.setDisplayName(Text.literal(n.nome));
            t.setPrefix(Text.literal(cor(n.prefixo)));
            Formatting f = n.corDoNick == null ? null : Formatting.byName(n.corDoNick);
            t.setColor(f != null && f.isColor() ? f : Formatting.WHITE);
        }
    }

    /** Coloca o jogador no time do nível dele (ou tira de qualquer time de apoiador). */
    public static void aplicar(MinecraftServer server, String nick) {
        Scoreboard sb = server.getScoreboard();
        Team atual = sb.getScoreHolderTeam(nick);
        Apoiadores.Registro r = Apoiadores.de(nick);
        VipConfig.Nivel n = Apoiadores.ativo(r) ? VipConfig.get().nivel(r.nivel) : null;
        Team alvo = n == null ? null : sb.getTeam(nomeDoTime(n));

        if (atual != null && atual == alvo) return;
        if (atual != null && atual.getName().startsWith(PREFIXO_TIME)) sb.removeScoreHolderFromTeam(nick, atual);
        if (alvo != null) sb.addScoreHolderToTeam(nick, alvo);
    }
}
