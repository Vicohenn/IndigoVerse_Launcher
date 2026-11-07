package indigo.vip;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** config/indigovip/config.json */
public final class VipConfig {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static VipConfig instance;

    public static final class Nivel {
        public String id;
        public String nome;
        /** Ordem de importância: 1 = menor. */
        public int ordem;
        /** Texto antes do nick (aceita códigos de cor com &). */
        public String prefixo;
        /** Cor do nick: white, gray, green, gold, light_purple, blue, aqua, yellow, red... */
        public String corDoNick;
        /** Comandos rodados pelo console quando o nível é ativado. {nick} = nick do jogador. */
        public List<String> comandosAoAtivar = new ArrayList<>();
        /** Comandos rodados quando o nível expira ou é removido. */
        public List<String> comandosAoExpirar = new ArrayList<>();
        /** Fila prioritária: quantas vagas além do limite do servidor este nível pode usar. */
        public Integer vagasExtras;

        Nivel() {}
        Nivel(String id, String nome, int ordem, String prefixo, String cor) {
            this.id = id; this.nome = nome; this.ordem = ordem; this.prefixo = prefixo; this.corDoNick = cor;
        }
    }

    /** Cargo de staff: aparece depois do nick, ex.: CrazyTommyBR (Dono). */
    public static final class Cargo {
        public String id;
        public String nome;
        /** 1 = mais alto (fica no topo do TAB). */
        public int ordem;
        /** Texto depois do nick (aceita & para cores). */
        public String sufixo;
        public String corDoNick;

        Cargo() {}
        Cargo(String id, String nome, int ordem, String sufixo, String cor) {
            this.id = id; this.nome = nome; this.ordem = ordem; this.sufixo = sufixo; this.corDoNick = cor;
        }
    }

    public static final class Produto {
        public String nome;
        /** true = só entrega com o jogador online (ex.: dar item). */
        public boolean precisaOnline = true;
        public List<String> comandos = new ArrayList<>();
        public String mensagemAoJogador = "";

        Produto() {}
        Produto(String nome, boolean online, List<String> cmds, String msg) {
            this.nome = nome; this.precisaOnline = online; this.comandos = cmds; this.mensagemAoJogador = msg;
        }
    }

    /** Pasta da fila, relativa à pasta do servidor. O bot do Discord grava os pedidos em <pasta>/pendentes. */
    public String pastaFila = "doacoes";
    /** Fila pelo Discord (quando o bot roda fora deste PC): token do bot e ID do canal privado da fila. */
    public String discordToken = "";
    public String discordCanalFila = "";
    public boolean anunciarNoChat = true;
    public String mensagemAnuncio = "&6✦ &f{nick} &7agora é apoiador {prefixo}&7! Obrigado por ajudar o IndigoVerse!";
    public List<Nivel> niveis = new ArrayList<>();
    public Map<String, Produto> produtos = new LinkedHashMap<>();
    /** Nível mínimo para ter mensagem de entrada personalizada (/entrada). */
    public String nivelMensagemEntrada = "indigo_legend";
    public String entradaPadrao = "&7chegou ao IndigoVerse!";
    /** {prefixo} {nick} {mensagem} */
    public String formatoEntrada = "&9✦ {prefixo}&f{nick} &f{mensagem}";
    /** Nível mínimo para usar /anunciar. */
    public String nivelAnunciar = "indigo_legend";
    public int anunciarIntervaloMinutos = 30;
    public String formatoAnuncio = "&8[&9Anúncio&8] {prefixo}&f{nick}&7: &f{mensagem}";
    public int tamanhoMaximoTexto = 120;
    /** Cargos de staff disponíveis. */
    public List<Cargo> cargosStaff = new ArrayList<>();
    /** Nick -> id do cargo de staff. Use /cargo definir <nick> <cargo>. */
    public Map<String, String> staff = new LinkedHashMap<>();

    public static VipConfig get() { return instance; }

    public static Path pasta() {
        return FabricLoader.getInstance().getConfigDir().resolve("indigovip");
    }

