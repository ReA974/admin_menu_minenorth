package com.minenorth_admin.net;

import com.minenorth_admin.MineNorthAdmin;
import com.minenorth_admin.server.AdminService;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Deux paquets génériques :
 *  - Req  (client -> serveur) : une action du panneau, TOUJOURS revérifiée côté serveur (droits, cible, valeurs) ;
 *  - View (serveur -> client) : les données d'un écran, en NBT.
 */
public final class Net {
    private static final String PROTOCOL = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MineNorthAdmin.MODID, "main"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    public static final UUID NIL = new UUID(0, 0);

    private Net() {}

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, Req.class, Req::encode, Req::decode, Req::handle);
        CHANNEL.registerMessage(id++, View.class, View::encode, View::decode, View::handle);
    }

    public static void send(ServerPlayer p, View v) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), v);
    }

    /** Client -> serveur. action : voir AdminService. a/b : textes, n : nombre. */
    public record Req(String action, UUID target, String a, String b, long n) {
        public static Req of(String action) {
            return new Req(action, NIL, "", "", 0);
        }

        public static void encode(Req m, FriendlyByteBuf buf) {
            buf.writeUtf(m.action, 64);
            buf.writeUUID(m.target);
            buf.writeUtf(m.a, 256);
            buf.writeUtf(m.b, 256);
            buf.writeLong(m.n);
        }

        public static Req decode(FriendlyByteBuf buf) {
            return new Req(buf.readUtf(64), buf.readUUID(), buf.readUtf(256), buf.readUtf(256), buf.readLong());
        }

        public static void handle(Req m, Supplier<NetworkEvent.Context> sup) {
            NetworkEvent.Context ctx = sup.get();
            ctx.enqueueWork(() -> {
                ServerPlayer p = ctx.getSender();
                if (p != null) AdminService.handle(p, m);
            });
            ctx.setPacketHandled(true);
        }
    }

    /** Serveur -> client. kind : "home", "player", "staff", "logs", "close". */
    public record View(String kind, CompoundTag data, String message, boolean ok) {
        public static void encode(View m, FriendlyByteBuf buf) {
            buf.writeUtf(m.kind, 32);
            buf.writeNbt(m.data);
            buf.writeUtf(m.message, 512);
            buf.writeBoolean(m.ok);
        }

        public static View decode(FriendlyByteBuf buf) {
            String kind = buf.readUtf(32);
            CompoundTag t = buf.readNbt();
            return new View(kind, t == null ? new CompoundTag() : t, buf.readUtf(512), buf.readBoolean());
        }

        public static void handle(View m, Supplier<NetworkEvent.Context> sup) {
            NetworkEvent.Context ctx = sup.get();
            ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.minenorth_admin.client.ClientHooks.handle(m)));
            ctx.setPacketHandled(true);
        }
    }
}
