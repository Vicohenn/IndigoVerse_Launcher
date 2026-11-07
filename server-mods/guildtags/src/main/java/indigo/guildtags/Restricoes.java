package indigo.guildtags;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BrewingStandBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.screen.slot.CraftingResultSlot;
import net.minecraft.screen.slot.Slot;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Todas as regras de bloqueio ficam aqui. */
public final class Restricoes {
    private static final Map<UUID, Long> ultimoAviso = new HashMap<>();

    private Restricoes() {}

    // ---------------- craft ----------------

    /** Slots de "resultado": mesa de craft, panela do Cobblemon, máquina de TM, suporte de poções etc. */
    public static boolean ehSlotDeResultado(Slot slot) {
        if (slot instanceof CraftingResultSlot) return true;
        if (slot instanceof net.minecraft.screen.slot.TradeOutputSlot) return false;   // compra de aldeão não é craft
        if (slot.inventory instanceof BrewingStandBlockEntity) return true;
        String n = slot.getClass().getName();
        return n.contains("Result") || n.contains("Output");
    }

    /** Chamado pelo SlotMixin: true = o jogador NÃO pode pegar o item deste slot. */
    public static boolean bloqueiaRetirada(ServerPlayerEntity player, Slot slot) {
        if (!ehSlotDeResultado(slot)) return false;
        ItemStack st = slot.getStack();
        String cat = Itens.categoriaDe(st, "craft");
        if (cat == null || Guildas.podeUsar(player, cat)) return false;
        avisar(player, "Apenas guildas com a tag §e" + nome(cat) + "§c podem fabricar " + st.getName().getString() + ".");
        return true;
    }

    /** Chamado pelo HopperBlockEntityMixin: funis não tiram itens restritos de máquinas (panela, poções, TM). */
    public static boolean bloqueiaFunil(Inventory origem, ItemStack stack) {
        boolean maquina = origem instanceof BrewingStandBlockEntity;
        if (!maquina && origem instanceof BlockEntity) {
            String n = origem.getClass().getName();
            maquina = n.contains("CampfireBlockEntity") || n.contains("TMMachine");
        }
        return maquina && Itens.categoriaDe(stack, "craft") != null;
    }

    // ---------------- clique com o botão direito em bloco ----------------

    public static ActionResult aoUsarBloco(ServerPlayerEntity player, ServerWorld world, Hand hand, BlockHitResult hit) {
        ItemStack st = player.getStackInHand(hand);
        TagsConfig cfg = TagsConfig.get();
        String blocoClicado = Registries.BLOCK.getId(world.getBlockState(hit.getBlockPos()).getBlock()).toString();

        // Máquina de TM: só quem tem a tag "tm" pode abrir
        if (blocoClicado.equals("cobblemon:tm_machine") && cfg.categorias.containsKey("tm") && !Guildas.podeUsar(player, "tm")) {
            avisar(player, "Apenas guildas com a tag §e" + nome("tm") + "§c podem usar a Máquina de TM.");
            return ActionResult.FAIL;
        }

        // Colheita: arbustos de berry, apricorns, mentas, alho-poró medicinal
        String colheita = Itens.categoriaDoBloco(world.getBlockState(hit.getBlockPos()));
        if (colheita != null && !Guildas.podeUsar(player, colheita)) {
            avisar(player, "Apenas guildas com a tag §e" + nome(colheita) + "§c podem colher isto.");
            return ActionResult.FAIL;
        }

        // Plantio de frutas e bagas
        String plantio = Itens.categoriaDe(st, "plantio");
        if (plantio != null && ehSolo(world, hit.getBlockPos()) && !Guildas.podeUsar(player, plantio)) {
            avisar(player, "Apenas guildas com a tag §e" + nome(plantio) + "§c podem plantar " + st.getName().getString() + ".");
            return ActionResult.FAIL;
        }

        if (st.getItem() instanceof BlockItem bi) {
            String bloco = Registries.BLOCK.getId(bi.getBlock()).toString();

            if (cfg.bloquearCrafter && bloco.equals("minecraft:crafter") && !(cfg.opsIgnoramRestricoes && player.hasPermissionLevel(2))) {
                avisar(player, "O Crafter está desativado no servidor.");
                return ActionResult.FAIL;
            }

            String catRedstone = Guildas.algumaCategoria(player, cfg.categoriasSemRedstone);
            if (catRedstone != null && cfg.blocosProibidosSemRedstone.contains(bloco)) {
                avisar(player, "Membros de guildas §e" + nome(catRedstone) + "§c não podem usar " + st.getName().getString() + ".");
                return ActionResult.FAIL;
            }

            String catClaim = Guildas.algumaCategoria(player, cfg.categoriasSoConstroemNaGuilda);
            if (catClaim != null) {
                BlockPos alvo = new ItemPlacementContext(player, hand, st, hit).getBlockPos();
                if (!claimDaGuilda(player, world, alvo)) {
                    avisar(player, "Membros de guildas §e" + nome(catClaim) + "§c só constroem em claims da própria guilda.");
                    return ActionResult.FAIL;
                }
            }
        }
        return ActionResult.PASS;
    }