    public static void load() {
        Path f = pasta().resolve("config.json");
        try {
            Files.createDirectories(pasta());
            if (Files.exists(f)) {
                try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    instance = GSON.fromJson(r, VipConfig.class);
                }
            }
        } catch (Exception e) {
            IndigoVipMod.LOGGER.error("Erro lendo {}: {} (usando padrão)", f, e.toString());
            instance = null;
        }
        if (instance == null || instance.niveis == null || instance.niveis.isEmpty()) {
            VipConfig padrao = padrao();
            if (instance != null && instance.produtos != null && !instance.produtos.isEmpty()) padrao.produtos = instance.produtos;
            instance = padrao;
            save();
        }
        if (instance.produtos == null) instance.produtos = new LinkedHashMap<>();
        if (instance.discordToken == null) instance.discordToken = "";
        if (instance.discordCanalFila == null) instance.discordCanalFila = "";
        if (instance.cargosStaff == null || instance.cargosStaff.isEmpty()) instance.cargosStaff = cargosPadrao();
        if (instance.staff == null) instance.staff = new LinkedHashMap<>();
        for (Nivel n : instance.niveis) if (n.vagasExtras == null) n.vagasExtras = vagasPadrao(n.id);
        save();   // grava campos novos no arquivo
    }

    public static void save() {
        try {
            Files.createDirectories(pasta());
            try (Writer w = Files.newBufferedWriter(pasta().resolve("config.json"), StandardCharsets.UTF_8)) {
                GSON.toJson(instance, w);
            }
        } catch (Exception e) {
            IndigoVipMod.LOGGER.error("Erro salvando config: {}", e.toString());
        }
    }

    public Nivel nivel(String id) {
        if (id == null) return null;
        for (Nivel n : niveis) if (n.id.equalsIgnoreCase(id)) return n;
        return null;
    }

    public Cargo cargo(String id) {
        if (id == null) return null;
        for (Cargo c : cargosStaff) if (c.id.equalsIgnoreCase(id)) return c;
        return null;
    }

    /** Cargo de staff do jogador (ignora maiúsculas no nick). */
    public Cargo cargoDe(String nick) {
        for (Map.Entry<String, String> e : staff.entrySet()) if (e.getKey().equalsIgnoreCase(nick)) return cargo(e.getValue());
        return null;
    }

    /** Fila prioritária: quanto maior o nível, mais vagas extras (Indigo Legend praticamente sempre entra). */
    private static int vagasPadrao(String id) {
        return switch (id == null ? "" : id) {
            case "treinador" -> 2;
            case "gym_leader" -> 4;
            case "elite_four" -> 6;
            case "indigo_legend" -> 50;
            default -> 0;
        };
    }

    private static List<Cargo> cargosPadrao() {
        List<Cargo> l = new ArrayList<>();
        l.add(new Cargo("dono", "Dono", 1, " &8(&eDono&8)", "yellow"));
        l.add(new Cargo("adm", "Adm", 2, " &8(&fAdm&8)", "white"));
        l.add(new Cargo("mod", "Mod", 3, " &8(&cMod&8)", "red"));
        l.add(new Cargo("aux", "Aux", 4, " &8(&9Aux&8)", "blue"));
        return l;
    }

    private static VipConfig padrao() {
        VipConfig c = new VipConfig();
        c.niveis.add(new Nivel("basico", "Básico", 1, "&8[&7Básico&8] ", "gray"));
        c.niveis.add(new Nivel("treinador", "Treinador", 2, "&8[&aTreinador&8] ", "green"));
        c.niveis.add(new Nivel("gym_leader", "Gym Leader", 3, "&8[&6Gym Leader&8] ", "gold"));
        c.niveis.add(new Nivel("elite_four", "Elite Four", 4, "&8[&dElite Four&8] ", "light_purple"));
        c.niveis.add(new Nivel("indigo_legend", "Indigo Legend", 5, "&8[&9&lIndigo Legend&8] ", "blue"));
        c.produtos.put("trainer_pass", new Produto("Trainer Pass Premium", true,
                new ArrayList<>(List.of("give {nick} minecraft:debug_stick[minecraft:custom_name='{\"text\":\"Ingresso Trainer Pass Premium\",\"color\":\"gold\",\"italic\":false}',minecraft:lore=['{\"text\":\"Abra o Trainer Pass (/bp) e clique em desbloquear Premium.\",\"color\":\"gray\"}']] 1")),
                "&6Seu ingresso do Trainer Pass Premium chegou! Abra o passe com &e/bp&6 e clique em desbloquear."));
        c.produtos.put("outro", new Produto("Doação livre", false, new ArrayList<>(),
                "&dObrigado pela doação! Ela ajuda a manter o IndigoVerse no ar."));
        return c;
    }
}
