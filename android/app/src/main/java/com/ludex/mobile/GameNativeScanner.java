package com.ludex.mobile;

import android.content.Context;
import android.net.Uri;
import androidx.documentfile.provider.DocumentFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class GameNativeScanner {
    public static final String PACKAGE="app.gamenative";

    public static final class ImportedGame {
        public final String provider,externalId,title;
        public ImportedGame(String provider,String externalId,String title){
            this.provider=provider;this.externalId=externalId;this.title=title;
        }
    }

    private GameNativeScanner(){}

    public static List<ImportedGame> scan(Context context, Uri treeUri) throws IOException {
        DocumentFile root=DocumentFile.fromTreeUri(context,treeUri);
        if(root==null||!root.isDirectory())throw new IOException("Pasta do GameNative inválida");
        LinkedHashMap<String,ImportedGame> games=new LinkedHashMap<>();
        scanSteam(context,root,games);
        scanFolder(root,new String[]{"GOG","games","common"},"gog",games);
        scanFolder(root,new String[]{"Epic","games"},"epic",games);
        scanFolder(root,new String[]{"Amazon","games"},"amazon",games);
        return new ArrayList<>(games.values());
    }

    private static void scanSteam(Context context,DocumentFile root,Map<String,ImportedGame> out)throws IOException{
        DocumentFile steam=findPath(root,"Steam","steamapps");
        if(steam==null)return;
        DocumentFile common=findChild(steam,"common");
        for(DocumentFile f:steam.listFiles()){
            String name=f.getName();
            if(name==null||!name.startsWith("appmanifest_")||!name.endsWith(".acf")||!f.isFile())continue;
            String text=read(context,f.getUri());
            String appid=valueFor(text,"appid"), title=valueFor(text,"name"), installDir=valueFor(text,"installdir");
            if(appid==null||title==null||installDir==null)continue;
            if(common!=null&&findChild(common,installDir)!=null){
                out.put("steam:"+appid,new ImportedGame("steam",appid,title));
            }
        }
    }

    private static void scanFolder(DocumentFile root,String[] path,String provider,Map<String,ImportedGame> out){
        DocumentFile dir=findPath(root,path);
        if(dir==null)return;
        for(DocumentFile f:dir.listFiles()){
            if(!f.isDirectory())continue;
            String title=f.getName();
            if(title==null||title.isBlank()||title.startsWith(".")||title.equalsIgnoreCase("staging"))continue;
            String id=slug(title);
            out.put(provider+":"+id,new ImportedGame(provider,id,title));
        }
    }

    private static DocumentFile findPath(DocumentFile root,String...parts){
        DocumentFile cur=root;
        for(String part:parts){cur=findChild(cur,part);if(cur==null)return null;}
        return cur;
    }

    private static DocumentFile findChild(DocumentFile dir,String name){
        if(dir==null||!dir.isDirectory())return null;
        for(DocumentFile f:dir.listFiles()){
            String n=f.getName();
            if(n!=null&&n.equalsIgnoreCase(name))return f;
        }
        return null;
    }

    private static String read(Context context,Uri uri)throws IOException{
        try(InputStream in=context.getContentResolver().openInputStream(uri)){
            if(in==null)throw new IOException("Não foi possível ler manifesto do GameNative");
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;
            while((n=in.read(b))!=-1)out.write(b,0,n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String valueFor(String content,String key){
        for(String line:content.split("\\R")){
            ArrayList<String> q=new ArrayList<>();boolean quote=false;StringBuilder s=new StringBuilder();
            for(int i=0;i<line.length();i++){
                char c=line.charAt(i);
                if(c=='"'){if(quote){q.add(s.toString());s.setLength(0);}quote=!quote;}
                else if(quote)s.append(c);
            }
            if(q.size()>=2&&q.get(0).equalsIgnoreCase(key))return q.get(1);
        }
        return null;
    }

    private static String slug(String s){
        String x=s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","-").replaceAll("(^-|-$)","");
        return x.isEmpty()?Integer.toHexString(s.hashCode()):x;
    }
}
