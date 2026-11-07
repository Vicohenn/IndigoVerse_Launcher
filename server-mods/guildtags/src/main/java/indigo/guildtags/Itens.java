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
    /** categoria -> itens extras de craft (itensCraft) */
    private static Map<String, List<Regra>> regrasCraft = new LinkedHashMap<>();
    private record RegraBloco(String id, TagKey<net.minecraft.block.Block> tag) {}
    private static Map<String, List<RegraBloco>> regrasColheita = new LinkedHashMap<>();

    private Itens() {}

    private static List<Regra> compilar(String cat, List<String> itens) {
        List<Regra> lista = new ArrayList<>();
        if (itens == null) return lista;
        for (String s : itens) {
            try {
                if (s.startsWith("#")) lista.add(new Regra(null, TagKey.of(RegistryKeys.ITEM, Identifier.of(s.substring(1))), null));
                else if (s.contains("*")) lista.add(new Regra(null, null, Pattern.compile(Pattern.quote(s).replace("*", "\\E.*\\Q"))));
                else lista.add(new Regra(s, null, null));
            } catch (Exception e) {
                GuildTagsMod.LOGGER.warn("Item inválido em {}: {}", cat, s);
            }
        }
        return lista;
    }

    public static void recarregar() {
        Map<String, List<Regra>> craft = new LinkedHashMap<>();
        Map<String, List<RegraBloco>> colheita = new LinkedHashMap<>();
        TagsConfig.get().categorias.forEach((cat, def) -> {
            craft.put(cat, compilar(cat, def.itensCraft));
            List<RegraBloco> bl = new ArrayList<>();
            if (def.blocosColheita != null) for (String s : def.blocosColheita) {
                try {
                    if (s.startsWith("#")) bl.add(new RegraBloco(null, TagKey.of(RegistryKeys.BLOCK, Identifier.of(s.substring(1)))));
                    else bl.add(new RegraBloco(s, null));
                } catch (Exception e) {
                    GuildTagsMod.LOGGER.warn("Bloco inválido em {}: {}", cat, s);
                }
            }
            colheita.put(cat, bl);
        });
        regrasCraft = craft;
        regrasColheita = colheita;
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

    private static boolean bate(List<Regra> lista, ItemStack stack, String id) {
        for (Regra r : lista) {
            if (r.id != null && r.id.equals(id)) return true;
            if (r.tag != null && stack.isIn(r.tag)) return true;
            if (r.curinga != null && r.curinga.matcher(id).matches()) return true;
        }
        return false;
    }

    /** Categoria cujos blocosColheita contêm este bloco, ou null. */
    public static String categoriaDoBloco(net.minecraft.block.BlockState state) {
        String id = Registries.BLOCK.getId(state.getBlock()).toString();
        for (Map.Entry<String, List<RegraBloco>> e : regrasColheita.entrySet()) {
            for (RegraBloco r : e.getValue()) {
                if (r.id != null && r.id.equals(id)) return e.getKey();
                if (r.tag != null && state.isIn(r.tag)) return e.getKey();
            }
        }
        return null;
    }

    /** Primeira categoria do tipo pedido ("craft" ou "plantio") que contém o item, ou null. */
    public static String categoriaDe(ItemStack stack, String tipo) {
        if (stack == null || stack.isEmpty()) return null;
        String id = Registries.ITEM.getId(stack.getItem()).toString();
        if ("craft".equals(tipo)) {
            for (Map.Entry<String, List<Regra>> e : regrasCraft.entrySet()) if (bate(e.getValue(), stack, id)) return e.getKey();
        }
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
