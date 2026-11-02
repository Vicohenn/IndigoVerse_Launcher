package indigo.log;

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

/** config/indigolog.json — criado na primeira vez que o servidor abre. */
public final class LogConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static LogConfig instance = new LogConfig();

    /** URL do webhook do canal da Staff (Discord → canal → Editar → Integrações → Webhooks). */
    public String webhookUrl = "";
    /** Fuso usado nos horários das mensagens. */
    public String fusoHorario = "America/Sao_Paulo";

    /** Registrar itens retirados de baús e outros containers. */
    public boolean logBaus = true;
    /** true = só registra retiradas feitas dentro de claims (Flan). */
    public boolean bausSomenteEmClaims = false;
    /** true = não registra quando o dono do claim mexe nos próprios baús. */
    public boolean ignorarDonoDoClaim = true;
    /** Blocos ignorados (ids). Ex.: "minecraft:furnace". */
    public List<String> blocosIgnorados = new ArrayList<>(List.of("minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker", "minecraft:brewing_stand"));

    /** Registrar batalhas contra NPCs do Radical Cobblemon Trainers. */
    public boolean logBatalhas = true;
    /**
     * Tipo de treinador do RCT (campo "type" do datapack) → nome que aparece no log.
     * Tipos que não estão aqui (treinadores comuns, Equipe Rocket etc.) não são registrados.
     */
    public Map<String, String> tiposDeTreinador = defaultTipos();

    private static Map<String, String> defaultTipos() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String regiao : List.of("kanto", "johto", "hoenn", "sinnoh")) {
            m.put(regiao, "Líder de Ginásio");
            m.put(regiao + "_league", "Elite dos 4");
            m.put(regiao + "_champion", "Campeão");
        }
        m.put("leader", "Líder de Ginásio");
        m.put("champ", "Campeão");
        return m;
    }

    public static LogConfig get() { return instance; }

    public static void load() {
        Path p = FabricLoader.getInstance().getConfigDir().resolve("indigolog.json");
        try {
            if (Files.exists(p)) {
                try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                    LogConfig c = GSON.fromJson(r, LogConfig.class);
                    if (c != null) instance = c;
                }
            }
            if (instance.webhookUrl == null) instance.webhookUrl = "";
            if (instance.tiposDeTreinador == null) instance.tiposDeTreinador = defaultTipos();
            if (instance.blocosIgnorados == null) instance.blocosIgnorados = new ArrayList<>();
            try (Writer w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
                GSON.toJson(instance, w);   // grava de volta com campos novos/padrões
            }
        } catch (Exception e) {
            IndigoLogMod.LOGGER.error("Erro lendo config/indigolog.json", e);
        }
    }
}
