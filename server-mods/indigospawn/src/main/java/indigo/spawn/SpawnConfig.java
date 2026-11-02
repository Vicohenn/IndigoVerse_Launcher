package indigo.spawn;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** config/indigospawn.json */
public final class SpawnConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static SpawnConfig instance;

    public static final class Ponto {
        public String dimensao = "minecraft:overworld";
        public double x, y, z;
        public float yaw, pitch;
    }

    public static final class Area {
        public String nome;
        public String dimensao = "minecraft:overworld";
        public int x1, y1, z1, x2, y2, z2;

        public boolean contem(String dim, BlockPos p) {
            return dimensao.equals(dim)
                    && p.getX() >= Math.min(x1, x2) && p.getX() <= Math.max(x1, x2)
                    && p.getY() >= Math.min(y1, y2) && p.getY() <= Math.max(y1, y2)
                    && p.getZ() >= Math.min(z1, z2) && p.getZ() <= Math.max(z1, z2);
        }

        public String descricao() {
            return nome + " (" + x1 + " " + y1 + " " + z1 + " → " + x2 + " " + y2 + " " + z2 + ", " + dimensao + ")";
        }
    }

    /** Ponto exato onde os jogadores aparecem (centro do bloco: some .5). */
    public Ponto spawn = new Ponto();
    /** Leva para o spawn exato quem entra no servidor pela primeira vez. */
    public boolean teleportarNoPrimeiroLogin = true;
    /** Leva para o spawn exato quem morre sem cama/âncora definida. */
    public boolean teleportarAoRenascerSemCama = true;
    /** OPs podem construir nas áreas protegidas. */
    public boolean opsIgnoramProtecao = true;
    /** Áreas onde ninguém (fora OP) quebra ou coloca blocos. Portas, alçapões, portões, botões e alavancas continuam funcionando. */
    public List<Area> areasProtegidas = new ArrayList<>();
    /** Blocos que NÃO podem ser usados (clique direito) dentro das áreas, ex.: "minecraft:chest". Vazio = tudo pode ser usado. */
    public List<String> blocosSemUsoNaArea = new ArrayList<>();

    public static SpawnConfig get() { return instance; }

    private static Path arquivo() { return FabricLoader.getInstance().getConfigDir().resolve("indigospawn.json"); }

    public static void load() {
        try {
            if (Files.exists(arquivo())) {
                try (Reader r = Files.newBufferedReader(arquivo(), StandardCharsets.UTF_8)) {
                    instance = GSON.fromJson(r, SpawnConfig.class);
                }
            }
        } catch (Exception e) {
            IndigoSpawnMod.LOGGER.error("Erro lendo indigospawn.json: {} (usando padrão)", e.toString());
        }
        if (instance == null) {
            instance = padrao();
            save();
        }
        if (instance.areasProtegidas == null) instance.areasProtegidas = new ArrayList<>();
        if (instance.blocosSemUsoNaArea == null) instance.blocosSemUsoNaArea = new ArrayList<>();
    }

    public static void save() {
        try (Writer w = Files.newBufferedWriter(arquivo(), StandardCharsets.UTF_8)) {
            GSON.toJson(instance, w);
        } catch (Exception e) {
            IndigoSpawnMod.LOGGER.error("Erro salvando indigospawn.json: {}", e.toString());
        }
    }

    private static SpawnConfig padrao() {
        SpawnConfig c = new SpawnConfig();
        c.spawn.x = -389.5; c.spawn.y = 65; c.spawn.z = 27396.5;
        // Mesma área que o spawn-protection=16 cobria, mas agora com portas funcionando.
        Area a = new Area();
        a.nome = "spawn";
        a.x1 = -406; a.y1 = -64; a.z1 = 27380;
        a.x2 = -374; a.y2 = 319; a.z2 = 27412;
        c.areasProtegidas.add(a);
        return c;
    }
}
