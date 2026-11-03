package legend.limit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class LimitConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("legendlimit.json");

    // id do item -> máximo de usos por jogador
    public Map<String, Integer> limits = new LinkedHashMap<>();
    // item de invocação -> espécies do Cobblemon que ele invoca (para checar a Pokédex)
    public Map<String, List<String>> itemSpecies = new LinkedHashMap<>();
    // usos no altar: já tem o lendário CAPTURADO na Pokédex / ainda não capturou
    public int limiteSeCapturado = 1;
    public int limiteSeNaoCapturado = 5;
    // quantas rodadas extras o /adicionar pode liberar por jogador e item
    public int maxAdicionar = 2;

    // estrutura -> item que o barril dela deve entregar (usado pelo comando "reparar")
    public Map<String, String> structureItems = new LinkedHashMap<>();
    // ids dos blocos de altar onde o limite vale. Vazio = qualquer bloco.
    public List<String> altarBlocks = new ArrayList<>();
    // conversão de barris de estruturas para barris com loot table (Lootr)
    public boolean autoConvert = true;
    public List<String> convertBlocks = new ArrayList<>(List.of("sophisticatedstorage:barrel"));
    public List<String> structurePrefixes = new ArrayList<>(List.of("cobbleverse:legendary/"));

    private static LimitConfig instance = new LimitConfig();

    public static LimitConfig get() { return instance; }

    public boolean isAltar(String blockId) {
        return altarBlocks.isEmpty() || altarBlocks.contains(blockId);
    }

    public static void load() {
        if (Files.exists(FILE)) {
            try (Reader reader = Files.newBufferedReader(FILE)) {
                LimitConfig loaded = GSON.fromJson(reader, LimitConfig.class);
                if (loaded != null) {
                    if (loaded.limits == null) loaded.limits = new LinkedHashMap<>();
                    if (loaded.altarBlocks == null) loaded.altarBlocks = new ArrayList<>();
                    if (loaded.itemSpecies == null) loaded.itemSpecies = new LinkedHashMap<>();
                    corrigirIds(loaded);
                    for (Map.Entry<String, List<String>> e : especiesPadrao().entrySet()) loaded.itemSpecies.putIfAbsent(e.getKey(), e.getValue());
                    instance = loaded;
                    save();
                    return;
                }
            } catch (Exception e) {
                LegendLimitMod.LOGGER.error("Não consegui ler legendlimit.json, usando padrão.", e);
            }
        }
        instance = new LimitConfig();
        instance.limits.put("lumymon:glacier_feather", 1);
        instance.limits.put("lumymon:thunder_feather", 1);
        instance.limits.put("lumymon:ember_feather", 1);
        instance.limits.put("lumymon:glacier_feather_shiny", 1);
        instance.limits.put("lumymon:thunder_feather_shiny", 1);
        instance.limits.put("lumymon:ember_feather_shiny", 1);
        instance.itemSpecies.putAll(especiesPadrao());
        save();
    }

    /** "lumymon:item/x" e "lumymon:block/x" não existem: o id certo é "lumymon:x". */
    private static void corrigirIds(LimitConfig c) {
        Map<String, Integer> novo = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : c.limits.entrySet()) {
            novo.put(e.getKey().replace(":item/", ":").replace(":block/", ":"), e.getValue());
        }
        c.limits = novo;
    }

    private static Map<String, List<String>> especiesPadrao() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("lumymon:glacier_feather", List.of("articuno"));
        m.put("lumymon:glacier_feather_shiny", List.of("articuno"));
        m.put("lumymon:thunder_feather", List.of("zapdos"));
        m.put("lumymon:thunder_feather_shiny", List.of("zapdos"));
        m.put("lumymon:ember_feather", List.of("moltres"));
        m.put("lumymon:ember_feather_shiny", List.of("moltres"));
        m.put("lumymon:silver_wing", List.of("lugia"));
        m.put("lumymon:silver_wing_shiny", List.of("lugia"));
        m.put("lumymon:rainbow_wing", List.of("hooh"));
        m.put("lumymon:rainbow_wing_shiny", List.of("hooh"));
        m.put("lumymon:earth_core", List.of("groudon"));
        m.put("lumymon:ocean_core", List.of("kyogre"));
        m.put("lumymon:sky_core", List.of("rayquaza"));
        m.put("lumymon:magma_stone", List.of("heatran"));
        m.put("lumymon:meteorite_crystal", List.of("deoxys"));
        m.put("lumymon:iceroot_carrot", List.of("glastrier"));
        m.put("lumymon:shaderoot_carrot", List.of("spectrier"));
        m.put("lumymon:calyrex_crown", List.of("calyrex"));
        m.put("lumymon:calyrex_crown_shiny", List.of("calyrex"));
        return m;
    }

    public static void save() {
        try (Writer writer = Files.newBufferedWriter(FILE)) {
            GSON.toJson(instance, writer);
        } catch (Exception e) {
            LegendLimitMod.LOGGER.error("Não consegui salvar legendlimit.json.", e);
        }

    }
}