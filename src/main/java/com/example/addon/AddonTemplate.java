package com.example.addon;

import com.example.addon.modules.ChunkFinder;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.item.Items;
import org.slf4j.Logger;

public class AddonTemplate extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Chunk Finder", Items.AMETHYST_SHARD.getDefaultStack());
    public static final HudGroup HUD_GROUP = new HudGroup("Chunk Finder");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Chunk Finder");
        Modules.get().add(new ChunkFinder());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.example.addon";
    }
}
