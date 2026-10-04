package com.minenorth_admin.server;

import com.minenorth_admin.Players;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Inventaire + coffre de l'ender d'un joueur, en ligne (direct) ou hors ligne (fichier world/playerdata).
 * Numérotation unique des emplacements : 0-35 inventaire (0-8 = barre rapide), 36-39 armure (pieds -> tête),
 * 40 main secondaire. Ender : 0-26.
 */
public final class PlayerInv {
    public static final int INV_SIZE = 41, ENDER_SIZE = 27;

    @Nullable private final ServerPlayer online;
    @Nullable private final File file;
    @Nullable private final CompoundTag root;
    public final ItemStack[] inv = new ItemStack[INV_SIZE];
    public final ItemStack[] ender = new ItemStack[ENDER_SIZE];

    private PlayerInv(@Nullable ServerPlayer online, @Nullable File file, @Nullable CompoundTag root) {
        this.online = online;
        this.file = file;
        this.root = root;
    }

    public boolean isOnline() {
        return online != null;
    }

    /** null si le joueur n'est pas en ligne et n'a pas de fichier lisible. */
    @Nullable
    public static PlayerInv load(MinecraftServer s, java.util.UUID id) {
        ServerPlayer p = Players.online(s, id);
        if (p != null) {
            PlayerInv pi = new PlayerInv(p, null, null);
            for (int i = 0; i < INV_SIZE; i++) pi.inv[i] = p.getInventory().getItem(i).copy();
            for (int i = 0; i < ENDER_SIZE; i++) pi.ender[i] = p.getEnderChestInventory().getItem(i).copy();
            return pi;
        }
        File f = Players.dataFile(s, id);
        if (!f.isFile()) return null;
        try {
            CompoundTag root = NbtIo.readCompressed(f);
            PlayerInv pi = new PlayerInv(null, f, root);
            java.util.Arrays.fill(pi.inv, ItemStack.EMPTY);
            java.util.Arrays.fill(pi.ender, ItemStack.EMPTY);
            ListTag l = root.getList("Inventory", Tag.TAG_COMPOUND);
            for (int i = 0; i < l.size(); i++) {
                CompoundTag t = l.getCompound(i);
                int slot = fromNbtSlot(t.getByte("Slot") & 255);
                if (slot >= 0) pi.inv[slot] = ItemStack.of(t);
            }
            ListTag e = root.getList("EnderItems", Tag.TAG_COMPOUND);
            for (int i = 0; i < e.size(); i++) {
                CompoundTag t = e.getCompound(i);
                int slot = t.getByte("Slot") & 255;
                if (slot < ENDER_SIZE) pi.ender[slot] = ItemStack.of(t);
            }
            return pi;
        } catch (IOException | RuntimeException ex) {
            com.minenorth_admin.MineNorthAdmin.LOG.warn("[Admin] Lecture impossible de {}", f, ex);
            return null;
        }
    }

    /** Slot NBT vanilla (0-35, 100-103 armure, 150 = -106 main secondaire) -> notre numérotation. */
    private static int fromNbtSlot(int s) {
        if (s < 36) return s;
        if (s >= 100 && s <= 103) return 36 + (s - 100);
        if (s == 150) return 40;
        return -1;
    }

    private static int toNbtSlot(int s) {
        if (s < 36) return s;
        if (s < 40) return 100 + (s - 36);
        return -106;
    }

    public ItemStack get(boolean enderChest, int slot) {
        ItemStack[] a = enderChest ? ender : inv;
        return slot >= 0 && slot < a.length && a[slot] != null ? a[slot] : ItemStack.EMPTY;
    }

    public void set(boolean enderChest, int slot, ItemStack st) {
        ItemStack[] a = enderChest ? ender : inv;
        if (slot >= 0 && slot < a.length) a[slot] = st;
    }

    /** Premier emplacement libre de l'inventaire principal (0-35), -1 si plein. */
    public int firstFree() {
        for (int i = 0; i < 36; i++) if (inv[i] == null || inv[i].isEmpty()) return i;
        return -1;
    }

    /**
     * Écrit les changements : directement dans l'inventaire si le joueur est en ligne, sinon dans son fichier
     * (refusé s'il s'est connecté entre-temps, pour ne pas écraser ses données).
     */
    public boolean save(MinecraftServer s, java.util.UUID id) {
        if (online != null) {
            if (online.isRemoved()) return false;
            for (int i = 0; i < INV_SIZE; i++) online.getInventory().setItem(i, inv[i] == null ? ItemStack.EMPTY : inv[i]);
            for (int i = 0; i < ENDER_SIZE; i++) online.getEnderChestInventory().setItem(i, ender[i] == null ? ItemStack.EMPTY : ender[i]);
            online.getInventory().setChanged();
            online.containerMenu.broadcastChanges();
            return true;
        }
        if (Players.online(s, id) != null || root == null || file == null) return false;
        ListTag l = new ListTag();
        for (int i = 0; i < INV_SIZE; i++) {
            if (inv[i] == null || inv[i].isEmpty()) continue;
            CompoundTag t = new CompoundTag();
            t.putByte("Slot", (byte) toNbtSlot(i));
            inv[i].save(t);
            l.add(t);
        }
        root.put("Inventory", l);
        ListTag e = new ListTag();
        for (int i = 0; i < ENDER_SIZE; i++) {
            if (ender[i] == null || ender[i].isEmpty()) continue;
            CompoundTag t = new CompoundTag();
            t.putByte("Slot", (byte) i);
            ender[i].save(t);
            e.add(t);
        }
        root.put("EnderItems", e);
        try {
            File tmp = new File(file.getParentFile(), file.getName() + ".admin_tmp");
            NbtIo.writeCompressed(root, tmp);
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException ex) {
            com.minenorth_admin.MineNorthAdmin.LOG.error("[Admin] Écriture impossible de {}", file, ex);
            return false;
        }
    }

    /** Pour l'écran : liste des objets non vides avec leur emplacement. */
    public ListTag toNbt(boolean enderChest) {
        ItemStack[] a = enderChest ? ender : inv;
        ListTag l = new ListTag();
        for (int i = 0; i < a.length; i++) {
            if (a[i] == null || a[i].isEmpty()) continue;
            CompoundTag t = new CompoundTag();
            t.putInt("S", i);
            t.put("I", a[i].save(new CompoundTag()));
            l.add(t);
        }
        return l;
    }
}
