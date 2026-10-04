package com.minenorth_admin.server;

import com.minenorth.vehicles.fourriere.ImpoundData;
import com.minenorth.vehicles.garage.GarageData;
import com.minenorth_admin.Players;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

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

    static CompoundTag view(MinecraftServer s, UUID id) {
        CompoundTag t = new CompoundTag();
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
        return g || f;
    }

    private static String label(GarageData.Stored v) {
        return v.label == null || v.label.isEmpty() ? v.itemId : v.label;
    }

    /**
     * a = "g" (garage) ou "f" (fourrière) ; n = index ; b = libellé attendu (sécurité si la liste a bougé).
     * Pour garage.transfer : b = libellé attendu, c = nom du nouveau propriétaire.
     */
    static Result act(ServerPlayer actor, UUID id, String action, String a, String b, long n) {
        MinecraftServer s = actor.server;
        GarageData gd = GarageData.get(s);
        ImpoundData id2 = ImpoundData.get(s);
        String name = Players.name(s, id);
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
                return Result.ok(label(v) + " transféré de " + name + " à " + to.name() + ".", "transfère " + label(v) + " à " + to.name());
            }
            default -> {
                return Result.fail("Action inconnue.");
            }
        }
    }
}
