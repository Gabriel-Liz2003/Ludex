package com.ludex.mobile;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** User-authorized Xbox game history via the third-party OpenXBL gateway (xbl.io). */
public final class XboxHistoryClient {
    private static final String API="https://api.xbl.io/v2";
    private static final int STATS_BATCH=40;

    public static final class Game {
        public final String titleId,name,imageUrl,device;
        public final long seconds,lastPlayedAt;
        Game(String titleId,String name,String imageUrl,String device,long seconds,long lastPlayedAt){
            this.titleId=titleId;this.name=name;this.imageUrl=imageUrl;this.device=device;
            this.seconds=seconds;this.lastPlayedAt=lastPlayedAt;
        }
    }
    public static final class Snapshot {
        public final String xuid;
        public final List<Game> games;
        Snapshot(String xuid,List<Game> games){this.xuid=xuid;this.games=games;}
    }
    private XboxHistoryClient(){}

    public static Snapshot fetch(String apiKey) throws Exception{
        if(apiKey==null||apiKey.trim().isEmpty())throw new IllegalArgumentException("Configure a chave OpenXBL primeiro");
        JSONObject profile=unwrap(get("/account",apiKey));
        JSONArray users=profile.optJSONArray("profileUsers");
        String xuid=users!=null&&users.length()>0?users.optJSONObject(0).optString("id",""):"";

        JSONObject history=unwrap(get("/player/titleHistory",apiKey));
        if(xuid.isBlank())xuid=history.optString("xuid","");
        if(xuid.isBlank())throw new IOException("A conta OpenXBL não retornou XUID. Confirme a conexão Xbox no painel do OpenXBL.");
        JSONArray titles=history.optJSONArray("titles");
        if(titles==null){
            JSONObject data=history.optJSONObject("data");
            if(data!=null)titles=data.optJSONArray("titles");
        }
        if(titles==null)throw new IOException("Resposta de histórico Xbox sem lista de títulos");

        LinkedHashMap<String,Game> seen=new LinkedHashMap<>();
        for(int i=0;i<titles.length();i++){
            JSONObject item=titles.optJSONObject(i);
            if(item==null)continue;
            String id=item.optString("titleId","").trim();
            String name=item.optString("name","").trim();
            if(id.isBlank()||name.isBlank())continue;
            String image=item.optString("displayImage",item.optString("imageUrl","")).trim();
            String devices="";
            JSONArray deviceList=item.optJSONArray("devices");
            if(deviceList!=null){
                ArrayList<String> names=new ArrayList<>();
                for(int d=0;d<deviceList.length();d++)names.add(deviceList.optString(d));
                devices=String.join(", ",names);
            }
            JSONObject titleHistory=item.optJSONObject("titleHistory");
            long last=titleHistory==null?0:parseTime(titleHistory.optString("lastTimePlayed",""));
            seen.putIfAbsent(id,new Game(id,name,image,devices,-1,last));
        }

        HashMap<String,Long> stats=fetchMinutes(apiKey,xuid,new ArrayList<>(seen.keySet()));
        ArrayList<Game> out=new ArrayList<>();
        for(Game game:seen.values()){
            Long minutes=stats.get(game.titleId);
            long seconds=minutes==null?-1:Math.max(0,minutes)*60L;
            out.add(new Game(game.titleId,game.name,game.imageUrl,game.device,seconds,game.lastPlayedAt));
        }
        out.sort((a,b)->Long.compare(b.seconds,a.seconds));
        return new Snapshot(xuid,out);
    }

    private static Map<String,Long> fetchMinutes(String key,String xuid,List<String> ids)throws Exception{
        HashMap<String,Long> out=new HashMap<>();
        for(int first=0;first<ids.size();first+=STATS_BATCH){
            JSONArray requested=new JSONArray();
            for(int i=first;i<Math.min(ids.size(),first+STATS_BATCH);i++){
                JSONObject stat=new JSONObject();
                stat.put("name","MinutesPlayed");
                stat.put("titleId",ids.get(i));
                requested.put(stat);
            }
            JSONObject request=new JSONObject();
            request.put("xuids",new JSONArray().put(xuid));
            request.put("arrangebyfield","xuid");
            request.put("stats",requested);

            try{
                JSONObject response=unwrap(post("/player/stats",key,request.toString()));
                JSONArray groups=response.optJSONArray("statlistscollection");
                if(groups==null)groups=response.optJSONArray("statListsCollection");
                if(groups==null)continue;
                for(int g=0;g<groups.length();g++){
                    JSONObject group=groups.optJSONObject(g);
                    if(group==null)continue;
                    JSONArray values=group.optJSONArray("stats");
                    if(values==null)continue;
                    for(int i=0;i<values.length();i++){
                        JSONObject stat=values.optJSONObject(i);
                        if(stat==null||!"MinutesPlayed".equalsIgnoreCase(stat.optString("name","MinutesPlayed")))continue;
                        String titleId=stat.optString("titleid",stat.optString("titleId",""));
                        if(titleId.isBlank())continue;
                        try{
                            long minutes=Long.parseLong(stat.optString("value",""));
                            if(minutes>=0)out.put(titleId,minutes);
                        }catch(Exception ignored){}
                    }
                }
            }catch(IOException error){
                // Title history remains useful when a specific game's statistic isn't available.
                if(error.getMessage()!=null&&error.getMessage().contains("HTTP 429"))throw error;
            }
        }
        return out;
    }

    private static JSONObject unwrap(JSONObject input){
        JSONObject content=input.optJSONObject("content");
        if(content!=null)return content;
        return input;
    }

    private static long parseTime(String value){
        if(value==null||value.isBlank())return 0;
        try{return Instant.parse(value).toEpochMilli();}
        catch(Exception ignored){
            try{return java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli();}
            catch(Exception ignored2){return 0;}
        }
    }

    private static JSONObject get(String path,String key)throws Exception{return request("GET",path,key,null);}
    private static JSONObject post(String path,String key,String json)throws Exception{return request("POST",path,key,json);}
    private static JSONObject request(String method,String path,String key,String json)throws Exception{
        HttpURLConnection conn=(HttpURLConnection)new URL(API+path).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestMethod(method);
        conn.setRequestProperty("X-Authorization",key);
        conn.setRequestProperty("Accept","application/json");
        conn.setRequestProperty("User-Agent","Ludex-Android/"+BuildConfig.VERSION_NAME);
        if(json!=null){
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type","application/json");
            try(OutputStream stream=conn.getOutputStream()){
                stream.write(json.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status=conn.getResponseCode();
        try(InputStream stream=status>=200&&status<300?conn.getInputStream():conn.getErrorStream()){
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            if(stream!=null){
                byte[] buffer=new byte[8192];int n;
                while((n=stream.read(buffer))!=-1){
                    if(bytes.size()+n>5*1024*1024)throw new IOException("Resposta OpenXBL grande demais");
                    bytes.write(buffer,0,n);
                }
            }
            if(status<200||status>=300)throw new IOException("OpenXBL HTTP "+status+" (verifique sua chave e os limites da conta)");
            return new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
        }finally{conn.disconnect();}
    }
}
