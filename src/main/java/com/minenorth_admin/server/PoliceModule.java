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
 * Onglet Police (mod MineNorth Police, modid « minenorthpolice ») : faire entrer / sortir un joueur de la police, donner tablette et équipement.
 * Le grade se change aussi ici (police.grade), comme avec la tablette du Commissaire ou /police grade.
 * Passe par fr.minenorth.police.api.PoliceApi en réflexion : le panneau compile et démarre sans le mod Police.
 */
final class PoliceModule {
    private PoliceModule() {}

    private static final String API = "fr.minenorth.police.api.PoliceApi";

    private static Object call(String method, Class<?>[] types, Object... args) {
        try {
            Method m = Class.forName(API).getMethod(method, types);
            return m.invoke(null, args);
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("API du mod Police indisponible (" + method + ") : mets à jour le mod Police.", e);
        }
    }

    private static String[] grades() {
        return (String[]) call("grades", new Class<?>[0]);
    }

    private static int grade(MinecraftServer s, UUID id) {
        return (Integer) call("grade", new Class<?>[]{MinecraftServer.class, UUID.class}, s, id);
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Integer> officers(MinecraftServer s) {
        return (Map<UUID, Integer>) call("officers", new Class<?>[]{MinecraftServer.class}, s);
    }

    private static String name(MinecraftServer s, UUID id) {
        return (String) call("name", new Class<?>[]{MinecraftServer.class, UUID.class}, s, id);
    }

    private static String setGrade(MinecraftServer s, UUID id, String name, int grade) {
        return (String) call("setGrade", new Class<?>[]{MinecraftServer.class, UUID.class, String.class, int.class}, s, id, name, grade);
    }

    /**
     * Fiche : le joueur est-il policier (et à quel grade), la liste des grades et celle des effectifs (noms RP).
     */
    static CompoundTag view(MinecraftServer s, UUID id) {
        CompoundTag t = new CompoundTag();
        String[] g = grades();
        int grade = grade(s, id);
        t.putBoolean("Police", grade >= 0);
        t.putString("Grade", grade >= 0 && grade < g.length ? g[grade] : "");
        t.putInt("GradeIdx", grade);
        ListTag names = new ListTag();
        for (String n : g) names.add(net.minecraft.nbt.StringTag.valueOf(n));
        t.put("Grades", names);
        record Row(String name, int grade, boolean on) {}
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<UUID, Integer> e : officers(s).entrySet()) {
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
        t.put("Officers", ol);
        return t;
    }

    /** Retire le joueur de la police (suppression du joueur). */
    static boolean wipe(MinecraftServer s, UUID id) {
        try {
            if (grade(s, id) < 0) return false;
            setGrade(s, id, Players.name(s, id), -1);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** police.add (entre dans la police au grade le plus bas), police.grade (n = grade), police.remove, police.tablet, police.kit. */
    static Result act(ServerPlayer actor, UUID id, String action, long n) {
        MinecraftServer s = actor.server;
        String name = Players.display(s, id);
        boolean police = grade(s, id) >= 0;
        switch (action) {
            case "police.add" -> {
                if (police) return Result.fail(name + " fait déjà partie de la police.");
                String[] g = grades();
                // Pseudo transmis au mod Police (fichier des citoyens) ; il affiche lui-même le nom RP.
                setGrade(s, id, Players.name(s, id), g.length - 1);
                return Result.ok(name + " fait maintenant partie de la police (" + g[g.length - 1] + ").", "ajouté à la police");
            }
            case "police.grade" -> {
                String[] g = grades();
                if (n < 0 || n >= g.length) return Result.fail("Grade inconnu.");
                if (!police) return Result.fail(name + " n'est pas policier.");
                int current = grade(s, id);
                if (current == n) return Result.fail(name + " est déjà " + g[(int) n] + ".");
                setGrade(s, id, Players.name(s, id), (int) n);
                return Result.ok(name + " passe " + g[(int) n] + ".", "change le grade de police : " + g[current] + " -> " + g[(int) n]);
            }
            case "police.remove" -> {
                if (!police) return Result.fail(name + " ne fait pas partie de la police.");
                setGrade(s, id, Players.name(s, id), -1);
                return Result.ok(name + " ne fait plus partie de la police.", "retiré de la police");
            }
            case "police.tablet" -> {
                if (!police) return Result.fail(name + " n'est pas policier.");
                boolean ok = (Boolean) call("giveTablet", new Class<?>[]{MinecraftServer.class, UUID.class}, s, id);
                return ok ? Result.ok("Tablette de police donnée à " + name + ".", "donne une tablette de police")
                        : Result.fail("Le joueur doit être connecté.");
            }
            case "police.kit" -> {
                if (!police) return Result.fail(name + " n'est pas policier.");
                int count = (Integer) call("giveEquipment", new Class<?>[]{MinecraftServer.class, UUID.class}, s, id);
                return count >= 0 ? Result.ok("Équipement de police donné à " + name + " (" + count + " objet(s)).", "donne l'équipement de police")
                        : Result.fail("Le joueur doit être connecté.");
            }
            default -> {
                return Result.fail("Action inconnue.");
            }
        }
    }
}
