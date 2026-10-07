package com.minenorth_admin.client;

import com.minenorth_admin.MineNorthAdmin;
import com.minenorth_admin.net.Net;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Panneau d'administration : onglets Joueurs (banque, permis, garage, police, inventaire, TP), Staff (rôles, membres) et Journal.
 * L'écran n'affiche que ce que le serveur envoie ; toutes les actions sont revalidées côté serveur.
 */
@OnlyIn(Dist.CLIENT)
public class AdminScreen extends Screen {
    static final int W = 400, H = 250;
    private static final ResourceLocation LOGO = new ResourceLocation(MineNorthAdmin.MODID, "textures/gui/logo.png");
    static final int BLUE = 0xFF161048, PANEL = 0xFF0E0A34, HOVER = 0xFF2E2480;
    static final int TEXT = 0xFFCFE3FF, DIM = 0xFF8FA8E0, WARN = 0xFFFFE066, OK = 0xFF5FE0A0, BAD = 0xFFFF5F6B, WHITE = 0xFFFFFFFF;
    static final int CYAN = Btn.CYAN;

    enum Tab { PLAYERS, STAFF, ETAT, LOGS }

    // ------------------------------------------------------------------ état
    private Tab tab = Tab.PLAYERS;
    private CompoundTag home = new CompoundTag();
    @Nullable private CompoundTag player, staff, logs, etat;
    private final Set<String> perms = new HashSet<>();
    private boolean owner;
    private String message = "";
    private boolean messageOk = true;

    // joueurs
    @Nullable private UUID selected;
    private String section = "";
    private int listOffset;
    private int permisSel = -1, permisOffset;
    /** 0 = garage, 1 = fourrière, 2 = plaques (fichier des immatriculations). */
    private int garageMode;

    private String garageKey() {
        return garageMode == 1 ? "Impound" : garageMode == 2 ? "Plates" : "Garage";
    }
    private int garageSel = -1, garageOffset;
    private int policeOffset, secoursOffset;
    private boolean invEnder;
    private int invSel = -1;
    private String confirm = "";   // action en attente de confirmation (2e clic)

    // staff
    private boolean staffMembers;
    @Nullable private String roleSel;
    @Nullable private UUID memberSel;
    private int roleOffset, memberOffset;
    @Nullable private String newMemberRole;

    // journal
    private int logOffset;

    // champs texte conservés entre deux reconstructions de l'écran
    private final Map<String, String> inputs = new HashMap<>();
    private final Map<String, EditBox> boxes = new HashMap<>();
    @Nullable private String focusKey;

    private int left, top;

    public AdminScreen() {
        super(Component.literal("Panneau admin"));
    }

    // ================================================================== réseau

    public void receive(Net.View v) {
        CompoundTag d = v.data();
        switch (v.kind()) {
            case "home" -> {
                home = d;
                perms.clear();
                ListTag pl = d.getList("Perms", Tag.TAG_STRING);
                for (int i = 0; i < pl.size(); i++) perms.add(pl.getString(i));
                owner = d.getBoolean("Owner");
                if (!has("STAFF") && tab == Tab.STAFF) tab = Tab.PLAYERS;
                // joueur supprimé : il disparaît de la liste, on ferme sa fiche
                if (selected != null) {
                    boolean still = false;
                    ListTag ps = d.getList("Players", Tag.TAG_COMPOUND);
                    for (int i = 0; i < ps.size() && !still; i++) still = ps.getCompound(i).getUUID("Id").equals(selected);
                    if (!still) {
                        selected = null;
                        player = null;
                    }
                }
                if (!has("LOGS") && tab == Tab.LOGS) tab = Tab.PLAYERS;
                if ((!has("ETAT_VIEW") || !home.getBoolean("Etat")) && tab == Tab.ETAT) tab = Tab.PLAYERS;
            }
            case "player" -> {
                UUID id = d.getUUID("Id");
                if (!id.equals(selected)) resetSelections();
                String newSection = d.getString("Section");
                if (!newSection.equals(section)) {
                    permisSel = -1;
                    garageSel = -1;
                    invSel = -1;
                }
                player = d;
                selected = id;
                section = newSection;
            }
            case "staff" -> staff = d;
            case "logs" -> logs = d;
            case "etat" -> etat = d;
            default -> {}
        }
        if (!v.message().isEmpty()) {
            message = v.message();
            messageOk = v.ok();
        }
        confirm = "";
        rebuildWidgets();
    }

    private void resetSelections() {
        permisSel = garageSel = invSel = -1;
        permisOffset = garageOffset = policeOffset = secoursOffset = 0;
        confirm = "";
    }

    boolean has(String perm) {
        return owner || (perms.contains(perm) && perms.contains("PANEL"));
    }

    private void send(String action, @Nullable UUID target, String a, String b, long n) {
        Net.CHANNEL.sendToServer(new Net.Req(action, target == null ? Net.NIL : target, a == null ? "" : a, b == null ? "" : b, n));
    }

    private void send(String action) {
        send(action, null, "", "", 0);
    }

    private void openPlayer(UUID id, String sec) {
        send("player", id, sec, "", 0);
    }

    // ================================================================== widgets

    private Btn btn(int x, int y, int w, int h, String label, int color, Runnable r) {
        return addRenderableWidget(new Btn(left + x, top + y, w, h, label, color, r));
    }

    private EditBox box(String key, int x, int y, int w, String hint, int max) {
        EditBox b = new EditBox(font, left + x, top + y, w, 14, Component.literal(hint));
        b.setMaxLength(max);
        b.setHint(Component.literal(hint).withStyle(ChatFormatting.DARK_GRAY));
        b.setValue(inputs.getOrDefault(key, ""));
        b.setResponder(val -> {
            inputs.put(key, val);
            if (key.equals("search")) listOffset = 0;
            if (key.equals("logSearch")) logOffset = 0;
        });
        boxes.put(key, b);
        addRenderableWidget(b);
        if (key.equals(focusKey)) {
            setFocused(b);
            b.setFocused(true);
        }
        return b;
    }

    private String in(String key) {
        return inputs.getOrDefault(key, "").trim();
    }

    private void confirmThen(String key, Runnable r) {
        if (confirm.equals(key)) {
            confirm = "";
            r.run();
        } else {
            confirm = key;
            message = "Clique une deuxième fois pour confirmer.";
            messageOk = false;
            rebuildWidgets();
        }
    }

    @Override
    protected void rebuildWidgets() {
        // on mémorise le champ actif pour le rendre après reconstruction
        focusKey = null;
        for (Map.Entry<String, EditBox> e : boxes.entrySet()) if (e.getValue().isFocused()) focusKey = e.getKey();
        super.rebuildWidgets();
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        boxes.clear();

        int x = W - 8;
        x -= 44;
        btn(x, 9, 44, 14, "Fermer", Btn.GHOST, this::onClose);
        if (has("LOGS")) {
            x -= 54;
            btn(x, 9, 50, 14, "Journal", Btn.GHOST, () -> {
                tab = Tab.LOGS;
                send("logs");
                rebuildWidgets();
            }).selected(tab == Tab.LOGS);
        }
        if (has("STAFF")) {
            x -= 46;
            btn(x, 9, 42, 14, "Staff", Btn.GHOST, () -> {
                tab = Tab.STAFF;
                send("staff");
                rebuildWidgets();
            }).selected(tab == Tab.STAFF);
        }
        if (has("ETAT_VIEW") && home.getBoolean("Etat")) {
            x -= 44;
            btn(x, 9, 40, 14, "État", Btn.GHOST, () -> {
                tab = Tab.ETAT;
                send("etat");
                rebuildWidgets();
            }).selected(tab == Tab.ETAT);
        }
        x -= 54;
        btn(x, 9, 50, 14, "Joueurs", Btn.GHOST, () -> {
            tab = Tab.PLAYERS;
            send("home");
            rebuildWidgets();
        }).selected(tab == Tab.PLAYERS);

        if (has("STAFF_MODE")) {
            boolean sm = home.getBoolean("StaffMode");
            btn(8, 225, 110, 11, sm ? "Mode staff : ON" : "Mode staff : OFF", sm ? Btn.GREEN : Btn.DARK, () -> send("mode.toggle")).selected(sm);
        }
        switch (tab) {
            case PLAYERS -> initPlayers();
            case STAFF -> initStaff();
            case ETAT -> initEtat();
            case LOGS -> initLogs();
        }
    }

    // ================================================================== onglet joueurs

    private static final int PL_X = 8, PL_W = 110, PL_Y = 52, PL_ROW = 12, PL_ROWS = 13;
    static final int RX = 124, RW = 268;

