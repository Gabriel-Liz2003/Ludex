package com.ludex.mobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;

public final class NintendoArtworkLoader {
    private NintendoArtworkLoader(){}

    public static Bitmap load(Context context,String titleId,String imageUrl){
        if(imageUrl==null||imageUrl.isBlank())return null;
        File dir=new File(context.getFilesDir(),"artwork/nintendo");
        if(!dir.exists()&&!dir.mkdirs())return null;
        File target=new File(dir,safe(titleId)+".img");
        Bitmap cached=decode(target);if(cached!=null)return cached;
        HttpURLConnection c=null;File tmp=new File(target.getAbsolutePath()+".tmp");
        try{
            c=(HttpURLConnection)new URL(imageUrl).openConnection();
            c.setInstanceFollowRedirects(true);c.setConnectTimeout(10000);c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent","Ludex-Android/"+BuildConfig.VERSION_NAME);
            int code=c.getResponseCode();if(code<200||code>=300)return null;
            String type=c.getContentType();if(type!=null&&!type.toLowerCase(Locale.ROOT).startsWith("image/"))return null;
            try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(tmp)){
                byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
            }
            if(tmp.length()<1024)return null;
            if(target.exists())target.delete();
            if(!tmp.renameTo(target))return null;
            return decode(target);
        }catch(Exception e){return null;}
        finally{if(c!=null)c.disconnect();if(tmp.exists()&&!target.exists())tmp.delete();}
    }

    private static Bitmap decode(File f){
        if(f==null||!f.isFile()||f.length()==0)return null;
        try{return BitmapFactory.decodeFile(f.getAbsolutePath());}catch(Exception e){return null;}
    }

    private static String safe(String s){return s==null?"unknown":s.replaceAll("[^A-Za-z0-9._-]","_");}
}
