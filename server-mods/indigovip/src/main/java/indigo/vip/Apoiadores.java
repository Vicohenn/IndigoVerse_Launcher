package indigo.vip;

import com.google.gson.reflect.TypeToken;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** config/indigovip/apoiadores.json — nível de cada jogador, guardado pelo nick. */
public final class Apoiadores {
    public static final class Registro {
        public String nick;
        public String nivel;
        /** Momento em que expira (epoch ms). 0 = permanente. */
        public long expiraEm;
        public long desde;
    }

    private static Map<String, Registro> dados = new LinkedHashMap<>();

    private Apoiadores() {}

    private static Path arquivo() { return VipConfig.pasta().resolve("apoiadores.json"); }

    public static void load() {
        try {
            if (Files.exists(arquivo())) {
                try (Reader r = Files.newBufferedReader(arquivo(), StandardCharsets.UTF_8)) {
                    Map<String, Registro> m = VipConfig.GSON.fromJson(r, new TypeToken<LinkedHashMap<String, Registro>>() {}.getType());
                    if (m != null) dados = m;
                }
            }
        } catch (Exception e) {
            IndigoVipMod.LOGGER.error("Erro lendo apoiadores.json: {}", e.toString());
        }
    }

    public static void save() {
        try {
            Files.createDirectories(VipConfig.pasta());
            Path tmp = arquivo().resolveSibling("apoiadores.json.tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                VipConfig.GSON.toJson(dados, w);
            }
            Files.move(tmp, arquivo(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            IndigoVipMod.LOGGER.error("Erro salvando apoiadores.json: {}", e.toString());
        }
    }

    private static String chave(String nick) { return nick.toLowerCase(Locale.ROOT); }

    public static Registro de(String nick) { return dados.get(chave(nick)); }

    public static Map<String, Registro> todos() { return dados; }

    public static void definir(String nick, String nivel, long expiraEm) {
        Registro r = dados.computeIfAbsent(chave(nick), k -> new Registro());
        r.nick = nick;
        if (!nivel.equals(r.nivel)) r.desde = System.currentTimeMillis();
        r.nivel = nivel;
        r.expiraEm = expiraEm;
        save();
    }

    public static Registro remover(String nick) {
        Registro r = dados.remove(chave(nick));
        if (r != null) save();
        return r;
    }

    public static boolean ativo(Registro r) {
        return r != null && (r.expiraEm == 0 || r.expiraEm > System.currentTimeMillis());
    }
}
