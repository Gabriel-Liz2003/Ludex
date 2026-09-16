package com.ludex.mobile;

import android.content.*;
import android.content.pm.PackageManager;
import java.util.*;

public final class EmulatorRegistry {
    public static final class Emulator { public final String name,packageName; Emulator(String n,String p){name=n;packageName=p;} }
    private static final LinkedHashMap<String,String> KNOWN=new LinkedHashMap<>();
    static{
        KNOWN.put("app.gamenative","GameNative");
        KNOWN.put("com.retroarch","RetroArch");KNOWN.put("com.retroarch.aarch64","RetroArch (64-bit)");
        KNOWN.put("org.dolphinemu.dolphinemu","Dolphin");KNOWN.put("org.ppsspp.ppsspp","PPSSPP");KNOWN.put("org.ppsspp.ppssppgold","PPSSPP Gold");
        KNOWN.put("com.github.stenzek.duckstation","DuckStation");KNOWN.put("xyz.aethersx2.android","AetherSX2 / NetherSX2");
        KNOWN.put("org.citra.citra_emu","Citra");KNOWN.put("org.citra.emu","Citra");KNOWN.put("org.yuzu.yuzu_emu","Yuzu");
        KNOWN.put("dev.eden.eden_emulator","Eden");KNOWN.put("dev.optimized.eden_emulator","Eden (Optimized)");
        KNOWN.put("org.vita3k.emulator","Vita3K");KNOWN.put("org.mupen64plusae.v3.fzurita","M64Plus FZ");
        KNOWN.put("me.magnum.melonds","melonDS");KNOWN.put("com.dsemu.drastic","DraStic");
    }
    private EmulatorRegistry(){}
    public static boolean isKnown(String pkg){return KNOWN.containsKey(pkg);}
    public static List<Emulator> detect(Context context){
        ArrayList<Emulator> out=new ArrayList<>();PackageManager pm=context.getPackageManager();
        for(Map.Entry<String,String> e:KNOWN.entrySet()){try{pm.getPackageInfo(e.getKey(),0);out.add(new Emulator(e.getValue(),e.getKey()));}catch(Exception ignored){}}
        return out;
    }
}