    private static boolean claimDaGuilda(ServerPlayerEntity player, ServerWorld world, BlockPos pos) {
        UUID dono = Guildas.donoDoClaim(world, pos);
        if (dono == null) return false;
        if (dono.equals(player.getUuid())) return true;
        String minha = Guildas.guildaDe(player.getServer(), player.getUuid());
        String doDono = Guildas.guildaDe(player.getServer(), dono);
        return minha != null && minha.equalsIgnoreCase(doDono);
    }

    private static final net.minecraft.registry.tag.TagKey<net.minecraft.block.Block> BERRY_SOIL =
            net.minecraft.registry.tag.TagKey.of(net.minecraft.registry.RegistryKeys.BLOCK, net.minecraft.util.Identifier.of("cobblemon", "berry_soil"));
    private static final net.minecraft.registry.tag.TagKey<net.minecraft.block.Block> LEEK_PLANTAVEL =
            net.minecraft.registry.tag.TagKey.of(net.minecraft.registry.RegistryKeys.BLOCK, net.minecraft.util.Identifier.of("cobblemon", "medicinal_leek_plantable"));

    private static boolean ehSolo(ServerWorld world, BlockPos pos) {
        var state = world.getBlockState(pos);
        return state.isIn(BlockTags.DIRT) || state.isOf(net.minecraft.block.Blocks.FARMLAND) || state.isIn(BlockTags.SAND)
                || state.isIn(BERRY_SOIL) || state.isIn(LEEK_PLANTAVEL);
    }

    /** Clique direito com o item no ar: o alho-poró medicinal é plantado na água (como a vitória-régia). */
    public static net.minecraft.util.TypedActionResult<ItemStack> aoUsarItem(ServerPlayerEntity player, ServerWorld world, Hand hand) {
        ItemStack st = player.getStackInHand(hand);
        String plantio = Itens.categoriaDe(st, "plantio");
        if (plantio == null || Guildas.podeUsar(player, plantio)) return net.minecraft.util.TypedActionResult.pass(st);
        var hr = world.raycast(new net.minecraft.world.RaycastContext(player.getEyePos(),
                player.getEyePos().add(player.getRotationVec(1f).multiply(5)),
                net.minecraft.world.RaycastContext.ShapeType.OUTLINE, net.minecraft.world.RaycastContext.FluidHandling.SOURCE_ONLY, player));
        if (hr instanceof BlockHitResult bh && hr.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK
                && (world.getFluidState(bh.getBlockPos()).isIn(net.minecraft.registry.tag.FluidTags.WATER) || ehSolo(world, bh.getBlockPos()))) {
            avisar(player, "Apenas guildas com a tag §e" + nome(plantio) + "§c podem plantar " + st.getName().getString() + ".");
            return net.minecraft.util.TypedActionResult.fail(st);
        }
        return net.minecraft.util.TypedActionResult.pass(st);
    }

    /** Quebrar arbustos de berry, apricorns, mentas e alho-poró: só a guilda da categoria. */
    public static boolean podeQuebrar(ServerPlayerEntity player, net.minecraft.block.BlockState state) {
        String cat = Itens.categoriaDoBloco(state);
        if (cat == null || Guildas.podeUsar(player, cat)) return true;
        avisar(player, "Apenas guildas com a tag §e" + nome(cat) + "§c podem colher isto.");
        return false;
    }

    // ---------------- util ----------------

    public static String nome(String categoria) {
        TagsConfig.Categoria c = TagsConfig.get().categorias.get(categoria);
        return c == null || c.nome == null ? categoria : c.nome;
    }

    private static void avisar(ServerPlayerEntity player, String msg) {
        long agora = System.currentTimeMillis();
        Long antes = ultimoAviso.get(player.getUuid());
        if (antes != null && agora - antes < 1500) return;
        ultimoAviso.put(player.getUuid(), agora);
        player.sendMessage(Text.literal("§c" + msg).formatted(Formatting.RED), true);
    }
}
