package com.voxel.utils;

import com.voxel.api.IMod;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

public class ModManager {
    private final List<IMod> mods = new ArrayList<>();

    public void loadMods(String modsDir) {
        File dir = new File(modsDir);
        if (!dir.exists()) dir.mkdirs();

        File[] files = dir.listFiles((d, name) -> name.endsWith(".jar"));
        if (files == null) return;

        List<URL> urls = new ArrayList<>();
        try {
            for (File file : files) {
                urls.add(file.toURI().toURL());
                System.out.println("Found mod: " + file.getName());
            }

            URLClassLoader classLoader = new URLClassLoader(urls.toArray(new URL[0]), getClass().getClassLoader());
            ServiceLoader<IMod> loader = ServiceLoader.load(IMod.class, classLoader);

            for (IMod mod : loader) {
                System.out.println("Loading mod: " + mod.getModId());
                mods.add(mod);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void initializeMods() {
        for (IMod mod : mods) {
            mod.onInitialize();
        }
    }
}
