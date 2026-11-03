package legend.limit;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Por jogador e por item de invocação:
 *   uses         tentativas usadas na rodada atual
 *   adicionados  quantas rodadas extras foram liberadas pelo /adicionar (máx. 2)
 *   limiteRodada tentativas da rodada atual liberadas pelo /adicionar (0 = padrão do config)
 *   capturado    1 se o jogador capturou o lendário durante a rodada atual
 */
public class UsageData extends PersistentState {

    private final Map<UUID, Map<String, Integer>> uses = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> adicionados = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> limiteRodada = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> capturado = new HashMap<>();

    private static int ler(Map<UUID, Map<String, Integer>> m, UUID p, String item) {
        return m.getOrDefault(p, Map.of()).getOrDefault(item, 0);
    }

    private void gravar(Map<UUID, Map<String, Integer>> m, UUID p, String item, int v) {
        m.computeIfAbsent(p, k -> new HashMap<>()).put(item, v);
        markDirty();
    }

    public int getUses(UUID player, String itemId) { return ler(uses, player, itemId); }

    public void addUse(UUID player, String itemId) { gravar(uses, player, itemId, getUses(player, itemId) + 1); }

    public int getAdicionados(UUID player, String itemId) { return ler(adicionados, player, itemId); }

    public int getLimiteRodada(UUID player, String itemId) { return ler(limiteRodada, player, itemId); }

    public boolean isCapturado(UUID player, String itemId) { return ler(capturado, player, itemId) == 1; }

    public void setCapturado(UUID player, String itemId) { gravar(capturado, player, itemId, 1); }

    /** Começa uma rodada nova liberada pelo /adicionar. */
    public void novaRodada(UUID player, String itemId, int tentativas) {
        gravar(adicionados, player, itemId, getAdicionados(player, itemId) + 1);
        gravar(limiteRodada, player, itemId, tentativas);
        gravar(uses, player, itemId, 0);
        gravar(capturado, player, itemId, 0);
    }

    /** Zera todas as contagens do jogador. */
    public void reset(UUID player) {
        uses.remove(player);
        adicionados.remove(player);
        limiteRodada.remove(player);
        capturado.remove(player);
        markDirty();
    }

    /** Zera só a contagem de um item do jogador. */
    public void reset(UUID player, String itemId) {
        for (Map<UUID, Map<String, Integer>> m : java.util.List.of(uses, adicionados, limiteRodada, capturado)) {
            Map<String, Integer> map = m.get(player);
            if (map != null) map.remove(itemId);
        }
        markDirty();
    }

    // ---------- salvar / carregar ----------

    private static final String KEY = "legendlimit_data";

    private static final PersistentState.Type<UsageData> TYPE =
            new PersistentState.Type<>(UsageData::new, UsageData::fromNbt, null);

    public static UsageData get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE, KEY);
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        nbt.put("players", salvar(uses));            // mesmo nome de antes: mantém as contagens já salvas
        nbt.put("adicionados", salvar(adicionados));
        nbt.put("limiteRodada", salvar(limiteRodada));
        nbt.put("capturado", salvar(capturado));
        return nbt;
    }

    private static NbtCompound salvar(Map<UUID, Map<String, Integer>> mapa) {
        NbtCompound players = new NbtCompound();
        for (Map.Entry<UUID, Map<String, Integer>> p : mapa.entrySet()) {
            NbtCompound items = new NbtCompound();
            for (Map.Entry<String, Integer> i : p.getValue().entrySet()) items.putInt(i.getKey(), i.getValue());
            players.put(p.getKey().toString(), items);
        }
        return players;
    }

    private static void carregar(NbtCompound tag, Map<UUID, Map<String, Integer>> destino) {
        for (String uuid : tag.getKeys()) {
            NbtCompound items = tag.getCompound(uuid);
            Map<String, Integer> map = new HashMap<>();
            for (String itemId : items.getKeys()) map.put(itemId, items.getInt(itemId));
            destino.put(UUID.fromString(uuid), map);
        }
    }

    public static UsageData fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        UsageData data = new UsageData();
        carregar(nbt.getCompound("players"), data.uses);
        carregar(nbt.getCompound("adicionados"), data.adicionados);
        carregar(nbt.getCompound("limiteRodada"), data.limiteRodada);
        carregar(nbt.getCompound("capturado"), data.capturado);
        return data;
    }
}
