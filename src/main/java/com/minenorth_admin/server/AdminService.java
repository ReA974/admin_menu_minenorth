package com.minenorth_admin.server;

import com.minenorth_admin.AuditLog;
import com.minenorth_admin.Players;
import com.minenorth_admin.net.Net;
import com.minenorth_admin.net.Net.Req;
import com.minenorth_admin.staff.Access;
import com.minenorth_admin.staff.Perm;
import com.minenorth_admin.staff.StaffData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;

/**
 * Toute la logique serveur du panneau. Chaque requête est revérifiée ici : droits du staff (rôle ou op),
 * présence du mod concerné, hiérarchie des rangs pour la gestion du staff.
 */
public final class AdminService {
    private AdminService() {}

    // ================================================================== entrée

    public static void handle(ServerPlayer actor, Req m) {
        Access acc = Access.of(actor);
        if (!acc.has(Perm.PANEL)) {
            Net.send(actor, new Net.View("close", new CompoundTag(), "", false));
            return;
        }
        String act = m.action();
        String module = act.contains(".") ? act.substring(0, act.indexOf('.')) : act;
        try {
            switch (module) {
                case "open" -> sendHome(actor, acc, true, "");
                case "home" -> sendHome(actor, acc, false, "");
                case "player" -> sendPlayer(actor, acc, m.target(), m.a(), "", true);
                case "logs" -> {
                    if (!acc.has(Perm.LOGS)) deny(actor);
                    else sendLogs(actor);
                }
                case "etat" -> {
                    if (!acc.has(Perm.ETAT_VIEW) || !Mods.etat()) {
                        deny(actor);
                        return;
                    }
                    Result r = act.equals("etat") ? null : etatAction(actor, acc, m);
                    sendEtat(actor, r == null ? "" : r.message(), r == null || r.ok());
                }
                case "staff", "role", "member" -> {
                    if (!acc.has(Perm.STAFF)) {
                        deny(actor);
                        return;
                    }
                    Result r = act.equals("staff") ? null : staff(actor, acc, m);
                    sendStaff(actor, acc, r == null ? "" : r.message(), r == null || r.ok());
                }
                case "mode" -> {
                    if (!acc.has(Perm.STAFF_MODE)) {
                        deny(actor);
                        return;
                    }
                    boolean on = StaffMode.toggle(actor);
                    AuditLog.log(actor, "", "staff", on ? "active le mode staff" : "désactive le mode staff");
                    sendHome(actor, acc, false, on ? "Mode staff activé : invisible + créatif." : "Mode staff désactivé.");
                }
                case "user" -> {
                    Result r = deleteUser(actor, acc, m.target());
                    if (r.ok()) sendHome(actor, Access.of(actor), false, r.message());
                    else sendPlayer(actor, acc, m.target(), m.b(), r.message(), false);
                }
                case "bank", "permis", "garage", "police", "secours", "inv", "tp", "mod" -> {
                    Result r = playerAction(actor, acc, module, m);
                    String section = switch (module) {
                        case "tp" -> m.b().isEmpty() ? "bank" : m.b();
                        default -> module;
                    };
                    sendPlayer(actor, acc, m.target(), section, r.message(), r.ok());
                }
                default -> {}
            }
        } catch (RuntimeException e) {
            com.minenorth_admin.MineNorthAdmin.LOG.error("[Admin] Erreur sur l'action {}", act, e);
            Net.send(actor, new Net.View("message", new CompoundTag(), "Erreur serveur : " + e.getClass().getSimpleName() + " (voir la console).", false));
        }
    }

    private static void deny(ServerPlayer actor) {
        Net.send(actor, new Net.View("message", new CompoundTag(), "Tu n'as pas la permission pour ça.", false));
    }

    // ================================================================== accueil

