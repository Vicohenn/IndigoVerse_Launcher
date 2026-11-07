package indigo.precos;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Todo cobble_merchant que carregar no mundo tem os preços das ofertas trocados pelos da tabela,
 * que vem dos NPCs de referência (os do spawn). Itens que não estão na tabela ficam com o preço do NPC.
 * Os NPCs de referência nunca são alterados.
 */
public class IndigoPrecosMod implements ModInitializer {
    static final Logger LOG = LoggerFactory.getLogger("IndigoPrecos");
    static final String TIPO = "cobbledollars:cobble_merchant";
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    static final Path ARQ = FabricLoader.getInstance().getConfigDir().resolve("indigoprecos.json");

    static class Config {
        String _leia = "";
        List<String> referencias = new ArrayList<>();
        LinkedHashMap<String, String> precos = new LinkedHashMap<>();
        LinkedHashMap<String, String> extras = new LinkedHashMap<>();
    }

    static Config cfg = new Config();
    static final Queue<Entity> fila = new ConcurrentLinkedQueue<>();

    @Override
    public void onInitialize() {
        carregar();
        ServerEntityEvents.ENTITY_LOAD.register((e, w) -> { if (ehMerchant(e)) fila.add(e); });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            Entity e;
            int n = 0;
            while (n++ < 50 && (e = fila.poll()) != null) {
                if (!e.isRemoved()) {
                    int m = aplicar(e);
                    if (m > 0) LOG.info("{} preço(s) ajustado(s) em {} {}", m, nome(e), e.getBlockPos().toShortString());
                }
            }
        });
        CommandRegistrationCallback.EVENT.register((d, reg, env) -> d.register(literal("indigoprecos")
            .requires(s -> s.hasPermissionLevel(3))
            .then(literal("importar").executes(IndigoPrecosMod::cmdImportar))
            .then(literal("aplicar").executes(IndigoPrecosMod::cmdAplicar))
            .then(literal("recarregar").executes(c -> { carregar(); return msg(c, "Config recarregada: " + tabela().size() + " preços, " + cfg.referencias.size() + " referências."); }))
            .then(literal("referencia")
                .then(literal("adicionar").then(argument("alvos", EntityArgumentType.entities()).executes(c -> cmdRef(c, true))))
                .then(literal("remover").then(argument("alvos", EntityArgumentType.entities()).executes(c -> cmdRef(c, false)))))));
    }

    static boolean ehMerchant(Entity e) {
        return TIPO.equals(Registries.ENTITY_TYPE.getId(e.getType()).toString());
    }

    static String nome(Entity e) { return e.hasCustomName() ? e.getCustomName().getString() : "merchant"; }

    static Map<String, String> tabela() {
        Map<String, String> t = new HashMap<>(cfg.precos);
        t.putAll(cfg.extras);
        return t;
    }

    static NbtList ofertas(NbtCompound shop, int cat) {
        return shop.getList("shop", NbtElement.COMPOUND_TYPE).getCompound(cat).getList("offers", NbtElement.COMPOUND_TYPE);
    }

    /** Troca os preços de um merchant (que não seja referência). Devolve quantos preços mudaram. */
    static int aplicar(Entity e) {
        if (cfg.referencias.contains(e.getUuidAsString())) return 0;
        NbtCompound n = e.writeNbt(new NbtCompound());
        NbtCompound shop = n.getCompound("CobbleDollars").getCompound("Shop");
        if (!"inline".equals(shop.getString("type"))) return 0;
        Map<String, String> t = tabela();
        int mudou = 0;
        NbtList cats = shop.getList("shop", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < cats.size(); i++) {
            NbtList of = cats.getCompound(i).getList("offers", NbtElement.COMPOUND_TYPE);
            for (int j = 0; j < of.size(); j++) {
                NbtCompound o = of.getCompound(j);
                if (o.contains("components")) continue;
                String p = t.get(o.getString("item"));
                if (p != null && !p.equals(o.getString("price"))) { o.putString("price", p); mudou++; }
            }
        }
        if (mudou > 0) e.readNbt(n);
        return mudou;
    }

    static int cmdImportar(CommandContext<ServerCommandSource> c) {
        MinecraftServer srv = c.getSource().getServer();
        LinkedHashMap<String, String> novos = new LinkedHashMap<>();
        int achados = 0;
        for (String id : cfg.referencias) {
            Entity e = null;
            for (ServerWorld w : srv.getWorlds()) { e = w.getEntity(UUID.fromString(id)); if (e != null) break; }
            if (e == null) continue;
            achados++;
            NbtCompound shop = e.writeNbt(new NbtCompound()).getCompound("CobbleDollars").getCompound("Shop");
            NbtList cats = shop.getList("shop", NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < cats.size(); i++) {
                NbtList of = ofertas(shop, i);
                for (int j = 0; j < of.size(); j++) {
                    NbtCompound o = of.getCompound(j);
                    if (!o.contains("components")) novos.putIfAbsent(o.getString("item"), o.getString("price"));
                }
            }
        }
        if (achados == 0) return msg(c, "Nenhum NPC de referência carregado. Vá até o spawn (perto deles) e rode de novo.");
        if (achados == cfg.referencias.size()) cfg.precos = novos;   // todos carregados: tabela refeita do zero
        else cfg.precos.putAll(novos);                              // só alguns: atualiza o que deu para ler
        salvar();
        msg(c, "Importado de " + achados + "/" + cfg.referencias.size() + " NPCs de referência: " + cfg.precos.size() + " itens.");
        return cmdAplicar(c);
    }

    static int cmdAplicar(CommandContext<ServerCommandSource> c) {
        int npcs = 0, precos = 0;
        for (ServerWorld w : c.getSource().getServer().getWorlds())
            for (Entity e : w.iterateEntities()) if (ehMerchant(e)) { int m = aplicar(e); if (m > 0) { npcs++; precos += m; } }
        return msg(c, "Aplicado nos comerciantes carregados: " + precos + " preço(s) em " + npcs + " NPC(s). Os outros mudam quando carregarem.");
    }

    static int cmdRef(CommandContext<ServerCommandSource> c, boolean add) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        int n = 0;
        for (Entity e : EntityArgumentType.getEntities(c, "alvos")) {
            if (!ehMerchant(e)) continue;
            String id = e.getUuidAsString();
            if (add ? !cfg.referencias.contains(id) && cfg.referencias.add(id) : cfg.referencias.remove(id)) n++;
        }
        salvar();
        return msg(c, (add ? "Adicionadas " : "Removidas ") + n + " referência(s). Total: " + cfg.referencias.size() + ". Rode /indigoprecos importar.");
    }

    static int msg(CommandContext<ServerCommandSource> c, String s) {
        c.getSource().sendFeedback(() -> Text.literal("[IndigoPreços] " + s), true);
        return 1;
    }

    static void carregar() {
        try {
            if (!Files.exists(ARQ)) {
                try (InputStream in = IndigoPrecosMod.class.getResourceAsStream("/indigoprecos/padrao.json")) {
                    Files.write(ARQ, in.readAllBytes());
                }
            }
            try (Reader r = Files.newBufferedReader(ARQ, StandardCharsets.UTF_8)) {
                Config c = GSON.fromJson(r, Config.class);
                if (c != null) {
                    if (c.referencias == null) c.referencias = new ArrayList<>();
                    if (c.precos == null) c.precos = new LinkedHashMap<>();
                    if (c.extras == null) c.extras = new LinkedHashMap<>();
                    cfg = c;
                }
            }
            LOG.info("{} preços e {} NPCs de referência carregados", tabela().size(), cfg.referencias.size());
        } catch (Exception ex) {
            LOG.error("Erro lendo {}", ARQ, ex);
        }
    }

    static void salvar() {
        try (Writer w = Files.newBufferedWriter(ARQ, StandardCharsets.UTF_8)) {
            GSON.toJson(cfg, w);
        } catch (Exception ex) {
            LOG.error("Erro salvando {}", ARQ, ex);
        }
    }
}
