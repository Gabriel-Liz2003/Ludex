package com.ludex.mobile;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

public final class PsnClient {
    private static final String AUTH_BASE="https://ca.account.sony.com/api/authz/v3/oauth";
    private static final String NPSSO_URL="https://ca.account.sony.com/api/v1/ssocookie";
    private static final String GAMES_BASE="https://m.np.playstation.com/api/gamelist/v2/users/me/titles";
    private static final String TROPHY_BASE="https://m.np.playstation.com/api/trophy/v1/users/me/trophyTitles";
    private static final String CLIENT_ID="09515159-7237-4370-9b40-3806e67c0891";
    private static final String REDIRECT_URI="com.scee.psxandroid.scecompcall://redirect";
    private static final String SCOPE="psn:mobile.v2.core psn:clientapp";
    private static final String BASIC_AUTH="Basic MDk1MTUxNTktNzIzNy00MzcwLTliNDAtMzgwNmU2N2MwODkxOnVjUGprYTV0bnRCMktxc1A=";
    private static final String USER_AGENT="Ludex-Android/"+BuildConfig.VERSION_NAME;

    public static final class Credentials {
        public final String accessToken,refreshToken;
        public final long accessExpiresAtMs,refreshExpiresAtMs;
        Credentials(String accessToken,String refreshToken,long accessExpiresAtMs,long refreshExpiresAtMs){
            this.accessToken=accessToken==null?"":accessToken;
            this.refreshToken=refreshToken==null?"":refreshToken;
            this.accessExpiresAtMs=Math.max(0,accessExpiresAtMs);
            this.refreshExpiresAtMs=Math.max(0,refreshExpiresAtMs);
        }
    }

    public static final class Title {
        public final String titleId,conceptId,name,imageUrl,category,service;
        public final long seconds,firstPlayedAtMs,lastPlayedAtMs;
        public final int playCount,trophyProgress,trophiesEarned,trophiesDefined,platinumEarned;

        Title(String titleId,String conceptId,String name,String imageUrl,String category,String service,
              long seconds,long firstPlayedAtMs,long lastPlayedAtMs,int playCount,
              int trophyProgress,int trophiesEarned,int trophiesDefined,int platinumEarned){
            this.titleId=titleId==null?"":titleId;
            this.conceptId=conceptId==null?"":conceptId;
            this.name=name==null?"":name;
            this.imageUrl=imageUrl==null?"":imageUrl;
            this.category=category==null?"":category;
            this.service=service==null?"":service;
            this.seconds=Math.max(0,seconds);
            this.firstPlayedAtMs=Math.max(0,firstPlayedAtMs);
            this.lastPlayedAtMs=Math.max(0,lastPlayedAtMs);
            this.playCount=Math.max(0,playCount);
            this.trophyProgress=Math.max(0,Math.min(100,trophyProgress));
            this.trophiesEarned=Math.max(0,trophiesEarned);
            this.trophiesDefined=Math.max(0,trophiesDefined);
            this.platinumEarned=Math.max(0,platinumEarned);
        }

        public String platform(){
            switch(category.toLowerCase(Locale.ROOT)){
                case "ps5_native_game": return "PlayStation 5";
                case "ps4_game": return "PlayStation 4";
                case "ps3_game": return "PlayStation 3";
                case "pspc_game": return "PC (PlayStation)";
                default: return "PlayStation";
            }
        }
    }

    private static final class TrophySummary {
        final int progress,earned,defined,platinum;
        TrophySummary(int progress,int earned,int defined,int platinum){
            this.progress=progress;this.earned=earned;this.defined=defined;this.platinum=platinum;
        }
    }

    private PsnClient(){}

    public static String signInUrl(){return "https://www.playstation.com/";}
    public static String npssoUrl(){return NPSSO_URL;}

