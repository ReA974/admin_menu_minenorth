package com.minenorth_admin.server;

import com.minenorth_admin.AdminConfig;
import com.minenorth_admin.Players;
import com.minenorth_eurobank.BankData;
import com.minenorth_eurobank.Money;
import com.minenorth_eurobank.items.BankCardItem;
import com.minenorth_eurobank.loan.Loan;
import com.minenorth_eurobank.loan.LoanService;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/** Onglet Banque (MineNorth EuroBank). Chargé seulement si le mod est présent. */
final class BankModule {
    private BankModule() {}

    static final long MAX_AMOUNT = 1_000_000_000_00L;   // 1 milliard d'euros, en centimes

    static CompoundTag view(MinecraftServer s, UUID id) {
        BankData d = BankData.get(s);
        CompoundTag t = new CompoundTag();
        t.putBoolean("Account", d.has(id));
        t.putLong("Balance", d.balance(id));
        t.putBoolean("Banker", d.isBanker(id));
        long cash = -1;
        ServerPlayer p = Players.online(s, id);
        if (p != null) {
            cash = Money.cashIn(p);
        } else {
            PlayerInv pi = PlayerInv.load(s, id);
            if (pi != null) {
                cash = 0;
                for (ItemStack st : pi.inv) if (st != null) cash += Money.value(st);
            }
        }
        t.putLong("Cash", cash);
        Loan l = d.openLoanOf(id);
        if (l != null) {
            CompoundTag lt = new CompoundTag();
            lt.putLong("Principal", l.principal);
            lt.putLong("Remaining", l.remaining());
            lt.putLong("Due", l.dueMs);
            lt.putString("Status", switch (l.status) {
                case Loan.PENDING -> "Demande en attente";
                case Loan.ACTIVE -> "En cours";
                case Loan.OVERDUE -> "En retard";
                default -> "?";
            });
            t.put("Loan", lt);
        }
        t.putLong("Total", d.total());
        t.putLong("Reserve", d.reserve());
        return t;
    }

    private static void tell(MinecraftServer s, UUID id, String msg) {
        ServerPlayer p = Players.online(s, id);
        if (p != null && AdminConfig.NOTIFY_TARGET.get()) p.sendSystemMessage(Component.literal("[Banque] " + msg));
    }

    static Result act(ServerPlayer actor, UUID id, String action, long n) {
        MinecraftServer s = actor.server;
        BankData d = BankData.get(s);
        String name = Players.name(s, id);
        switch (action) {
            case "bank.open" -> {
                if (d.has(id)) return Result.fail(name + " a déjà un compte.");
                d.open(id);
                d.rename(id, name);
                return Result.ok("Compte ouvert pour " + name + ".", "ouvre un compte");
            }
            case "bank.set", "bank.add", "bank.take" -> {
                if (!d.has(id)) return Result.fail(name + " n'a pas de compte bancaire.");
                if (n < 0 || n > MAX_AMOUNT || (n == 0 && !action.equals("bank.set"))) return Result.fail("Montant invalide.");
                long before = d.balance(id);
                long after = switch (action) {
                    case "bank.set" -> n;
                    case "bank.add" -> Math.min(MAX_AMOUNT, before + n);
                    default -> Math.max(0, before - n);
                };
                d.set(id, after);
                d.rename(id, name);
                String what = Money.format(before) + " → " + Money.format(after);
                tell(s, id, "Votre solde a été ajusté par l'administration : " + Money.format(after) + ".");
                return Result.ok("Solde de " + name + " : " + what + ".", "solde " + what);
            }
            case "bank.banker" -> {
                boolean on = !d.isBanker(id);
                d.setBanker(id, on);
                return Result.ok(name + (on ? " est maintenant banquier." : " n'est plus banquier."), on ? "nommé banquier" : "retiré des banquiers");
            }
            case "bank.card" -> {
                ServerPlayer p = Players.online(s, id);
                if (p == null) return Result.fail(name + " doit être connecté pour recevoir une carte.");
                if (!d.has(id)) return Result.fail(name + " n'a pas de compte bancaire.");
                ItemStack card = BankCardItem.create(p);
                if (!p.getInventory().add(card)) p.drop(card, false);
                tell(s, id, "L'administration vous a remis une carte bancaire.");
                return Result.ok("Carte bancaire remise à " + name + ".", "donne une carte bancaire");
            }
            case "bank.forgive" -> {
                Loan l = d.openLoanOf(id);
                if (l == null) return Result.fail(name + " n'a aucun prêt en cours.");
                long rest = l.remaining();
                String msg = l.status == Loan.PENDING ? LoanService.reject(s, l.id) : LoanService.forgive(s, l.id);
                return Result.ok(msg, (l.status == Loan.REJECTED ? "refuse la demande de prêt" : "annule la dette de " + Money.format(rest)));
            }
            default -> {
                return Result.fail("Action inconnue.");
            }
        }
    }
}
