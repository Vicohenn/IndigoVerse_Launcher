package legend.limit;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.loot.LootTable;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureStart;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.UUID;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Troca barris de estruturas (ex.: barril do Sophisticated Storage com a Pena Glacial dentro)
 * por barris comuns com loot table. O Lootr (ja instalado) passa a entregar uma copia por jogador.
 *
 * Modo automatico (ao carregar chunks) so converte se: o bloco esta em "convertBlocks",
 * contem um item da lista "limits", esta dentro de uma estrutura cujo id comeca com um dos
 * "structurePrefixes" e existe a loot table legendlimit:pedestal/<nome_do_item>.
 *
 * Comando "reparar" (manual): converte barris VAZIOS dentro das estruturas listadas em
 * "structureItems" (estrutura -> item), para torres onde alguem ja pegou o item.
 */
public class BarrelConverter {

    private record Candidate(ServerWorld world, BlockPos pos) {}

    /** Limpeza atrasada: itens que caírem depois da troca (alguns mods soltam o conteúdo 1-2 ticks depois). */
    private record Limpeza(ServerWorld world, Box area, Set<UUID> antes, int[] ticks) {}

    private static final java.util.List<Limpeza> LIMPEZAS = new java.util.ArrayList<>();
    private static final int TICKS_LIMPEZA = 10;

    private static final Queue<Candidate> QUEUE = new ConcurrentLinkedQueue<>();
    private static final Pattern FACING = Pattern.compile("facing=(down|up|north|south|east|west)");
    private static final int PER_TICK = 20;

    public static void register() {
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> onChunkLoad(world, chunk));
        ServerTickEvents.END_SERVER_TICK.register(BarrelConverter::drainQueue);

