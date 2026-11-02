package indigo.log;

import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Monta o texto do time do jogador para o log de batalhas:
 *
 *   📋 Time de Nick
 *   **Charizard** ✨ Nv. 50 · Adamant · Blaze · Item: Charizardite X · Tera: Fire
 *   IVs 31/31/31/31/31/31 · EVs 252/0/4/0/0/252 · Golpes: Flare Blitz, Dragon Claw, Earthquake, Roost
 *
 * Tudo por reflexão (o mod compila sem o Cobblemon no classpath).
 */
final class TimeFormatter {
    private static final String[] STATS = {"HP", "ATTACK", "DEFENCE", "SPECIAL_ATTACK", "SPECIAL_DEFENCE", "SPEED"};

    private TimeFormatter() {}

    static String time(String nick, List<?> battlePokemons) {
        StringBuilder sb = new StringBuilder("📋 **Time de ").append(nick).append("** (IVs/EVs: HP/Atk/Def/SpA/SpD/Spe)");
        for (Object bp : battlePokemons) {
            try {
                Object p = call(bp, "getOriginalPokemon");
                sb.append("\n").append(pokemon(p));
            } catch (Throwable t) {
                sb.append("\n• (não consegui ler este Pokémon)");
            }
        }
        return sb.toString();
    }

    private static String pokemon(Object p) {
        List<String> l1 = new ArrayList<>();
        String nome = "**" + especie(p) + "**";
        if (Boolean.TRUE.equals(tentar(() -> call(p, "getShiny")))) nome += " ✨";
        l1.add(nome + " Nv. " + tentar(() -> call(p, "getLevel")));
        l1.add(tentarTexto(() -> natureza(p)));
        l1.add(tentarTexto(() -> bonito(String.valueOf(call(call(p, "getAbility"), "getName")))));
        String item = tentarTexto(() -> {
            ItemStack st = (ItemStack) call(p, "heldItem");
            return st == null || st.isEmpty() ? null : st.getName().getString();
        });
        if (item != null) l1.add("Item: " + item);
        String tera = tentarTexto(() -> bonito(String.valueOf(call(call(p, "getTeraType"), "getName"))));
        if (tera != null) l1.add("Tera: " + tera);

        List<String> l2 = new ArrayList<>();
        l2.add("IVs " + stats(tentar(() -> call(p, "getIvs"))));
        l2.add("EVs " + stats(tentar(() -> call(p, "getEvs"))));
        List<String> golpes = new ArrayList<>();
        Object moveSet = tentar(() -> call(p, "getMoveSet"));
        if (moveSet != null) {
            Object lista = tentar(() -> call(moveSet, "getMoves"));
            if (lista instanceof List<?> ms) {
                for (Object m : ms) {
                    String g = tentarTexto(() -> ((Text) call(m, "getDisplayName")).getString());
                    if (g == null || g.contains(".")) g = tentarTexto(() -> bonito(String.valueOf(call(m, "getName"))));
                    if (g != null) golpes.add(g);
                }
            }
        }
        l2.add("Golpes: " + (golpes.isEmpty() ? "?" : String.join(", ", golpes)));

        l1.removeIf(x -> x == null);
        return "• " + String.join(" · ", l1) + "\n  " + String.join(" · ", l2);
    }

    static String especie(Object pokemon) {
        String n = tentarTexto(() -> ((Text) call(call(pokemon, "getSpecies"), "getTranslatedName")).getString());
        if (n == null || n.contains(".")) n = tentarTexto(() -> bonito(String.valueOf(call(call(pokemon, "getSpecies"), "getName"))));
        return n == null ? "?" : n;
    }

    private static String natureza(Object p) throws Exception {
        Object nat = null;
        try { nat = call(p, "getEffectiveNature"); } catch (Throwable ignored) { }
        if (nat == null) nat = call(p, "getNature");
        Object id = call(nat, "getName");   // Identifier cobblemon:adamant
        String path = String.valueOf(id);
        if (path.contains(":")) path = path.substring(path.indexOf(':') + 1);
        String txt = bonito(path);
        Object minted = null;
        try { minted = call(p, "getMintedNature"); } catch (Throwable ignored) { }
        return minted != null ? txt + " (menta)" : txt;
    }

    private static String stats(Object pokemonStats) {
        if (pokemonStats == null) return "?";
        try {
            Class<?> statsEnum = Class.forName("com.cobblemon.mod.common.api.pokemon.stats.Stats");
            Class<?> statIf = Class.forName("com.cobblemon.mod.common.api.pokemon.stats.Stat");
            Method get = pokemonStats.getClass().getMethod("getOrDefault", statIf);
            List<String> v = new ArrayList<>();
            for (String s : STATS) v.add(String.valueOf(get.invoke(pokemonStats, statsEnum.getField(s).get(null))));
            return String.join("/", v);
        } catch (Throwable t) {
            return "?";
        }
    }

    /** "flare_blitz" / "flareblitz" / "adamant" -> "Flare Blitz" / "Flareblitz" / "Adamant" */
    static String bonito(String id) {
        if (id == null || id.isBlank() || id.equals("null")) return null;
        String[] partes = id.replace('-', '_').split("_");
        StringBuilder sb = new StringBuilder();
        for (String pt : partes) {
            if (pt.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(pt.substring(0, 1).toUpperCase(Locale.ROOT)).append(pt.substring(1));
        }
        return sb.toString();
    }

    private interface Acao { Object run() throws Exception; }

    private static Object tentar(Acao a) {
        try { return a.run(); } catch (Throwable t) { return null; }
    }

    private static String tentarTexto(Acao a) {
        Object o = tentar(a);
        return o == null ? null : String.valueOf(o);
    }

    private static Object call(Object alvo, String metodo) throws Exception {
        return alvo.getClass().getMethod(metodo).invoke(alvo);
    }
}
