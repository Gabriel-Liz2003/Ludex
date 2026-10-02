package com.ludex.mobile;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class SteamPlaytimeClient {
    public static final class LibraryGame {
        public final String appId,title,iconHash;
        public final long seconds,acquiredAtMs,lastPlayedAtMs;
        public final boolean familyShared;

        LibraryGame(String appId,String title,String iconHash,long seconds,boolean familyShared,long acquiredAtMs,long lastPlayedAtMs){
            this.appId=appId;
            this.title=title;
            this.iconHash=iconHash==null?"":iconHash;
            this.seconds=Math.max(0,seconds);
            this.familyShared=familyShared;
            this.acquiredAtMs=Math.max(0,acquiredAtMs);
            this.lastPlayedAtMs=Math.max(0,lastPlayedAtMs);
        }
    }

    public static final class FamilyLibrary {
        public final String familyGroupId;
        public final List<LibraryGame> games;

        FamilyLibrary(String familyGroupId,List<LibraryGame> games){
            this.familyGroupId=familyGroupId;
            this.games=games;
        }
    }

    private SteamPlaytimeClient(){}

    public static List<LibraryGame> getOwnedLibrary(String apiKey,String steamId64) throws Exception {
        if(apiKey==null||apiKey.trim().isEmpty())throw new IllegalArgumentException("API Key vazia");
        validateSteamId(steamId64);

        String url="https://api.steampowered.com/IPlayerService/GetOwnedGames/v1/"+
            "?key="+enc(apiKey.trim())+
            "&steamid="+enc(steamId64.trim())+
            "&include_appinfo=true&include_played_free_games=true";

        JSONObject root=getJson(url);
        JSONObject response=root.optJSONObject("response");
        if(response==null)throw new IOException("Resposta da Steam inválida");
        JSONArray games=response.optJSONArray("games");
        ArrayList<LibraryGame> out=new ArrayList<>();
        if(games==null)return out;
        for(int i=0;i<games.length();i++){
            JSONObject g=games.optJSONObject(i);
            if(g==null)continue;
            int appId=g.optInt("appid",-1);
            String title=g.optString("name","").trim();
            if(appId<0||title.isEmpty())continue;
            long minutes=Math.max(0,g.optLong("playtime_forever",0));
            out.add(new LibraryGame(
                Integer.toString(appId),
                title,
                g.optString("img_icon_url",""),
                minutes*60L,
                false,
                0,
                unixSecondsToMillis(g.optLong("rtime_last_played",0))
            ));
        }
        return out;
    }

    public static FamilyLibrary getFamilyLibrary(String accessToken,String steamId64) throws Exception {
        if(accessToken==null||accessToken.trim().isEmpty())throw new IllegalArgumentException("Token da Família Steam vazio");
        validateSteamId(steamId64);
        String token=extractAccessToken(accessToken);

        JSONObject groupRoot=getJson(
            "https://api.steampowered.com/IFamilyGroupsService/GetFamilyGroupForUser/v1/"+
            "?access_token="+enc(token)
        );
        JSONObject groupResponse=groupRoot.optJSONObject("response");
        String familyGroupId=groupResponse==null?"":groupResponse.optString("family_groupid","");
        if(familyGroupId.isBlank()||"0".equals(familyGroupId)){
            throw new IOException("Sua conta não retornou uma Família Steam ativa");
        }

        JSONObject libraryRoot=getJson(
            "https://api.steampowered.com/IFamilyGroupsService/GetSharedLibraryApps/v1/"+
            "?access_token="+enc(token)+
            "&family_groupid="+enc(familyGroupId)+
            "&include_own=true&include_excluded=false&include_free=true&include_non_games=false"+
            "&language=brazilian&steamid="+enc(steamId64.trim())
        );
        JSONObject libraryResponse=libraryRoot.optJSONObject("response");
        JSONArray apps=libraryResponse==null?null:libraryResponse.optJSONArray("apps");
        if(apps==null)apps=new JSONArray();

        Map<String,Long> playtime=familyPlaytime(token,familyGroupId,steamId64.trim());
        ArrayList<LibraryGame> out=new ArrayList<>();
        for(int i=0;i<apps.length();i++){
            JSONObject g=apps.optJSONObject(i);
            if(g==null)continue;
            long appId=g.optLong("appid",-1);
            String title=g.optString("name","").trim();
            if(appId<0||title.isEmpty())continue;
            String id=Long.toString(appId);

            boolean ownedByUser=false;
            JSONArray owners=g.optJSONArray("owner_steamids");
            if(owners!=null){
                for(int j=0;j<owners.length();j++){
                    if(steamId64.trim().equals(owners.optString(j))){
                        ownedByUser=true;
                        break;
                    }
                }
            }

            long seconds=playtime.getOrDefault(id,Math.max(0,g.optLong("rt_playtime",0))*60L);
            out.add(new LibraryGame(
                id,
                title,
                g.optString("img_icon_hash",""),
                seconds,
                !ownedByUser,
                unixSecondsToMillis(g.optLong("rt_time_acquired",0)),
                unixSecondsToMillis(g.optLong("rt_last_played",0))
            ));
        }
        return new FamilyLibrary(familyGroupId,out);
    }

    private static Map<String,Long> familyPlaytime(String token,String familyGroupId,String steamId64){
        LinkedHashMap<String,Long> out=new LinkedHashMap<>();
        try{
            JSONObject root=getJson(
                "https://api.steampowered.com/IFamilyGroupsService/GetPlaytimeSummary/v1/"+
                "?access_token="+enc(token)+
                "&family_groupid="+enc(familyGroupId)
            );
            JSONObject response=root.optJSONObject("response");
            JSONArray entries=response==null?null:response.optJSONArray("entries");
            if(entries==null)return out;
            for(int i=0;i<entries.length();i++){
                JSONObject e=entries.optJSONObject(i);
                if(e==null||!steamId64.equals(e.optString("steamid")))continue;
                long appId=e.optLong("appid",-1);
                if(appId<0)continue;
                out.put(Long.toString(appId),Math.max(0,e.optLong("seconds_played",0)));
            }
        }catch(Exception ignored){}
        return out;
    }

    public static LibraryGame mergePreferOwned(LibraryGame current,LibraryGame incoming){
        if(current==null)return incoming;
        if(incoming==null)return current;

        LibraryGame owned=!incoming.familyShared?incoming:(!current.familyShared?current:incoming);
        LibraryGame other=owned==incoming?current:incoming;

        String icon=owned.iconHash==null||owned.iconHash.isBlank()?other.iconHash:owned.iconHash;
        long seconds=Math.max(owned.seconds,other.seconds);
        long acquired=owned.acquiredAtMs>0?owned.acquiredAtMs:other.acquiredAtMs;
        long lastPlayed=Math.max(owned.lastPlayedAtMs,other.lastPlayedAtMs);

        return new LibraryGame(
            owned.appId,
            owned.title,
            icon,
            seconds,
            owned.familyShared,
            acquired,
            lastPlayed
        );
    }

    private static long unixSecondsToMillis(long value){
        if(value<=0)return 0;
        return value*1000L;
    }

    public static String extractAccessToken(String raw) throws JSONException {
        String x=raw==null?"":raw.trim();
        if(x.isEmpty())return "";
        if(!x.startsWith("{"))return x;
        JSONObject root=new JSONObject(x);
        JSONObject data=root.optJSONObject("data");
        String token=data==null?"":data.optString("webapi_token","");
        if(token.isBlank())throw new JSONException("JSON sem data.webapi_token");
        return token.trim();
    }

    private static void validateSteamId(String steamId64){
        if(steamId64==null||!steamId64.matches("\\d{16,20}"))throw new IllegalArgumentException("SteamID64 inválido");
    }

    private static String enc(String value) throws UnsupportedEncodingException {
        return URLEncoder.encode(value,StandardCharsets.UTF_8.name());
    }

    private static JSONObject getJson(String url) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent","Ludex-Android/"+BuildConfig.VERSION_NAME);
        c.setRequestProperty("Accept","application/json");
        int code=c.getResponseCode();
        if(code<200||code>=300){
            read(c.getErrorStream());
            throw new IOException("Steam respondeu HTTP "+code);
        }
        return new JSONObject(read(c.getInputStream()));
    }

    private static String read(InputStream in)throws IOException{
        if(in==null)return "";
        try(InputStream x=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[16384];int n;while((n=x.read(b))!=-1)out.write(b,0,n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
