package indigo.spawn;

import com.google.gson.reflect.TypeToken;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Kit inicial (uma vez por jogador) e limite de waystones por chunk. */
public final class Extras {
    private Extras() {}

    // ================= kit inicial =================

    private static Set<String> receberam = new HashSet<>();
    /** Jogadores novos esperando terminar o login do EasyAuth para receber o kit: uuid -> tick limite. */
    private static final Map<UUID, Long> pendentes = new ConcurrentHashMap<>();

    private static Path arquivoKit() { return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("indigospawn-kit-entregue.json"); }

    public static void carregarKit() {
        try {
            if (Files.exists(arquivoKit())) {
                try (Reader r = Files.newBufferedReader(arquivoKit(), StandardCharsets.UTF_8)) {
                    Set<String> s = SpawnConfigGson.GSON.fromJson(r, new TypeToken<HashSet<String>>() {}.getType());
                    if (s != null) receberam = s;
                }
            }
        } catch (Exception e) {
            IndigoSpawnMod.LOGGER.error("Erro lendo indigospawn-kit-entregue.json: {}", e.toString());
        }
    }

    private static void salvarKit() {
        try (Writer w = Files.newBufferedWriter(arquivoKit(), StandardCharsets.UTF_8)) {
            SpawnConfigGson.GSON.toJson(receberam, w);
        } catch (Exception e) {
            IndigoSpawnMod.LOGGER.error("Erro salvando indigospawn-kit-entregue.json: {}", e.toString());
        }
    }

    /** Chamado no primeiro login: agenda a entrega (espera até 5 min pelo login do EasyAuth). */
    public static void agendarKit(ServerPlayerEntity p, long tickAtual) {
        if (SpawnConfig.get().kitInicial.isEmpty() || receberam.contains(p.getUuidAsString())) return;
        pendentes.put(p.getUuid(), tickAtual + 20L * 60 * 5);
    }

    /** A cada segundo: entrega o kit para quem já fez login. */
    public static void entregarPendentes(MinecraftServer server, long tickAtual) {
        for (Iterator<Map.Entry<UUID, Long>> it = pendentes.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Long> e = it.next();
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
            if (p == null) { it.remove(); continue; }            // saiu antes: tenta de novo no próximo login
            if (!autenticado(p) && e.getValue() > tickAtual) continue;
            it.remove();
            darKit(p);
        }
    }

    private static void darKit(ServerPlayerEntity p) {
        if (!receberam.add(p.getUuidAsString())) return;
        salvarKit();
        for (SpawnConfig.ItemKit k : SpawnConfig.get().kitInicial) {
            Item item = Registries.ITEM.get(Identifier.of(k.item));
            if (item == net.minecraft.item.Items.AIR) { IndigoSpawnMod.LOGGER.warn("Item do kit não existe: {}", k.item); continue; }
            ItemStack st = new ItemStack(item, Math.max(1, k.quantidade));
            if (!p.getInventory().insertStack(st)) p.dropItem(st, false);
        }
        String msg = SpawnConfig.get().mensagemKit;
        if (msg != null && !msg.isBlank()) p.sendMessage(Text.literal(msg.replace('&', '§')));
        IndigoSpawnMod.LOGGER.info("Kit inicial entregue a {}", p.getGameProfile().getName());
    }

    private static Method metodoAuth;
    private static boolean semEasyAuth;

    /** EasyAuth esconde o inventário até o login; só entregamos depois. Sem EasyAuth = sempre autenticado. */
    private static boolean autenticado(ServerPlayerEntity p) {
        if (semEasyAuth) return true;
        try {
            if (metodoAuth == null) {
                for (Method m : p.getClass().getMethods()) {
                    if (m.getParameterCount() == 0 && (m.getReturnType() == boolean.class)
                            && m.getName().toLowerCase().contains("isauthenticated")) { metodoAuth = m; break; }
                }
                if (metodoAuth == null) { semEasyAuth = true; return true; }
            }
            return (boolean) metodoAuth.invoke(p);
        } catch (Exception e) {
            semEasyAuth = true;
            return true;
        }
    }

    // ================= 1 waystone por chunk =================

    private static TagKey<Block> tagWaystones;

    public static ActionResult limitarWaystone(ServerPlayerEntity player, ServerWorld world, Hand hand, BlockHitResult hit) {
        int max = SpawnConfig.get().waystonesPorChunk;
        if (max <= 0 || Protecao.liberado(player)) return ActionResult.PASS;
        ItemStack st = player.getStackInHand(hand);
        if (!(st.getItem() instanceof BlockItem bi)) return ActionResult.PASS;
        if (tagWaystones == null) tagWaystones = TagKey.of(RegistryKeys.BLOCK, Identifier.of("waystones", "waystones"));
        if (!bi.getBlock().getDefaultState().isIn(tagWaystones)) return ActionResult.PASS;

        BlockPos alvo = new ItemPlacementContext(player, hand, st, hit).getBlockPos();
        ChunkPos cp = new ChunkPos(alvo);
        WorldChunk chunk = world.getChunk(cp.x, cp.z);
        int achadas = 0;
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            // cada waystone tem 2 blocos, mas só a parte de baixo tem block entity
            if (be.getCachedState().isIn(tagWaystones)) achadas++;
        }
        if (achadas >= max) {
            player.sendMessage(Text.literal("§cJá existe " + (max == 1 ? "uma Waystone" : max + " Waystones") + " neste chunk. Coloque em outro chunk."), true);
            return ActionResult.FAIL;
        }
        return ActionResult.PASS;
    }
}
