package com.minenorth_admin.server;

import com.minenorth.vehicles.fourriere.ImpoundData;
import com.minenorth.vehicles.garage.GarageData;
import com.minenorth_admin.Players;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/** Onglet Garage (MineNorth véhicules) : garage et fourrière d'un joueur. Chargé seulement si le mod est présent. */
final class GarageModule {
    private GarageModule() {}

    private static ListTag list(List<GarageData.Stored> l) {
        ListTag out = new ListTag();
        for (GarageData.Stored v : l) {
            CompoundTag t = new CompoundTag();
            t.putString("Label", v.label == null || v.label.isEmpty() ? v.itemId : v.label);
            t.putString("Item", v.itemId == null ? "" : v.itemId);
            out.add(t);
        }
        return out;
    }

    // ------------------------------------------------------------------ fichier des immatriculations
    // Lu par réflexion : le panneau compile avec l'ancien jar du garage (libs/) et l'onglet Plaques reste vide s'il est trop ancien.

    private static final String REGISTRY = "com.minenorth.vehicles.registry.PlateRegistry";

    private static Object registry(MinecraftServer s) {
        try {
            return Class.forName(REGISTRY).getMethod("get", MinecraftServer.class).invoke(null, s);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String str(Object o, String f) {
        try {
            Object v = o.getClass().getField(f).get(o);
            return v == null ? "" : String.valueOf(v);
        } catch (Throwable t) {
            return "";
        }
    }

    /** Lignes du fichier au nom du joueur, dans l'ordre d'achat. */
    private static List<Object> plates(MinecraftServer s, UUID id) {
        List<Object> out = new ArrayList<>();
        Object reg = registry(s);
        if (reg == null) return out;
        try {
            for (Object e : (List<?>) reg.getClass().getField("entries").get(reg)) {
                if (id.equals(e.getClass().getField("owner").get(e))) out.add(e);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static ListTag plateList(MinecraftServer s, UUID id) {
        ListTag out = new ListTag();
        SimpleDateFormat fmt = new SimpleDateFormat("dd/MM/yy");
        for (Object e : plates(s, id)) {
            CompoundTag t = new CompoundTag();
            String color = str(e, "color");
            t.putString("Plate", str(e, "plate"));
            t.putString("Label", str(e, "plate") + "  " + str(e, "model") + (color.isEmpty() ? "" : " (" + color + ")"));
            long time = 0;
            try { time = e.getClass().getField("time").getLong(e); } catch (Throwable ignored) {}
            t.putString("Item", fmt.format(new Date(time)));
            out.add(t);
        }
        return out;
    }

    private static Result plateAction(ServerPlayer actor, UUID id, String action, String plate, String newOwner) {
        MinecraftServer s = actor.server;
        Object reg = registry(s);
        if (reg == null) return Result.fail("Le mod garage installé est trop ancien (pas de fichier des immatriculations).");
        boolean mine = false;
        for (Object e : plates(s, id)) if (str(e, "plate").equals(plate)) mine = true;
        if (!mine) return Result.fail("Plaque introuvable pour ce joueur (liste modifiée ?). Actualise.");
        String name = Players.display(s, id);
        try {
            if (action.equals("garage.plate.delete")) {
                reg.getClass().getMethod("remove", MinecraftServer.class, String.class).invoke(reg, s, plate);
                return Result.ok("Plaque " + plate + " retirée du fichier des immatriculations.", "retire la plaque " + plate + " du fichier");
            }
            Players.Known to = Players.byName(s, newOwner);
            if (to == null) return Result.fail("Joueur introuvable : " + newOwner + ".");
            if (to.id().equals(id)) return Result.fail("Cette plaque est déjà à son nom.");
            reg.getClass().getMethod("transfer", MinecraftServer.class, String.class, UUID.class, String.class)
                    .invoke(reg, s, plate, to.id(), Players.name(s, to.id()));
            String toName = Players.display(s, to.id());
            return Result.ok("Plaque " + plate + " de " + name + " transférée à " + toName + ".", "transfère la plaque " + plate + " à " + toName);
        } catch (Throwable t) {
            return Result.fail("Action impossible sur le fichier des immatriculations : " + t);
        }
    }

    static CompoundTag view(MinecraftServer s, UUID id) {
        CompoundTag t = new CompoundTag();
        t.put("Plates", plateList(s, id));
        List<GarageData.Stored> g = GarageData.get(s).garages.get(id);
        List<GarageData.Stored> f = ImpoundData.get(s).vehicles.get(id);
        t.put("Garage", list(g == null ? List.of() : g));
        t.put("Impound", list(f == null ? List.of() : f));
        return t;
    }

    static boolean wipe(MinecraftServer s, UUID id) {
        GarageData gd = GarageData.get(s);
        ImpoundData im = ImpoundData.get(s);
        boolean g = gd.garages.remove(id) != null, f = im.vehicles.remove(id) != null;
        if (g) gd.setDirty();
        if (f) im.setDirty();
        boolean pl = false;
        Object reg = registry(s);
        if (reg != null) {
            for (Object e : plates(s, id)) {
                try {
                    reg.getClass().getMethod("remove", MinecraftServer.class, String.class).invoke(reg, s, str(e, "plate"));
                    pl = true;
                } catch (Throwable ignored) {}
            }
        }
        return g || f || pl;
    }

    private static String label(GarageData.Stored v) {
        return v.label == null || v.label.isEmpty() ? v.itemId : v.label;
    }

    /**
     * a = "g" (garage) ou "f" (fourrière) ; n = index ; b = libellé attendu (sécurité si la liste a bougé).
     * Pour garage.transfer : b = libellé attendu, c = nom du nouveau propriétaire.
     * garage.plate.delete / garage.plate.transfer : a = plaque, b = nom du nouveau propriétaire.
     */
    static Result act(ServerPlayer actor, UUID id, String action, String a, String b, long n) {
        if (action.startsWith("garage.plate.")) return plateAction(actor, id, action, a, b);
        MinecraftServer s = actor.server;
        GarageData gd = GarageData.get(s);
        ImpoundData id2 = ImpoundData.get(s);
        String name = Players.display(s, id);
        boolean transfer = action.equals("garage.transfer");
        boolean impound = !transfer && ("f".equals(a) || action.equals("garage.release"));
        List<GarageData.Stored> list = impound ? id2.vehicles.get(id) : gd.garages.get(id);
        String expected = transfer ? a : b;
        if (list == null || n < 0 || n >= list.size()) return Result.fail("Véhicule introuvable (liste modifiée ?). Actualise.");
        GarageData.Stored v = list.get((int) n);
        if (!label(v).equals(expected)) return Result.fail("La liste a changé entre-temps. Actualise.");

        switch (action) {
            case "garage.delete" -> {
                list.remove((int) n);
                if (impound) id2.setDirty();
                else gd.setDirty();
                return Result.ok(label(v) + " supprimé " + (impound ? "de la fourrière" : "du garage") + " de " + name + ".",
                        "supprime " + label(v) + (impound ? " (fourrière)" : " (garage)"));
            }
            case "garage.release" -> {
                list.remove((int) n);
                gd.of(id).add(v);
                id2.setDirty();
                gd.setDirty();
                return Result.ok(label(v) + " sorti de la fourrière et rangé dans le garage de " + name + ".", "libère " + label(v) + " de la fourrière");
            }
            case "garage.transfer" -> {
                Players.Known to = Players.byName(s, b);
                if (to == null) return Result.fail("Joueur introuvable : " + b + ".");
                if (to.id().equals(id)) return Result.fail("C'est déjà son véhicule.");
                list.remove((int) n);
                gd.of(to.id()).add(v);
                gd.setDirty();
                String toName = Players.display(s, to.id());
                return Result.ok(label(v) + " transféré de " + name + " à " + toName + ".", "transfère " + label(v) + " à " + toName);
            }
            default -> {
                return Result.fail("Action inconnue.");
            }
        }
    }
}
