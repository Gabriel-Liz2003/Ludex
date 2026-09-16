package com.ludex.mobile;

import android.content.*;
import android.content.pm.PackageManager;
import java.util.*;

public final class EmulatorRegistry {
    public static final class Emulator {
        public final String name,packageName,platform,platformId,libretroSystem;
        public final Set<String> extensions;
        Emulator(String n,String p,String platform,String platformId,String libretroSystem,String... ext){
            name=n;packageName=p;this.platform=platform;this.platformId=platformId;this.libretroSystem=libretroSystem;
            extensions=new LinkedHashSet<>();
            for(String x:ext)extensions.add(x.toLowerCase(Locale.ROOT));
        }
        public boolean isMultiSystem(){return "multi".equals(platformId);}
    }

    private static final LinkedHashMap<String,Emulator> KNOWN=new LinkedHashMap<>();
    static{
        add("app.gamenative","GameNative","PC","pc",null);

        add("dev.eden.eden_emulator","Eden","Nintendo Switch","switch",null,"xci","nsp","nca","nro");
        add("dev.optimized.eden_emulator","Eden (Optimized)","Nintendo Switch","switch",null,"xci","nsp","nca","nro");
        add("org.yuzu.yuzu_emu","Yuzu","Nintendo Switch","switch",null,"xci","nsp","nca","nro");
        add("org.sudachi.sudachi_emu","Sudachi","Nintendo Switch","switch",null,"xci","nsp","nca","nro");
        add("org.citron.citron_emu","Citron","Nintendo Switch","switch",null,"xci","nsp","nca","nro");

        add("org.dolphinemu.dolphinemu","Dolphin","GameCube / Wii","gc-wii","Nintendo - GameCube","iso","gcm","rvz","wbfs","wad");
        add("org.ppsspp.ppsspp","PPSSPP","PlayStation Portable","psp","Sony - PlayStation Portable","iso","cso","pbp");
        add("org.ppsspp.ppssppgold","PPSSPP Gold","PlayStation Portable","psp","Sony - PlayStation Portable","iso","cso","pbp");
        add("com.github.stenzek.duckstation","DuckStation","PlayStation","ps1","Sony - PlayStation","cue","chd","iso","pbp","m3u");
        add("xyz.aethersx2.android","AetherSX2 / NetherSX2","PlayStation 2","ps2","Sony - PlayStation 2","iso","chd","bin","gz","cso");
        add("net.pcsx2.android","PCSX2","PlayStation 2","ps2","Sony - PlayStation 2","iso","chd","bin","gz","cso");

        add("org.citra.citra_emu","Citra","Nintendo 3DS","3ds","Nintendo - Nintendo 3DS","3ds","cia","cci","cxi");
        add("org.citra.emu","Citra","Nintendo 3DS","3ds","Nintendo - Nintendo 3DS","3ds","cia","cci","cxi");
        add("io.github.lime3ds.android","Lime3DS","Nintendo 3DS","3ds","Nintendo - Nintendo 3DS","3ds","cia","cci","cxi");
        add("io.github.azahar_emu.azahar","Azahar","Nintendo 3DS","3ds","Nintendo - Nintendo 3DS","3ds","cia","cci","cxi");

        add("me.magnum.melonds","melonDS","Nintendo DS","nds","Nintendo - Nintendo DS","nds");
        add("com.dsemu.drastic","DraStic","Nintendo DS","nds","Nintendo - Nintendo DS","nds");
        add("org.vita3k.emulator","Vita3K","PlayStation Vita","vita",null,"vpk","zip");
        add("org.mupen64plusae.v3.fzurita","M64Plus FZ","Nintendo 64","n64","Nintendo - Nintendo 64","z64","n64","v64");

        add("org.flycast.flycast","Flycast","Dreamcast","dreamcast","Sega - Dreamcast","chd","cue","gdi","cdi");
        add("com.retroarch","RetroArch","Multi-system","multi",null);
        add("com.retroarch.aarch64","RetroArch (64-bit)","Multi-system","multi",null);
    }

    private static void add(String pkg,String name,String platform,String platformId,String libretro,String... ext){
        KNOWN.put(pkg,new Emulator(name,pkg,platform,platformId,libretro,ext));
    }

    private EmulatorRegistry(){}

    public static boolean isKnown(String pkg){return KNOWN.containsKey(pkg);}
    public static Emulator get(String pkg){return KNOWN.get(pkg);}

    public static List<Emulator> detect(Context context){
        ArrayList<Emulator> out=new ArrayList<>();PackageManager pm=context.getPackageManager();
        for(Emulator e:KNOWN.values()){
            try{pm.getPackageInfo(e.packageName,0);out.add(e);}catch(Exception ignored){}
        }
        return out;
    }
}
