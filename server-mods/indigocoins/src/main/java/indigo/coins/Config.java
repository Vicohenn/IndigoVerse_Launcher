package indigo.coins;

import java.util.ArrayList;
import java.util.List;

/** config/indigocoins/config.json */
public final class Config {
    public static final class ItemLoja {
        public String id;
        public String nome;
        public int custo;
        /** Rodados pelo console. {nick} = jogador. */
        public List<String> comandos = new ArrayList<>();

        public ItemLoja() {}
        ItemLoja(String id, String nome, int custo, String... cmds) {
            this.id = id; this.nome = nome; this.custo = custo; this.comandos = new ArrayList<>(List.of(cmds));
        }
    }

    // ---------- raspadinha ----------
    /**
     * Nível de permissão de /raspadinha conferir <jogador>. Jogadores comuns (nível 0) não conseguem usar:
     * quem roda é o NPC (Easy NPC, ação "comando" com permissão alta) no botão "Conferir raspadinhas".
     */
    public int permissaoConferir = 2;
    /** Chance de um bilhete sair premiado: 1 em "umEm". */
    public int umEm = 500;
    /** A rodada (número premiado) troca a cada X horas ou quando alguém ganha. */
    public int horasPorRodada = 24;
    /** Preço de /raspadinha comprar em CobbleDollars (0 = desativado; aí só pela loja de NPC / VIP). */
    public long precoRaspadinha = 25000;
    /** Prêmio: 1 coin com esta chance; senão de 2 a 5 (iguais). */
    public double chanceUmaMoeda = 0.90;
    public int maxMoedas = 5;
    /** Anunciar no chat quando alguém ganha. */
    public boolean anunciarVencedor = true;

    // ---------- drop ----------
    /** Chance de cada jogador vencedor ganhar 1 coin ao derrotar um trainer do RCT (trainers, líderes, bosses). */
    public double chanceDropTrainer = 0.01;

    // ---------- venda ----------
    /** Segundos para o comprador aceitar uma oferta. */
    public int segundosOferta = 120;

    // ---------- loja ----------
    public List<ItemLoja> loja = new ArrayList<>(List.of(
            new ItemLoja("master_ball", "Master Ball", 2, "give {nick} cobblemon:master_ball 1"),
            new ItemLoja("vip_treinador", "VIP Treinador (30 dias)", 10, "vip dar {nick} treinador 30")));

    // ---------- Amulet Coin ----------
    /** Item usado como base (só os marcados pela loja funcionam). */
    public String amuletoItemBase = "cobblemon:amulet_coin";
    /** Preço por /amuleto npccomprar (botão do NPC) e /amuleto comprar. */
    public long precoAmuleto = 150000;
    public int amuletoMultiplicador = 2;
    /** Quantas horas dá para acumular usando vários amuletos. */
    public int amuletoMaxHorasAcumuladas = 5;
    /** Mostra o tempo restante numa barra no topo da tela. */
    public boolean amuletoContadorNaTela = true;

    // ---------- Lucky Egg ----------
    /** Liga o desgaste: o Lucky Egg segurado por um Pokémon quebra depois de luckyEggLimiteXp de XP. */
    public boolean luckyEggAtivo = true;
    public String luckyEggItem = "cobblemon:lucky_egg";
    public long luckyEggLimiteXp = 250000;
    /** Só XP de batalha gasta o ovo (doces de XP e comandos não gastam). */
    public boolean luckyEggSoBatalha = true;
    /** Preço por /luckyegg npccomprar (botão do NPC). */
    public long precoLuckyEgg = 100000;
    /** Mostra o progresso do ovo numa barra no topo da tela por alguns segundos depois de cada ganho de XP. */
    public boolean luckyEggBarraNaTela = true;
    public int luckyEggSegundosNaTela = 10;

    // ---------- relatório (testes) ----------
    /** Planilha (CSV, abre no Excel) com cada rodada. Caminho relativo à pasta do servidor. */
    public boolean relatorioAtivo = true;
    public String relatorioArquivo = "relatorio-raspadinha.csv";
}
