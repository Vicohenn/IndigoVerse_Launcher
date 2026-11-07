package indigo.vip;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageDecoratorEvent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.particle.SimpleParticleType;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Benefícios de conforto e visual por nível de apoio:
 *   /hat e /ec (Treinador), /workbench (Elite Four), cores no chat com & (Gym Leader),
 *   /particulas (Gym Leader), /seguidor (Elite Four), vagas e taxa do GTS por nível.
 */
public final class Conforto {
    private static final Gson GSON = new Gson();
    private static MinecraftServer server;
    private static final String TAG = "indigo_seguidor";

    static final Map<String, SimpleParticleType> PARTICULAS = new LinkedHashMap<>();
    static final Map<String, String> BOLAS = new LinkedHashMap<>();
    static {
        PARTICULAS.put("coracoes", ParticleTypes.HEART);
        PARTICULAS.put("notas", ParticleTypes.NOTE);
        PARTICULAS.put("chamas", ParticleTypes.FLAME);
        PARTICULAS.put("almas", ParticleTypes.SOUL_FIRE_FLAME);
        PARTICULAS.put("estrelas", ParticleTypes.END_ROD);
        PARTICULAS.put("folhas", ParticleTypes.CHERRY_LEAVES);
        PARTICULAS.put("brilhos", ParticleTypes.HAPPY_VILLAGER);
        PARTICULAS.put("magia", ParticleTypes.WITCH);
        PARTICULAS.put("neve", ParticleTypes.SNOWFLAKE);
        PARTICULAS.put("encantamento", ParticleTypes.ENCHANT);
        BOLAS.put("pokebola", "cobblemon:poke_ball");
        BOLAS.put("greatball", "cobblemon:great_ball");
        BOLAS.put("ultraball", "cobblemon:ultra_ball");
        BOLAS.put("masterball", "cobblemon:master_ball");
        BOLAS.put("premierball", "cobblemon:premier_ball");
        BOLAS.put("luxuryball", "cobblemon:luxury_ball");
        BOLAS.put("healball", "cobblemon:heal_ball");
    }

    /** uuid -> partícula escolhida; uuid -> bola do seguidor. */
    private static Map<String, String> particulas = new HashMap<>();
    private static Map<String, String> seguidores = new HashMap<>();
    private static long ticks;

    private Conforto() {}

    private static Path arquivo(String n) { return VipConfig.pasta().resolve(n); }

