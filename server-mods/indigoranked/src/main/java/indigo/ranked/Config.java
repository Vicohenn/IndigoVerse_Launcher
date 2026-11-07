package indigo.ranked;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** config/indigoranked/config.json */
public final class Config {

    // ---------- arena ----------
    public static final class Ponto {
        public double x, y, z;
        public float yaw;
        public Ponto() {}
        Ponto(double x, double y, double z, float yaw) { this.x = x; this.y = y; this.z = z; this.yaw = yaw; }
    }
    /** Dimensão e cantos da Arena Central (use /ranqueada admin arena canto1|canto2). */
    public String arenaDimensao = "minecraft:overworld";
    public int[] arenaCanto1 = null;
    public int[] arenaCanto2 = null;
    /** Onde cada jogador fica no começo da partida (opcional: /ranqueada admin arena ladoA|ladoB). */
    public Ponto ladoA = null;
    public Ponto ladoB = null;

    // ---------- entrada ----------
    public int horasMinimas = 10;
    /** Conquista (advancement) exigida para entrar, ex. a da Liga de Kanto. Vazio = não exige. */
    public String conquistaObrigatoria = "";
    /** Tag de placar (/tag) exigida para entrar. Vazio = não exige. */
    public String tagObrigatoria = "";

    // ---------- batalha ----------
    public int nivel = 50;
    public int segundosContagem = 5;
    public int segundosEntreJogosMd3 = 10;

    // ---------- fila ----------
    public int margemInicial = 100;
    public int margemCrescimento = 50;
    public int segundosParaCrescer = 30;
    public int margemMaxima = 250;
    /** Diferença máxima de ranks entre os dois jogadores. */
    public int distanciaMaximaRanks = 1;
    public boolean desafioDireto = true;
    public boolean bloquearMesmoIp = true;

    // ---------- pontos ----------
    public int plInicial = 150;
    public int vitoriaBase = 20, derrotaBase = 16;
    public int vitoriaMin = 8, vitoriaMax = 32, derrotaMin = 8, derrotaMax = 28;
    public double fatorElo = 400;
    public int partidasPosicionamento = 5;
    public double multiplicadorPosicionamento = 2.0;
    /** Peso dos pontos contra o mesmo adversário no mesmo dia: 1ª, 2ª, 3ª+ partida. */
    public double[] pesoMesmoOponente = {1.0, 0.5, 0.0};

    // ---------- promoção / queda ----------
    public int perdaAoFalharPromocao = 30;
    public int partidasProtecao = 5;
    public int seriesFalhasParaAjuda = 3;

    // ---------- inatividade (só o último rank) ----------
    public int partidasMinimasSemana = 5;
    public int perdaInatividade = 15;

    // ---------- anti-abuso ----------
    public int turnosMinimos = 3;
    public int segundosMinimos = 60;
    public int confrontosSemanaAlerta = 6;
    /** Webhook do Discord para alertas e resultados (cole o seu; vazio = só no console). */
    public String webhookDiscord = "";
    public boolean resultadosNoDiscord = true;

    // ---------- ranks ----------
    public static final class Rank {
        public String id, nome, cor;
        public int min;
        public Rank() {}
        Rank(String id, String nome, int min, String cor) { this.id = id; this.nome = nome; this.min = min; this.cor = cor; }
    }
    public List<Rank> ranks = new ArrayList<>(List.of(
            new Rank("pokeball", "Poké Ball", 0, "&c"),
            new Rank("greatball", "Great Ball", 300, "&9"),
            new Rank("ultraball", "Ultra Ball", 600, "&e"),
            new Rank("masterball", "Master Ball", 900, "&5"),
            new Rank("elitefour", "Elite Four", 1200, "&6")));
    public String nomeCampeao = "Campeão Indigo";
    public String corCampeao = "&b";

    // ---------- recompensas de fim de temporada ----------
    public static final class Recompensa {
        public long dinheiro;
        public List<String> comandos = new ArrayList<>();
        public Recompensa() {}
        Recompensa(long d, String... cmds) { dinheiro = d; comandos = new ArrayList<>(List.of(cmds)); }
    }
    /** Por id de rank, e "campeao". {nick} = jogador. Entregues quando o jogador estiver online. */
    public Map<String, Recompensa> recompensas = new LinkedHashMap<>(Map.of(
            "pokeball", new Recompensa(50_000),
            "greatball", new Recompensa(150_000),
            "ultraball", new Recompensa(300_000),
            "masterball", new Recompensa(600_000),
            "elitefour", new Recompensa(1_200_000, "indigocoins admin dar {nick} 1"),
            "campeao", new Recompensa(3_000_000, "indigocoins admin dar {nick} 3")));
}
