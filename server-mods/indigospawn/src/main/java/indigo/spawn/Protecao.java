package indigo.spawn;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.BucketItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class Protecao {
    private static final Map<UUID, Long> ultimoAviso = new HashMap<>();

    private Protecao() {}

    public static String dim(World w) { return w.getRegistryKey().getValue().toString(); }

    public static SpawnConfig.Area areaEm(World w, BlockPos p) {
        String d = dim(w);
        for (SpawnConfig.Area a : SpawnConfig.get().areasProtegidas) if (a.contem(d, p)) return a;
        return null;
    }

    public static boolean liberado(PlayerEntity p) {
        return SpawnConfig.get().opsIgnoramProtecao && p.hasPermissionLevel(2);
    }

    /** Itens que colocam algo no mundo ao clicar num bloco. */
    public static boolean colocaAlgo(ItemStack st) {
        if (st.isEmpty()) return false;
        if (st.getItem() instanceof BlockItem || st.getItem() instanceof BucketItem) return true;
        String id = Registries.ITEM.getId(st.getItem()).getPath();
        return id.endsWith("spawn_egg") || id.equals("armor_stand") || id.equals("item_frame") || id.equals("glow_item_frame")
                || id.equals("painting") || id.endsWith("minecart") || id.endsWith("boat") || id.endsWith("_raft")
                || id.equals("flint_and_steel") || id.equals("fire_charge") || id.equals("end_crystal")
                || id.equals("bone_meal") || id.endsWith("_hoe") || id.endsWith("_shovel") || id.endsWith("_axe");
    }

    /** Entidades "decorativas" que contam como construção (molduras, suportes de armadura, quadros). */
    public static boolean decorativa(Entity e) {
        return e instanceof ArmorStandEntity || !(e instanceof LivingEntity) && !(e instanceof PlayerEntity)
                && Registries.ENTITY_TYPE.getId(e.getType()).getPath().matches("item_frame|glow_item_frame|painting|leash_knot|.*minecart|.*boat|.*raft");
    }

    public static void avisar(PlayerEntity p) {
        if (!(p instanceof ServerPlayerEntity sp)) return;
        long agora = System.currentTimeMillis();
        Long antes = ultimoAviso.get(sp.getUuid());
        if (antes != null && agora - antes < 1500) return;
        ultimoAviso.put(sp.getUuid(), agora);
        sp.sendMessage(Text.literal("§cEsta área é protegida."), true);
    }
}
