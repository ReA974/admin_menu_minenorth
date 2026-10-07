package com.minenorth_admin.server;

import com.minenorth_admin.Players;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/** Onglet Inventaire : inventaire + coffre de l'ender, joueur en ligne ou hors ligne. */
final class InvModule {
    private InvModule() {}

    static CompoundTag view(MinecraftServer s, UUID id) {
        CompoundTag t = new CompoundTag();
        PlayerInv pi = PlayerInv.load(s, id);
        t.putBoolean("Found", pi != null);
        if (pi == null) return t;
        t.putBoolean("Live", pi.isOnline());
        t.put("Inv", pi.toNbt(false));
        t.put("Ender", pi.toNbt(true));
        return t;
    }

    private static String desc(ItemStack st) {
        return st.getCount() + "x " + st.getHoverName().getString();
    }

    private static void give(ServerPlayer p, ItemStack st) {
        if (!p.getInventory().add(st)) p.drop(st, false);
    }

    /** a = "inv" ou "ender" ; n = emplacement ; b = id d'objet attendu (sécurité si l'inventaire a bougé). */
    static Result act(ServerPlayer actor, UUID id, String action, String a, String b, long n) {
        MinecraftServer s = actor.server;
        String name = Players.display(s, id);
        boolean ender = "ender".equals(a);
        PlayerInv pi = PlayerInv.load(s, id);
        if (pi == null) return Result.fail("Inventaire de " + name + " illisible (jamais connecté ?).");
        String where = ender ? " (ender)" : "";

        switch (action) {
            case "inv.copy", "inv.take", "inv.delete" -> {
                int slot = (int) n;
                ItemStack st = pi.get(ender, slot);
                if (st.isEmpty()) return Result.fail("Emplacement vide (l'inventaire a changé ?). Actualise.");
                String itemId = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(st.getItem()).toString();
                if (!b.isEmpty() && !b.equals(itemId)) return Result.fail("L'objet a changé entre-temps. Actualise.");
                if (action.equals("inv.copy")) {
                    give(actor, st.copy());
                    return Result.ok("Copie de " + desc(st) + " ajoutée à ton inventaire.", "copie " + desc(st) + where);
                }
                pi.set(ender, slot, ItemStack.EMPTY);
                if (!pi.save(s, id)) return Result.fail("Impossible d'enregistrer (le joueur vient de se connecter ?). Réessaie.");
                if (action.equals("inv.take")) {
                    give(actor, st.copy());
                    return Result.ok(desc(st) + " pris à " + name + ".", "prend " + desc(st) + where);
                }
                return Result.ok(desc(st) + " supprimé de l'inventaire de " + name + ".", "supprime " + desc(st) + where);
            }
            case "inv.give" -> {
                ItemStack hand = actor.getItemInHand(InteractionHand.MAIN_HAND);
                if (hand.isEmpty()) return Result.fail("Prends en main l'objet à donner.");
                if (actor.getUUID().equals(id)) return Result.fail("C'est ton propre inventaire.");
                ItemStack copy = hand.copy();
                if (pi.isOnline()) {
                    ServerPlayer target = Players.online(s, id);
                    if (target == null) return Result.fail(name + " vient de se déconnecter. Réessaie.");
                    give(target, copy);
                } else {
                    int free = pi.firstFree();
                    if (free < 0) return Result.fail("L'inventaire de " + name + " est plein.");
                    pi.set(false, free, copy);
                    if (!pi.save(s, id)) return Result.fail("Impossible d'enregistrer. Réessaie.");
                }
                actor.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                return Result.ok(desc(copy) + " donné à " + name + ".", "donne " + desc(copy));
            }
            case "inv.clear" -> {
                int count = 0;
                ItemStack[] arr = ender ? pi.ender : pi.inv;
                for (int i = 0; i < arr.length; i++) {
                    if (arr[i] != null && !arr[i].isEmpty()) count++;
                    arr[i] = ItemStack.EMPTY;
                }
                if (count == 0) return Result.fail("Déjà vide.");
                if (!pi.save(s, id)) return Result.fail("Impossible d'enregistrer. Réessaie.");
                return Result.ok((ender ? "Coffre de l'ender" : "Inventaire") + " de " + name + " vidé (" + count + " pile(s)).",
                        "vide " + (ender ? "l'ender chest" : "l'inventaire") + " (" + count + " piles)");
            }
            default -> {
                return Result.fail("Action inconnue.");
            }
        }
    }
}
