package com.minenorth_admin;

import com.minenorth_admin.net.Net;
import com.minenorth_admin.server.AdminService;
import com.minenorth_admin.staff.Access;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * Panneau d'administration MineNorthRP : /mnadmin.
 * La commande n'existe (et n'est autocomplétée) que pour les membres du staff : les autres joueurs ne voient rien.
 */
@Mod(MineNorthAdmin.MODID)
public class MineNorthAdmin {
    public static final String MODID = "minenorth_admin";
    public static final Logger LOG = LogUtils.getLogger();

    public MineNorthAdmin() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        bus.addListener(this::commonSetup);
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, AdminConfig.SPEC);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void commonSetup(FMLCommonSetupEvent e) {
        e.enqueueWork(Net::register);
    }

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("mnadmin").requires(Access::canOpen)
                .executes(c -> {
                    AdminService.handle(c.getSource().getPlayerOrException(), Net.Req.of("open"));
                    return 1;
                }));
    }
}
