package indigo.coins;

import com.google.gson.reflect.TypeToken;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypedActionResult;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Amulet Coin do IndigoVerse: item da loja (Amulet Coin do Cobblemon com uma marca).
 * Clique direito: consome e dá 1 hora (de jogo online) de dinheiro de batalha em dobro.
 * Usar outra soma mais 1 hora, até o limite configurado.
 */
public final class Amuleto {
    static final String MARCA = "indigo_amuleto";
    /** uuid -> milissegundos restantes (só conta enquanto o jogador está online) */
    static Map<String, Long> ativos = new LinkedHashMap<>();
    private static boolean sujo;

    private Amuleto() {}

    static void carregar() {
        try {
            Map<String, Long> m = Dados.lerPublico("amuletos.json", new TypeToken<LinkedHashMap<String, Long>>() {}.getType());
            ativos = m != null ? m : new LinkedHashMap<>();
        } catch (Exception e) {
            IndigoCoinsMod.LOGGER.error("Erro lendo amuletos.json", e);
        }
    }

    static void salvar() { Dados.salvar("amuletos.json", ativos); sujo = false; }

    // ---------------- efeito (chamado pelo mixin) ----------------

    public static BigInteger aplicar(PlayerEntity p, BigInteger valor) {
        try {
            if (p == null || valor == null || valor.signum() <= 0) return valor;
            Long resta = ativos.get(p.getUuidAsString());
            if (resta == null || resta <= 0) return valor;
            return valor.multiply(BigInteger.valueOf(Math.max(1, Dados.config.amuletoMultiplicador)));
        } catch (Exception e) {
            return valor;
        }
    }

    // ---------------- item ----------------

    private static Item base() {
        Item i = Registries.ITEM.get(Identifier.of(Dados.config.amuletoItemBase));
        return i == Items.AIR ? Items.GOLD_NUGGET : i;
    }

    static ItemStack criarItem(int qtd) {
        ItemStack st = new ItemStack(base(), qtd);
        st.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Amulet Coin (1 hora)").styled(s -> s.withItalic(false).withColor(Formatting.GOLD)));
        st.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal("Clique com o botão direito para ativar.").styled(s -> s.withItalic(false).withColor(Formatting.GRAY)),
                Text.literal("Dinheiro de batalhas em dobro por 1 hora").styled(s -> s.withItalic(false).withColor(Formatting.AQUA)),
                Text.literal("(o tempo só corre enquanto você está online)").styled(s -> s.withItalic(false).withColor(Formatting.DARK_GRAY)))));
        NbtCompound tag = new NbtCompound();
        tag.putBoolean(MARCA, true);
        st.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
        st.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        st.set(DataComponentTypes.MAX_STACK_SIZE, 16);
        return st;
    }

    static boolean ehAmuleto(ItemStack st) {
        if (st.isEmpty()) return false;
        NbtComponent c = st.get(DataComponentTypes.CUSTOM_DATA);
        return c != null && c.copyNbt().getBoolean(MARCA);
    }

    static void dar(ServerPlayerEntity p, int qtd) {
        while (qtd > 0) {
            int n = Math.min(16, qtd);
            ItemStack st = criarItem(n);
            if (!p.getInventory().insertStack(st)) p.dropItem(st, false);
            qtd -= n;
        }
    }

    /** Clique direito com o amuleto. */
    static TypedActionResult<ItemStack> usar(ServerPlayerEntity p, Hand hand) {
        ItemStack st = p.getStackInHand(hand);
        if (!ehAmuleto(st)) return TypedActionResult.pass(st);
        long hora = 3_600_000L;
        long atual = Math.max(0, ativos.getOrDefault(p.getUuidAsString(), 0L));
        long max = Math.max(1, Dados.config.amuletoMaxHorasAcumuladas) * hora;
        if (atual + hora > max) {
            p.sendMessage(Text.literal("§eVocê já tem " + tempo(atual) + " de Amulet Coin. O máximo acumulado é "
                    + Dados.config.amuletoMaxHorasAcumuladas + "h."), true);
            return TypedActionResult.fail(st);
        }
        st.decrement(1);
        ativos.put(p.getUuidAsString(), atual + hora);
        salvar();
        Dados.log("AMULETO ativado por " + p.getGameProfile().getName() + " (restante " + tempo(atual + hora) + ")");
        p.sendMessage(Text.literal("§6✦ Amulet Coin ativada! §eDinheiro de batalhas em dobro por §f" + tempo(atual + hora) + "§e."));
        return TypedActionResult.success(st);
    }

    /** A cada segundo: desconta o tempo de quem está online e avisa quando acaba. */
    static void tick(MinecraftServer server) {
        if (ativos.isEmpty()) return;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            String u = p.getUuidAsString();
            Long resta = ativos.get(u);
            if (resta == null) continue;
            long novo = resta - 1000;
            if (novo <= 0) {
                ativos.remove(u);
                p.sendMessage(Text.literal("§7Sua Amulet Coin acabou. O dinheiro de batalhas voltou ao normal."));
            } else {
                if (resta > 300_000 && novo <= 300_000) p.sendMessage(Text.literal("§eSua Amulet Coin acaba em 5 minutos."));
                ativos.put(u, novo);
            }
            sujo = true;
        }
    }

    /** Salva a cada minuto (e ao desligar), para não gravar o arquivo todo segundo. */
    static void salvarSeMudou() { if (sujo) salvar(); }

    static String tempo(long ms) {
        long min = Math.max(0, ms) / 60_000;
        return (min / 60) + "h" + String.format("%02d", min % 60);
    }
}
