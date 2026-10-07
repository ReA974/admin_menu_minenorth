package com.minenorth_admin.server;

import com.minenorth_admin.Players;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Onglet Pompiers (mod MineNorth Secours, modid « minenorthsecours ») : nommer un joueur pompier, gérer son grade,
 * le retirer, lui donner la tablette.
 * Passe par fr.minenorth.secours.api.SecoursApi en réflexion : le panneau compile et démarre sans le mod Secours.
 */
final class SecoursModule {
    private SecoursModule() {}

    private static final String API = "fr.minenorth.secours.api.SecoursApi";

    private static Object call(String method, Class<?>[] types, Object... args) {
        try {
            Method m = Class.forName(API).getMethod(method, types);
            return m.invoke(null, args);
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("API du mod Secours indisponible (" + method + ") : mets à jour le mod Secours.", e);
        }
    }

    private static String[] grades() {
        return (String[]) call("grades", new Class<?>[0]);
    }

    private static int grade(MinecraftServer s, UUID id) {
        return (Integer) call("grade", new Class<?>[]{MinecraftServer.class, UUID.class}, s, id);
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Integer> staff(MinecraftServer s) {
        return (Map<UUID, Integer>) call("staff", new Class<?>[]{MinecraftServer.class}, s);
    }

    private static String setGrade(MinecraftServer s, UUID id, String name, int grade) {
        return (String) call("setGrade", new Class<?>[]{MinecraftServer.class, UUID.class, String.class, int.class}, s, id, name, grade);
    }

    /** Fiche : le joueur est-il pompier (et à quel grade), la liste des grades et celle des effectifs (noms RP). */
    static CompoundTag view(MinecraftServer s, UUID id) {
        CompoundTag t = new CompoundTag();
        String[] g = grades();
        int grade = grade(s, id);
        t.putBoolean("Secours", grade >= 0);
        t.putInt("GradeIdx", grade);
        t.putString("Grade", grade >= 0 && grade < g.length ? g[grade] : "");
        ListTag names = new ListTag();
        for (String n : g) names.add(net.minecraft.nbt.StringTag.valueOf(n));
        t.put("Grades", names);
        record Row(String name, int grade, boolean on) {}
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<UUID, Integer> e : staff(s).entrySet()) {
            rows.add(new Row(Players.display(s, e.getKey()), Math.max(0, Math.min(g.length - 1, e.getValue())),
                    Players.online(s, e.getKey()) != null));
        }
        rows.sort((a, b) -> a.grade() != b.grade() ? Integer.compare(a.grade(), b.grade()) : a.name().compareToIgnoreCase(b.name()));
        ListTag ol = new ListTag();
        for (Row r : rows) {
            CompoundTag o = new CompoundTag();
            o.putString("Name", r.name());
            o.putString("Grade", g[r.grade()]);
            o.putBoolean("On", r.on());
            ol.add(o);
        }
        t.put("Members", ol);
        return t;
    }

    /** secours.grade (n = 0..2 : nomme ou change le grade), secours.remove, secours.tablet. */
    static Result act(ServerPlayer actor, UUID id, String action, long n) {
        MinecraftServer s = actor.server;
        String name = Players.display(s, id);
        int current = grade(s, id);
        switch (action) {
            case "secours.grade" -> {
                String[] g = grades();
                if (n < 0 || n >= g.length) return Result.fail("Grade inconnu.");
                if (current == n) return Result.fail(name + " est déjà " + g[(int) n] + ".");
                // Pseudo transmis au mod Secours ; il affiche lui-même le nom RP.
                setGrade(s, id, Players.name(s, id), (int) n);
                return current < 0
                        ? Result.ok(name + " est maintenant pompier (" + g[(int) n] + ").", "nomme pompier (" + g[(int) n] + ")")
                        : Result.ok(name + " passe " + g[(int) n] + ".", "change le grade de pompier : " + g[current] + " -> " + g[(int) n]);
            }
            case "secours.remove" -> {
                if (current < 0) return Result.fail(name + " n'est pas pompier.");
                setGrade(s, id, Players.name(s, id), -1);
                return Result.ok(name + " n'est plus pompier.", "retiré des pompiers");
            }
            case "secours.tablet" -> {
                if (current < 0) return Result.fail(name + " n'est pas pompier.");
                boolean ok = (Boolean) call("giveTablet", new Class<?>[]{MinecraftServer.class, UUID.class}, s, id);
                return ok ? Result.ok("Tablette des secours donnée à " + name + ".", "donne une tablette des secours")
                        : Result.fail("Le joueur doit être connecté.");
            }
            default -> {
                return Result.fail("Action inconnue.");
            }
        }
    }
}
