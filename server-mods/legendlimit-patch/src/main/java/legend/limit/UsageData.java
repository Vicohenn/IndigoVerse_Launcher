package legend.limit;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class UsageData extends PersistentState {

    // jogador -> (id do item -> quantas vezes usou)
    private final Map<UUID, Map<String, Integer>> uses = new HashMap<>();
    // jogador -> (id do item -> usos extras comprados)
    private final Map<UUID, Map<String, Integer>> extras = new HashMap<>();

    public int getExtras(UUID player, String itemId) {
        return extras.getOrDefault(player, Map.of()).getOrDefault(itemId, 0);
    }

    public void addExtras(UUID player, String itemId, int qtd) {
        extras.computeIfAbsent(player, k -> new HashMap<>()).merge(itemId, qtd, Integer::sum);
        markDirty();
    }

    public int getUses(UUID player, String itemId) {
        return uses.getOrDefault(player, Map.of()).getOrDefault(itemId, 0);
    }

    public void addUse(UUID player, String itemId) {
        uses.computeIfAbsent(player, k -> new HashMap<>()).merge(itemId, 1, Integer::sum);
        markDirty();
    }

    /** Zera todas as contagens do jogador. */
    public void reset(UUID player) {
        uses.remove(player);
        extras.remove(player);
        markDirty();
    }

    /** Zera só a contagem de um item do jogador. */
    public void reset(UUID player, String itemId) {
        Map<String, Integer> map = uses.get(player);
        if (map != null) map.remove(itemId);
        Map<String, Integer> ex = extras.get(player);
        if (ex != null) ex.remove(itemId);
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
        NbtCompound players = new NbtCompound();
        for (Map.Entry<UUID, Map<String, Integer>> p : uses.entrySet()) {
            NbtCompound items = new NbtCompound();
            for (Map.Entry<String, Integer> i : p.getValue().entrySet()) {
                items.putInt(i.getKey(), i.getValue());
            }
            players.put(p.getKey().toString(), items);
        }
        nbt.put("players", players);
        nbt.put("extras", salvar(extras));
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

    public static UsageData fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        UsageData data = new UsageData();
        NbtCompound players = nbt.getCompound("players");
        for (String uuid : players.getKeys()) {
            NbtCompound items = players.getCompound(uuid);
            Map<String, Integer> map = new HashMap<>();
            for (String itemId : items.getKeys()) {
                map.put(itemId, items.getInt(itemId));
            }
            data.uses.put(UUID.fromString(uuid), map);
        }
        NbtCompound ex = nbt.getCompound("extras");
        for (String uuid : ex.getKeys()) {
            NbtCompound items = ex.getCompound(uuid);
            Map<String, Integer> map = new HashMap<>();
            for (String itemId : items.getKeys()) map.put(itemId, items.getInt(itemId));
            data.extras.put(UUID.fromString(uuid), map);
        }
        return data;
    }
}