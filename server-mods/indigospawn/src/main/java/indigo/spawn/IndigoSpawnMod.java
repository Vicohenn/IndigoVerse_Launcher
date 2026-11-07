package indigo.spawn;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.BlockState;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.RaycastContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * IndigoSpawn (comandos só para OP)
 *   /indigospawn definir                         usa a sua posição atual como spawn exato
 *   /indigospawn ir                              vai até o spawn
 *   /indigospawn area adicionar <nome> <de> <ate>
 *   /indigospawn area remover <nome>
 *   /indigospawn area listar
 *   /indigospawn recarregar
 */
public final class IndigoSpawnMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("indigospawn");

    private record Agendado(long tick, UUID jogador) {}
    private static final List<Agendado> agenda = new ArrayList<>();
    private static long tick;
    /** Jogadores novos vigiados por alguns minutos: se aparecerem em cima do telhado do spawn, voltam para dentro. */
    private static final Map<UUID, Long> vigiados = new ConcurrentHashMap<>();
    private static final long VIGIA_TICKS = 20L * 60 * 5;

    @Override
    public void onInitialize() {
        SpawnConfig.load();
        Extras.carregarKit();

        // ---------- spawn exato ----------
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity p = handler.player;
            boolean primeiro = p.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.LEAVE_GAME)) == 0;
            if (primeiro) Extras.agendarKit(p, tick);
            if (!SpawnConfig.get().teleportarNoPrimeiroLogin) return;
            if (primeiro) {
                // duas vezes: logo depois de entrar e de novo após o login (EasyAuth pode mexer na posição)
                agenda.add(new Agendado(tick + 20, p.getUuid()));
                agenda.add(new Agendado(tick + 80, p.getUuid()));
                vigiados.put(p.getUuid(), tick + VIGIA_TICKS);
            }
        });

        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (!alive && SpawnConfig.get().teleportarAoRenascerSemCama && newPlayer.getSpawnPointPosition() == null) {
                irParaSpawn(newPlayer);
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tick++;
            if (tick % 20 == 0) Extras.entregarPendentes(server, tick);
            if (tick % 20 == 0 && !vigiados.isEmpty()) {
                vigiados.entrySet().removeIf(e -> {
                    ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
                    if (p == null || e.getValue() < tick) return true;
                    if (noTelhado(p)) irParaSpawn(p);
                    return false;
                });
            }
            if (agenda.isEmpty()) return;
            for (Iterator<Agendado> it = agenda.iterator(); it.hasNext(); ) {
                Agendado a = it.next();
                if (a.tick() > tick) continue;
                it.remove();
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(a.jogador());
                if (p != null) irParaSpawn(p);
            }
        });

        // ---------- proteção ----------
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, be) -> {
            if (Protecao.liberado(player) || Protecao.areaEm(world, pos) == null) return true;
            Protecao.avisar(player);
            return false;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!world.isClient && player instanceof ServerPlayerEntity sp && world instanceof ServerWorld sw) {
                ActionResult r = Extras.limitarWaystone(sp, sw, hand, hit);
                if (r != ActionResult.PASS) return r;
            }
            if (world.isClient || Protecao.liberado(player)) return ActionResult.PASS;
            BlockPos pos = hit.getBlockPos();
            BlockPos colocar = pos.offset(hit.getSide());
            boolean naArea = Protecao.areaEm(world, pos) != null;
            BlockState st = world.getBlockState(pos);

            if (naArea) {
                String id = Registries.BLOCK.getId(st.getBlock()).toString();
                if (SpawnConfig.get().blocosSemUsoNaArea.contains(id) || st.isIn(BlockTags.ALL_SIGNS)) {
                    Protecao.avisar(player);
                    return ActionResult.FAIL;
                }
            }
            ItemStack item = player.getStackInHand(hand);
            if (Protecao.colocaAlgo(item) && (naArea || Protecao.areaEm(world, colocar) != null)) {
                // clicar numa porta/alçapão/portão/botão segurando um bloco ainda abre a porta
                if (!player.isSneaking() && naArea && interativo(st)) return ActionResult.PASS;
                Protecao.avisar(player);
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack item = player.getStackInHand(hand);
            if (world.isClient || Protecao.liberado(player) || !Protecao.colocaAlgo(item)) return TypedActionResult.pass(item);
            HitResult hr = world.raycast(new RaycastContext(player.getEyePos(),
                    player.getEyePos().add(player.getRotationVec(1f).multiply(6)),
                    RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.ANY, player));
            if (hr instanceof BlockHitResult bh && hr.getType() == HitResult.Type.BLOCK
                    && (Protecao.areaEm(world, bh.getBlockPos()) != null || Protecao.areaEm(world, bh.getBlockPos().offset(bh.getSide())) != null)) {
                Protecao.avisar(player);
                return TypedActionResult.fail(item);
            }
            return TypedActionResult.pass(item);
        });

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClient || Protecao.liberado(player) || !Protecao.decorativa(entity)) return ActionResult.PASS;
            if (Protecao.areaEm(world, entity.getBlockPos()) == null) return ActionResult.PASS;
            Protecao.avisar(player);
            return ActionResult.FAIL;
        });

        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClient || Protecao.liberado(player) || !Protecao.decorativa(entity)) return ActionResult.PASS;
            if (Protecao.areaEm(world, entity.getBlockPos()) == null) return ActionResult.PASS;
            Protecao.avisar(player);
            return ActionResult.FAIL;
        });

        // ---------- comandos ----------
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> dispatcher.register(
                literal("indigospawn").requires(s -> s.hasPermissionLevel(2))
                        .then(literal("definir").executes(IndigoSpawnMod::definir))
                        .then(literal("ir").executes(c -> {
                            irParaSpawn(c.getSource().getPlayerOrThrow());
                            return 1;
                        }))
                        .then(literal("recarregar").executes(c -> {
                            SpawnConfig.load();
                            c.getSource().sendFeedback(() -> Text.literal("§aindigospawn.json recarregado."), true);
                            return 1;
                        }))
                        .then(literal("area")
                                .then(literal("listar").executes(IndigoSpawnMod::listar))
                                .then(literal("adicionar")
                                        .then(argument("nome", StringArgumentType.word())
                                                .then(argument("de", BlockPosArgumentType.blockPos())
                                                        .then(argument("ate", BlockPosArgumentType.blockPos())
                                                                .executes(IndigoSpawnMod::adicionar)))))
                                .then(literal("remover")
                                        .then(argument("nome", StringArgumentType.word())
                                                .suggests((c, b) -> CommandSource.suggestMatching(
                                                        SpawnConfig.get().areasProtegidas.stream().map(a -> a.nome), b))
                                                .executes(IndigoSpawnMod::remover))))));

        LOGGER.info("IndigoSpawn carregado: {} áreas protegidas.", SpawnConfig.get().areasProtegidas.size());
    }

    private static boolean interativo(BlockState st) {
        return st.isIn(BlockTags.DOORS) || st.isIn(BlockTags.TRAPDOORS) || st.isIn(BlockTags.FENCE_GATES)
                || st.isIn(BlockTags.BUTTONS) || st.isOf(net.minecraft.block.Blocks.LEVER) || st.isOf(net.minecraft.block.Blocks.BELL);
    }

    /** Ponto exato do spawn (para o mixin), ou null se a dimensão não for a do spawn. */
    public static BlockPos spawnExato(ServerWorld world) {
        SpawnConfig c = SpawnConfig.get();
        if (c == null || c.spawn == null) return null;
        if (!Protecao.dim(world).equals(c.spawn.dimensao)) return null;
        return BlockPos.ofFloored(c.spawn.x, c.spawn.y, c.spawn.z);
    }

    /** Em cima da casa do spawn: perto do ponto, acima dele e a céu aberto (andar de cima da casa não conta). */
    private static boolean noTelhado(ServerPlayerEntity p) {
        SpawnConfig.Ponto s = SpawnConfig.get().spawn;
        if (!Protecao.dim(p.getWorld()).equals(s.dimensao)) return false;
        if (p.getY() < s.y + 3) return false;
        if (Math.abs(p.getX() - s.x) > 16 || Math.abs(p.getZ() - s.z) > 16) return false;
        return p.getWorld().isSkyVisible(p.getBlockPos().up());
    }

    public static void irParaSpawn(ServerPlayerEntity p) {
        SpawnConfig.Ponto s = SpawnConfig.get().spawn;
        MinecraftServer server = p.getServer();
        if (server == null) return;
        ServerWorld w = server.getWorld(RegistryKey.of(RegistryKeys.WORLD, Identifier.of(s.dimensao)));
        if (w == null) w = server.getOverworld();
        p.teleport(w, s.x, s.y, s.z, s.yaw, s.pitch);
    }

    private static int definir(CommandContext<ServerCommandSource> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
        SpawnConfig.Ponto s = SpawnConfig.get().spawn;
        s.dimensao = Protecao.dim(p.getWorld());
        s.x = p.getX(); s.y = p.getY(); s.z = p.getZ();
        s.yaw = p.getYaw(); s.pitch = p.getPitch();
        SpawnConfig.save();
        if (p.getWorld() instanceof ServerWorld sw && sw == p.getServer().getOverworld()) {
            sw.setSpawnPos(p.getBlockPos(), p.getYaw());
        }
        c.getSource().sendFeedback(() -> Text.literal(String.format("§aSpawn definido em %.1f %.1f %.1f.", s.x, s.y, s.z)), true);
        return 1;
    }

    private static int adicionar(CommandContext<ServerCommandSource> c) {
        String nome = StringArgumentType.getString(c, "nome");
        BlockPos de = BlockPosArgumentType.getBlockPos(c, "de");
        BlockPos ate = BlockPosArgumentType.getBlockPos(c, "ate");
        SpawnConfig.get().areasProtegidas.removeIf(a -> a.nome.equalsIgnoreCase(nome));
        SpawnConfig.Area a = new SpawnConfig.Area();
        a.nome = nome;
        a.dimensao = Protecao.dim(c.getSource().getWorld());
        a.x1 = de.getX(); a.y1 = de.getY(); a.z1 = de.getZ();
        a.x2 = ate.getX(); a.y2 = ate.getY(); a.z2 = ate.getZ();
        SpawnConfig.get().areasProtegidas.add(a);
        SpawnConfig.save();
        c.getSource().sendFeedback(() -> Text.literal("§aÁrea protegida: " + a.descricao()), true);
        return 1;
    }

    private static int remover(CommandContext<ServerCommandSource> c) {
        String nome = StringArgumentType.getString(c, "nome");
        boolean ok = SpawnConfig.get().areasProtegidas.removeIf(a -> a.nome.equalsIgnoreCase(nome));
        if (ok) {
            SpawnConfig.save();
            c.getSource().sendFeedback(() -> Text.literal("§eÁrea " + nome + " removida."), true);
        } else {
            c.getSource().sendError(Text.literal("Área não encontrada."));
        }
        return ok ? 1 : 0;
    }

    private static int listar(CommandContext<ServerCommandSource> c) {
        StringBuilder sb = new StringBuilder("§6§lÁreas protegidas§r");
        for (SpawnConfig.Area a : SpawnConfig.get().areasProtegidas) sb.append("\n§e").append(a.descricao());
        if (SpawnConfig.get().areasProtegidas.isEmpty()) sb.append("\n§7nenhuma");
        c.getSource().sendFeedback(() -> Text.literal(sb.toString()), false);
        return 1;
    }
}
