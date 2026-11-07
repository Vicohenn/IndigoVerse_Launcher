package indigo.coins;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * "Indigo Coin encontrada": item marcador que o datapack coloca no loot (ex. Pickup do Cobbleworkers).
 * Quando entra no inventário de um jogador, vira uma Indigo Coin registrada no nome dele.
 */
final class Achados {
    static final String CHAVE = "indigo_coin_achada";

    private Achados() {}

    static boolean ehAchado(ItemStack st) {
        if (st.isEmpty()) return false;
        NbtComponent c = st.get(DataComponentTypes.CUSTOM_DATA);
        return c != null && c.copyNbt().contains(CHAVE);
    }

    static void converter(MinecraftServer server, ServerPlayerEntity p) {
        PlayerInventory inv = p.getInventory();
        int total = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack st = inv.getStack(i);
            if (!ehAchado(st)) continue;
            total += st.getCount();
            inv.setStack(i, ItemStack.EMPTY);
        }
        if (p.currentScreenHandler != null && ehAchado(p.currentScreenHandler.getCursorStack())) {
            total += p.currentScreenHandler.getCursorStack().getCount();
            p.currentScreenHandler.setCursorStack(ItemStack.EMPTY);
        }
        for (int k = 0; k < total; k++) {
            String id = Moedas.criar(p.getUuid(), p.getGameProfile().getName(), "drop:pickup", null);
            p.sendMessage(Text.literal("§6§l✦ §eUm Pokémon com Pickup encontrou uma §bIndigo Coin§e! §7(nº " + id + ") §8/indigocoins"));
            server.getPlayerManager().broadcast(Text.literal("§6✦ §f" + p.getGameProfile().getName()
                    + " §6recebeu uma §bIndigo Coin§6 encontrada por um Pokémon com Pickup!"), false);
        }
    }
}
