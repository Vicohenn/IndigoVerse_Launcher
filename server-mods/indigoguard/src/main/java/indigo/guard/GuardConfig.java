package indigo.guard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

/** config/indigoguard.json */
public final class GuardConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static GuardConfig instance = new GuardConfig();

    // ---------- Discord ----------
    /** Token do bot. Vazio = usa o mesmo token do config/indigovip/config.json. */
    public String discordToken = "";
    /** Canal onde os alertas aparecem (#indigoguard-logs). */
    public String canalLogs = "";
    /** Canal privado onde o bot coloca as decisões dos botões (#indigoguard-acoes). */
    public String canalAcoes = "";
    /** ID do cargo @staff que é marcado nos alertas. */
    public String cargoStaff = "";

    // ---------- staff ----------
    /** Nicks ou UUIDs da staff. Só eles podem dar ou ter OP sem gerar alerta. */
    public List<String> staff = new ArrayList<>();

    // ---------- movimento ----------
    public boolean movimentoAtivo = true;
    /** Segundos no ar sem cair (sem estar montado, nadando, de elytra...) para virar alerta de voo. */
    public double segundosNoArParaAlerta = 3.0;
    /** Velocidade média (blocos por segundo) em 2 s para alerta de velocidade. Correndo normal ≈ 5,6. */
    public double velocidadeMaxima = 20.0;

    // ---------- contas no mesmo IP ----------
    public boolean contasAtivo = true;

    // ---------- dinheiro e itens raros ----------
    public boolean economiaAtivo = true;
    /** Ganho máximo de CobbleDollars em 5 minutos antes de virar alerta. */
    public long maxGanhoDinheiro5Min = 2_000_000L;
    /** Item -> quantos podem entrar no inventário em 5 minutos antes de virar alerta. */
    public Map<String, Integer> itensRaros = itensPadrao();

    // ---------- mineração ----------
    public boolean mineracaoAtivo = true;
    /** Nome do grupo -> blocos (aceita * no id) */
    public Map<String, List<String>> grupoMinerios = mineriosPadrao();
    /** Nome do grupo -> quantos em 10 minutos antes de virar alerta */
    public Map<String, Integer> limiteMinerios = limitesPadrao();

    /** Segundos entre dois alertas do mesmo tipo para o mesmo jogador. */
    public int cooldownSegundos = 120;

    public static GuardConfig get() { return instance; }

    private static Path arquivo() { return FabricLoader.getInstance().getConfigDir().resolve("indigoguard.json"); }

    public static void load() {
        Path p = arquivo();
        try {
            if (Files.exists(p)) {
                try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                    GuardConfig c = GSON.fromJson(r, GuardConfig.class);
                    if (c != null) instance = c;
                }
            }
        } catch (Exception e) {
            IndigoGuardMod.LOGGER.error("IndigoGuard: erro lendo indigoguard.json", e);
        }
        if (instance.staff == null) instance.staff = new ArrayList<>();
        if (instance.itensRaros == null) instance.itensRaros = itensPadrao();
        if (instance.grupoMinerios == null) instance.grupoMinerios = mineriosPadrao();
        if (instance.limiteMinerios == null) instance.limiteMinerios = limitesPadrao();
        save();
    }

    public static void save() {
        try (Writer w = Files.newBufferedWriter(arquivo(), StandardCharsets.UTF_8)) {
            GSON.toJson(instance, w);
        } catch (Exception e) {
            IndigoGuardMod.LOGGER.error("IndigoGuard: erro salvando indigoguard.json", e);
        }
    }

    /** Token: o do indigoguard.json ou, se vazio, o do IndigoVIP. */
    public String token() {
        if (discordToken != null && !discordToken.isBlank()) return discordToken.trim();
        try {
            Path vip = FabricLoader.getInstance().getConfigDir().resolve("indigovip").resolve("config.json");
            if (Files.exists(vip)) {
                JsonObject o = JsonParser.parseString(Files.readString(vip, StandardCharsets.UTF_8)).getAsJsonObject();
                if (o.has("discordToken")) return o.get("discordToken").getAsString().trim();
            }
        } catch (Exception ignored) { }
        return "";
    }

    public boolean ehStaff(String nome, java.util.UUID uuid) {
        for (String s : staff) {
            if (s.equalsIgnoreCase(nome) || (uuid != null && s.equalsIgnoreCase(uuid.toString()))) return true;
        }
        return false;
    }

    private static Map<String, Integer> itensPadrao() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("cobblemon:master_ball", 3);
        m.put("cobblemon:ancient_origin_ball", 2);
        m.put("cobblemon:ability_patch", 2);
        m.put("cobblemon:ability_capsule", 3);
        m.put("cobblemon:rare_candy", 10);
        for (String d : "health mighty tough smart courage quick sickly weak brittle numb coward slow".split(" ")) {
            m.put("cobblemon:" + d + "_candy", 6);
        }
        m.put("mega_showdown:mega_stone", 3);
        m.put("mega_showdown:keystone", 3);
        m.put("lumymon:locator_chip", 3);
        for (String f : "glacier_feather thunder_feather ember_feather glacier_feather_shiny thunder_feather_shiny ember_feather_shiny silver_wing rainbow_wing earth_core ocean_core sky_core calyrex_crown".split(" ")) {
            m.put("lumymon:" + f, 2);
        }
        return m;
    }

    private static Map<String, List<String>> mineriosPadrao() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("diamante", List.of("minecraft:diamond_ore", "minecraft:deepslate_diamond_ore"));
        m.put("esmeralda", List.of("minecraft:emerald_ore", "minecraft:deepslate_emerald_ore"));
        m.put("netherita", List.of("minecraft:ancient_debris"));
        m.put("pedras de evolução", List.of("cobblemon:*stone_ore"));
        m.put("keystone", List.of("mega_showdown:keystone_ore"));
        return m;
    }

    private static Map<String, Integer> limitesPadrao() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("diamante", 24);
        m.put("esmeralda", 16);
        m.put("netherita", 10);
        m.put("pedras de evolução", 30);
        m.put("keystone", 6);
        return m;
    }
}
