package indigo.guildtags;

import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Descobre a qual categoria um item pertence (ids, #tags e curingas do config). */
public final class Itens {
    private record Regra(String id, TagKey<net.minecraft.item.Item> tag, Pattern curinga) {}

    private static Map<String, List<Regra>> regras = new LinkedHashMap<>();

    private Itens() {}

    public static void recarregar() {
        Map<String, List<Regra>> novo = new LinkedHashMap<>();
        TagsConfig.get().categorias.forEach((cat, def) -> {
            List<Regra> lista = new ArrayList<>();
            for (String s : def.itens) {
                try {
                    if (s.startsWith("#")) {
                        lista.add(new Regra(null, TagKey.of(RegistryKeys.ITEM, Identifier.of(s.substring(1))), null));
                    } else if (s.contains("*")) {
                        lista.add(new Regra(null, null, Pattern.compile(Pattern.quote(s).replace("*", "\\E.*\\Q"))));
                    } else {
                        lista.add(new Regra(s, null, null));
                    }
                } catch (Exception e) {
                    GuildTagsMod.LOGGER.warn("Item inválido em {}: {}", cat, s);
                }
            }
            novo.put(cat, lista);
        });
        regras = novo;
    }

    /** Primeira categoria do tipo pedido ("craft" ou "plantio") que contém o item, ou null. */
    public static String categoriaDe(ItemStack stack, String tipo) {
        if (stack == null || stack.isEmpty()) return null;
        String id = Registries.ITEM.getId(stack.getItem()).toString();
        for (Map.Entry<String, List<Regra>> e : regras.entrySet()) {
            TagsConfig.Categoria def = TagsConfig.get().categorias.get(e.getKey());
            if (def == null || !tipo.equals(def.tipo)) continue;
            for (Regra r : e.getValue()) {
                if (r.id != null && r.id.equals(id)) return e.getKey();
                if (r.tag != null && stack.isIn(r.tag)) return e.getKey();
                if (r.curinga != null && r.curinga.matcher(id).matches()) return e.getKey();
            }
        }
        return null;
    }
}
