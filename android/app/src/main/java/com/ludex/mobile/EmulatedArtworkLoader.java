package com.ludex.mobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class EmulatedArtworkLoader {
    private EmulatedArtworkLoader(){}

    public static Bitmap load(Context context,String platform,String libretroSystem,String title,String steamGridKey){
        File dir=new File(context.getFilesDir(),"artwork/emulated");
        if(!dir.exists()&&!dir.mkdirs())return null;
        File target=new File(dir,sha1((platform==null?"":platform)+"|"+title)+".img");
        Bitmap cached=decode(target);
        if(cached!=null)return cached;

        if(libretroSystem!=null&&!libretroSystem.isBlank()){
            for(String candidate:titleCandidates(title)){
                String url="https://thumbnails.libretro.com/"+path(libretroSystem)+"/Named_Boxarts/"+path(candidate)+".png";
                if(downloadImage(url,target)){
                    Bitmap b=decode(target);if(b!=null)return b;
                }
            }
        }

        if(steamGridKey!=null&&!steamGridKey.isBlank()){
            try{
                String search=apiText("https://www.steamgriddb.com/api/v2/search/autocomplete/"+path(title),steamGridKey);
                JSONArray games=new JSONObject(search).optJSONArray("data");
                if(games!=null&&games.length()>0){
                    int id=games.getJSONObject(0).optInt("id",-1);
                    if(id>0){
                        String grids=apiText("https://www.steamgriddb.com/api/v2/grids/game/"+id+"?dimensions=600x900&types=static",steamGridKey);
                        JSONArray images=new JSONObject(grids).optJSONArray("data");
                        if(images!=null&&images.length()>0){
                            String url=images.getJSONObject(0).optString("url","");
                            if(!url.isBlank()&&downloadImage(url,target)){
                                Bitmap b=decode(target);if(b!=null)return b;
                            }
                        }
                    }
                }
            }catch(Exception ignored){}
        }
        return null;
    }

    private static List<String> titleCandidates(String title){
        LinkedHashSet<String> out=new LinkedHashSet<>();
        String clean=title==null?"":title.trim();
        if(clean.isBlank())return new ArrayList<>();
        out.add(clean);
        out.add(clean.replace(": "," - ").replace(":"," -"));
        out.add(clean.replaceAll("\\s*\\([^)]*(USA|Europe|Japan|World)[^)]*\\)\\s*"," ").replaceAll("\\s+"," ").trim());
        return new ArrayList<>(out);
    }

    private static String apiText(String url,String key)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(12000);c.setReadTimeout(20000);
        c.setRequestProperty("Authorization","Bearer "+key);
        c.setRequestProperty("User-Agent","Ludex-Android/"+BuildConfig.VERSION_NAME);
        int code=c.getResponseCode();
        if(code<200||code>=300)throw new IOException("SteamGridDB HTTP "+code);
        try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
            return out.toString(StandardCharsets.UTF_8.name());
        }finally{c.disconnect();}
    }

    private static boolean downloadImage(String url,File target){
        HttpURLConnection c=null;
        File tmp=new File(target.getAbsolutePath()+".tmp");
        try{
            c=(HttpURLConnection)new URL(url).openConnection();
            c.setInstanceFollowRedirects(true);c.setConnectTimeout(10000);c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent","Ludex-Android/"+BuildConfig.VERSION_NAME);
            int code=c.getResponseCode();
            if(code<200||code>=300)return false;
            String type=c.getContentType();
            if(type!=null&&!type.toLowerCase(Locale.ROOT).startsWith("image/"))return false;
            try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(tmp)){
                byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
            }
            if(tmp.length()<1024)return false;
            if(target.exists())target.delete();
            return tmp.renameTo(target);
        }catch(Exception e){return false;}
        finally{if(c!=null)c.disconnect();if(tmp.exists()&&!target.exists())tmp.delete();}
    }

    private static Bitmap decode(File f){
        if(f==null||!f.isFile()||f.length()==0)return null;
        try{return BitmapFactory.decodeFile(f.getAbsolutePath());}catch(Exception e){return null;}
    }

    private static String path(String s){
        try{return URLEncoder.encode(s,StandardCharsets.UTF_8.name()).replace("+","%20").replace("%2F","/");}
        catch(Exception e){return s;}
    }

    private static String sha1(String s){
        try{
            MessageDigest md=MessageDigest.getInstance("SHA-1");byte[] d=md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder out=new StringBuilder();for(byte b:d)out.append(String.format(Locale.ROOT,"%02x",b));return out.toString();
        }catch(Exception e){return Integer.toHexString(s.hashCode());}
    }
}