    private List<CompoundTag> filteredPlayers() {
        List<CompoundTag> out = new ArrayList<>();
        String q = in("search").toLowerCase(Locale.ROOT);
        ListTag l = home.getList("Players", Tag.TAG_COMPOUND);
        for (int i = 0; i < l.size(); i++) {
            CompoundTag p = l.getCompound(i);
            if (q.isEmpty() || p.getString("Name").toLowerCase(Locale.ROOT).contains(q)
                    || p.getString("Pseudo").toLowerCase(Locale.ROOT).contains(q)) out.add(p);
        }
        return out;
    }

    private List<String> sections() {
        List<String> s = new ArrayList<>();
        if (home.getBoolean("Bank") && has("BANK_VIEW")) s.add("bank");
        if (home.getBoolean("Permis") && has("PERMIS_VIEW")) s.add("permis");
        if (home.getBoolean("Garage") && has("GARAGE_VIEW")) s.add("garage");
        if (home.getBoolean("Police") && has("POLICE_VIEW")) s.add("police");
        if (home.getBoolean("Secours") && has("SECOURS_VIEW")) s.add("secours");
        if (has("INV_VIEW")) s.add("inv");
        if (has("MODERATE")) s.add("mod");
        return s;
    }

    private static String sectionLabel(String s) {
        return switch (s) {
            case "bank" -> "Banque";
            case "permis" -> "Permis";
            case "garage" -> "Garage";
            case "police" -> "Police";
            case "secours" -> "Pompiers";
            case "mod" -> "Sanctions";
            default -> "Inventaire";
        };
    }

    private void initPlayers() {
        box("search", PL_X, 34, PL_W, "Nom RP ou pseudo…", 32);
        int max = Math.max(0, filteredPlayers().size() - PL_ROWS);
        btn(PL_X, PL_Y + PL_ROWS * PL_ROW + 4, PL_W / 2 - 1, 12, "▲", Btn.DARK, () -> listOffset = Math.max(0, listOffset - PL_ROWS))
                .enabled(listOffset > 0);
        btn(PL_X + PL_W / 2 + 1, PL_Y + PL_ROWS * PL_ROW + 4, PL_W / 2 - 1, 12, "▼", Btn.DARK,
                () -> listOffset = Math.min(max, listOffset + PL_ROWS)).enabled(listOffset < max);

        if (player == null || selected == null) return;
        boolean on = player.getBoolean("On");
        btn(300, 34, 44, 12, "TP vers", Btn.DARK, () -> send("tp.to", selected, "", section, 0)).enabled(on && has("TELEPORT"));
        btn(346, 34, 46, 12, "TP ici", Btn.DARK, () -> send("tp.here", selected, "", section, 0)).enabled(on && has("TELEPORT"));
        btn(330, 47, 62, 10, "Actualiser", Btn.GHOST, () -> openPlayer(selected, section));
        if (has("DELETE")) {
            UUID target = selected;
            btn(262, 47, 64, 10, confirm.equals("udel") ? "Confirmer ?" : "Supprimer", Btn.RED, () -> {
                if (confirm.equals("udel")) {
                    confirm = "";
                    send("user.delete", target, "", section, 0);
                } else {
                    confirm = "udel";
                    message = "Clique encore : TOUTES ses données (inventaire, banque, permis, garage) seront effacées.";
                    messageOk = false;
                    rebuildWidgets();
                }
            }).enabled(!on).selected(confirm.equals("udel"));
        }

        int x = RX;
        List<String> secs = sections();
        // Largeur des onglets adaptée à leur nombre (5 onglets avec Police).
        int tabW = secs.isEmpty() ? 64 : Math.min(64, (RW + 3) / secs.size() - 3);
        for (String s : secs) {
            String label = sectionLabel(s);
            if (font.width(label) > tabW - 4) label = s.equals("inv") ? "Inv." : s.equals("mod") ? "Sanc." : s.equals("secours") ? "Pomp." : label;
            btn(x, 60, tabW, 14, label, Btn.DARK, () -> {
                confirm = "";
                openPlayer(selected, s);
            }).selected(s.equals(section));
            x += tabW + 3;
        }
        CompoundTag data = player.contains("Data") ? player.getCompound("Data") : null;
        if (data == null) return;
        switch (section) {
            case "bank" -> initBank(data);
            case "permis" -> initPermis(data);
            case "garage" -> initGarage(data);
            case "police" -> initPolice(data);
            case "secours" -> initSecours(data);
            case "inv" -> initInv(data);
            case "mod" -> initMod(data);
            default -> {}
        }
    }

