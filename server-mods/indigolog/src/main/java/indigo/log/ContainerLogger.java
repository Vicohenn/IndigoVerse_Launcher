package indigo.log;

import com.mojang.authlib.GameProfile;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.EnderChestInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Registra itens RETIRADOS de baús e outros containers.
 *
 * Funciona sem mixin: a cada tick olha qual tela cada jogador tem aberta.
 * Quando um jogador abre um container de bloco, guarda o que tinha dentro;
 * quando fecha (ou sai do servidor), compara e registra o que saiu.
 * Se o bloco está num claim do Flan, a mensagem diz de quem é o claim.
 */
public final class ContainerLogger {

    private record Sessao(ScreenHandler handler, BlockPos pos, String dimensao, String bloco,
                          Map<String, Integer> antes, Map<String, String> nomes, String claim, boolean claimDoProprio) {}

    private final Map<UUID, Sessao> abertas = new HashMap<>();

    public void tick(MinecraftServer server) {
        LogConfig cfg = LogConfig.get();
        if (!cfg.logBaus) return;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ScreenHandler atual = player.currentScreenHandler;
            Sessao s = abertas.get(player.getUuid());
            if (s != null && s.handler != atual) {          // fechou (ou trocou de tela)
                abertas.remove(player.getUuid());
                finalizar(player.getGameProfile().getName(), s);
                s = null;
            }
            if (s == null && atual != null && atual != player.playerScreenHandler) {
                Sessao nova = iniciar(server, player, atual, cfg);
                if (nova != null) abertas.put(player.getUuid(), nova);
            }
        }
    }

    public void onDisconnect(ServerPlayerEntity player) {
        Sessao s = abertas.remove(player.getUuid());
        if (s != null) finalizar(player.getGameProfile().getName(), s);
    }

    private Sessao iniciar(MinecraftServer server, ServerPlayerEntity player, ScreenHandler handler, LogConfig cfg) {
        // Só containers com slots que não são do próprio jogador (nada de inventário, mochila no corpo, baú do End etc.)
        boolean temContainer = false;
        for (Slot slot : handler.slots) {
            if (slot.inventory instanceof EnderChestInventory) return null;
            if (!(slot.inventory instanceof PlayerInventory)) temContainer = true;
        }
        if (!temContainer) return null;

        // Qual bloco o jogador abriu: o que ele está olhando
        HitResult hit = player.raycast(6.0, 0f, false);
        if (hit.getType() != HitResult.Type.BLOCK) return null;
        BlockPos pos = ((BlockHitResult) hit).getBlockPos();
        ServerWorld world = (ServerWorld) player.getWorld();
        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof Inventory)) return null;          // só containers de bloco (baú, barril, shulker, funil...)
        String bloco = Registries.BLOCK.getId(world.getBlockState(pos).getBlock()).toString();
        if (cfg.blocosIgnorados.contains(bloco)) return null;

        // Claim do Flan (se o mod estiver presente)
        String claim = null;
        boolean doProprio = false;
        UUID dono = donoDoClaim(world, pos);
        if (dono != null) {
            doProprio = dono.equals(player.getUuid());
            claim = nomeDoJogador(server, dono);
        }
        if (cfg.bausSomenteEmClaims && claim == null) return null;
        if (cfg.ignorarDonoDoClaim && doProprio) return null;

        Map<String, String> nomes = new HashMap<>();
        return new Sessao(handler, pos, world.getRegistryKey().getValue().toString(), bloco,
                contar(handler, nomes), nomes, claim, doProprio);
    }

    private void finalizar(String jogador, Sessao s) {
        Map<String, String> nomes = new HashMap<>(s.nomes);
        Map<String, Integer> depois = contar(s.handler, nomes);
        Map<String, Integer> saiu = new LinkedHashMap<>();
        s.antes.forEach((item, qtd) -> {
            int dif = qtd - depois.getOrDefault(item, 0);
            if (dif > 0) saiu.put(item, dif);
        });
        if (saiu.isEmpty()) return;

        StringBuilder itens = new StringBuilder();
        saiu.forEach((item, qtd) -> {
            if (itens.length() > 0) itens.append(", ");
            itens.append(qtd).append("x ").append(nomes.getOrDefault(item, item));
        });
        String onde = String.format("%s em %s (%d, %d, %d)", nomeBloco(s.bloco), s.dimensao.replace("minecraft:", ""),
                s.pos.getX(), s.pos.getY(), s.pos.getZ());
        String claim = s.claim == null ? "" : " — claim de **" + s.claim + "**";
        DiscordWebhook.BAUS.enviar("📦 `" + DiscordWebhook.agora() + "` **" + jogador + "** retirou de " + onde + claim + ": " + itens);
    }

    /** Soma os itens dos slots que não são do jogador. */
    private static Map<String, Integer> contar(ScreenHandler handler, Map<String, String> nomes) {
        Map<String, Integer> total = new HashMap<>();
        for (Slot slot : handler.slots) {
            if (slot.inventory instanceof PlayerInventory) continue;
            ItemStack st = slot.getStack();
            if (st.isEmpty()) continue;
            String id = Registries.ITEM.getId(st.getItem()).toString();
            total.merge(id, st.getCount(), Integer::sum);
            nomes.putIfAbsent(id, st.getName().getString());
        }
        return total;
    }

    private static String nomeBloco(String id) {
        String n = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        return n.replace('_', ' ');
    }

    // ---------- Flan (por reflexão, para não precisar dele para compilar) ----------
    private static Method storageGet, getClaimAt, getOwner;
    private static boolean flanAusente;

    private static UUID donoDoClaim(ServerWorld world, BlockPos pos) {
        if (flanAusente) return null;
        try {
            if (storageGet == null) {
                Class<?> storage = Class.forName("io.github.flemmli97.flan.claim.ClaimStorage");
                Class<?> claimCls = Class.forName("io.github.flemmli97.flan.claim.Claim");
                storageGet = storage.getMethod("get", ServerWorld.class);
                getClaimAt = storage.getMethod("getClaimAt", BlockPos.class);
                getOwner = claimCls.getMethod("getOwner");
            }
            Object st = storageGet.invoke(null, world);
            Object claim = getClaimAt.invoke(st, pos);
            return claim == null ? null : (UUID) getOwner.invoke(claim);
        } catch (ClassNotFoundException e) {
            flanAusente = true;
            return null;
        } catch (Exception e) {
            IndigoLogMod.LOGGER.debug("Flan: {}", e.toString());
            return null;
        }
    }

    private static String nomeDoJogador(MinecraftServer server, UUID uuid) {
        ServerPlayerEntity online = server.getPlayerManager().getPlayer(uuid);
        if (online != null) return online.getGameProfile().getName();
        try {
            Optional<GameProfile> p = server.getUserCache().getByUuid(uuid);
            if (p.isPresent()) return p.get().getName();
        } catch (Exception ignored) { }
        return uuid.toString().substring(0, 8);   // claim de admin ou jogador desconhecido
    }
}
