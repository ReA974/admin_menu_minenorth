package com.minenorth_admin.staff;

import com.minenorth_admin.AdminConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.Set;

/**
 * Droits effectifs d'un joueur dans le panneau.
 * Propriétaire = op du niveau configuré (4 par défaut) : tous les droits, rang infini.
 * Sinon : droits du rôle attribué dans l'onglet Staff (rien si aucun rôle).
 */
public final class Access {
    public static final int OWNER_RANK = Integer.MAX_VALUE;

    public final boolean owner;
    public final int rank;
    public final Set<Perm> perms;
    @Nullable public final String roleName;

    private Access(boolean owner, int rank, Set<Perm> perms, @Nullable String roleName) {
        this.owner = owner;
        this.rank = rank;
        this.perms = perms;
        this.roleName = roleName;
    }

    public static final Access NONE = new Access(false, 0, EnumSet.noneOf(Perm.class), null);

    public static Access of(@Nullable ServerPlayer p) {
        if (p == null) return NONE;
        if (p.hasPermissions(AdminConfig.OWNER_OP_LEVEL.get())) {
            return new Access(true, OWNER_RANK, EnumSet.allOf(Perm.class), "Propriétaire");
        }
        StaffData d = StaffData.get(p.server);
        StaffData.Member m = d.member(p.getUUID());
        StaffData.Role r = m == null ? null : d.role(m.role);
        if (r == null) return NONE;
        EnumSet<Perm> perms = r.perms.isEmpty() ? EnumSet.noneOf(Perm.class) : EnumSet.copyOf(r.perms);
        return new Access(false, r.rank, perms, r.name);
    }

    /** Pour le prédicat de la commande : la console a tous les droits. */
    public static boolean canOpen(CommandSourceStack s) {
        if (!s.isPlayer()) return s.hasPermission(4);
        return of(s.getPlayer()).has(Perm.PANEL);
    }

    public boolean has(Perm p) {
        return perms.contains(p) && (p == Perm.PANEL || perms.contains(Perm.PANEL));
    }

    /** Rang du membre staff (0 si simple joueur, infini si propriétaire). */
    public static int rankOf(ServerPlayer actorContext, java.util.UUID id) {
        ServerPlayer online = actorContext.server.getPlayerList().getPlayer(id);
        if (online != null) return of(online).rank;
        StaffData d = StaffData.get(actorContext.server);
        StaffData.Member m = d.member(id);
        StaffData.Role r = m == null ? null : d.role(m.role);
        return r == null ? 0 : r.rank;
    }
}