    /** "12,50" -> 1250 centimes ; -1 si invalide. */
    private static long cents(String s) {
        try {
            s = s.trim().replace(',', '.').replace("€", "").replace(" ", "");
            if (s.isEmpty()) return -1;
            long c = new BigDecimal(s).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
            return c >= 0 ? c : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    static String euros(long cents) {
        long a = Math.abs(cents);
        String units = String.format(Locale.FRANCE, "%,d", a / 100).replace(' ', ' ').replace(' ', ' ');
        return (cents < 0 ? "-" : "") + units + "," + String.format("%02d", a % 100) + " €";
    }

    private void amountAction(String action) {
        long c = cents(in("amount"));
        if (c < 0 || (c == 0 && !action.equals("bank.set"))) {
            message = "Montant invalide (ex. 1500 ou 12,50).";
            messageOk = false;
            return;
        }
        send(action, selected, "", "", c);
    }

    private void initBank(CompoundTag d) {
        boolean edit = has("BANK_EDIT");
        boolean acct = d.getBoolean("Account");
        boolean on = player.getBoolean("On");
        box("amount", RX, 160, 80, "Montant €", 16);
        btn(RX + 84, 159, 58, 16, "Définir", Btn.CYAN, () -> amountAction("bank.set")).enabled(edit && acct);
        btn(RX + 146, 159, 58, 16, "Ajouter", Btn.GREEN, () -> amountAction("bank.add")).enabled(edit && acct);
        btn(RX + 208, 159, 60, 16, "Retirer", Btn.PINK, () -> amountAction("bank.take")).enabled(edit && acct);
        btn(RX, 180, 86, 16, "Ouvrir un compte", Btn.DARK, () -> send("bank.open", selected, "", "", 0)).enabled(edit && !acct);
        btn(RX + 90, 180, 86, 16, d.getBoolean("Banker") ? "Retirer banquier" : "Nommer banquier", Btn.DARK,
                () -> send("bank.banker", selected, "", "", 0)).enabled(edit);
        btn(RX + 180, 180, 88, 16, "Carte bancaire", Btn.DARK, () -> send("bank.card", selected, "", "", 0)).enabled(edit && acct && on);
        if (d.contains("Loan")) {
            boolean pending = d.getCompound("Loan").getString("Status").startsWith("Demande");
            btn(RX, 200, 130, 16, pending ? "Refuser la demande" : "Annuler la dette", Btn.RED,
                    () -> confirmThen("forgive", () -> send("bank.forgive", selected, "", "", 0))).enabled(edit)
                    .selected(confirm.equals("forgive"));
        }
    }

    private static final int LIST_Y = 94, LIST_ROW = 13, LIST_ROWS = 7;

    private void listScroll(int count, Runnable up, Runnable down, boolean canUp, boolean canDown) {
        if (count <= LIST_ROWS) return;
        btn(RX + RW - 12, LIST_Y, 12, LIST_ROWS * LIST_ROW / 2 - 1, "▲", Btn.DARK, up).enabled(canUp);
        btn(RX + RW - 12, LIST_Y + LIST_ROWS * LIST_ROW / 2 + 1, 12, LIST_ROWS * LIST_ROW / 2 - 1, "▼", Btn.DARK, down).enabled(canDown);
    }

    private void initPermis(CompoundTag d) {
        boolean edit = has("PERMIS_EDIT");
        ListTag lic = d.getList("Licences", Tag.TAG_COMPOUND);
        if (d.getBoolean("UsesPoints")) {
            box("points", RX + 90, 76, 28, "pts", 3);
            btn(RX + 121, 76, 50, 14, "Définir", Btn.CYAN, () -> {
                try {
                    send("permis.points", selected, "", "", Integer.parseInt(in("points")));
                } catch (NumberFormatException e) {
                    message = "Nombre de points invalide.";
                    messageOk = false;
                }
            }).enabled(edit);
        }
        int cds = d.getList("Cooldowns", Tag.TAG_COMPOUND).size();
        btn(RX + 176, 76, 92, 14, "Effacer délais (" + cds + ")", Btn.DARK, () -> send("permis.cooldowns", selected, "", "", 0))
                .enabled(edit && cds > 0);

        int max = Math.max(0, lic.size() - LIST_ROWS);
        listScroll(lic.size(), () -> permisOffset = Math.max(0, permisOffset - 1), () -> permisOffset = Math.min(max, permisOffset + 1),
                permisOffset > 0, permisOffset < max);

        boolean sel = permisSel >= 0 && permisSel < lic.size();
        String id = sel ? lic.getCompound(permisSel).getString("Id") : "";
        boolean hasIt = sel && lic.getCompound(permisSel).getBoolean("Has");
        box("days", RX, 192, 40, "Jours", 5);
        btn(RX + 44, 191, 70, 16, hasIt ? "Renouveler" : "Donner", Btn.GREEN, () -> {
            String v = in("days");
            long days;
            try {
                days = v.isEmpty() ? -1 : Long.parseLong(v);
            } catch (NumberFormatException e) {
                message = "Durée invalide (nombre de jours).";
                messageOk = false;
                return;
            }
            send("permis.give", selected, id, "", days);
        }).enabled(edit && sel);
        btn(RX + 118, 191, 70, 16, "Retirer", Btn.RED, () -> confirmThen("revoke", () -> send("permis.revoke", selected, id, "", 0)))
                .enabled(edit && hasIt).selected(confirm.equals("revoke"));
        btn(RX + 192, 191, 76, 16, "Nouvelle carte", Btn.DARK, () -> send("permis.card", selected, id, "", 0))
                .enabled(edit && hasIt && player.getBoolean("On"));
        btn(RX + 176, 209, 92, 13, "Arrêter l'épreuve", Btn.RED, () -> send("permis.stoptest", selected, "", "", 0))
                .enabled(edit && player.getBoolean("On"));
    }

    private void initGarage(CompoundTag d) {
        boolean edit = has("GARAGE_EDIT");
        ListTag g = d.getList("Garage", Tag.TAG_COMPOUND), f = d.getList("Impound", Tag.TAG_COMPOUND), pl = d.getList("Plates", Tag.TAG_COMPOUND);
        String[] names = {"Garage (" + g.size() + ")", "Fourrière (" + f.size() + ")", "Plaques (" + pl.size() + ")"};
        for (int i = 0; i < 3; i++) {
            final int mode = i;
            btn(RX + i * 90, 76, 86, 14, names[i], Btn.DARK, () -> {
                garageMode = mode;
                garageSel = -1;
                garageOffset = 0;
                rebuildWidgets();
            }).selected(garageMode == i);
        }
        ListTag list = d.getList(garageKey(), Tag.TAG_COMPOUND);
        int max = Math.max(0, list.size() - LIST_ROWS);
        listScroll(list.size(), () -> garageOffset = Math.max(0, garageOffset - 1), () -> garageOffset = Math.min(max, garageOffset + 1),
                garageOffset > 0, garageOffset < max);
        boolean sel = garageSel >= 0 && garageSel < list.size();
        String label = sel ? list.getCompound(garageSel).getString("Label") : "";
        int idx = garageSel;
        if (garageMode == 2) {
            String plate = sel ? list.getCompound(garageSel).getString("Plate") : "";
            btn(RX, 192, 70, 16, "Supprimer", Btn.RED,
                    () -> confirmThen("pdel", () -> send("garage.plate.delete", selected, plate, "", idx)))
                    .enabled(edit && sel).selected(confirm.equals("pdel"));
            box("newOwner", RX + 74, 193, 110, "Nom RP du propriétaire", 32);
            btn(RX + 188, 192, 80, 16, "Transférer", Btn.DARK, () -> {
                if (in("newOwner").isEmpty()) {
                    message = "Indique le nom du nouveau titulaire de la carte grise.";
                    messageOk = false;
                    return;
                }
                send("garage.plate.transfer", selected, plate, in("newOwner"), idx);
            }).enabled(edit && sel);
            return;
        }
        btn(RX, 192, 70, 16, "Supprimer", Btn.RED,
                () -> confirmThen("gdel", () -> send("garage.delete", selected, garageMode == 1 ? "f" : "g", label, idx)))
                .enabled(edit && sel).selected(confirm.equals("gdel"));
        if (garageMode == 1) {
            btn(RX + 74, 192, 110, 16, "Libérer → garage", Btn.GREEN, () -> send("garage.release", selected, "f", label, idx))
                    .enabled(edit && sel);
        } else {
            box("newOwner", RX + 74, 193, 110, "Nom RP du propriétaire", 32);
            btn(RX + 188, 192, 80, 16, "Transférer", Btn.DARK, () -> {
                if (in("newOwner").isEmpty()) {
                    message = "Indique le pseudo du nouveau propriétaire.";
                    messageOk = false;
                    return;
                }
                send("garage.transfer", selected, label, in("newOwner"), idx);
            }).enabled(edit && sel);
        }
    }

    // inventaire : position des cases
    private static final int INV_X = RX + 6, INV_Y = 96, CELL = 18;

    private int[] slotPos(int slot) {
        if (invEnder) return new int[]{INV_X + (slot % 9) * CELL, INV_Y + (slot / 9) * CELL};
        if (slot < 9) return new int[]{INV_X + slot * CELL, INV_Y + 3 * CELL + 4};
        if (slot < 36) return new int[]{INV_X + (slot % 9) * CELL, INV_Y + (slot / 9 - 1) * CELL};
        if (slot < 40) return new int[]{INV_X + 9 * CELL + 10, INV_Y + (39 - slot) * CELL};
        return new int[]{INV_X + 9 * CELL + 10 + CELL + 4, INV_Y + 3 * CELL};
    }

    private int slotCount() {
        return invEnder ? 27 : 41;
    }

    private int slotAt(double mx, double my) {
        for (int s = 0; s < slotCount(); s++) {
            int[] p = slotPos(s);
            int x = left + p[0], y = top + p[1];
            if (mx >= x && mx < x + CELL && my >= y && my < y + CELL) return s;
        }
        return -1;
    }

    private Map<Integer, ItemStack> items(CompoundTag d) {
        Map<Integer, ItemStack> m = new HashMap<>();
        ListTag l = d.getList(invEnder ? "Ender" : "Inv", Tag.TAG_COMPOUND);
        for (int i = 0; i < l.size(); i++) {
            CompoundTag t = l.getCompound(i);
            m.put(t.getInt("S"), ItemStack.of(t.getCompound("I")));
        }
        return m;
    }

    private void initInv(CompoundTag d) {
        if (!d.getBoolean("Found")) return;
        boolean edit = has("INV_EDIT");
        btn(RX, 76, 70, 14, "Inventaire", Btn.DARK, () -> {
            invEnder = false;
            invSel = -1;
            rebuildWidgets();
        }).selected(!invEnder);
        btn(RX + 74, 76, 74, 14, "Ender chest", Btn.DARK, () -> {
            invEnder = true;
            invSel = -1;
            rebuildWidgets();
        }).selected(invEnder);
        ItemStack sel = invSel >= 0 ? items(d).getOrDefault(invSel, ItemStack.EMPTY) : ItemStack.EMPTY;
        String itemId = sel.isEmpty() ? "" : String.valueOf(ForgeRegistries.ITEMS.getKey(sel.getItem()));
        String where = invEnder ? "ender" : "inv";
        int slot = invSel;
        btn(RX, 178, 64, 16, "Copier", Btn.CYAN, () -> send("inv.copy", selected, where, itemId, slot)).enabled(edit && !sel.isEmpty());
        btn(RX + 68, 178, 64, 16, "Prendre", Btn.DARK, () -> send("inv.take", selected, where, itemId, slot)).enabled(edit && !sel.isEmpty());
        btn(RX + 136, 178, 64, 16, "Supprimer", Btn.RED,
                () -> confirmThen("idel", () -> send("inv.delete", selected, where, itemId, slot)))
                .enabled(edit && !sel.isEmpty()).selected(confirm.equals("idel"));
        btn(RX, 198, 132, 16, "Donner l'objet en main", Btn.GREEN, () -> send("inv.give", selected, "inv", "", 0)).enabled(edit);
        btn(RX + 136, 198, 64, 16, "Tout vider", Btn.RED, () -> confirmThen("iclear", () -> send("inv.clear", selected, where, "", 0)))
                .enabled(edit).selected(confirm.equals("iclear"));
    }

    // ================================================================== onglet staff

    private static final int ST_Y = 54, ST_ROW = 14, ST_ROWS = 9;

    private List<CompoundTag> roles() {
        List<CompoundTag> out = new ArrayList<>();
        if (staff == null) return out;
        ListTag l = staff.getList("Roles", Tag.TAG_COMPOUND);
        for (int i = 0; i < l.size(); i++) out.add(l.getCompound(i));
        return out;
    }

    private List<CompoundTag> members() {
        List<CompoundTag> out = new ArrayList<>();
        if (staff == null) return out;
        ListTag l = staff.getList("Members", Tag.TAG_COMPOUND);
        for (int i = 0; i < l.size(); i++) out.add(l.getCompound(i));
        return out;
    }

    @Nullable
    private CompoundTag role(@Nullable String id) {
        if (id == null) return null;
        for (CompoundTag r : roles()) if (r.getString("Id").equals(id)) return r;
        return null;
    }

    private int myRank() {
        return staff == null ? 0 : staff.getInt("MyRank");
    }

    private void initStaff() {
        btn(8, 34, 60, 14, "Rôles", Btn.DARK, () -> {
            staffMembers = false;
            rebuildWidgets();
        }).selected(!staffMembers);
        btn(72, 34, 60, 14, "Membres", Btn.DARK, () -> {
            staffMembers = true;
            rebuildWidgets();
        }).selected(staffMembers);
        btn(W - 8 - 62, 36, 62, 10, "Actualiser", Btn.GHOST, () -> send("staff"));
        if (staff == null) return;
        if (staffMembers) initMembers();
        else initRoles();
    }

    private void initRoles() {
        List<CompoundTag> rs = roles();
        int max = Math.max(0, rs.size() - ST_ROWS);
        if (rs.size() > ST_ROWS) {
            btn(146, ST_Y, 12, ST_ROWS * ST_ROW / 2 - 1, "▲", Btn.DARK, () -> roleOffset = Math.max(0, roleOffset - 1)).enabled(roleOffset > 0);
            btn(146, ST_Y + ST_ROWS * ST_ROW / 2 + 1, 12, ST_ROWS * ST_ROW / 2 - 1, "▼", Btn.DARK,
                    () -> roleOffset = Math.min(max, roleOffset + 1)).enabled(roleOffset < max);
        }
        box("newRole", 8, 188, 104, "Nouveau rôle", 24);
        box("newRank", 116, 188, 42, "Rang", 3);
        btn(8, 206, 150, 16, "Créer le rôle", Btn.GREEN, () -> {
            int rank;
            try {
                rank = Integer.parseInt(in("newRank"));
            } catch (NumberFormatException e) {
                message = "Rang invalide (nombre, plus grand = plus de pouvoir).";
                messageOk = false;
                return;
            }
            send("role.create", null, in("newRole"), "", rank);
            inputs.put("newRole", "");
        });

        CompoundTag r = role(roleSel);
        if (r == null) return;
        boolean editable = r.getInt("Rank") < myRank();
        String id = r.getString("Id");
        if (!inputs.containsKey("roleName:" + id)) inputs.put("roleName:" + id, r.getString("Name"));
        if (!inputs.containsKey("roleRank:" + id)) inputs.put("roleRank:" + id, String.valueOf(r.getInt("Rank")));
        box("roleName:" + id, 166, ST_Y, 148, "Nom", 24).setEditable(editable);
        btn(318, ST_Y, 74, 14, "Renommer", Btn.DARK, () -> send("role.rename", null, id, in("roleName:" + id), 0)).enabled(editable);
        box("roleRank:" + id, 166, ST_Y + 18, 40, "Rang", 3).setEditable(editable);
        btn(210, ST_Y + 18, 60, 14, "Rang", Btn.DARK, () -> {
            try {
                send("role.rank", null, id, "", Integer.parseInt(in("roleRank:" + id)));
            } catch (NumberFormatException e) {
                message = "Rang invalide.";
                messageOk = false;
            }
        }).enabled(editable);
        Set<String> rp = new HashSet<>();
        ListTag pl = r.getList("Perms", Tag.TAG_STRING);
        for (int i = 0; i < pl.size(); i++) rp.add(pl.getString(i));
        com.minenorth_admin.staff.Perm[] all = com.minenorth_admin.staff.Perm.values();
        for (int i = 0; i < all.length; i++) {
            com.minenorth_admin.staff.Perm p = all[i];
            boolean on = rp.contains(p.name());
            int x = 166 + (i % 2) * 114, y = ST_Y + 40 + (i / 2) * 13;
            btn(x, y, 110, 12, (on ? "✔ " : "✖ ") + p.label, on ? Btn.GREEN : Btn.DARK,
                    () -> send("role.perm", null, id, p.name(), 0)).enabled(editable && has(p.name()));
        }
        btn(166, 214, 110, 14, "Supprimer le rôle", Btn.RED, () -> confirmThen("rdel", () -> {
            send("role.delete", null, id, "", 0);
            roleSel = null;
        })).enabled(editable).selected(confirm.equals("rdel"));
    }

    /** Rôles que je peux attribuer (rang inférieur au mien). */
    private List<CompoundTag> assignable() {
        List<CompoundTag> out = new ArrayList<>();
        for (CompoundTag r : roles()) if (r.getInt("Rank") < myRank()) out.add(r);
        return out;
    }

    private void initMembers() {
        List<CompoundTag> ms = members();
        int max = Math.max(0, ms.size() - ST_ROWS);
        if (ms.size() > ST_ROWS) {
            btn(240, ST_Y, 12, ST_ROWS * ST_ROW / 2 - 1, "▲", Btn.DARK, () -> memberOffset = Math.max(0, memberOffset - 1)).enabled(memberOffset > 0);
            btn(240, ST_Y + ST_ROWS * ST_ROW / 2 + 1, 12, ST_ROWS * ST_ROW / 2 - 1, "▼", Btn.DARK,
                    () -> memberOffset = Math.min(max, memberOffset + 1)).enabled(memberOffset < max);
        }
        box("memberName", 258, ST_Y + 12, 134, "Nom RP ou pseudo", 32);
        List<CompoundTag> as = assignable();
        if (newMemberRole == null || as.stream().noneMatch(r -> r.getString("Id").equals(newMemberRole))) {
            newMemberRole = as.isEmpty() ? null : as.get(as.size() - 1).getString("Id");   // le plus bas par défaut
        }
        CompoundTag nr = role(newMemberRole);
        btn(258, ST_Y + 30, 134, 16, "Rôle : " + (nr == null ? "aucun" : nr.getString("Name")), Btn.DARK, () -> {
            if (as.isEmpty()) return;
            int i = 0;
            for (int k = 0; k < as.size(); k++) if (as.get(k).getString("Id").equals(newMemberRole)) i = k;
            newMemberRole = as.get((i + 1) % as.size()).getString("Id");
            rebuildWidgets();
        }).enabled(!as.isEmpty());
        btn(258, ST_Y + 50, 134, 16, "Valider", Btn.GREEN, () -> send("member.set", null, newMemberRole, in("memberName"), 0))
                .enabled(nr != null);
        if (memberSel != null) {
            btn(258, ST_Y + 100, 134, 16, "Retirer du staff", Btn.RED,
                    () -> confirmThen("mdel", () -> send("member.remove", memberSel, "", "", 0)))
                    .selected(confirm.equals("mdel"));
            UUID ms2 = memberSel;
            btn(258, ST_Y + 120, 134, 16, "Voir sa fiche", Btn.DARK, () -> {
                tab = Tab.PLAYERS;
                openPlayer(ms2, sections().isEmpty() ? "inv" : sections().get(0));
            });
        }
    }

    // ================================================================== état

    private void initEtat() {
        boolean edit = has("ETAT_EDIT");
        boolean electionOn = etat != null && etat.getBoolean("Election");
        box("etatTax", 8, 98, 80, "% (ex. 2,5)", 8);
        btn(94, 97, 110, 16, "Fixer l'impôt", Btn.GREEN, () -> send("etat.tax", null, in("etatTax"), "", 0)).enabled(edit && !in("etatTax").isEmpty());
        btn(208, 97, 120, 16, "Valeur de la config", Btn.DARK, () -> send("etat.taxreset")).enabled(edit);
        box("etatMayor", 8, 134, 150, "Pseudo ou nom RP", 32);
        btn(162, 133, 100, 16, "Nommer maire", Btn.GREEN, () -> send("etat.mayor", null, in("etatMayor"), "", 0)).enabled(edit && !in("etatMayor").isEmpty());
        btn(266, 133, 120, 16, "Retirer le maire", Btn.RED, () -> confirmThen("nomayor", () -> send("etat.nomayor")))
                .enabled(edit && etat != null && !etat.getString("Mayor").isEmpty()).selected(confirm.equals("nomayor"));
        box("etatMin", 8, 170, 80, "minutes (vide = config)", 6);
        btn(94, 169, 70, 16, "Ouvrir", Btn.GREEN, () -> {
            String v = in("etatMin");
            long min = 0;
            try {
                if (!v.isEmpty()) min = Long.parseLong(v);
            } catch (NumberFormatException e) {
                message = "Durée invalide (minutes).";
                messageOk = false;
                return;
            }
            send("etat.open", null, "", "", min);
        }).enabled(edit && !electionOn);
        btn(168, 169, 70, 16, "Clôturer", Btn.DARK, () -> send("etat.close")).enabled(edit && electionOn);
        btn(242, 169, 70, 16, "Annuler", Btn.RED, () -> confirmThen("elcancel", () -> send("etat.cancel")))
                .enabled(edit && electionOn).selected(confirm.equals("elcancel"));
    }

    private static String num(double v) {
        String s = String.format(Locale.ROOT, "%.2f", v);
        return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("[.]$", "") : s;
    }

    private void renderEtat(GuiGraphics g) {
        if (etat == null) {
            text(g, "Chargement…", 8, 40, DIM);
            return;
        }
        long bal = etat.getLong("Balance");
        text(g, "Trésor : " + (bal / 100) + (bal % 100 == 0 ? "" : "," + String.format("%02d", Math.abs(bal % 100))) + " €", 8, 40, WHITE);
        text(g, "Impôt sur chaque achat : " + num(etat.getDouble("Tax")) + " %  (plafond du maire : " + num(etat.getDouble("TaxMax")) + " %)", 8, 52, TEXT);
        String mayor = etat.getString("Mayor");
        text(g, mayor.isEmpty() ? "Maire : personne" : "Maire : " + mayor, 8, 64, mayor.isEmpty() ? DIM : OK);
        text(g, "Élection : " + (etat.getBoolean("Election") ? "en cours" : "aucune") + " · Agents municipaux : " + etat.getInt("Agents"), 8, 76, DIM);
        text(g, "Impôt (part de chaque achat versée au trésor)", 8, 88, DIM);
        text(g, "Maire (remplace l'actuel, ses agents sont révoqués)", 8, 124, DIM);
        text(g, "Élection (durée en minutes)", 8, 160, DIM);
        if (!has("ETAT_EDIT")) text(g, "Lecture seule (pas de droit de modification).", 8, 196, DIM);
    }

    // ================================================================== journal

    private static final int LOG_Y = 52, LOG_ROW = 11, LOG_ROWS = 16;

    private List<CompoundTag> filteredLogs() {
        List<CompoundTag> out = new ArrayList<>();
        if (logs == null) return out;
        String q = in("logSearch").toLowerCase(Locale.ROOT);
        ListTag l = logs.getList("Logs", Tag.TAG_COMPOUND);
        for (int i = 0; i < l.size(); i++) {
            CompoundTag t = l.getCompound(i);
            if (q.isEmpty() || logLine(t).toLowerCase(Locale.ROOT).contains(q)) out.add(t);
        }
        return out;
    }

    private static String logLine(CompoundTag t) {
        String target = t.getString("P");
        return t.getString("A") + (target.isEmpty() ? "" : " → " + target) + " [" + t.getString("M") + "] " + t.getString("X");
    }

    private void initLogs() {
        box("logSearch", 8, 34, 200, "Filtrer (pseudo, action…)", 32);
        btn(212, 34, 70, 14, "Actualiser", Btn.DARK, () -> send("logs"));
        int max = Math.max(0, filteredLogs().size() - LOG_ROWS);
        btn(W - 20, LOG_Y, 12, LOG_ROWS * LOG_ROW / 2 - 1, "▲", Btn.DARK, () -> logOffset = Math.max(0, logOffset - LOG_ROWS)).enabled(logOffset > 0);
        btn(W - 20, LOG_Y + LOG_ROWS * LOG_ROW / 2 + 1, 12, LOG_ROWS * LOG_ROW / 2 - 1, "▼", Btn.DARK,
                () -> logOffset = Math.min(max, logOffset + LOG_ROWS)).enabled(logOffset < max);
    }

    // ================================================================== souris

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int d = (int) -Math.signum(delta);
        double rx = mx - left;
        switch (tab) {
            case PLAYERS -> {
                if (rx < RX) {
                    listOffset = clamp(listOffset + d * 3, filteredPlayers().size() - PL_ROWS);
                } else if (player != null && player.contains("Data")) {
                    CompoundTag data = player.getCompound("Data");
                    if (section.equals("permis")) permisOffset = clamp(permisOffset + d, data.getList("Licences", Tag.TAG_COMPOUND).size() - LIST_ROWS);
                    if (section.equals("police")) policeOffset = clamp(policeOffset + d, data.getList("Officers", Tag.TAG_COMPOUND).size() - LIST_ROWS);
                    if (section.equals("secours")) secoursOffset = clamp(secoursOffset + d, data.getList("Members", Tag.TAG_COMPOUND).size() - LIST_ROWS);
                    if (section.equals("garage")) garageOffset = clamp(garageOffset + d,
                            data.getList(garageKey(), Tag.TAG_COMPOUND).size() - LIST_ROWS);
                }
            }
            case STAFF -> {
                if (staffMembers) memberOffset = clamp(memberOffset + d, members().size() - ST_ROWS);
                else roleOffset = clamp(roleOffset + d, roles().size() - ST_ROWS);
            }
            case LOGS -> logOffset = clamp(logOffset + d * 3, filteredLogs().size() - LOG_ROWS);
        }
        rebuildWidgets();
        return true;
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(Math.max(0, max), v));
    }

