package com.ludex.mobile;

import android.content.Context;
import android.graphics.Bitmap;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.Locale;

/** Fallback artwork for public catalog games missing a platform cover.
 * Never call this for private Ren'Py, local APK or manually added titles.
 */
final class CatalogArtworkLoader {
    private static final int MAX_JSON_BYTES=256*1024;
    private static final int MAX_IMAGE_BYTES=8*1024*1024;
    // Riot's public press-kit logo previews, usable without an API key.
    private static final String LOL_ARTWORK=
        "https://www.riotgames.com/darkroom/800/9c08e3b3ce6281f252a4dfbf61357a11%3A75ab7e60b85225c6c7f19ae4024e27a6/lol-logo-rendered-hi-res.png";
    private static final String VALORANT_ARTWORK=
        "https://www.riotgames.com/darkroom/800/9691de74fc891930fdb7d200d72396ec%3A36d04b23935d6c621156e13d621ffc15/v-lockup-horizontal-pos-off-white.png";

    private CatalogArtworkLoader(){}

    static Bitmap load(Context context,String title,String steamGridKey){
        if(title==null||title.trim().isEmpty())return null;
        File dir=new File(context.getFilesDir(),"artwork/catalog");
        if(!dir.isDirectory()&&!dir.mkdirs())return null;
        String normalized=normalize(title);
        if(normalized.isEmpty())return null;
        File target=new File(dir,hash(normalized)+".img");
        Bitmap cached=ArtworkBitmap.decode(target);
        if(cached!=null)return cached;

        // SteamGridDB covers modern games unavailable in the Steam catalog (e.g. Riot, Genshin).
        if(steamGridKey!=null&&!steamGridKey.isBlank()){
            try{
                String result=getJson("https://www.steamgriddb.com/api/v2/search/autocomplete/"+encode(title),steamGridKey);
                JSONArray candidates=new JSONObject(result).optJSONArray("data");
                if(candidates!=null){
                    int id=-1;
                    for(int i=0;i<candidates.length();i++){
                        JSONObject game=candidates.optJSONObject(i);
                        if(game!=null&&normalized.equals(normalize(game.optString("name","")))){
                            id=game.optInt("id",-1);
                            if(id>0)break;
                        }
                    }
                    if(id>0){
                        String response=getJson("https://www.steamgriddb.com/api/v2/grids/game/"+id+"?dimensions=600x900&types=static",steamGridKey);
                        JSONArray covers=new JSONObject(response).optJSONArray("data");
                        if(covers!=null){
                            for(int i=0;i<Math.min(5,covers.length());i++){
                                JSONObject cover=covers.optJSONObject(i);
                                if(cover==null)continue;
                                String url=cover.optString("url","");
                                if(!isSteamGridImage(url))continue;
                                Bitmap image=download(url,target);
                                if(image!=null)return image;
                            }
                        }
                    }
                }
            }catch(Exception ignored){/* fall through to other providers */}
        }

        if("league of legends".equals(normalized)){
            Bitmap image=download(LOL_ARTWORK,target);
            if(image!=null)return image;
        }else if("valorant".equals(normalized)){
            Bitmap image=download(VALORANT_ARTWORK,target);
            if(image!=null)return image;
        }

        // Steam's public search is only used for an exact title match. Never attach
        // an unrelated game's cover just because it was the first search result.
        try{
            String result=getJson("https://store.steampowered.com/api/storesearch/?term="+encode(title)+"&cc=us&l=en",null);
            JSONArray items=new JSONObject(result).optJSONArray("items");
            if(items!=null){
                for(int i=0;i<Math.min(items.length(),30);i++){
                    JSONObject item=items.optJSONObject(i);
                    if(item==null||!normalized.equals(normalize(item.optString("name",""))))continue;
                    int appId=item.optInt("id",-1);
                    if(appId>0)return GameArtworkLoader.load(context,"steam",Integer.toString(appId));
                }
            }
        }catch(Exception ignored){}
        return null;
    }

    static String normalize(String title){
        if(title==null)return "";
        String normalized=Normalizer.normalize(title,Normalizer.Form.NFKD)
            .replaceAll("\\p{M}+","").toLowerCase(Locale.ROOT)
            .replace('®',' ').replace('™',' ');
        return normalized.replaceAll("[^a-z0-9]+"," ").trim().replaceAll("\\s+"," ");
    }

    private static boolean isSteamGridImage(String value){
        try{
            URL url=new URL(value);
            String host=url.getHost().toLowerCase(Locale.ROOT);
            return "https".equalsIgnoreCase(url.getProtocol())&&
                ("steamgriddb.com".equals(host)||host.endsWith(".steamgriddb.com"));
        }catch(Exception ignored){return false;}
    }

    private static String getJson(String url,String key)throws Exception{
        HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();
        try{
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(12000);
            connection.setRequestProperty("User-Agent","Ludex-Android/"+BuildConfig.VERSION_NAME);
            connection.setRequestProperty("Accept","application/json");
            if(key!=null&&!key.isBlank())connection.setRequestProperty("Authorization","Bearer "+key);
            int code=connection.getResponseCode();
            if(code<200||code>=300)throw new java.io.IOException("Artwork metadata HTTP "+code);
            try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buf=new byte[8192];int n,total=0;
                while((n=in.read(buf))!=-1){
                    total+=n;
                    if(total>MAX_JSON_BYTES)throw new java.io.IOException("Artwork metadata too large");
                    out.write(buf,0,n);
                }
                return out.toString(StandardCharsets.UTF_8.name());
            }
        }finally{connection.disconnect();}
    }

    private static Bitmap download(String url,File target){
        HttpURLConnection connection=null;
        File tmp=new File(target.getAbsolutePath()+".tmp");
        try{
            URL address=new URL(url);
            if(!"https".equalsIgnoreCase(address.getProtocol()))return null;
            connection=(HttpURLConnection)address.openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent","Ludex-Android/"+BuildConfig.VERSION_NAME);
            int code=connection.getResponseCode();
            if(code<200||code>=300)return null;
            String type=connection.getContentType();
            if(type!=null&&!type.toLowerCase(Locale.ROOT).startsWith("image/"))return null;
            if(connection.getContentLengthLong()>MAX_IMAGE_BYTES)return null;
            try(InputStream in=connection.getInputStream();OutputStream out=new FileOutputStream(tmp)){
                byte[] buf=new byte[16384];int n,total=0;
                while((n=in.read(buf))!=-1){
                    total+=n;
                    if(total>MAX_IMAGE_BYTES)return null;
                    out.write(buf,0,n);
                }
            }
            if(tmp.length()<512)return null;
            Bitmap image=ArtworkBitmap.decode(tmp);
            if(image==null)return null;
            if(target.exists()&&!target.delete())return image;
            if(!tmp.renameTo(target))return image;
            return image;
        }catch(Exception ignored){return null;}
        finally{
            if(connection!=null)connection.disconnect();
            if(tmp.exists())tmp.delete();
        }
    }

    private static String encode(String s){
        return URLEncoder.encode(s,StandardCharsets.UTF_8).replace("+","%20");
    }

    private static String hash(String s){
        try{
            byte[] bytes=MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder out=new StringBuilder();
            for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b));
            return out.toString();
        }catch(Exception e){return Integer.toHexString(s.hashCode());}
    }
}
