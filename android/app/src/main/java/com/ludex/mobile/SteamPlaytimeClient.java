package com.ludex.mobile;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class SteamPlaytimeClient {
    private SteamPlaytimeClient(){}

    public static Map<String,Long> getOwnedPlaytime(String apiKey,String steamId64) throws Exception {
        if(apiKey==null||apiKey.trim().isEmpty())throw new IllegalArgumentException("API Key vazia");
        if(steamId64==null||!steamId64.matches("\\d{16,20}"))throw new IllegalArgumentException("SteamID64 inválido");

        String url="https://api.steampowered.com/IPlayerService/GetOwnedGames/v1/"+
            "?key="+URLEncoder.encode(apiKey.trim(),StandardCharsets.UTF_8.name())+
            "&steamid="+URLEncoder.encode(steamId64.trim(),StandardCharsets.UTF_8.name())+
            "&include_appinfo=false&include_played_free_games=true";

        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(15000);c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent","Ludex-Android/"+BuildConfig.VERSION_NAME);
        c.setRequestProperty("Accept","application/json");
        int code=c.getResponseCode();
        if(code<200||code>=300){
            String detail=read(c.getErrorStream());
            throw new IOException("Steam respondeu HTTP "+code+(detail.isBlank()?"":""));
        }

        JSONObject root=new JSONObject(read(c.getInputStream()));
        JSONObject response=root.optJSONObject("response");
        if(response==null)throw new IOException("Resposta da Steam inválida");
        JSONArray games=response.optJSONArray("games");
        LinkedHashMap<String,Long> out=new LinkedHashMap<>();
        if(games==null)return out;
        for(int i=0;i<games.length();i++){
            JSONObject g=games.getJSONObject(i);
            String appId=Integer.toString(g.optInt("appid",-1));
            if("-1".equals(appId))continue;
            long minutes=Math.max(0,g.optLong("playtime_forever",0));
            out.put(appId,minutes*60L);
        }
        return out;
    }

    private static String read(InputStream in)throws IOException{
        if(in==null)return "";
        try(InputStream x=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[16384];int n;while((n=x.read(b))!=-1)out.write(b,0,n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
