package indigo.ranked;

import indigo.ranked.Dados.Jogador;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Ranks, divisões, Elo, promoção e queda. */
final class Pontos {
    private Pontos() {}

    static List<Config.Rank> ranks() {
        List<Config.Rank> r = new ArrayList<>(Dados.config.ranks);
        r.sort(Comparator.comparingInt(x -> x.min));
        return r;
    }

    static int indiceRank(int pl) {
        List<Config.Rank> r = ranks();
        int idx = 0;
        for (int i = 0; i < r.size(); i++) if (pl >= r.get(i).min) idx = i;
        return idx;
    }

    static Config.Rank rank(int pl) { return ranks().get(indiceRank(pl)); }

    static boolean ultimoRank(int pl) { return indiceRank(pl) == ranks().size() - 1; }

    /** Pontos em que começa o próximo rank (ou -1 se já está no último). */
    static int proximoMin(int pl) {
        List<Config.Rank> r = ranks();
        int i = indiceRank(pl);
        return i + 1 < r.size() ? r.get(i + 1).min : -1;
    }

    /** "III", "II" ou "I" (o rank dividido em 3 partes iguais); vazio no último rank. */
    static String divisao(int pl) {
        int prox = proximoMin(pl);
        if (prox < 0) return "";
        Config.Rank r = rank(pl);
        int tam = Math.max(3, prox - r.min);
        int parte = Math.min(2, (pl - r.min) * 3 / tam);
        return new String[]{"III", "II", "I"}[parte];
    }

    static UUID campeao() {
        UUID melhor = null;
        int maior = Integer.MIN_VALUE;
        int topo = ranks().get(ranks().size() - 1).min;
        for (Map.Entry<String, Jogador> e : Dados.jogadores().entrySet()) {
            Jogador j = e.getValue();
            if (j.banido || j.pl < topo || j.partidasTemporada == 0) continue;
            if (j.pl > maior) { maior = j.pl; melhor = UUID.fromString(e.getKey()); }
        }
        return melhor;
    }

    static String nomeRank(UUID u, Jogador j) {
        if (u.equals(campeao())) return cor(Dados.config.corCampeao) + Dados.config.nomeCampeao;
        Config.Rank r = rank(j.pl);
        String d = divisao(j.pl);
        return cor(r.cor) + r.nome + (d.isEmpty() ? "" : " " + d);
    }

    static String cor(String s) { return s == null ? "" : s.replace('&', '§'); }

    // ---------- Elo ----------

    static double esperado(int meu, int dele) {
        return 1.0 / (1.0 + Math.pow(10, (dele - meu) / Dados.config.fatorElo));
    }

    static int ganho(int meu, int dele) {
        Config c = Dados.config;
        int g = (int) Math.round(2.0 * c.vitoriaBase * (1 - esperado(meu, dele)));
        return Math.max(c.vitoriaMin, Math.min(c.vitoriaMax, g));
    }

    static int perda(int meu, int dele) {
        Config c = Dados.config;
        int p = (int) Math.round(2.0 * c.derrotaBase * esperado(meu, dele));
        return Math.max(c.derrotaMin, Math.min(c.derrotaMax, p));
    }

    /** Peso pelo número de partidas de hoje contra esse adversário (antes desta). */
    static double peso(Jogador j, UUID oponente) {
        double[] p = Dados.config.pesoMesmoOponente;
        int n = j.contraHoje.getOrDefault(Dados.hoje() + "|" + oponente, 0);
        if (p == null || p.length == 0) return 1;
        return p[Math.min(n, p.length - 1)];
    }

    static void contarConfronto(Jogador j, UUID oponente) {
        String hoje = Dados.hoje();
        j.contraHoje.keySet().removeIf(k -> !k.startsWith(hoje + "|"));
        j.contraHoje.merge(hoje + "|" + oponente, 1, Integer::sum);
        long agora = System.currentTimeMillis();
        j.partidasRecentes.removeIf(t -> agora - t > 7L * 24 * 3600 * 1000);
        j.partidasRecentes.add(agora);
        j.partidas++;
        j.partidasTemporada++;
    }

    /**
     * Aplica uma partida MD1 (ou o resultado de uma série para quem não estava em promoção).
     * Devolve a variação de pontos aplicada.
     */
    static int aplicar(Jogador j, UUID oponente, int plOponente, boolean venceu, boolean valePontos) {
        double fator = valePontos ? peso(j, oponente) : 0;
        if (j.posicionamentoRestante > 0) fator *= Dados.config.multiplicadorPosicionamento;
        int antes = j.pl;
        int delta = (int) Math.round((venceu ? ganho(j.pl, plOponente) : -perda(j.pl, plOponente)) * fator);
        if (venceu) j.vitorias++; else j.derrotas++;
        if (valePontos && j.posicionamentoRestante > 0) j.posicionamentoRestante--;
        contarConfronto(j, oponente);

        int novo = Math.max(0, j.pl + delta);
        Config.Rank r = rank(j.pl);
        int prox = proximoMin(j.pl);
        if (delta > 0 && prox > 0 && novo >= prox - 1) {
            // chegou no teto: a próxima partida é a série de promoção
            novo = prox - 1;
            j.emPromocao = true;
        }
        if (delta < 0 && novo < r.min) {
            if (j.protecao > 0 || antes > r.min) novo = r.min;   // segura no mínimo do rank uma vez (ou enquanto protegido)
        }
        if (j.protecao > 0) j.protecao--;
        j.pl = novo;
        Dados.marcar();
        return j.pl - antes;
    }

    /** Resultado da série de promoção para quem estava em promoção. */
    static void resultadoPromocao(Jogador j, boolean subiu, String oponente) {
        int prox = proximoMin(j.pl);
        j.emPromocao = false;
        j.ultimoOponenteSerie = oponente;
        if (subiu && prox > 0) {
            j.pl = prox;
            j.protecao = Dados.config.partidasProtecao;
            j.seriesFalhas = 0;
        } else {
            j.pl = Math.max(0, j.pl - Dados.config.perdaAoFalharPromocao);
            j.seriesFalhas++;
        }
        Dados.marcar();
    }
}
