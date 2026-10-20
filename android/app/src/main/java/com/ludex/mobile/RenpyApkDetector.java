package com.ludex.mobile;

import android.content.pm.ApplicationInfo;
import java.io.File;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Checks installed APK paths only; does not access application private data. */
public final class RenpyApkDetector {
    private RenpyApkDetector(){}

    public static boolean isRenpy(ApplicationInfo app){
        if(app==null)return false;
        String pkg=app.packageName==null?"":app.packageName.toLowerCase(Locale.ROOT);
        if(pkg.startsWith("org.renpy.")||pkg.startsWith("com.renpy."))return true;
        return hasRenpyAssets(app.sourceDir);
    }

    static boolean hasRenpyAssets(String apkPath){
        if(apkPath==null||apkPath.isBlank()||!new File(apkPath).isFile())return false;
        try(ZipFile zip=new ZipFile(apkPath)){
            boolean engine=false,game=false;
            Enumeration<? extends ZipEntry> entries=zip.entries();
            while(entries.hasMoreElements()){
                String entry=entries.nextElement().getName().toLowerCase(Locale.ROOT);
                if(entry.startsWith("assets/x-renpy/x-common/")||entry.startsWith("assets/renpy/common/"))engine=true;
                if(entry.startsWith("assets/x-game/")||entry.startsWith("assets/game/"))game=true;
                if(engine&&game)return true;
            }
        }catch(Exception ignored){}
        return false;
    }
}