    private static Map<String, String> ler(String n) {
        try {
            Path p = arquivo(n);
            if (Files.exists(p)) {
                Map<String, String> m = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), new TypeToken<Map<String, String>>() {}.getType());
                if (m != null) return new HashMap<>(m);
            }
        } catch (Exception e) {
            IndigoVipMod.LOGGER.error("Erro lendo {}: {}", n, e.toString());
        }
        return new HashMap<>();
    }

    private static void gravar(String n, Map<String, String> m) {
        try { Files.writeString(arquivo(n), GSON.toJson(m), StandardCharsets.UTF_8); }
        catch (Exception e) { IndigoVipMod.LOGGER.error("Erro gravando {}: {}", n, e.toString()); }
    }

    public static void iniciar() {
        particulas = ler("particulas.json");
        seguidores = ler("seguidores.json");
        ServerMessageDecoratorEvent.EVENT.register(ServerMessageDecoratorEvent.CONTENT_PHASE, (sender, msg) -> {
            if (sender == null || !tem(sender, VipConfig.get().nivelCoresChat)) return msg;
            String s = msg.getString();
            if (s.indexOf('&') < 0) return msg;
            return Text.literal(cores(s));
        });
        // seguidor que ficou no mundo sem dono (queda do servidor etc.) some ao carregar
        ServerEntityEvents.ENTITY_LOAD.register((e, world) -> {
            if (e.getCommandTags().contains(TAG) && !(e.getVehicle() instanceof PlayerEntity)) e.discard();
        });
    }

    public static void aoIniciarServidor(MinecraftServer s) { server = s; }

    /** &0-9 a-f l m n o r viram cores; &k (embaralhado) não. */
    static String cores(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '&' && i + 1 < s.length() && "0123456789abcdeflmnorABCDEFLMNOR".indexOf(s.charAt(i + 1)) >= 0) {
                b.append('§').append(Character.toLowerCase(s.charAt(++i)));
            } else b.append(ch);
        }
        return b.toString();
    }

    static boolean tem(ServerPlayerEntity p, String nivelMin) {
        return p.hasPermissionLevel(2) || Beneficios.temBeneficio(p.getGameProfile().getName(), nivelMin);
    }

    private static String nomeNivel(String id) {
        VipConfig.Nivel n = VipConfig.get().nivel(id);
        return n == null ? id : n.nome;
    }

    private static int semNivel(CommandContext<ServerCommandSource> c, String nivel) {
        c.getSource().sendError(Text.literal("Benefício do apoio " + nomeNivel(nivel) + " ou acima. Veja /vip."));
        return 0;
    }

    // ---------------- comandos ----------------

    public static void registrar(CommandDispatcher<ServerCommandSource> d) {
        d.register(literal("hat").executes(c -> {
            ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
            if (!tem(p, VipConfig.get().nivelHatEc)) return semNivel(c, VipConfig.get().nivelHatEc);
            ItemStack mao = p.getMainHandStack();
            if (mao.isEmpty()) { c.getSource().sendError(Text.literal("Segure o item que quer colocar na cabeça.")); return 0; }
            ItemStack cabeca = p.getEquippedStack(EquipmentSlot.HEAD);
            ItemStack um = mao.split(1);
            p.equipStack(EquipmentSlot.HEAD, um);
            if (!cabeca.isEmpty() && !p.getInventory().insertStack(cabeca)) p.dropItem(cabeca, false);
            c.getSource().sendFeedback(() -> Text.literal("§aChapéu novo!"), false);
            return 1;
        }));
        d.register(literal("ec").executes(Conforto::ec));
        d.register(literal("enderchest").executes(Conforto::ec));
        d.register(literal("workbench").executes(Conforto::workbench));
        d.register(literal("bancada").executes(Conforto::workbench));
        var part = literal("particulas").executes(c -> {
            c.getSource().sendFeedback(() -> Text.literal("§6Partículas: §f" + String.join(", ", PARTICULAS.keySet())
                    + "§7. Use §e/particulas <tipo>§7 ou §e/particulas desligar§7."), false);
            return 1;
        }).then(literal("desligar").executes(c -> {
            particulas.remove(c.getSource().getPlayerOrThrow().getUuidAsString());
            gravar("particulas.json", particulas);
            c.getSource().sendFeedback(() -> Text.literal("§7Partículas desligadas."), false);
            return 1;
        }));
        for (String tipo : PARTICULAS.keySet())
            part.then(literal(tipo).executes(c -> {
                ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
                if (!tem(p, VipConfig.get().nivelParticulas)) return semNivel(c, VipConfig.get().nivelParticulas);
                particulas.put(p.getUuidAsString(), tipo);
                gravar("particulas.json", particulas);
                c.getSource().sendFeedback(() -> Text.literal("§aPartículas: §f" + tipo), false);
                return 1;
            }));
        d.register(part);
        var seg = literal("seguidor").executes(c -> {
            c.getSource().sendFeedback(() -> Text.literal("§6Seguidor: §f" + String.join(", ", BOLAS.keySet())
                    + "§7. Use §e/seguidor <bola>§7 ou §e/seguidor desligar§7."), false);
            return 1;
        }).then(literal("desligar").executes(c -> {
            ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
            seguidores.remove(p.getUuidAsString());
            gravar("seguidores.json", seguidores);
            removerSeguidor(p);
            c.getSource().sendFeedback(() -> Text.literal("§7Seguidor guardado."), false);
            return 1;
        }));
        for (String bola : BOLAS.keySet())
            seg.then(literal(bola).executes(c -> {
                ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
                if (!tem(p, VipConfig.get().nivelSeguidor)) return semNivel(c, VipConfig.get().nivelSeguidor);
                seguidores.put(p.getUuidAsString(), bola);
                gravar("seguidores.json", seguidores);
                removerSeguidor(p);
                criarSeguidor(p);
                c.getSource().sendFeedback(() -> Text.literal("§aSeu seguidor agora é: §f" + bola), false);
                return 1;
            }));
        d.register(seg);
    }

    private static int ec(CommandContext<ServerCommandSource> c) throws CommandSyntaxException {
        ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
        if (!tem(p, VipConfig.get().nivelHatEc)) return semNivel(c, VipConfig.get().nivelHatEc);
        p.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (id, inv, pl) -> GenericContainerScreenHandler.createGeneric9x3(id, inv, p.getEnderChestInventory()),
                Text.translatable("container.enderchest")));
        return 1;
    }

    private static int workbench(CommandContext<ServerCommandSource> c) throws CommandSyntaxException {
        ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
        if (!tem(p, VipConfig.get().nivelWorkbench)) return semNivel(c, VipConfig.get().nivelWorkbench);
        p.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (id, inv, pl) -> new CraftingScreenHandler(id, inv, ScreenHandlerContext.create(pl.getWorld(), pl.getBlockPos())) {
                    @Override
                    public boolean canUse(PlayerEntity player) { return true; }
                },
                Text.translatable("container.crafting")));
        return 1;
    }

    // ---------------- partículas e seguidor (a cada tick) ----------------

    public static void tick(MinecraftServer s) {
        ticks++;
        if (ticks % 4 == 0 && !particulas.isEmpty()) {
            for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
                String tipo = particulas.get(p.getUuidAsString());
                if (tipo == null || p.isSpectator() || p.isInvisible()) continue;
                SimpleParticleType pt = PARTICULAS.get(tipo);
                if (pt == null || !tem(p, VipConfig.get().nivelParticulas)) continue;
                p.getServerWorld().spawnParticles(pt, p.getX(), p.getY() + 0.15, p.getZ(), 2, 0.3, 0.1, 0.3, 0.01);
            }
        }
        if (ticks % 20 == 0 && !seguidores.isEmpty()) {
            for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
                String bola = seguidores.get(p.getUuidAsString());
                boolean tem = temSeguidor(p);
                if (bola == null || p.isSpectator() || !tem(p, VipConfig.get().nivelSeguidor)) {
                    if (tem) removerSeguidor(p);
                    continue;
                }
                if (!tem) criarSeguidor(p);
            }
        }
    }

    private static boolean temSeguidor(ServerPlayerEntity p) {
        for (Entity e : p.getPassengerList()) if (e.getCommandTags().contains(TAG)) return true;
        return false;
    }

    public static void removerSeguidor(ServerPlayerEntity p) {
        for (Entity e : p.getPassengerList()) if (e.getCommandTags().contains(TAG)) e.discard();
    }

    private static void criarSeguidor(ServerPlayerEntity p) {
        String item = BOLAS.getOrDefault(seguidores.get(p.getUuidAsString()), "cobblemon:poke_ball");
        String tagUnica = "iseg_" + p.getUuidAsString().substring(0, 8);
        String nbt = "{Tags:[\"" + TAG + "\",\"" + tagUnica + "\"],item:{id:\"" + item + "\",count:1},billboard:\"center\","
                + "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[0.7f,-0.35f,0f],scale:[0.45f,0.45f,0.45f]}}";
        ServerCommandSource src = p.getServer().getCommandSource().withSilent().withLevel(4)
                .withWorld(p.getServerWorld()).withPosition(p.getPos());
        var cm = p.getServer().getCommandManager();
        cm.executeWithPrefix(src, "summon minecraft:item_display ~ ~ ~ " + nbt);
        cm.executeWithPrefix(src, "ride @e[type=minecraft:item_display,tag=" + tagUnica + ",limit=1,sort=nearest] mount " + p.getGameProfile().getName());
        // se não montou (ex. jogador já tem outro passageiro), apaga para não sobrar no mundo
        if (!temSeguidor(p)) cm.executeWithPrefix(src, "kill @e[type=minecraft:item_display,tag=" + tagUnica + "]");
    }

    // ---------------- GTS ----------------

    private static VipConfig.Nivel nivelPorUuid(UUID u) {
        if (server == null || u == null) return null;
        String nick = null;
        ServerPlayerEntity p = server.getPlayerManager().getPlayer(u);
        if (p != null) nick = p.getGameProfile().getName();
        else if (server.getUserCache() != null) {
            Optional<GameProfile> gp = server.getUserCache().getByUuid(u);
            if (gp.isPresent()) nick = gp.get().getName();
        }
        return nick == null ? null : Beneficios.nivelDe(nick);
    }

    /** Vagas de anúncio no GTS para quem está usando o comando. */
    public static int limiteGts(ServerPlayerEntity p, int padraoGts) {
        try {
            if (p == null) return padraoGts;
            VipConfig cfg = VipConfig.get();
            VipConfig.Nivel n = Beneficios.nivelDe(p.getGameProfile().getName());
            Integer v = n == null || cfg.gtsVagasPorNivel == null ? null : cfg.gtsVagasPorNivel.get(n.id);
            return v != null ? v : cfg.gtsVagasSemApoio;
        } catch (Exception e) {
            return padraoGts;
        }
    }

    public static double taxaGts(UUID vendedor, double padraoGts) {
        try {
            VipConfig.Nivel n = nivelPorUuid(vendedor);
            Map<String, Double> m = VipConfig.get().gtsTaxaPorNivel;
            Double t = n == null || m == null ? null : m.get(n.id);
            return t != null ? t : padraoGts;
        } catch (Exception e) {
            return padraoGts;
        }
    }
}
