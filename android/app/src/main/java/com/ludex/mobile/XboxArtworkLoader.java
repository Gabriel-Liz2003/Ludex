package com.ludex.mobile;

import android.content.Context;
import android.graphics.Bitmap;
import java.io.*;
import java.net.*;

public final class XboxArtworkLoader {
    private XboxArtworkLoader(){}

    public static Bitmap load(Context context,String gameId,String imageUrl){
        if(imageUrl==null||!imageUrl.startsWith("https://"))return null;
        File dir=new File(context.getFilesDir(),"artwork/xbox");
        if(!dir.exists()&&!dir.mkdirs())return null;
        File file=new File(dir,gameId.replaceAll("[^A-Za-z0-9_-]","_")+".img");
        Bitmap cached=ArtworkBitmap.decode(file);
        if(cached!=null)return cached;
        File temp=new File(file.getAbsolutePath()+".tmp");
        HttpURLConnection connection=null;
        try{
            connection=(HttpURLConnection)new URL(imageUrl).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(20000);
            if(connection.getResponseCode()!=200)return null;
            String type=connection.getContentType();
            if(type!=null&&!type.startsWith("image/"))return null;
            try(InputStream in=connection.getInputStream();OutputStream out=new FileOutputStream(temp)){
                byte[] data=new byte[8192];int count;
                while((count=in.read(data))!=-1){
                    if(temp.length()>5*1024*1024)return null;
                    out.write(data,0,count);
                }
            }
            if(temp.length()==0||!temp.renameTo(file))return null;
            return ArtworkBitmap.decode(file);
        }catch(Exception e){return null;}
        finally{
            if(connection!=null)connection.disconnect();
            if(temp.exists())temp.delete();
        }
    }
}