    public static String extractNpsso(String raw) throws Exception {
        String value=raw==null?"":raw.trim();
        if(value.isEmpty())throw new IllegalArgumentException("NPSSO vazio");
        if(value.startsWith("{")){
            JSONObject json=new JSONObject(value);
            String token=json.optString("npsso","").trim();
            if(token.isEmpty())throw new IllegalArgumentException("JSON sem campo npsso");
            return token;
        }
        if(value.startsWith("\"")&&value.endsWith("\"")&&value.length()>2){
            value=value.substring(1,value.length()-1).trim();
        }
        return value;
    }

    public static Credentials exchangeNpsso(String rawNpsso) throws Exception {
        String npsso=extractNpsso(rawNpsso);
        String code=exchangeNpssoForCode(npsso);
        LinkedHashMap<String,String> form=new LinkedHashMap<>();
        form.put("code",code);
        form.put("redirect_uri",REDIRECT_URI);
        form.put("grant_type","authorization_code");
        form.put("token_format","jwt");
        return exchangeToken(form);
    }

    public static Credentials refresh(String refreshToken) throws Exception {
        if(refreshToken==null||refreshToken.isBlank())throw new IllegalArgumentException("Refresh token PSN vazio");
        LinkedHashMap<String,String> form=new LinkedHashMap<>();
        form.put("refresh_token",refreshToken.trim());
        form.put("grant_type","refresh_token");
        form.put("token_format","jwt");
        form.put("scope",SCOPE);
        Credentials renewed=exchangeToken(form);
        if(!renewed.refreshToken.isBlank())return renewed;
        return new Credentials(
            renewed.accessToken,
            refreshToken.trim(),
            renewed.accessExpiresAtMs,
            renewed.refreshExpiresAtMs
        );
    }

    private static String exchangeNpssoForCode(String npsso) throws Exception {
        LinkedHashMap<String,String> query=new LinkedHashMap<>();
        query.put("access_type","offline");
        query.put("client_id",CLIENT_ID);
        query.put("redirect_uri",REDIRECT_URI);
        query.put("response_type","code");
        query.put("scope",SCOPE);
        String url=AUTH_BASE+"/authorize?"+encodeForm(query);

        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Cookie","npsso="+npsso);
        c.setRequestProperty("User-Agent",USER_AGENT);
        int status=c.getResponseCode();
        String location=c.getHeaderField("Location");
        c.disconnect();

        if(location==null||location.isBlank()){
            throw new IOException("A Sony não retornou o código de autorização (HTTP "+status+"). Gere um NPSSO novo.");
        }

        String code=queryValue(location,"code");
        if(code.isBlank())throw new IOException("NPSSO inválido ou expirado. Gere um NPSSO novo.");
        return code;
    }

    private static Credentials exchangeToken(Map<String,String> form) throws Exception {
        String text=request("POST",AUTH_BASE+"/token",BASIC_AUTH,"application/x-www-form-urlencoded",encodeForm(form));
        JSONObject json=new JSONObject(text);
        String access=json.optString("access_token","").trim();
        String refresh=json.optString("refresh_token","").trim();
        if(access.isEmpty()){
            String error=json.optString("error_description",json.optString("error","Falha ao autenticar PSN"));
            throw new IOException(error);
        }
        long now=System.currentTimeMillis();
        long accessExpires=now+Math.max(60,json.optLong("expires_in",3600))*1000L;
        long refreshExpires=now+Math.max(60,json.optLong("refresh_token_expires_in",5184000))*1000L;
        return new Credentials(access,refresh,accessExpires,refreshExpires);
    }

