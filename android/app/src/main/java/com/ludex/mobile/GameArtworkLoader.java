package com.ludex.mobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.Locale;

public final class GameArtworkLoader {
    private GameArtworkLoader(){}

    public static Bitmap load(Context context,String provider,String externalId) {
        if(provider==null||externalId==null)return null;
        if(!"steam".equalsIgnoreCase(provider))return null;

        File dir=new File(context.getFilesDir(),"artwork");
        if(!dir.exists()&&!dir.mkdirs())return null;
        File target=new File(dir,"steam-"+safe(externalId)+".jpg");

        Bitmap cached=decode(target);
        if(cached!=null)return cached;

        String[] urls={
            "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/"+externalId+"/library_600x900_2x.jpg",
            "https://cdn.akamai.steamstatic.com/steam/apps/"+externalId+"/library_600x900_2x.jpg",
            "https://cdn.akamai.steamstatic.com/steam/apps/"+externalId+"/header.jpg"
        };

        for(String url:urls){
            File tmp=new File(target.getAbsolutePath()+".tmp");
            if(download(url,tmp)){
                Bitmap bmp=decode(tmp);
                if(bmp!=null){
                    if(target.exists())target.delete();
                    if(!tmp.renameTo(target)){
                        copy(tmp,target);tmp.delete();
                    }
                    return bmp;
                }
                tmp.delete();
            }
        }
        return null;
    }

    private static boolean download(String url,File out){
        HttpURLConnection c=null;
        try{
            c=(HttpURLConnection)new URL(url).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(10000);c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent","Ludex-Android/"+BuildConfig.VERSION_NAME);
            int code=c.getResponseCode();
            if(code<200||code>=300)return false;
            String type=c.getContentType();
            if(type!=null&&!type.toLowerCase(Locale.ROOT).startsWith("image/"))return false;
            try(InputStream in=c.getInputStream();OutputStream os=new FileOutputStream(out)){
                byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)os.write(b,0,n);
            }
            return out.length()>1024;
        }catch(Exception e){
            if(out.exists())out.delete();
            return false;
        }finally{
            if(c!=null)c.disconnect();
        }
    }

    private static Bitmap decode(File f){
        if(f==null||!f.isFile()||f.length()==0)return null;
        try{return BitmapFactory.decodeFile(f.getAbsolutePath());}
        catch(Exception e){return null;}
    }

    private static void copy(File from,File to){
        try(InputStream in=new FileInputStream(from);OutputStream out=new FileOutputStream(to)){
            byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
        }catch(Exception ignored){}
    }

    private static String safe(String s){
        return s.replaceAll("[^A-Za-z0-9._-]","_");
    }
}