    private boolean inRow(double mx, double my, int x, int y, int w, int h) {
        return mx >= left + x && mx < left + x + w && my >= top + y && my < top + y + h;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        switch (tab) {
            case PLAYERS -> {
                List<CompoundTag> ps = filteredPlayers();
                for (int i = 0; i < PL_ROWS; i++) {
                    int idx = listOffset + i;
                    if (idx >= ps.size()) break;
                    if (inRow(mx, my, PL_X, PL_Y + i * PL_ROW, PL_W, PL_ROW)) {
                        List<String> secs = sections();
                        String sec = secs.contains(section) ? section : secs.isEmpty() ? "inv" : secs.get(0);
                        openPlayer(ps.get(idx).getUUID("Id"), sec);
                        return true;
                    }
                }
                if (player == null || !player.contains("Data")) return false;
                CompoundTag data = player.getCompound("Data");
                if (section.equals("permis")) {
                    int n = data.getList("Licences", Tag.TAG_COMPOUND).size();
                    for (int i = 0; i < LIST_ROWS; i++) {
                        int idx = permisOffset + i;
                        if (idx < n && inRow(mx, my, RX, LIST_Y + i * LIST_ROW, RW - 14, LIST_ROW)) {
                            permisSel = idx;
                            confirm = "";
                            rebuildWidgets();
                            return true;
                        }
                    }
                } else if (section.equals("garage")) {
                    int n = data.getList(garageKey(), Tag.TAG_COMPOUND).size();
                    for (int i = 0; i < LIST_ROWS; i++) {
                        int idx = garageOffset + i;
                        if (idx < n && inRow(mx, my, RX, LIST_Y + i * LIST_ROW, RW - 14, LIST_ROW)) {
                            garageSel = idx;
                            confirm = "";
                            rebuildWidgets();
                            return true;
                        }
                    }
                } else if (section.equals("inv")) {
                    int s = slotAt(mx, my);
                    if (s >= 0) {
                        invSel = s;
                        confirm = "";
                        rebuildWidgets();
                        return true;
                    }
                }
            }
            case STAFF -> {
                if (staffMembers) {
                    List<CompoundTag> ms = members();
                    for (int i = 0; i < ST_ROWS; i++) {
                        int idx = memberOffset + i;
                        if (idx < ms.size() && inRow(mx, my, 8, ST_Y + i * ST_ROW, 230, ST_ROW)) {
                            CompoundTag m = ms.get(idx);
                            memberSel = m.getUUID("Id");
                            inputs.put("memberName", m.getString("Name"));
                            newMemberRole = m.getString("Role");
                            confirm = "";
                            rebuildWidgets();
                            return true;
                        }
                    }
                } else {
                    List<CompoundTag> rs = roles();
                    for (int i = 0; i < ST_ROWS; i++) {
                        int idx = roleOffset + i;
                        if (idx < rs.size() && inRow(mx, my, 8, ST_Y + i * ST_ROW, 136, ST_ROW)) {
                            roleSel = rs.get(idx).getString("Id");
                            inputs.remove("roleName:" + roleSel);
                            inputs.remove("roleRank:" + roleSel);
                            confirm = "";
                            rebuildWidgets();
                            return true;
                        }
                    }
                }
            }
            default -> {}
        }
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ================================================================== rendu

    private void drawScaled(GuiGraphics g, Component c, int x, int y, float maxScale, int maxWidth, int color) {
        float sc = Math.min(maxScale, maxWidth / (float) Math.max(1, font.width(c)));
        g.pose().pushPose();
        g.pose().scale(sc, sc, 1f);
        g.drawString(font, c, Math.round(x / sc), Math.round(y / sc), color, false);
        g.pose().popPose();
    }

    private static Component bold(String s) {
        return Component.literal(s).withStyle(ChatFormatting.BOLD);
    }

    private void text(GuiGraphics g, String s, int x, int y, int color) {
        g.drawString(font, s, left + x, top + y, color, false);
    }

    private void textFit(GuiGraphics g, String s, int x, int y, int maxW, int color) {
        if (font.width(s) > maxW) s = font.plainSubstrByWidth(s, maxW - 6) + "…";
        g.drawString(font, s, left + x, top + y, color, false);
    }

    private void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(left + x, top + y, left + x + w, top + y + h, PANEL);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        g.fill(left - 3, top - 3, left + W + 3, top + H + 3, 0xFF0E0E10);
        g.fill(left, top, left + W, top + H, BLUE);
        RenderSystem.enableBlend();
        g.blit(LOGO, left + 8, top + 4, 24, 24, 0, 0, 256, 256, 256, 256);
        drawScaled(g, bold("PANNEAU ADMIN"), left + 38, top + 7, 1.3f, 120, WHITE);
        String role = home.getString("Role");
        text(g, role.isEmpty() ? "MineNorthRP" : role, 38, 20, CYAN);

        ItemStack tip = null;
        String textTip = null;
        switch (tab) {
            case PLAYERS -> {
                Object o = renderPlayers(g, mx, my);
                if (o instanceof ItemStack st) tip = st;
            }
            case STAFF -> renderStaff(g, mx, my);
            case ETAT -> renderEtat(g);
            case LOGS -> textTip = renderLogs(g, mx, my);
        }
        if (!message.isEmpty()) textFit(g, message, 8, H - 11, W - 16, messageOk ? OK : WARN);
        super.render(g, mx, my, pt);
        if (tip != null && !tip.isEmpty()) g.renderTooltip(font, tip, mx, my);
        if (textTip != null) g.renderTooltip(font, font.split(Component.literal(textTip), 260), mx, my);
    }

