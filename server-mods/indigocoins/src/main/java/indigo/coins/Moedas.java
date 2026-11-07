package indigo.coins;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Indigo Coins: cada uma tem um ID único de 12 dígitos e um dono (UUID). Não existe item físico. */
final class Moedas {
    static final SecureRandom RNG = new SecureRandom();

    private Moedas() {}

    static String numero12() {
        return String.format("%012d", (long) (RNG.nextDouble() * 1_000_000_000_000L) % 1_000_000_000_000L);
    }

    /** ID novo que nunca foi usado (nem por moeda gasta). */
    static String novoId() {
        String id;
        do { id = numero12(); } while (Dados.moedas.containsKey(id));
        return id;
    }

    /** Cria uma moeda para o jogador. idPreferido (ex.: número premiado) é usado se ainda estiver livre. */
    static String criar(UUID dono, String nick, String origem, String idPreferido) {
        String id = idPreferido != null && !Dados.moedas.containsKey(idPreferido) ? idPreferido : novoId();
        Dados.Moeda m = new Dados.Moeda();
        m.dono = dono.toString();
        m.nick = nick;
        m.origem = origem;
        m.criadaEm = System.currentTimeMillis();
        Dados.moedas.put(id, m);
        Dados.salvarMoedas();
        Dados.log("CRIADA " + id + " dono=" + nick + " (" + dono + ") origem=" + origem);
        return id;
    }

    /** IDs das moedas ativas do jogador, mais antigas primeiro. */
    static List<String> doJogador(UUID dono) {
        String u = dono.toString();
        List<String> l = new ArrayList<>();
        for (Map.Entry<String, Dados.Moeda> e : Dados.moedas.entrySet())
            if (e.getValue().gastaEm == 0 && u.equals(e.getValue().dono)) l.add(e.getKey());
        return l;
    }

    /** Quantidade do prêmio: 1 com chanceUmaMoeda, senão 2..maxMoedas. */
    static int quantidadePremio() {
        Config c = Dados.config;
        if (c.maxMoedas <= 1 || RNG.nextDouble() < c.chanceUmaMoeda) return 1;
        return 2 + RNG.nextInt(c.maxMoedas - 1);
    }
}