    private static void sendHome(ServerPlayer actor, Access acc, boolean open, String msg) {
        MinecraftServer s = actor.server;
        CompoundTag t = new CompoundTag();
        t.putBoolean("Open", open);
        t.putBoolean("Owner", acc.owner);
        t.putString("Role", acc.roleName == null ? "" : acc.roleName);
        ListTag perms = new ListTag();
        for (Perm p : acc.perms) perms.add(StringTag.valueOf(p.name()));
        t.put("Perms", perms);
        t.putBoolean("Bank", Mods.bank());
        t.putBoolean("Permis", Mods.permis());
        t.putBoolean("Garage", Mods.garage());
        t.putBoolean("Police", Mods.police());
        t.putBoolean("Secours", Mods.secours());
        t.putBoolean("Etat", Mods.etat());
        t.putBoolean("StaffMode", StaffMode.is(actor));
        // Liste triée sur le nom affiché (nom RP), joueurs connectés d'abord.
        java.util.List<CompoundTag> rows = new java.util.ArrayList<>();
        for (Players.Known k : Players.all(s)) {
            CompoundTag p = new CompoundTag();
            p.putUUID("Id", k.id());
            p.putString("Name", Players.display(s, k.id()));
            p.putString("Pseudo", k.name());
            p.putBoolean("On", k.online());
            rows.add(p);
        }
        rows.sort((a, b) -> a.getBoolean("On") != b.getBoolean("On") ? (a.getBoolean("On") ? -1 : 1)
                : a.getString("Name").compareToIgnoreCase(b.getString("Name")));
        ListTag players = new ListTag();
        players.addAll(rows);
        t.put("Players", players);
        Net.send(actor, new Net.View("home", t, msg, true));
    }

    // ================================================================== fiche joueur

    private static boolean canView(Access acc, String section) {
        return switch (section) {
            case "bank" -> acc.has(Perm.BANK_VIEW) && Mods.bank();
            case "permis" -> acc.has(Perm.PERMIS_VIEW) && Mods.permis();
            case "garage" -> acc.has(Perm.GARAGE_VIEW) && Mods.garage();
            case "police" -> acc.has(Perm.POLICE_VIEW) && Mods.police();
            case "secours" -> acc.has(Perm.SECOURS_VIEW) && Mods.secours();
            case "inv" -> acc.has(Perm.INV_VIEW);
            case "mod" -> acc.has(Perm.MODERATE);
            default -> false;
        };
    }

    private static void sendPlayer(ServerPlayer actor, Access acc, UUID id, String section, String msg, boolean ok) {
        MinecraftServer s = actor.server;
        if (id == null || id.equals(Net.NIL)) return;
        CompoundTag t = new CompoundTag();
        t.putUUID("Id", id);
        t.putString("Name", Players.display(s, id));
        t.putString("Pseudo", Players.name(s, id));
        ServerPlayer online = Players.online(s, id);
        t.putBoolean("On", online != null);
        if (online != null) {
            t.putString("Where", online.level().dimension().location() + " " + online.blockPosition().toShortString());
        }
        StaffData.Member mem = StaffData.get(s).member(id);
        if (mem != null && acc.has(Perm.STAFF)) {
            StaffData.Role r = StaffData.get(s).role(mem.role);
            if (r != null) t.putString("StaffRole", r.name);
        }
        t.putString("Section", section);
        if (canView(acc, section)) {
            CompoundTag data = switch (section) {
                case "bank" -> BankModule.view(s, id);
                case "permis" -> PermisModule.view(s, id);
                case "garage" -> GarageModule.view(s, id);
                case "police" -> PoliceModule.view(s, id);
                case "secours" -> SecoursModule.view(s, id);
                case "mod" -> ModModule.view(s, id);
                default -> InvModule.view(s, id);
            };
            t.put("Data", data);
        }
        Net.send(actor, new Net.View("player", t, msg, ok));
    }

