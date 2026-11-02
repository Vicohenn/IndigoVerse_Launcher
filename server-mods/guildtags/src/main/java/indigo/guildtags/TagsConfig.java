package indigo.guildtags;

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

/**
 * config/guildtags.json
 *
 * "categorias": o que cada categoria controla (ids de item, #tags ou curingas com *).
 * "guildas": quais guildas têm cada categoria (máximo "maxGuildasPorCategoria").
 * As guildas são gerenciadas pelo comando /guildatag; não precisa editar à mão.
 */
public final class TagsConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static TagsConfig instance = new TagsConfig();
    private static Path path;

    public static final class Categoria {
        /** Nome que aparece nas mensagens. */
        public String nome;
        /** "craft" = bloqueia fabricar/retirar o resultado; "plantio" = bloqueia plantar. */
        public String tipo;
        /** Itens da categoria: "cobblemon:poke_ball", "#cobblemon:poke_balls" (tag) ou "cobblecuisine:*_shake". */
        public List<String> itens;

        public Categoria() {}
        Categoria(String nome, String tipo, String... itens) {
            this.nome = nome;
            this.tipo = tipo;
            this.itens = new ArrayList<>(List.of(itens));
        }
    }

    public int maxGuildasPorCategoria = 3;
    /** Operadores (/op) não são bloqueados. */
    public boolean opsIgnoramRestricoes = true;

    public Map<String, Categoria> categorias = padrao();
    /** categoria → nomes das guildas que têm a tag. */
    public Map<String, List<String>> guildas = new LinkedHashMap<>();

    /** Membros de guildas com estas categorias não podem colocar estes blocos (farm automática de apricorn). */
    public List<String> categoriasSemRedstone = new ArrayList<>(List.of("pokebolas"));
    public List<String> blocosProibidosSemRedstone = new ArrayList<>(List.of("minecraft:observer", "minecraft:dropper", "minecraft:dispenser"));
    /** Membros de guildas com estas categorias só constroem em claims (Flan) de membros da própria guilda. */
    public List<String> categoriasSoConstroemNaGuilda = new ArrayList<>(List.of("pokebolas"));
    /** Ninguém pode colocar o Crafter (craft automático driblaria as restrições). */
    public boolean bloquearCrafter = true;

    private static Map<String, Categoria> padrao() {
        Map<String, Categoria> m = new LinkedHashMap<>();
        m.put("pokebolas", new Categoria("Pokébolas", "craft", "#cobblemon:poke_balls"));
        m.put("medicinais", new Categoria("Medicinais", "craft",
                "#cobblemon:potions", "#cobblemon:revives", "#cobblemon:restores", "#cobblemon:remedies",
                "#cobblemon:ethers", "cobblemon:berry_juice", "cobblemon:full_heal", "cobblemon:medicinal_brew",
                "cobblemon:heal_powder", "cobblemon:energy_root", "cobblemon:revival_herb"));
        m.put("vitaminas", new Categoria("Vitaminas", "craft",
                "#cobblemon:vitamins", "cobblecuisine:*_shake"));
        m.put("frutas", new Categoria("Frutas e Bagas", "plantio",
                "#cobblemon:berries", "#cobblemon:apricorn_sprouts"));
        m.put("itens_de_segurar", new Categoria("Itens de Segurar", "craft",
                "#cobblemon:held/is_held_item"));
        m.put("tm", new Categoria("TMs", "craft",
                "cobblemon:technical_machine"));
        return m;
    }

    public static TagsConfig get() { return instance; }

    public static void load() {
        path = FabricLoader.getInstance().getConfigDir().resolve("guildtags.json");
        try {
            if (Files.exists(path)) {
                try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    TagsConfig c = GSON.fromJson(r, TagsConfig.class);
                    if (c != null) instance = c;
                }
            }
            if (instance.categorias == null || instance.categorias.isEmpty()) instance.categorias = padrao();
            if (instance.guildas == null) instance.guildas = new LinkedHashMap<>();
            for (String cat : instance.categorias.keySet()) instance.guildas.computeIfAbsent(cat, k -> new ArrayList<>());
            save();
        } catch (Exception e) {
            GuildTagsMod.LOGGER.error("Erro lendo config/guildtags.json", e);
        }
        Itens.recarregar();
    }

    public static void save() {
        try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(instance, w);
        } catch (Exception e) {
            GuildTagsMod.LOGGER.error("Erro salvando config/guildtags.json", e);
        }
    }
}
