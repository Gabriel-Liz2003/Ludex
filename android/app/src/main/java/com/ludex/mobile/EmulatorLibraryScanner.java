package com.ludex.mobile;

import android.content.Context;
import android.net.Uri;
import androidx.documentfile.provider.DocumentFile;
import java.util.*;

public final class EmulatorLibraryScanner {
    public static final class ImportedGame {
        public final String title,uri;
        ImportedGame(String title,String uri){this.title=title;this.uri=uri;}
    }
    private EmulatorLibraryScanner(){}

    public static List<ImportedGame> scan(Context context,Uri tree,EmulatorRegistry.Emulator emulator){
        ArrayList<ImportedGame> out=new ArrayList<>();
        DocumentFile root=DocumentFile.fromTreeUri(context,tree);
        if(root==null||!root.exists()||!root.canRead())return out;
        walk(root,emulator.extensions,out,0);
        out.sort(Comparator.comparing(x->x.title.toLowerCase(Locale.ROOT)));
        return out;
    }

    private static void walk(DocumentFile dir,Set<String> ext,List<ImportedGame> out,int depth){
        if(depth>4)return;
        for(DocumentFile f:dir.listFiles()){
            if(f.isDirectory()){walk(f,ext,out,depth+1);continue;}
            String name=f.getName();
            if(name==null)continue;
            int dot=name.lastIndexOf('.');
            if(dot<=0||dot==name.length()-1)continue;
            String suffix=name.substring(dot+1).toLowerCase(Locale.ROOT);
            if(!ext.isEmpty()&&!ext.contains(suffix))continue;
            String title=cleanTitle(name.substring(0,dot));
            out.add(new ImportedGame(title,f.getUri().toString()));
        }
    }

    public static String cleanTitle(String raw){
        String x=raw;
        x=x.replaceAll("\\[[0-9A-Fa-f]{16}\\]"," ");
        x=x.replaceAll("\\([^)]*(?:USA|Europe|Japan|World|En|Fr|De|Es|It|Rev|Disc|Disk)[^)]*\\)"," ");
        x=x.replaceAll("\\[[^]]*(?:USA|Europe|Japan|World|En|Fr|De|Es|It|Rev|Disc|Disk)[^]]*\\]"," ");
        x=x.replaceAll("[._]+"," ");
        x=x.replaceAll("\\s+"," ").trim();
        return x.isEmpty()?raw:x;
    }
}
