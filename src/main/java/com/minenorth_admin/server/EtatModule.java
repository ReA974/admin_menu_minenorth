package com.minenorth_admin.server;

import com.minenorth_admin.Players;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.UUID;

/**
 * Onglet État (mod MineNorth État, modid « minenorthetat ») : trésor, impôt sur les achats, maire, élections.
 * Passe par fr.minenorth.etat.api.EtatApi en réflexion : le panneau compile et démarre sans le mod État.
 */
final class EtatModule {
    private EtatModule() {}

    private static final String API = "fr.minenorth.etat.api.EtatApi";

    private static Object call(String method, Class<?>[] types, Object... args) {
        try {
            Method m = Class.forName(API).getMethod(method, types);
            return m.invoke(null, args);
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("API du mod État indisponible (" + method + ") : mets à jour le mod État.", e);
        }
    }

    private static final Class<?>[] S = {MinecraftServer.class};

    static CompoundTag view(MinecraftServer s) {
        CompoundTag t = new CompoundTag();
        t.putLong("Balance", (Long) call("balance", S, s));
        t.putDouble("Tax", (Double) call("taxPercent", S, s));
        t.putDouble("TaxMax", (Double) call("taxMaxPercent", new Class<?>[0]));
        UUID mayor = (UUID) call("mayor", S, s);
        t.putString("Mayor", mayor == null ? "" : Players.display(s, mayor));
        t.putBoolean("Election", (Boolean) call("electionOpen", S, s));
        t.putInt("Agents", (Integer) call("agentCount", S, s));
        t.putBoolean("Locked", (Boolean) call("mayorLocked", S, s));
        ListTag sal = new ListTag();
        for (String row : (String[]) call("salaryRows", S, s)) {
            String[] p = row.split("[|]", 4);
            if (p.length < 4) continue;
            CompoundTag c = new CompoundTag();
            c.putString("Key", p[0]);
            c.putString("Label", p[1]);
            try {
                c.putDouble("Euros", Double.parseDouble(p[2]));
            } catch (NumberFormatException ignored) {}
            c.putBoolean("Set", p[3].equals("1"));
            sal.add(c);
        }
        t.put("Salaries", sal);
        ListTag ag = new ListTag();
        for (String row : (String[]) call("agentRows", S, s)) {
            String[] p = row.split("[|]", 3);
            if (p.length < 3) continue;
            CompoundTag c = new CompoundTag();
            c.putString("Id", p[0]);
            c.putString("Name", p[1]);
            c.putString("Title", p[2]);
            ag.add(c);
        }
        t.put("AgentList", ag);
        ListTag led = new ListTag();
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("dd/MM HH:mm");
        for (String row : (String[]) call("ledger", S, s)) {
            String[] p = row.split("[|]", 2);
            String line = row;
            try {
                line = fmt.format(new java.util.Date(Long.parseLong(p[0]))) + "  " + (p.length > 1 ? p[1] : "");
            } catch (NumberFormatException ignored) {}
            led.add(net.minecraft.nbt.StringTag.valueOf(line));
        }
        t.put("Ledger", led);
        return t;
    }