    public static List<Title> getPlayedGames(Credentials credentials) throws Exception {
        if(credentials==null||credentials.accessToken.isBlank())throw new IllegalArgumentException("Sessão PSN inválida");
        Map<String,TrophySummary> trophies;
        try{trophies=fetchTrophySummaries(credentials.accessToken);}
        catch(Exception ignored){trophies=Collections.emptyMap();}

        ArrayList<Title> out=new ArrayList<>();
        HashSet<Integer> seenOffsets=new HashSet<>();
        int offset=0;
        final int limit=100;

        while(seenOffsets.add(offset)){
            String url=GAMES_BASE+
                "?categories="+enc("ps4_game,ps5_native_game,pspc_game,unknown")+
                "&limit="+limit+"&offset="+offset;
            JSONObject json=new JSONObject(request("GET",url,"Bearer "+credentials.accessToken,null,null));
            if(json.has("error"))throw new IOException(json.optJSONObject("error").optString("message","Falha ao consultar jogos PSN"));

            JSONArray titles=json.optJSONArray("titles");
            if(titles==null||titles.length()==0)break;

            for(int i=0;i<titles.length();i++){
                JSONObject item=titles.optJSONObject(i);
                if(item==null)continue;
                String titleId=item.optString("titleId","").trim();
                String name=firstNonBlank(item.optString("localizedName",""),item.optString("name",""));
                if(titleId.isEmpty()||name.isEmpty())continue;

                JSONObject concept=item.optJSONObject("concept");
                String conceptId=concept==null?"":Long.toString(concept.optLong("id",0));
                if("0".equals(conceptId))conceptId="";
                if(concept!=null)name=firstNonBlank(concept.optString("name",""),name);

                String image=firstNonBlank(item.optString("localizedImageUrl",""),item.optString("imageUrl",""));
                if(image.isBlank()&&concept!=null)image=conceptImage(concept);

                TrophySummary trophy=trophies.get(normalizeTitle(name));
                if(trophy==null)trophy=new TrophySummary(0,0,0,0);

                out.add(new Title(
                    titleId,
                    conceptId,
                    name,
                    image,
                    item.optString("category",""),
                    item.optString("service",""),
                    parseDurationSeconds(item.optString("playDuration","")),
                    parseDate(item.optString("firstPlayedDateTime","")),
                    parseDate(item.optString("lastPlayedDateTime","")),
                    item.optInt("playCount",0),
                    trophy.progress,
                    trophy.earned,
                    trophy.defined,
                    trophy.platinum
                ));
            }

            int next=json.optInt("nextOffset",-1);
            int total=json.optInt("totalItemCount",-1);
            if(next>=0&&next!=offset){offset=next;continue;}
            offset+=titles.length();
            if(titles.length()<limit||(total>=0&&offset>=total))break;
        }

        return out;
    }

    private static Map<String,TrophySummary> fetchTrophySummaries(String accessToken) throws Exception {
        LinkedHashMap<String,TrophySummary> out=new LinkedHashMap<>();
        int offset=0;
        final int limit=800;
        HashSet<Integer> seenOffsets=new HashSet<>();

        while(seenOffsets.add(offset)){
            String url=TROPHY_BASE+"?limit="+limit+"&offset="+offset;
            JSONObject json=new JSONObject(request("GET",url,"Bearer "+accessToken,null,null));
            JSONArray titles=json.optJSONArray("trophyTitles");
            if(titles==null||titles.length()==0)break;

            for(int i=0;i<titles.length();i++){
                JSONObject item=titles.optJSONObject(i);
                if(item==null)continue;
                String name=item.optString("trophyTitleName","").trim();
                if(name.isEmpty())continue;
                JSONObject earned=item.optJSONObject("earnedTrophies");
                JSONObject defined=item.optJSONObject("definedTrophies");
                int earnedCount=trophyCount(earned);
                int definedCount=trophyCount(defined);
                int platinum=earned==null?0:earned.optInt("platinum",0);
                String key=normalizeTitle(name);
                TrophySummary existing=out.get(key);
                TrophySummary incoming=new TrophySummary(item.optInt("progress",0),earnedCount,definedCount,platinum);
                if(existing==null||incoming.progress>existing.progress)out.put(key,incoming);
            }

            int next=json.optInt("nextOffset",-1);
            int total=json.optInt("totalItemCount",-1);
            if(next>=0&&next!=offset){offset=next;continue;}
            offset+=titles.length();
            if(titles.length()<limit||(total>=0&&offset>=total))break;
        }
        return out;
    }

    private static int trophyCount(JSONObject o){
        if(o==null)return 0;
        return Math.max(0,o.optInt("bronze",0))+
            Math.max(0,o.optInt("silver",0))+
            Math.max(0,o.optInt("gold",0))+
            Math.max(0,o.optInt("platinum",0));
    }

