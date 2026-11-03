package legend.limit;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

public class UsageGuard {

    /** Uma conferência aguardando: o item sumiu do inventário nos próximos ticks? */
    private static class Pending {
        final UUID player;
        final String itemId;
        final Item item;
        final int countBefore;
        int ticksLeft = 20; // 1 segundo

        Pending(UUID player, String itemId, Item item, int countBefore) {
            this.player = player;
            this.itemId = itemId;
            this.item = item;
            this.countBefore = countBefore;
        }
    }

    private static final List<Pending> PENDING = new ArrayList<>();

    public static void register() {
        UseBlockCallback.EVENT.register(UsageGuard::onUseBlock);
        ServerTickEvents.END_SERVER_TICK.register(UsageGuard::checkPending);
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            String blockId = Registries.BLOCK.getId(state.getBlock()).toString();

            // 1. Verifica se o bloco alvo é um Lootr ou um Altar protegido
            if (blockId.startsWith("lootr:") || LimitConfig.get().altarBlocks.contains(blockId)) {

                // 2. Permite a quebra APENAS se o jogador for OP e estiver no Criativo
                if (player.isCreative() && player.hasPermissionLevel(2)) {
                    return true; // OP no criativo quebra normalmente
                }

                // 3. Qualquer outro caso (sobrevivência, ou criativo sem OP) é bloqueado
                player.sendMessage(Text.literal("§cVocê não tem permissão para destruir estruturas de lendários!"), true);
                return false; // Cancela a destruição do bloco
            }

            // Permite que outros blocos comuns do mundo sejam quebrados
            return true;
        });
    }

    private static ActionResult onUseBlock(PlayerEntity player, World world, Hand hand, BlockHitResult hit) {
        if (world.isClient() || !(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;

        ItemStack stack = player.getStackInHand(hand);
        if (stack.isEmpty()) return ActionResult.PASS;

        String itemId = Registries.ITEM.getId(stack.getItem()).toString();
        if (!LimitConfig.get().limits.containsKey(itemId)) return ActionResult.PASS;

        String blockId = Registries.BLOCK.getId(world.getBlockState(hit.getBlockPos()).getBlock()).toString();
        if (!LimitConfig.get().isAltar(blockId)) return ActionResult.PASS;

        UsageData data = UsageData.get(sp.getServer());
        int used = data.getUses(player.getUuid(), itemId);
        Integer limit = Limites.limite(player.getUuid(), itemId, data);
        if (limit == null) return ActionResult.PASS;

        if (used >= limit) {
            UUID id = player.getUuid();
            String motivo = Limites.capturouNaRodada(id, itemId, data)
                    ? "Você já capturou este lendário nesta rodada (" + used + "/" + limit + ")."
                    : "Suas " + limit + " tentativa(s) desta rodada acabaram (" + used + "/" + limit + ").";
            boolean podeMais = Limites.temEspecie(itemId) && data.getAdicionados(id, itemId) < LimitConfig.get().maxAdicionar;
            sp.sendMessage(Text.literal(motivo + (podeMais ? " Novas tentativas só por doação." : " Você já usou todas as rodadas deste lendário."))
                    .formatted(Formatting.RED), true);
            return ActionResult.FAIL;
        }

        // evita registrar duas conferências do mesmo jogador/item (mão principal + secundária)
        boolean alreadyPending = PENDING.stream()
                .anyMatch(p -> p.player.equals(player.getUuid()) && p.itemId.equals(itemId));
        if (!alreadyPending) {
            int before = player.getInventory().count(stack.getItem());
            PENDING.add(new Pending(player.getUuid(), itemId, stack.getItem(), before));
        }
        return ActionResult.PASS;
    }

    private static void checkPending(MinecraftServer server) {
        if (PENDING.isEmpty()) return;

        UsageData data = UsageData.get(server);
        Iterator<Pending> it = PENDING.iterator();
        while (it.hasNext()) {
            Pending p = it.next();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(p.player);
            if (player == null) { it.remove(); continue; }

            if (player.getInventory().count(p.item) < p.countBefore) {
                // o item foi consumido: a invocação aconteceu
                data.addUse(p.player, p.itemId);
                int used = data.getUses(p.player, p.itemId);
                Integer limit = Limites.limite(p.player, p.itemId, data);
                if (limit != null) {
                    player.sendMessage(Text.literal("Invocações usadas: " + used + "/" + limit + Limites.descricao(p.player, p.itemId, data) + ".")
                            .formatted(Formatting.GOLD), false);
                }
                it.remove();
            } else if (--p.ticksLeft <= 0) {
                it.remove(); // nada foi consumido (clique em outra coisa)
            }
        }
    }
}