    /** etat.tax (a = pourcentage), etat.taxreset, etat.mayor (a = pseudo ou nom), etat.nomayor, etat.open (n = minutes, 0 = config), etat.close, etat.cancel. */
    static Result act(ServerPlayer actor, String action, String a, String b, long n) {
        MinecraftServer s = actor.server;
        switch (action) {
            case "etat.tax" -> {
                double p;
                try {
                    p = Double.parseDouble(a.trim().replace(',', '.').replace("%", ""));
                } catch (NumberFormatException e) {
                    return Result.fail("Pourcentage invalide (ex. 2,5).");
                }
                String msg = (String) call("setTaxPercent", new Class<?>[]{MinecraftServer.class, double.class}, s, p);
                return msg.startsWith("L'impôt doit") ? Result.fail(msg) : Result.ok(msg, "fixe l'impôt à " + p + " %");
            }
            case "etat.taxreset" -> {
                String msg = (String) call("resetTaxPercent", S, s);
                return Result.ok(msg, "remet l'impôt à la valeur de la config");
            }
            case "etat.mayor" -> {
                UUID id = find(s, a);
                if (id == null) return Result.fail("Joueur introuvable (pseudo ou nom RP exact).");
                String name = Players.display(s, id);
                String msg = (String) call("setMayor", new Class<?>[]{MinecraftServer.class, UUID.class, String.class}, s, id, name);
                return Result.ok(msg, "nomme " + name + " maire");
            }
            case "etat.salary" -> {
                double euros;
                try {
                    euros = Double.parseDouble(a.trim().replace(',', '.').replace("€", ""));
                } catch (NumberFormatException e) {
                    return Result.fail("Montant invalide (ex. 250).");
                }
                String key = b;
                String msg = (String) call("setSalary", new Class<?>[]{MinecraftServer.class, String.class, double.class}, s, key, euros);
                return msg.startsWith("Salaire invalide") ? Result.fail(msg) : Result.ok(msg, "fixe le salaire " + key + " à " + euros + " €");
            }
            case "etat.salaryreset" -> {
                String msg = (String) call("resetSalary", new Class<?>[]{MinecraftServer.class, String.class}, s, a);
                return Result.ok(msg, "remet le salaire " + a + " à la config");
            }
            case "etat.revoke" -> {
                UUID id;
                try {
                    id = UUID.fromString(a);
                } catch (IllegalArgumentException e) {
                    return Result.fail("Agent inconnu.");
                }
                String name = (String) call("revokeAgent", new Class<?>[]{MinecraftServer.class, UUID.class}, s, id);
                return name == null ? Result.fail("Cet agent n'existe plus.") : Result.ok(name + " n'est plus agent municipal.", "révoque l'agent " + name);
            }
            case "etat.treasury" -> {
                double euros;
                try {
                    euros = Double.parseDouble(a.trim().replace(',', '.').replace("€", "").replace(" ", ""));
                } catch (NumberFormatException e) {
                    return Result.fail("Montant invalide (ex. 500 ou -200).");
                }
                long cents = Math.round(euros * 100.0);
                if (cents == 0) return Result.fail("Montant nul.");
                String msg = (String) call("adjustTreasury", new Class<?>[]{MinecraftServer.class, long.class, String.class}, s, cents, actor.getGameProfile().getName());
                return Result.ok(msg, (cents > 0 ? "ajoute " : "retire ") + Math.abs(euros) + " € au trésor de l'État");
            }
            case "etat.lock" -> {
                boolean lock = n != 0;
                String msg = (String) call("setMayorLocked", new Class<?>[]{MinecraftServer.class, boolean.class}, s, lock);
                return Result.ok(msg, lock ? "suspend les pouvoirs du maire" : "rétablit les pouvoirs du maire");
            }
            case "etat.nomayor" -> {
                String msg = (String) call("setMayor", new Class<?>[]{MinecraftServer.class, UUID.class, String.class}, s, null, "");
                return Result.ok(msg, "retire le maire");
            }
            case "etat.open" -> {
                String err = (String) call("openElection", new Class<?>[]{MinecraftServer.class, int.class}, s, (int) Math.max(0, n));
                return err == null ? Result.ok("Élection ouverte.", "ouvre une élection") : Result.fail(err);
            }
            case "etat.close" -> {
                String err = (String) call("closeElection", S, s);
                return err == null ? Result.ok("Élection clôturée.", "clôture l'élection") : Result.fail(err);
            }
            case "etat.cancel" -> {
                String err = (String) call("cancelElection", S, s);
                return err == null ? Result.ok("Élection annulée.", "annule l'élection") : Result.fail(err);
            }
            default -> {
                return Result.fail("Action inconnue.");
            }
        }
    }

    /** Joueur connu par pseudo ou par nom RP (sans tenir compte de la casse). */
    private static UUID find(MinecraftServer s, String query) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return null;
        for (Players.Known k : Players.all(s)) {
            if (k.name().toLowerCase(Locale.ROOT).equals(q) || Players.display(s, k.id()).toLowerCase(Locale.ROOT).equals(q)) return k.id();
        }
        return null;
    }
}
