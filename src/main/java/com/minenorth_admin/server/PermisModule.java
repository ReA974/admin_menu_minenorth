package com.minenorth_admin.server;

import com.minenorth_admin.AdminConfig;
import com.minenorth_admin.Players;
import com.minenorth_permis.Licences;
import com.minenorth_permis.PermisConfig;
import com.minenorth_permis.Shop;
import com.minenorth_permis.data.PermisData;
import com.minenorth_permis.items.PermisCardItem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.UUID;

/** Onglet Permis & licences (MineNorth Permis). Chargé seulement si le mod est présent. */
final class PermisModule {
    private PermisModule() {}

    static CompoundTag view(MinecraftServer s, UUID id) {
        PermisConfig.Root cfg = PermisConfig.get();
        CompoundTag t = new CompoundTag();
        t.putInt("Points", Licences.points(s, id));
        t.putInt("MaxPoints", cfg.maxPoints);
        t.putBoolean("UsesPoints", cfg.anyPoints());
        t.putInt("DefaultDays", cfg.validityDays);
        ListTag list = new ListTag();
        for (PermisConfig.Licence l : cfg.licences) {
            CompoundTag lt = new CompoundTag();
            lt.putString("Id", l.id);
            lt.putString("Name", (l.code == null || l.code.isEmpty() ? "" : l.code + " · ") + l.name);
            Long exp = Licences.expiry(s, id, l.id);
            boolean valid = exp != null && System.currentTimeMillis() <= exp;
            lt.putBoolean("Has", valid);
            if (valid) lt.putString("Until", Licences.formatDate(exp));
            list.add(lt);
        }
        t.put("Licences", list);
        PermisData.Holder h = PermisData.get(s).peek(id);
        ListTag cds = new ListTag();
        if (h != null) {
            for (Map.Entry<String, Long> e : h.cooldowns.entrySet()) {
                long left = e.getValue() - System.currentTimeMillis();
                if (left <= 0) continue;
                CompoundTag c = new CompoundTag();
                c.putString("Key", e.getKey());
                c.putString("Left", Licences.formatDuration(left));
                cds.add(c);
            }
        }
        t.put("Cooldowns", cds);
        return t;
    }

    static boolean wipe(MinecraftServer s, UUID id) {
        PermisData d = PermisData.get(s);
        boolean r = d.holders().remove(id) != null;
        if (r) d.setDirty();
        return r;
    }

    private static void tell(MinecraftServer s, UUID id, String msg) {
        ServerPlayer p = Players.online(s, id);
        if (p != null && AdminConfig.NOTIFY_TARGET.get()) p.sendSystemMessage(Component.literal("[Permis] " + msg));
    }

    /** a = id de licence ; n = jours (give : -1 = durée de la config, 0 = permanent) ou points. */
    static Result act(ServerPlayer actor, UUID id, String action, String a, long n) {
        MinecraftServer s = actor.server;
        PermisConfig.Root cfg = PermisConfig.get();
        String name = Players.display(s, id);
        ServerPlayer target = Players.online(s, id);
        PermisConfig.Licence def = cfg.licence(a);
        switch (action) {
            case "permis.give" -> {
                if (def == null) return Result.fail("Licence inconnue.");
                if (n < -1 || n > 36500) return Result.fail("Durée invalide (-1, 0 ou 1 à 36500 jours).");
                int days = (int) n;
                if (target != null) Shop.obtain(target, def.id, days);   // donne aussi la carte si la config le prévoit
                else Licences.grant(s, id, name, def.id, days);
                tell(s, id, "Le staff t'a délivré : " + def.name + ".");
                String dur = days == 0 ? "permanent" : days < 0 ? cfg.validityDays + " j (config)" : days + " j";
                return Result.ok(def.name + " donné à " + name + " (" + dur + ")"
                        + (target == null ? " — carte à remettre à sa connexion." : "."), "donne " + def.id + " (" + dur + ")");
            }
            case "permis.revoke" -> {
                if (def == null) return Result.fail("Licence inconnue.");
                if (!Licences.revoke(s, id, def.id)) return Result.fail(name + " n'a pas " + def.name + ".");
                tell(s, id, "Ton " + def.name + " t'a été retiré par le staff.");
                return Result.ok(def.name + " retiré à " + name + ".", "retire " + def.id);
            }
            case "permis.card" -> {
                if (def == null) return Result.fail("Licence inconnue.");
                if (target == null) return Result.fail(name + " doit être connecté pour recevoir une carte.");
                if (!Licences.isValid(s, id, def.id)) return Result.fail(name + " n'a pas " + def.name + " : donne-le d'abord.");
                ItemStack c = PermisCardItem.create(target, def.id);
                if (!target.getInventory().add(c)) target.drop(c, false);
                tell(s, id, "Le staff t'a remis une nouvelle carte : " + def.name + ".");
                return Result.ok("Nouvelle carte remise à " + name + " (l'ancienne est annulée).", "carte " + def.id);
            }
            case "permis.points" -> {
                if (n < 0 || n > cfg.maxPoints) return Result.fail("Points invalides (0 à " + cfg.maxPoints + ").");
                int before = Licences.points(s, id);
                int v = Licences.setPoints(s, id, (int) n);
                tell(s, id, "Points de permis : " + v + "/" + cfg.maxPoints + ".");
                return Result.ok(name + " : " + v + "/" + cfg.maxPoints + " points"
                        + (v == 0 && cfg.revokeAtZeroPoints ? " — permis de conduire retiré." : "."), "points " + before + " → " + v);
            }
            case "permis.stoptest" -> {
                if (target == null) return Result.fail(name + " n'est pas connecté.");
                boolean stopped = com.minenorth_permis.tests.DrivingTests.stop(target) | com.minenorth_permis.tests.ShootingTests.stop(target);
                if (!stopped) return Result.fail(name + " ne passe aucune épreuve.");
                tell(s, id, "Ton épreuve a été arrêtée par le staff.");
                return Result.ok("Épreuve de " + name + " arrêtée.", "arrête son épreuve");
            }
            case "permis.cooldowns" -> {
                PermisData d = PermisData.get(s);
                PermisData.Holder h = d.peek(id);
                if (h == null || h.cooldowns.isEmpty()) return Result.fail(name + " n'a aucun délai d'attente.");
                h.cooldowns.clear();
                d.setDirty();
                return Result.ok("Délais d'attente des examens effacés pour " + name + ".", "efface les délais d'examen");
            }
            default -> {
                return Result.fail("Action inconnue.");
            }
        }
    }
}
