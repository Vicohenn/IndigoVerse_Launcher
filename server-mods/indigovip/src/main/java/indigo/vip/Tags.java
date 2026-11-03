package indigo.vip;

import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;

/**
 * As tags usam times do placar (scoreboard): o prefixo/sufixo aparece no chat, na lista do TAB
 * e em cima da cabeça, sem precisar de nada no cliente.
 *  - Apoiador: times "ivip<n>_<id>", prefixo antes do nick ([Treinador] Nick).
 *  - Staff: times "istf<n>_<cargo>[_<vip>]", sufixo depois do nick (Nick (Dono)), mantendo o prefixo
 *    de apoiador se a pessoa também for apoiadora. "istf" vem antes de "ivip", então a staff fica no topo do TAB.
 */
public final class Tags {
    private static final String PREFIXO_TIME = "ivip";
    private static final String PREFIXO_STAFF = "istf";

    private Tags() {}

    public static String cor(String s) { return s == null ? "" : s.replace('&', '§'); }

    public static String nomeDoTime(VipConfig.Nivel n) {
        return PREFIXO_TIME + Math.max(0, 9 - n.ordem) + "_" + n.id;
    }

    private static String nomeDoTimeStaff(VipConfig.Cargo c, VipConfig.Nivel n) {
        return PREFIXO_STAFF + Math.min(9, Math.max(0, c.ordem)) + "_" + c.id + (n == null ? "" : "_" + n.id);
    }

    private static boolean ehTimeNosso(Team t) {
        return t != null && (t.getName().startsWith(PREFIXO_TIME) || t.getName().startsWith(PREFIXO_STAFF));
    }

    private static Formatting formato(String nome) {
        Formatting f = nome == null ? null : Formatting.byName(nome);
        return f != null && f.isColor() ? f : Formatting.WHITE;
    }

    /** Cria ou atualiza os times de todos os níveis e apaga os times de staff (são recriados ao aplicar). */
    public static void criarTimes(MinecraftServer server) {
        Scoreboard sb = server.getScoreboard();
        for (VipConfig.Nivel n : VipConfig.get().niveis) {
            Team t = sb.getTeam(nomeDoTime(n));
            if (t == null) t = sb.addTeam(nomeDoTime(n));
            t.setDisplayName(Text.literal(n.nome));
            t.setPrefix(Text.literal(cor(n.prefixo)));
            t.setSuffix(Text.empty());
            t.setColor(formato(n.corDoNick));
        }
        for (Team t : new ArrayList<>(sb.getTeams())) {
            if (t.getName().startsWith(PREFIXO_STAFF)) sb.removeTeam(t);
        }
        for (String nick : VipConfig.get().staff.keySet()) aplicar(server, nick);
    }

    private static Team timeStaff(Scoreboard sb, VipConfig.Cargo c, VipConfig.Nivel n) {
        String nome = nomeDoTimeStaff(c, n);
        Team t = sb.getTeam(nome);
        if (t == null) t = sb.addTeam(nome);
        t.setDisplayName(Text.literal(c.nome));
        t.setPrefix(Text.literal(n == null ? "" : cor(n.prefixo)));
        t.setSuffix(Text.literal(cor(c.sufixo)));
        t.setColor(formato(c.corDoNick));
        return t;
    }

    /** Coloca o jogador no time certo: staff (com ou sem apoio), só apoio, ou nenhum. */
    public static void aplicar(MinecraftServer server, String nick) {
        Scoreboard sb = server.getScoreboard();
        Team atual = sb.getScoreHolderTeam(nick);
        Apoiadores.Registro r = Apoiadores.de(nick);
        VipConfig.Nivel n = Apoiadores.ativo(r) ? VipConfig.get().nivel(r.nivel) : null;
        VipConfig.Cargo c = VipConfig.get().cargoDe(nick);

        Team alvo;
        if (c != null) alvo = timeStaff(sb, c, n);
        else alvo = n == null ? null : sb.getTeam(nomeDoTime(n));

        if (atual != null && atual == alvo) return;
        // não tira o jogador de times que não são nossos (ex.: times de outro mod), a não ser para colocar no nosso
        if (atual != null && (ehTimeNosso(atual) || alvo != null)) sb.removeScoreHolderFromTeam(nick, atual);
        if (alvo != null) sb.addScoreHolderToTeam(nick, alvo);
    }
}
