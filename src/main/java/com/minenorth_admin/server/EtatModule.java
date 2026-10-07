package com.minenorth_admin.server;

import com.minenorth_admin.Players;
import net.minecraft.nbt.CompoundTag;
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
        return t;
    }

    /** etat.tax (a = pourcentage), etat.taxreset, etat.mayor (a = pseudo ou nom), etat.nomayor, etat.open (n = minutes, 0 = config), etat.close, etat.cancel. */
    static Result act(ServerPlayer actor, String action, String a, long n) {
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