    // ------------------------------------------------------------------ joueurs

    @Nullable
    private Object renderPlayers(GuiGraphics g, int mx, int my) {
        List<CompoundTag> ps = filteredPlayers();
        listOffset = clamp(listOffset, ps.size() - PL_ROWS);
        panel(g, PL_X, PL_Y - 1, PL_W, PL_ROWS * PL_ROW + 2);
        for (int i = 0; i < PL_ROWS; i++) {
            int idx = listOffset + i;
            if (idx >= ps.size()) break;
            CompoundTag p = ps.get(idx);
            int y = PL_Y + i * PL_ROW;
            boolean sel = p.getUUID("Id").equals(selected);
            if (sel) g.fill(left + PL_X, top + y, left + PL_X + PL_W, top + y + PL_ROW, CYAN);
            else if (inRow(mx, my, PL_X, y, PL_W, PL_ROW)) g.fill(left + PL_X, top + y, left + PL_X + PL_W, top + y + PL_ROW, HOVER);
            g.fill(left + PL_X + 3, top + y + 4, left + PL_X + 7, top + y + 8, p.getBoolean("On") ? OK : 0xFF555577);
            textFit(g, p.getString("Name"), PL_X + 10, y + 2, PL_W - 12, sel ? WHITE : TEXT);
        }
        if (ps.isEmpty()) text(g, "Aucun joueur.", PL_X + 4, PL_Y + 3, DIM);
        text(g, ps.size() + " joueur(s)", PL_X, PL_Y + PL_ROWS * PL_ROW + 18, DIM);

        if (player == null || selected == null) {
            text(g, "Choisis un joueur dans la liste.", RX, 40, TEXT);
            if (sections().isEmpty()) text(g, "Ton rôle ne donne accès à aucun onglet joueur.", RX, 54, WARN);
            return null;
        }
        drawScaled(g, bold(player.getString("Name")), left + RX, top + 34, 1.2f, 170, WHITE);
        boolean on = player.getBoolean("On");
        String pseudo = player.getString("Pseudo");
        String status = (on ? "● En ligne" : "● Hors ligne") + (pseudo.isEmpty() || pseudo.equals(player.getString("Name")) ? "" : " · " + pseudo);
        text(g, status, RX, 48, on ? OK : DIM);
        if (on) textFit(g, player.getString("Where"), RX + font.width(status) + 6, 48, 200 - font.width(status), DIM);
        if (player.contains("StaffRole")) text(g, "Staff : " + player.getString("StaffRole"), RX + 176, 36, WARN);

        if (!player.contains("Data")) {
            text(g, sections().isEmpty() ? "" : "Choisis un onglet.", RX, 82, TEXT);
            return null;
        }
        CompoundTag d = player.getCompound("Data");
        return switch (section) {
            case "bank" -> {
                renderBank(g, d);
                yield null;
            }
            case "permis" -> {
                renderPermis(g, d, mx, my);
                yield null;
            }
            case "garage" -> {
                renderGarage(g, d, mx, my);
                yield null;
            }
            case "police" -> {
                renderPolice(g, d, mx, my);
                yield null;
            }
            case "secours" -> {
                renderSecours(g, d, mx, my);
                yield null;
            }
            case "inv" -> renderInv(g, d, mx, my);
            case "mod" -> {
                renderMod(g, d);
                yield null;
            }
            default -> null;
        };
    }