    private static Result playerAction(ServerPlayer actor, Access acc, String module, Req m) {
        MinecraftServer s = actor.server;
        UUID id = m.target();
        if (id == null || id.equals(Net.NIL)) return Result.fail("Aucun joueur sélectionné.");
        Perm need = switch (module) {
            case "bank" -> Perm.BANK_EDIT;
            case "permis" -> Perm.PERMIS_EDIT;
            case "garage" -> Perm.GARAGE_EDIT;
            case "police" -> Perm.POLICE_EDIT;
            case "secours" -> Perm.SECOURS_EDIT;
            case "inv" -> Perm.INV_EDIT;
            case "mod" -> Perm.MODERATE;
            default -> Perm.TELEPORT;
        };
        // copier un objet = lecture seule, mais ça crée des objets : réservé à INV_EDIT aussi
        if (!acc.has(need)) return Result.fail("Tu n'as pas la permission : " + need.label + ".");
        // un membre du staff ne peut pas agir sur un staff de rang supérieur ou égal (sauf sur lui-même)
        if (!actor.getUUID().equals(id) && !acc.owner && Access.rankOf(actor, id) >= acc.rank && Access.rankOf(actor, id) > 0) {
            return Result.fail("Ce joueur est un membre du staff de rang supérieur ou égal au tien.");
        }
        Result r = switch (module) {
            case "bank" -> Mods.bank() ? BankModule.act(actor, id, m.action(), m.n()) : Result.fail("EuroBank n'est pas installé.");
            case "permis" -> Mods.permis() ? PermisModule.act(actor, id, m.action(), m.a(), m.n()) : Result.fail("MineNorth Permis n'est pas installé.");
            case "garage" -> Mods.garage() ? GarageModule.act(actor, id, m.action(), m.a(), m.b(), m.n()) : Result.fail("Le mod garage n'est pas installé.");
            case "police" -> Mods.police() ? PoliceModule.act(actor, id, m.action(), m.n()) : Result.fail("Le mod Police n'est pas installé.");
            case "secours" -> Mods.secours() ? SecoursModule.act(actor, id, m.action(), m.n()) : Result.fail("Le mod Secours n'est pas installé.");
            case "inv" -> InvModule.act(actor, id, m.action(), m.a(), m.b(), m.n());
            case "mod" -> ModModule.act(actor, id, m.action(), m.a(), m.n());
            default -> teleport(actor, id, m.action());
        };
        if (r.ok() && r.log() != null) AuditLog.log(actor, Players.display(s, id), module, r.log());
        return r;
    }

    private static Result teleport(ServerPlayer actor, UUID id, String action) {
        ServerPlayer t = Players.online(actor.server, id);
        if (t == null) return Result.fail("Le joueur doit être connecté.");
        if (t == actor) return Result.fail("C'est toi.");
        String name = Players.display(actor.server, id);
        if (action.equals("tp.to")) {
            actor.teleportTo(t.serverLevel(), t.getX(), t.getY(), t.getZ(), t.getYRot(), t.getXRot());
            return Result.ok("Téléporté vers " + name + ".", "se téléporte vers le joueur");
        }
        if (action.equals("tp.here")) {
            t.teleportTo(actor.serverLevel(), actor.getX(), actor.getY(), actor.getZ(), t.getYRot(), t.getXRot());
            return Result.ok(name + " téléporté vers toi.", "téléporte le joueur vers lui");
        }
        return Result.fail("Action inconnue.");
    }

    // ================================================================== suppression d'un joueur