    private static String conceptImage(JSONObject concept){
        JSONObject media=concept.optJSONObject("media");
        if(media==null)return "";
        JSONArray images=media.optJSONArray("images");
        if(images==null)return "";
        String fallback="";
        String[] preferred={"FOUR_BY_THREE_BANNER","MASTER","PORTRAIT_BANNER","GAMEHUB_COVER_ART"};
        for(String type:preferred){
            for(int i=0;i<images.length();i++){
                JSONObject image=images.optJSONObject(i);
                if(image==null)continue;
                String url=image.optString("url","");
                if(fallback.isBlank()&&!url.isBlank())fallback=url;
                if(type.equalsIgnoreCase(image.optString("type",""))&&!url.isBlank())return url;
            }
        }
        return fallback;
    }

    private static long parseDurationSeconds(String raw){
        if(raw==null||raw.isBlank())return 0;
        try{return Math.max(0,Duration.parse(raw.trim()).getSeconds());}
        catch(Exception ignored){return 0;}
    }

    private static long parseDate(String raw){
        if(raw==null||raw.isBlank())return 0;
        try{return Instant.parse(raw.trim()).toEpochMilli();}
        catch(Exception ignored){return 0;}
    }

    private static String normalizeTitle(String raw){
        if(raw==null)return "";
        return java.text.Normalizer.normalize(raw,java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}+","")
            .toLowerCase(Locale.ROOT)
            .replace("&","and")
            .replaceAll("[^a-z0-9]+","")
            .trim();
    }

    private static String firstNonBlank(String... values){
        if(values==null)return "";
        for(String value:values)if(value!=null&&!value.trim().isEmpty())return value.trim();
        return "";
    }

    private static String queryValue(String rawUrl,String wanted) throws Exception {
        int q=rawUrl.indexOf('?');
        if(q<0)return "";
        String query=rawUrl.substring(q+1);
        int hash=query.indexOf('#');
        if(hash>=0)query=query.substring(0,hash);
        for(String part:query.split("&")){
            int eq=part.indexOf('=');
            String key=URLDecoder.decode(eq<0?part:part.substring(0,eq),StandardCharsets.UTF_8.name());
            if(!wanted.equals(key))continue;
            return URLDecoder.decode(eq<0?"":part.substring(eq+1),StandardCharsets.UTF_8.name());
        }
        return "";
    }

    private static String request(String method,String url,String authorization,String contentType,String body) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(45000);
        c.setRequestProperty("Accept","application/json");
        c.setRequestProperty("Accept-Language","pt-BR");
        c.setRequestProperty("User-Agent",USER_AGENT);
        if(authorization!=null)c.setRequestProperty("Authorization",authorization);
        if(body!=null){
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type",contentType==null?"application/x-www-form-urlencoded":contentType);
            try(OutputStream out=c.getOutputStream()){out.write(body.getBytes(StandardCharsets.UTF_8));}
        }

        int code=c.getResponseCode();
        InputStream stream=code>=200&&code<300?c.getInputStream():c.getErrorStream();
        String text=read(stream);
        c.disconnect();
        if(code<200||code>=300){
            String detail=text.length()>400?text.substring(0,400):text;
            throw new IOException("PSN HTTP "+code+(detail.isBlank()?"":": "+detail));
        }
        return text;
    }

    private static String encodeForm(Map<String,String> values) throws Exception {
        StringBuilder out=new StringBuilder();
        for(Map.Entry<String,String> entry:values.entrySet()){
            if(out.length()>0)out.append('&');
            out.append(enc(entry.getKey())).append('=').append(enc(entry.getValue()));
        }
        return out.toString();
    }

    private static String enc(String value) throws UnsupportedEncodingException {
        return URLEncoder.encode(value==null?"":value,StandardCharsets.UTF_8.name());
    }

    private static String read(InputStream in)throws IOException{
        if(in==null)return "";
        try(InputStream x=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[16384];int n;while((n=x.read(b))!=-1)out.write(b,0,n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