    private void renderBank(GuiGraphics g, CompoundTag d) {
        if (!d.getBoolean("Account")) {
            text(g, "Aucun compte bancaire.", RX, 80, WARN);
        } else {
            text(g, "Solde du compte", RX, 80, CYAN);
            drawScaled(g, bold(euros(d.getLong("Balance"))), left + RX, top + 91, 1.4f, 160, WHITE);
        }
        long cash = d.getLong("Cash");
        text(g, "Espèces sur lui : " + (cash < 0 ? "inconnues" : euros(cash)), RX, 108, TEXT);
        text(g, "Banquier : " + (d.getBoolean("Banker") ? "oui" : "non"), RX, 119, TEXT);
        if (d.contains("Loan")) {
            CompoundTag l = d.getCompound("Loan");
            String due = l.getLong("Due") > 0 ? " · échéance " + new java.text.SimpleDateFormat("dd/MM/yyyy").format(new java.util.Date(l.getLong("Due"))) : "";
            textFit(g, "Prêt : " + l.getString("Status") + " · reste " + euros(l.getLong("Remaining")) + due, RX, 130, RW, WARN);
        } else {
            text(g, "Prêt : aucun", RX, 130, TEXT);
        }
        textFit(g, "Économie : " + euros(d.getLong("Total")) + " sur les comptes · capital banque " + euros(d.getLong("Reserve")), RX, 145, RW, DIM);
        if (!has("BANK_EDIT")) text(g, "Lecture seule (pas de droit de modification).", RX, 220, DIM);
    }

    private void renderList(GuiGraphics g, int mx, int my, int count, int offset, int sel, java.util.function.IntFunction<String[]> row) {
        panel(g, RX, LIST_Y - 1, RW - (count > LIST_ROWS ? 14 : 0), LIST_ROWS * LIST_ROW + 2);
        int w = RW - (count > LIST_ROWS ? 14 : 0);
        for (int i = 0; i < LIST_ROWS; i++) {
            int idx = offset + i;
            if (idx >= count) break;
            int y = LIST_Y + i * LIST_ROW;
            if (idx == sel) g.fill(left + RX, top + y, left + RX + w, top + y + LIST_ROW, CYAN);
            else if (inRow(mx, my, RX, y, w, LIST_ROW)) g.fill(left + RX, top + y, left + RX + w, top + y + LIST_ROW, HOVER);
            String[] r = row.apply(idx);   // {gauche, droite, couleurDroite}
            int rw = font.width(r[1]);
            textFit(g, r[0], RX + 4, y + 3, w - rw - 12, WHITE);
            text(g, r[1], RX + w - 4 - rw, y + 3, idx == sel ? WHITE : (int) Long.parseLong(r[2], 16));
        }
    }