        // /legendlimit converter [raio] [executar]   e   /legendlimit reparar [raio] [executar]
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(literal("legendlimit")
                        .requires(src -> src.hasPermissionLevel(2))
                        .then(sub("converter", false))
                        .then(sub("reparar", true))));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> sub(String name, boolean repair) {
        return literal(name)
                .executes(ctx -> scan(ctx, 4, false, repair))
                .then(argument("raio", IntegerArgumentType.integer(1, 16))
                        .executes(ctx -> scan(ctx, IntegerArgumentType.getInteger(ctx, "raio"), false, repair))
                        .then(literal("executar")
                                .executes(ctx -> scan(ctx, IntegerArgumentType.getInteger(ctx, "raio"), true, repair))));
    }

    // ---------- conversao automatica ----------

    private static void onChunkLoad(ServerWorld world, WorldChunk chunk) {
        LimitConfig cfg = LimitConfig.get();
        if (!cfg.autoConvert || cfg.convertBlocks.isEmpty()) return;
        try {
            // so guarda posicoes aqui; o trabalho de verdade acontece no tick do servidor
            for (BlockPos pos : new ArrayList<>(chunk.getBlockEntityPositions())) {
                if (isConvertBlock(chunk.getBlockState(pos))) {
                    QUEUE.add(new Candidate(world, pos.toImmutable()));
                }
            }
        } catch (Exception e) {
            LegendLimitMod.LOGGER.warn("[legendlimit] Falha ao examinar chunk carregado.", e);
        }
    }

    private static void drainQueue(MinecraftServer server) {
        if (!LIMPEZAS.isEmpty()) {
            Iterator<Limpeza> it = LIMPEZAS.iterator();
            while (it.hasNext()) {
                Limpeza l = it.next();
                apagarItensNovos(l.world(), l.area(), l.antes());
                if (--l.ticks()[0] <= 0) it.remove();
            }
        }
        for (int i = 0; i < PER_TICK; i++) {
            Candidate c = QUEUE.poll();
            if (c == null) return;
            try {
                if (!c.world().isChunkLoaded(c.pos())) continue;
                String item = inspect(c.world(), c.pos());
                if (item != null) convert(c.world(), c.pos(), item);
            } catch (Exception e) {
                LegendLimitMod.LOGGER.warn("[legendlimit] Falha ao converter barril em " + c.pos().toShortString(), e);
            }
        }
    }

    // ---------- comandos ----------

    private static int scan(CommandContext<ServerCommandSource> ctx, int radius, boolean execute, boolean repair) {
        ServerCommandSource src = ctx.getSource();
        ServerWorld world = src.getWorld();
        BlockPos center = BlockPos.ofFloored(src.getPosition());
        int cx = center.getX() >> 4;
        int cz = center.getZ() >> 4;
        String cmdName = repair ? "reparar" : "converter";

        int found = 0;
        int converted = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                WorldChunk chunk = world.getChunkManager().getWorldChunk(cx + dx, cz + dz);
                if (chunk == null) continue; // so olha chunks ja carregados
                for (BlockPos pos : new ArrayList<>(chunk.getBlockEntityPositions())) {
                    String item = repair ? inspectRepair(world, pos) : inspect(world, pos);
                    if (item == null) continue;
                    found++;
                    boolean ok = execute && convert(world, pos, item);
                    if (ok) converted++;
                    if (found <= 20) {
                        String msg = " - " + pos.toShortString() + " (" + item + ")" + (ok ? " convertido" : "");
                        src.sendFeedback(() -> Text.literal(msg), false);
                    }
                }
            }
        }

        final int f = found;
        final int c = converted;
        if (execute) {
            src.sendFeedback(() -> Text.literal("Encontrados: " + f + ". Convertidos: " + c + "."), true);
        } else {
            src.sendFeedback(() -> Text.literal("Simulacao: " + f
                    + " barril(is) seriam convertidos. Para converter, use '/legendlimit " + cmdName
                    + " <raio> executar'."), false);
        }
        return found;
    }

    // ---------- logica ----------

    private static boolean isConvertBlock(BlockState state) {
        String id = Registries.BLOCK.getId(state.getBlock()).toString();
        return LimitConfig.get().convertBlocks.contains(id);
    }

    /** Modo automatico: devolve o id do item se este bloco deve ser convertido; senao null. */
    static String inspect(ServerWorld world, BlockPos pos) {
        if (!isConvertBlock(world.getBlockState(pos))) return null;
        BlockEntity be = world.getBlockEntity(pos);
        if (be == null) return null;

        NbtCompound nbt = be.createNbt(world.getRegistryManager());
        if (nbt.contains("LootTable")) return null; // ja usa loot table

        Set<String> found = new LinkedHashSet<>();
        collect(nbt, found, LimitConfig.get().limits.keySet());
        if (found.isEmpty()) return null;
        if (found.size() > 1) {
            LegendLimitMod.LOGGER.warn("[legendlimit] Barril em {} tem mais de um item da lista {}; ignorado.",
                    pos.toShortString(), found);
            return null;
        }
        boolean inside = insideStructure(world, pos, id -> {
            for (String prefix : LimitConfig.get().structurePrefixes) {
                if (id.startsWith(prefix)) return true;
            }
            return false;
        });
        if (!inside) return null;
        return found.iterator().next();
    }

    /** Comando "reparar": barril VAZIO dentro de uma estrutura mapeada em structureItems. */
    static String inspectRepair(ServerWorld world, BlockPos pos) {
        if (!isConvertBlock(world.getBlockState(pos))) return null;
        BlockEntity be = world.getBlockEntity(pos);
        if (be == null) return null;

        NbtCompound nbt = be.createNbt(world.getRegistryManager());
        if (nbt.contains("LootTable")) return null;
        if (!isEmptyInventory(nbt)) return null;

        for (Map.Entry<String, String> e : LimitConfig.get().structureItems.entrySet()) {
            String structureId = e.getKey();
            if (insideStructure(world, pos, id -> id.equals(structureId))) return e.getValue();
        }
        return null;
    }

    /** So considera vazio se o layout do inventario for reconhecido (campo "Size") e sem itens. */
    private static boolean isEmptyInventory(NbtCompound nbt) {
        NbtCompound inv = nbt.getCompound("storageWrapper").getCompound("contents").getCompound("inventory");
        if (!inv.contains("Size")) return false;
        return inv.getList("Items", NbtElement.COMPOUND_TYPE).isEmpty();
    }

    /** Procura recursivamente, no NBT do bloco, textos iguais aos ids dos itens vigiados. */
    private static void collect(NbtElement element, Set<String> found, Set<String> watched) {
        if (element instanceof NbtString s) {
            if (watched.contains(s.asString())) found.add(s.asString());
        } else if (element instanceof NbtCompound c) {
            for (String key : c.getKeys()) collect(c.get(key), found, watched);
        } else if (element instanceof NbtList l) {
            for (NbtElement e : l) collect(e, found, watched);
        }
    }

    private static boolean insideStructure(ServerWorld world, BlockPos pos, Predicate<String> matcher) {
        StructureStart start = world.getStructureAccessor().getStructureContaining(pos, entry -> {
            String id = entry.getKey().map(k -> k.getValue().toString()).orElse("");
            return matcher.test(id);
        });
        return start != null && start.hasChildren();
    }

    /** Remove os itens no chão dentro da área que não existiam antes da troca. */
    private static int apagarItensNovos(ServerWorld world, Box area, Set<UUID> antes) {
        int n = 0;
        for (ItemEntity e : world.getEntitiesByClass(ItemEntity.class, area, x -> !antes.contains(x.getUuid()))) {
            e.discard();
            n++;
        }
        return n;
    }

    static boolean convert(ServerWorld world, BlockPos pos, String itemId) {
        Identifier item = Identifier.tryParse(itemId);
        if (item == null) return false;
        Identifier tableId = Identifier.of(LegendLimitMod.MOD_ID, "pedestal/" + item.getPath());
        MinecraftServer server = world.getServer();

        // seguranca: sem loot table o barril novo ficaria vazio
        RegistryKey<LootTable> key = RegistryKey.of(RegistryKeys.LOOT_TABLE, tableId);
        if (server.getReloadableRegistries().getLootTable(key) == LootTable.EMPTY) {
            LegendLimitMod.LOGGER.warn("[legendlimit] Falta a loot table {} (barril em {} nao foi convertido).",
                    tableId, pos.toShortString());
            return false;
        }

        Matcher m = FACING.matcher(world.getBlockState(pos).toString());
        String facing = m.find() ? m.group(1) : "up";

        // itens que já estavam no chão antes da troca (de jogadores etc.) não são tocados
        Box area = new Box(pos).expand(3);
        Set<UUID> antes = new HashSet<>();
        for (ItemEntity e : world.getEntitiesByClass(ItemEntity.class, area, x -> true)) antes.add(e.getUuid());

        ServerCommandSource src = server.getCommandSource().withWorld(world).withSilent();
        server.getCommandManager().executeWithPrefix(src, String.format(
                "setblock %d %d %d minecraft:barrel[facing=%s]{LootTable:\"%s\"} replace",
                pos.getX(), pos.getY(), pos.getZ(), facing, tableId));

        // apaga tudo o que o barril antigo soltou no chão ao ser trocado; fica só o barril do Lootr
        int apagados = apagarItensNovos(world, area, antes);
        LIMPEZAS.add(new Limpeza(world, area, antes, new int[]{TICKS_LIMPEZA}));
        if (apagados > 0) {
            LegendLimitMod.LOGGER.info("[legendlimit] {} item(ns) do barril antigo em {} apagado(s) do chão.",
                    apagados, pos.toShortString());
        }

        LegendLimitMod.LOGGER.info("[legendlimit] Barril em {} convertido (loot table {}).", pos.toShortString(), tableId);
        return true;
    }
}
