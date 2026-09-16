package com.ludex.mobile;

import android.content.Context;
import android.net.Uri;
import androidx.documentfile.provider.DocumentFile;
import java.util.*;
import java.util.regex.*;

public final class EdenLibraryScanner {
    public static final class ImportedGame {
        public final String packageName,title,uri,programId;
        ImportedGame(String packageName,String title,String uri,String programId){
            this.packageName=packageName;this.title=title;this.uri=uri;this.programId=programId;
        }
    }

    private static final Set<String> EXT=new HashSet<>(Arrays.asList("xci","nsp","nca","nro"));
    private static final Pattern TITLE_ID=Pattern.compile("(?i)(?:\\[|\\b)([0-9a-f]{16})(?:\\]|\\b)");

    private EdenLibraryScanner(){}

    public static List<ImportedGame> scan(Context context,Uri tree,String packageName){
        ArrayList<ImportedGame> out=new ArrayList<>();
        DocumentFile root=DocumentFile.fromTreeUri(context,tree);
        if(root==null||!root.exists()||!root.canRead())return out;
        walk(root,packageName,out);
        out.sort(Comparator.comparing(x->x.title.toLowerCase(Locale.ROOT)));
        return out;
    }

    private static void walk(DocumentFile dir,String packageName,List<ImportedGame> out){
        for(DocumentFile f:dir.listFiles()){
            if(f.isDirectory()){walk(f,packageName,out);continue;}
            String name=f.getName();
            if(name==null)continue;
            int dot=name.lastIndexOf('.');
            if(dot<=0||dot==name.length()-1)continue;
            String ext=name.substring(dot+1).toLowerCase(Locale.ROOT);
            if(!EXT.contains(ext))continue;
            String stem=name.substring(0,dot);
            String programId=extractProgramId(stem);
            String title=cleanTitle(stem);
            out.add(new ImportedGame(packageName,title,f.getUri().toString(),programId));
        }
    }

    static String extractProgramId(String raw){
        Matcher m=TITLE_ID.matcher(raw);
        if(!m.find())return "";
        try{
            return Long.toUnsignedString(Long.parseUnsignedLong(m.group(1),16));
        }catch(Exception e){return "";}
    }

    static String cleanTitle(String raw){
        String x=raw;
        x=x.replaceAll("\\[[0-9A-Fa-f]{16}\\]"," ");
        x=x.replaceAll("\\((?:v|ver(?:sion)?)[^)]*\\)"," ");
        x=x.replaceAll("\\[(?:v|ver(?:sion)?)[^]]*\\]"," ");
        x=x.replaceAll("[._]+"," ");
        x=x.replaceAll("\\s+"," ").trim();
        return x.isEmpty()?raw:x;
    }
}