    private void renderPermis(GuiGraphics g, CompoundTag d, int mx, int my) {
        ListTag lic = d.getList("Licences", Tag.TAG_COMPOUND);
        if (d.getBoolean("UsesPoints")) text(g, "Points : " + d.getInt("Points") + "/" + d.getInt("MaxPoints"), RX, 79, WHITE);
        permisOffset = clamp(permisOffset, lic.size() - LIST_ROWS);
        renderList(g, mx, my, lic.size(), permisOffset, permisSel, i -> {
            CompoundTag l = lic.getCompound(i);
            boolean has = l.getBoolean("Has");
            String until = l.getString("Until");
            return new String[]{l.getString("Name"),
                    has ? ("jamais".equals(until) ? "✔ permanent" : "✔ jusqu'au " + until) : "✖ non",
                    has ? "FF5FE0A0" : "FF8FA8E0"};
        });
        if (lic.isEmpty()) text(g, "Aucune licence dans la config du mod permis.", RX + 4, LIST_Y + 3, DIM);
        textFit(g, "Jours : vide = " + d.getInt("DefaultDays") + " j (config), 0 = perm.", RX, 211, 172, DIM);
        ListTag cds = d.getList("Cooldowns", Tag.TAG_COMPOUND);
        if (!cds.isEmpty()) {
            StringBuilder b = new StringBuilder("Délais : ");
            for (int i = 0; i < cds.size(); i++) {
                if (i > 0) b.append(", ");
                b.append(cds.getCompound(i).getString("Key")).append(" ").append(cds.getCompound(i).getString("Left"));
            }
            textFit(g, b.toString(), RX, 222, RW, WARN);
        }
    }

    // ------------------------------------------------------------------ police