    /**
     * Efface TOUTES les données d'un joueur hors ligne : fichiers du monde (inventaire, position, succès, stats),
     * compte bancaire, permis, garage + fourrière, rôle staff, et via MineNorth API : identité, secours,
     * entreprises, accueil police, portes. S'il revient, il repart de zéro.
     */
    private static Result deleteUser(ServerPlayer actor, Access acc, UUID id) {
        MinecraftServer s = actor.server;
        if (!acc.has(Perm.DELETE)) return Result.fail("Tu n'as pas la permission : " + Perm.DELETE.label + ".");
        if (id == null || id.equals(Net.NIL)) return Result.fail("Aucun joueur sélectionné.");
        if (id.equals(actor.getUUID())) return Result.fail("Tu ne peux pas te supprimer toi-même.");
        if (Players.online(s, id) != null) return Result.fail("Le joueur doit être déconnecté (kick-le d'abord).");
        if (!acc.owner && Access.rankOf(actor, id) >= acc.rank && Access.rankOf(actor, id) > 0) {
            return Result.fail("Ce joueur est un membre du staff de rang supérieur ou égal au tien.");
        }
        String name = Players.display(s, id);
        java.util.List<String> done = new java.util.ArrayList<>();
        int files = 0;
        java.nio.file.Path[] paths = {
                s.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR).resolve(id + ".dat"),
                s.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR).resolve(id + ".dat_old"),
                s.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_ADVANCEMENTS_DIR).resolve(id + ".json"),
                s.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_STATS_DIR).resolve(id + ".json")};
        for (java.nio.file.Path p : paths) {
            try {
                if (java.nio.file.Files.deleteIfExists(p)) files++;
            } catch (java.io.IOException e) {
                com.minenorth_admin.MineNorthAdmin.LOG.warn("[Admin] Suppression impossible : {}", p, e);
            }
        }
        if (files > 0) done.add("fichiers (" + files + ")");
        if (Mods.bank() && BankModule.wipe(s, id)) done.add("banque");
        if (Mods.permis() && PermisModule.wipe(s, id)) done.add("permis");
        if (Mods.garage() && GarageModule.wipe(s, id)) done.add("garage");
        if (Mods.police() && PoliceModule.wipe(s, id)) done.add("police");
        if (StaffData.get(s).removeMember(id)) done.add("rôle staff");
        // Les autres mods (identité, secours, entreprises, accueil, portes…) nettoient eux-mêmes via MineNorth API.
        fr.minenorth.api.PlayerWipeEvent wipe = new fr.minenorth.api.PlayerWipeEvent(s, id, name);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(wipe);
        done.addAll(wipe.cleaned());
        if (done.isEmpty()) return Result.fail("Aucune donnée trouvée pour " + name + ".");
        String what = String.join(", ", done);
        AuditLog.log(actor, name, "joueur", "SUPPRIME le joueur (" + what + ")");
        return Result.ok(name + " supprimé : " + what + ".", null);
    }

    // ================================================================== staff

    private static void sendStaff(ServerPlayer actor, Access acc, String msg, boolean ok) {
        StaffData d = StaffData.get(actor.server);
        CompoundTag t = new CompoundTag();
        t.putInt("MyRank", acc.rank);
        t.putBoolean("Owner", acc.owner);
        ListTag roles = new ListTag();
        for (StaffData.Role r : d.roles()) {
            CompoundTag rt = new CompoundTag();
            rt.putString("Id", r.id);
            rt.putString("Name", r.name);
            rt.putInt("Rank", r.rank);
            ListTag perms = new ListTag();
            for (Perm p : r.perms) perms.add(StringTag.valueOf(p.name()));
            rt.put("Perms", perms);
            int count = 0;
            for (StaffData.Member mm : d.members().values()) if (mm.role.equals(r.id)) count++;
            rt.putInt("Count", count);
            roles.add(rt);
        }
        t.put("Roles", roles);
        ListTag members = new ListTag();
        for (Map.Entry<UUID, StaffData.Member> e : d.members().entrySet()) {
            CompoundTag mt = new CompoundTag();
            mt.putUUID("Id", e.getKey());
            String rp = Players.rpName(actor.server, e.getKey());
            String n = Players.name(actor.server, e.getKey());
            mt.putString("Name", !rp.isEmpty() ? rp : n.length() == 8 && !e.getValue().name.isEmpty() ? e.getValue().name : n);
            mt.putString("Role", e.getValue().role);
            mt.putBoolean("On", Players.online(actor.server, e.getKey()) != null);
            members.add(mt);
        }
        t.put("Members", members);
        Net.send(actor, new Net.View("staff", t, msg, ok));
    }

    private static String cleanName(String s) {
        s = s == null ? "" : s.replaceAll("[\\p{Cntrl}§|]", "").trim();
        return s.length() > 24 ? s.substring(0, 24) : s;
    }

    /** Recharge l'arbre de commandes d'un joueur (la commande /mnadmin apparaît ou disparaît). */
    private static void refreshCommands(MinecraftServer s, UUID id) {
        ServerPlayer p = Players.online(s, id);
        if (p != null) s.getCommands().sendCommands(p);
    }

    private static Result staff(ServerPlayer actor, Access acc, Req m) {
        MinecraftServer s = actor.server;
        StaffData d = StaffData.get(s);
        Result r = staffInner(actor, acc, d, m);
        if (r.ok() && r.log() != null) {
            // membres : "cible|texte" ; rôles : texte seul
            String[] parts = r.log().split("\\|", 2);
            if (parts.length == 2) AuditLog.log(actor, parts[0], "staff", parts[1]);
            else AuditLog.log(actor, "", "staff", r.log());
        }
        return r;
    }

    private static Result staffInner(ServerPlayer actor, Access acc, StaffData d, Req m) {
        MinecraftServer s = actor.server;
        switch (m.action()) {
            case "role.create" -> {
                String name = cleanName(m.a());
                int rank = (int) m.n();
                if (name.isEmpty()) return Result.fail("Donne un nom au rôle.");
                if (rank < 1 || rank > StaffData.MAX_RANK) return Result.fail("Rang invalide (1 à " + StaffData.MAX_RANK + ").");
                if (rank >= acc.rank) return Result.fail("Le rang doit être inférieur au tien (" + acc.rank + ").");
                StaffData.Role r = d.addRole(name, rank);
                r.perms.add(Perm.PANEL);
                return Result.ok("Rôle « " + name + " » créé (rang " + rank + ").", "crée le rôle " + name + " (rang " + rank + ")");
            }
            case "role.rename", "role.rank", "role.perm", "role.delete" -> {
                StaffData.Role r = d.role(m.a());
                if (r == null) return Result.fail("Rôle introuvable.");
                if (r.rank >= acc.rank) return Result.fail("Tu ne peux modifier que les rôles de rang inférieur au tien.");
                switch (m.action()) {
                    case "role.rename" -> {
                        String name = cleanName(m.b());
                        if (name.isEmpty()) return Result.fail("Nom invalide.");
                        String old = r.name;
                        r.name = name;
                        d.setDirty();
                        return Result.ok("Rôle renommé en « " + name + " ».", "renomme le rôle " + old + " en " + name);
                    }
                    case "role.rank" -> {
                        int rank = (int) m.n();
                        if (rank < 1 || rank > StaffData.MAX_RANK || rank >= acc.rank) return Result.fail("Rang invalide (1 à " + Math.min(StaffData.MAX_RANK, acc.rank - 1) + ").");
                        r.rank = rank;
                        d.setDirty();
                        return Result.ok("Rang de « " + r.name + " » : " + rank + ".", "rang du rôle " + r.name + " -> " + rank);
                    }
                    case "role.perm" -> {
                        Perm p = Perm.byName(m.b());
                        if (p == null) return Result.fail("Permission inconnue.");
                        if (!acc.has(p) && !acc.owner) return Result.fail("Tu ne peux pas donner une permission que tu n'as pas.");
                        boolean on = !r.perms.contains(p);
                        if (on) r.perms.add(p);
                        else r.perms.remove(p);
                        d.setDirty();
                        for (Map.Entry<UUID, StaffData.Member> e : d.members().entrySet()) {
                            if (e.getValue().role.equals(r.id)) refreshCommands(s, e.getKey());
                        }
                        return Result.ok(r.name + " : " + p.label + (on ? " accordé." : " retiré."), r.name + " : " + p.label + (on ? " +" : " -"));
                    }
                    default -> {
                        java.util.List<UUID> affected = new java.util.ArrayList<>();
                        for (Map.Entry<UUID, StaffData.Member> e : d.members().entrySet()) {
                            if (e.getValue().role.equals(r.id)) affected.add(e.getKey());
                        }
                        int n = d.deleteRole(r.id);
                        for (UUID u : affected) refreshCommands(s, u);
                        return Result.ok("Rôle « " + r.name + " » supprimé (" + n + " membre(s) retiré(s)).", "supprime le rôle " + r.name);
                    }
                }
            }
            case "member.set" -> {
                StaffData.Role r = d.role(m.a());
                if (r == null) return Result.fail("Choisis un rôle.");
                if (r.rank >= acc.rank) return Result.fail("Tu ne peux attribuer que des rôles de rang inférieur au tien.");
                Players.Known k = Players.byName(s, m.b());
                if (k == null) return Result.fail("Joueur introuvable : " + m.b() + " (nom RP « Prénom Nom » ou pseudo ; il doit s'être connecté au moins une fois).");
                if (k.id().equals(actor.getUUID()) && !acc.owner) return Result.fail("Tu ne peux pas changer ton propre rôle.");
                int current = Access.rankOf(actor, k.id());
                if (current >= acc.rank) return Result.fail(Players.display(s, k.id()) + " a un rang supérieur ou égal au tien.");
                d.setMember(k.id(), k.name(), r.id);
                refreshCommands(s, k.id());
                String shown = Players.display(s, k.id());
                return Result.ok(shown + " a maintenant le rôle « " + r.name + " ».", shown + "|rôle " + r.name);
            }
            case "member.remove" -> {
                UUID id = m.target();
                StaffData.Member mem = d.member(id);
                if (mem == null) return Result.fail("Ce joueur n'est pas dans le staff.");
                if (id.equals(actor.getUUID()) && !acc.owner) return Result.fail("Tu ne peux pas te retirer toi-même.");
                if (Access.rankOf(actor, id) >= acc.rank) return Result.fail("Ce membre a un rang supérieur ou égal au tien.");
                d.removeMember(id);
                refreshCommands(s, id);
                String shown = Players.display(s, id);
                return Result.ok(shown + " retiré du staff.", shown + "|retiré du staff");
            }
            default -> {
                return Result.fail("Action inconnue.");
            }
        }
    }

    // ================================================================== état

    private static Result etatAction(ServerPlayer actor, Access acc, Req m) {
        if (!acc.has(Perm.ETAT_EDIT)) return Result.fail("Tu n'as pas la permission : " + Perm.ETAT_EDIT.label + ".");
        Result r = EtatModule.act(actor, m.action(), m.a(), m.n());
        if (r.ok() && r.log() != null) AuditLog.log(actor, "", "état", r.log());
        return r;
    }

    private static void sendEtat(ServerPlayer actor, String msg, boolean ok) {
        Net.send(actor, new Net.View("etat", EtatModule.view(actor.server), msg, ok));
    }

    // ================================================================== journal

    private static void sendLogs(ServerPlayer actor) {
        ListTag l = new ListTag();
        for (StaffData.LogEntry e : StaffData.get(actor.server).recentLogs(300)) {
            CompoundTag t = new CompoundTag();
            t.putString("D", AuditLog.date(e.time()));
            t.putString("A", e.actor());
            t.putString("P", e.target());
            t.putString("M", e.module());
            t.putString("X", e.text());
            l.add(t);
        }
        CompoundTag t = new CompoundTag();
        t.put("Logs", l);
        Net.send(actor, new Net.View("logs", t, "", true));
    }
}