    /** "30m", "2h", "7j" / "7d" -> minutes ; -1 si invalide. */
    private static long minutes(String s) {
        s = s.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (s.length() < 2) return -1;
        try {
            long n = Long.parseLong(s.substring(0, s.length() - 1));
            if (n <= 0) return -1;
            return switch (s.charAt(s.length() - 1)) {
                case 'm' -> n;
                case 'h' -> n * 60;
                case 'j', 'd' -> n * 1440;
                default -> -1;
            };
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void initMod(CompoundTag d) {
        boolean edit = has("MODERATE");
        boolean on = player.getBoolean("On");
        boolean banned = d.getBoolean("Banned");
        UUID target = selected;
        box("reason", RX, 98, RW, "Raison (affichée au joueur)", 100);
        box("duration", RX, 120, 96, "Durée : 30m, 2h, 7j", 8);
        btn(RX + 100, 119, 110, 16, "Ban temporaire", Btn.RED, () -> {
            long m = minutes(in("duration"));
            if (m < 0) {
                message = "Durée invalide (ex. 30m, 2h, 7j).";
                messageOk = false;
                return;
            }
            confirmThen("tban", () -> send("mod.tempban", target, in("reason"), "", m));
        }).enabled(edit).selected(confirm.equals("tban"));
        btn(RX, 146, 86, 16, "Expulser", Btn.DARK, () -> send("mod.kick", target, in("reason"), "", 0)).enabled(edit && on);
        btn(RX + 90, 146, 86, 16, confirm.equals("ban") ? "Confirmer ?" : "Ban définitif", Btn.RED,
                () -> confirmThen("ban", () -> send("mod.ban", target, in("reason"), "", 0))).enabled(edit).selected(confirm.equals("ban"));
        btn(RX + 180, 146, 88, 16, "Débannir", Btn.GREEN, () -> send("mod.unban", target, "", "", 0)).enabled(edit && banned);
    }

    private void renderMod(GuiGraphics g, CompoundTag d) {
        if (d.getBoolean("Banned")) {
            long exp = d.getLong("Expires");
            String until = exp > 0 ? "jusqu'au " + new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm").format(new java.util.Date(exp)) : "définitivement";
            textFit(g, "Banni " + until + " · par " + d.getString("By"), RX, 79, RW, BAD);
            if (!d.getString("Reason").isEmpty()) textFit(g, "Raison : " + d.getString("Reason"), RX, 88, RW, DIM);
        } else {
            text(g, "Non banni", RX, 79, OK);
        }
        text(g, "Ban temporaire : indique une durée. Ban définitif : sans durée.", RX, 172, DIM);
        if (!has("MODERATE")) text(g, "Pas de droit de sanction.", RX, 186, DIM);
    }

    private void initPolice(CompoundTag d) {
        boolean edit = has("POLICE_EDIT");
        boolean on = player.getBoolean("On");
        boolean police = d.getBoolean("Police");
        ListTag officers = d.getList("Officers", Tag.TAG_COMPOUND);
        int max = Math.max(0, officers.size() - LIST_ROWS);
        listScroll(officers.size(), () -> policeOffset = Math.max(0, policeOffset - 1), () -> policeOffset = Math.min(max, policeOffset + 1),
                policeOffset > 0, policeOffset < max);
        if (police) {
            // Un bouton par grade ; le grade actuel est surligné.
            int current = d.getInt("GradeIdx");
            ListTag grades = d.getList("Grades", Tag.TAG_STRING);
            int n = Math.max(1, grades.size());
            int w = (RW - 3 * (n - 1)) / n;
            for (int i = 0; i < grades.size(); i++) {
                int idx = i;
                btn(RX + i * (w + 3), 191, w, 16, grades.getString(i), Btn.DARK,
                        () -> send("police.grade", selected, "", "", idx)).enabled(edit && current != idx).selected(current == idx);
            }
        } else {
            btn(RX, 191, RW, 16, "Faire entrer dans la police", Btn.GREEN, () -> send("police.add", selected, "", "", 0)).enabled(edit);
        }
        btn(RX, 210, 88, 14, "Retirer", Btn.RED,
                () -> confirmThen("police", () -> send("police.remove", selected, "", "", 0))).enabled(edit && police).selected(confirm.equals("police"));
        btn(RX + 90, 210, 88, 14, "Tablette", Btn.DARK, () -> send("police.tablet", selected, "", "", 0)).enabled(edit && on && police);
        btn(RX + 180, 210, 88, 14, "Équipement", Btn.DARK, () -> send("police.kit", selected, "", "", 0)).enabled(edit && on && police);
    }

    private void renderPolice(GuiGraphics g, CompoundTag d, int mx, int my) {
        boolean police = d.getBoolean("Police");
        ListTag officers = d.getList("Officers", Tag.TAG_COMPOUND);
        text(g, police ? "Policier (" + d.getString("Grade") + ")" : "Pas dans la police", RX, 79, police ? OK : DIM);
        text(g, "Effectifs : " + officers.size(), RX + 170, 79, DIM);
        policeOffset = clamp(policeOffset, officers.size() - LIST_ROWS);
        renderList(g, mx, my, officers.size(), policeOffset, -1, i -> {
            CompoundTag o = officers.getCompound(i);
            return new String[]{(o.getBoolean("On") ? "● " : "○ ") + o.getString("Name"), o.getString("Grade"), "FF8FA8E0"};
        });
        if (officers.isEmpty()) text(g, "Aucun policier pour le moment.", RX + 4, LIST_Y + 3, DIM);
        if (!has("POLICE_EDIT")) text(g, "Lecture seule (pas de droit de modification).", RX, 228, DIM);
    }

    private void initSecours(CompoundTag d) {
        boolean edit = has("SECOURS_EDIT");
        boolean on = player.getBoolean("On");
        boolean member = d.getBoolean("Secours");
        int current = d.getInt("GradeIdx");
        ListTag grades = d.getList("Grades", Tag.TAG_STRING);
        ListTag members = d.getList("Members", Tag.TAG_COMPOUND);
        int max = Math.max(0, members.size() - LIST_ROWS);
        listScroll(members.size(), () -> secoursOffset = Math.max(0, secoursOffset - 1), () -> secoursOffset = Math.min(max, secoursOffset + 1),
                secoursOffset > 0, secoursOffset < max);
        // Un bouton par grade : nomme le joueur pompier s'il ne l'est pas encore, sinon change son grade.
        int n = Math.max(1, grades.size());
        int w = (RW - 3 * (n - 1)) / n;
        for (int i = 0; i < grades.size(); i++) {
            int idx = i;
            btn(RX + i * (w + 3), 191, w, 16, grades.getString(i), member ? Btn.DARK : Btn.GREEN,
                    () -> send("secours.grade", selected, "", "", idx)).enabled(edit && !(member && current == idx)).selected(member && current == idx);
        }
        btn(RX, 210, 130, 14, "Retirer des pompiers", Btn.RED,
                () -> confirmThen("secours", () -> send("secours.remove", selected, "", "", 0))).enabled(edit && member).selected(confirm.equals("secours"));
        btn(RX + 134, 210, 134, 14, "Donner la tablette", Btn.DARK, () -> send("secours.tablet", selected, "", "", 0)).enabled(edit && on && member);
    }

    private void renderSecours(GuiGraphics g, CompoundTag d, int mx, int my) {
        boolean member = d.getBoolean("Secours");
        ListTag members = d.getList("Members", Tag.TAG_COMPOUND);
        text(g, member ? "Pompier (" + d.getString("Grade") + ")" : "Pas pompier", RX, 79, member ? OK : DIM);
        text(g, "Effectifs : " + members.size(), RX + 170, 79, DIM);
        secoursOffset = clamp(secoursOffset, members.size() - LIST_ROWS);
        renderList(g, mx, my, members.size(), secoursOffset, -1, i -> {
            CompoundTag o = members.getCompound(i);
            return new String[]{(o.getBoolean("On") ? "● " : "○ ") + o.getString("Name"), o.getString("Grade"), "FFE08F8F"};
        });
        if (members.isEmpty()) text(g, "Aucun pompier pour le moment.", RX + 4, LIST_Y + 3, DIM);
        if (!has("SECOURS_EDIT")) text(g, "Lecture seule (pas de droit de modification).", RX, 228, DIM);
        else text(g, member ? "Clique un grade pour le changer." : "Clique un grade pour le nommer pompier.", RX, 228, DIM);
    }

    private void renderGarage(GuiGraphics g, CompoundTag d, int mx, int my) {
        ListTag list = d.getList(garageKey(), Tag.TAG_COMPOUND);
        garageOffset = clamp(garageOffset, list.size() - LIST_ROWS);
        renderList(g, mx, my, list.size(), garageOffset, garageSel, i -> {
            CompoundTag v = list.getCompound(i);
            String item = v.getString("Item");
            int c = item.indexOf(':');
            return new String[]{v.getString("Label"), c >= 0 ? item.substring(c + 1) : item, "FF8FA8E0"};
        });
        if (list.isEmpty()) text(g, garageMode == 1 ? "Aucun véhicule en fourrière." : garageMode == 2 ? "Aucune plaque à son nom." : "Garage vide.", RX + 4, LIST_Y + 3, DIM);
        if (!has("GARAGE_EDIT")) text(g, "Lecture seule.", RX, 214, DIM);
    }

    @Nullable
    private ItemStack renderInv(GuiGraphics g, CompoundTag d, int mx, int my) {
        if (!d.getBoolean("Found")) {
            text(g, "Aucune donnée d'inventaire (jamais connecté ?).", RX, 82, WARN);
            return null;
        }
        boolean live = d.getBoolean("Live");
        text(g, live ? "En direct" : "Hors ligne (fichier)", RX + 156, 79, live ? OK : WARN);
        Map<Integer, ItemStack> items = items(d);
        ItemStack hover = null;
        for (int s = 0; s < slotCount(); s++) {
            int[] p = slotPos(s);
            int x = left + p[0], y = top + p[1];
            boolean in = mx >= x && mx < x + CELL && my >= y && my < y + CELL;
            g.fill(x, y, x + CELL - 1, y + CELL - 1, s == invSel ? CYAN : in ? HOVER : PANEL);
            ItemStack st = items.get(s);
            if (st != null && !st.isEmpty()) {
                g.renderItem(st, x + 1, y + 1);
                g.renderItemDecorations(font, st, x + 1, y + 1);
                if (in) hover = st;
            }
        }
        if (!invEnder) {
            int[] a = slotPos(39), o = slotPos(40);
            text(g, "Armure", a[0] - 2, a[1] - 9, DIM);
            text(g, "Main 2", o[0] - 4, o[1] - 9, DIM);
        }
        if (!has("INV_EDIT")) text(g, "Lecture seule.", RX, 218, DIM);
        return hover;
    }

    // ------------------------------------------------------------------ staff

    private void renderStaff(GuiGraphics g, int mx, int my) {
        if (staff == null) {
            text(g, "Chargement…", 8, ST_Y, DIM);
            return;
        }
        text(g, owner ? "Tu es propriétaire (op) : tous les droits." : "Ton rang : " + myRank() + ". Tu gères les rangs inférieurs.", 140, 37, DIM);
        if (staffMembers) {
            List<CompoundTag> ms = members();
            memberOffset = clamp(memberOffset, ms.size() - ST_ROWS);
            panel(g, 8, ST_Y - 1, 230, ST_ROWS * ST_ROW + 2);
            for (int i = 0; i < ST_ROWS; i++) {
                int idx = memberOffset + i;
                if (idx >= ms.size()) break;
                CompoundTag m = ms.get(idx);
                int y = ST_Y + i * ST_ROW;
                boolean sel = m.getUUID("Id").equals(memberSel);
                if (sel) g.fill(left + 8, top + y, left + 238, top + y + ST_ROW, CYAN);
                else if (inRow(mx, my, 8, y, 230, ST_ROW)) g.fill(left + 8, top + y, left + 238, top + y + ST_ROW, HOVER);
                g.fill(left + 11, top + y + 5, left + 15, top + y + 9, m.getBoolean("On") ? OK : 0xFF555577);
                textFit(g, m.getString("Name"), 18, y + 3, 110, WHITE);
                CompoundTag r = role(m.getString("Role"));
                String rn = r == null ? "?" : r.getString("Name") + " (" + r.getInt("Rank") + ")";
                text(g, rn, 236 - font.width(rn), y + 3, sel ? WHITE : CYAN);
            }
            if (ms.isEmpty()) text(g, "Aucun membre. Ajoute-en un à droite.", 12, ST_Y + 3, DIM);
            text(g, "Ajouter / changer de rôle", 258, ST_Y, CYAN);
            if (assignable().isEmpty()) textFit(g, "Crée d'abord un rôle de rang inférieur au tien.", 258, ST_Y + 70, 134, WARN);
            text(g, "Les rôles sont invisibles", 258, 214, DIM);
            text(g, "pour les joueurs.", 258, 224, DIM);
        } else {
            List<CompoundTag> rs = roles();
            roleOffset = clamp(roleOffset, rs.size() - ST_ROWS);
            panel(g, 8, ST_Y - 1, 136, ST_ROWS * ST_ROW + 2);
            for (int i = 0; i < ST_ROWS; i++) {
                int idx = roleOffset + i;
                if (idx >= rs.size()) break;
                CompoundTag r = rs.get(idx);
                int y = ST_Y + i * ST_ROW;
                boolean sel = r.getString("Id").equals(roleSel);
                if (sel) g.fill(left + 8, top + y, left + 144, top + y + ST_ROW, CYAN);
                else if (inRow(mx, my, 8, y, 136, ST_ROW)) g.fill(left + 8, top + y, left + 144, top + y + ST_ROW, HOVER);
                String right = r.getInt("Rank") + " · " + r.getInt("Count");
                textFit(g, r.getString("Name"), 12, y + 3, 120 - font.width(right), r.getInt("Rank") < myRank() ? WHITE : DIM);
                text(g, right, 141 - font.width(right), y + 3, sel ? WHITE : DIM);
            }
            if (rs.isEmpty()) text(g, "Aucun rôle.", 12, ST_Y + 3, DIM);
            text(g, "rang · membres", 60, ST_Y + ST_ROWS * ST_ROW + 3, DIM);
            CompoundTag r = role(roleSel);
            if (r == null) {
                text(g, "Choisis un rôle pour le modifier.", 166, ST_Y + 3, TEXT);
                text(g, "Rang : plus il est grand, plus le rôle", 166, ST_Y + 20, DIM);
                text(g, "a de pouvoir. On ne gère que les rangs", 166, ST_Y + 30, DIM);
                text(g, "inférieurs au sien.", 166, ST_Y + 40, DIM);
            } else if (r.getInt("Rank") >= myRank()) {
                text(g, "Rang trop élevé : lecture seule.", 274, ST_Y + 21, WARN);
            }
        }
    }

    // ------------------------------------------------------------------ journal

    @Nullable
    private String renderLogs(GuiGraphics g, int mx, int my) {
        List<CompoundTag> ls = filteredLogs();
        logOffset = clamp(logOffset, ls.size() - LOG_ROWS);
        panel(g, 8, LOG_Y - 1, W - 30, LOG_ROWS * LOG_ROW + 2);
        String tip = null;
        for (int i = 0; i < LOG_ROWS; i++) {
            int idx = logOffset + i;
            if (idx >= ls.size()) break;
            CompoundTag t = ls.get(idx);
            int y = LOG_Y + i * LOG_ROW;
            boolean in = inRow(mx, my, 8, y, W - 30, LOG_ROW);
            if (in) g.fill(left + 8, top + y, left + W - 22, top + y + LOG_ROW, HOVER);
            String date = t.getString("D");
            text(g, date, 11, y + 2, DIM);
            int x = 11 + font.width(date) + 6;
            String line = logLine(t);
            textFit(g, line, x, y + 2, W - 30 - x, TEXT);
            if (in && font.width(line) > W - 30 - x) tip = date + "  " + line;
        }
        if (logs == null) text(g, "Chargement…", 12, LOG_Y + 3, DIM);
        else if (ls.isEmpty()) text(g, "Aucune action enregistrée.", 12, LOG_Y + 3, DIM);
        text(g, ls.size() + " entrée(s) · fichier serveur : logs/minenorth_admin.log", 8, LOG_Y + LOG_ROWS * LOG_ROW + 4, DIM);
        return tip;
    }
}
