package com.ludex.mobile;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

public final class NintendoPlayActivityClient {
    private static final String CLIENT_ID="5c38e31cd085304b";
    private static final String REDIRECT_URI="npf"+CLIENT_ID+"://auth";
    private static final String ACCOUNTS="https://accounts.nintendo.com";
    private static final String API="https://app-api.znej.nintendo.com";
    private static final String USER_AGENT="com.nintendo.znej/3.0.3 (iOS/26.0.1)";

    public static final class LoginRequest {
        public final String url,codeVerifier,state;
        LoginRequest(String url,String codeVerifier,String state){this.url=url;this.codeVerifier=codeVerifier;this.state=state;}
    }

    public static final class Callback {
        public final String code,state;
        Callback(String code,String state){this.code=code;this.state=state;}
    }

    public static final class Title {
        public final String titleId,titleName,platform,imageUrl,firstPlayedAt,lastPlayedAt;
        public final long totalPlayedMinutes;
        Title(String titleId,String titleName,String platform,String imageUrl,String firstPlayedAt,String lastPlayedAt,long totalPlayedMinutes){
            this.titleId=titleId;this.titleName=titleName;this.platform=platform;this.imageUrl=imageUrl;
            this.firstPlayedAt=firstPlayedAt;this.lastPlayedAt=lastPlayedAt;this.totalPlayedMinutes=totalPlayedMinutes;
        }
    }

    private static final class AccessPair {
        final String access,id;
        AccessPair(String access,String id){this.access=access;this.id=id;}
    }

    private NintendoPlayActivityClient(){}

    public static LoginRequest newLoginRequest() throws Exception {
        String state=randomBase64(36),verifier=randomBase64(32);
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.UTF_8));
        String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(digest);

        LinkedHashMap<String,String> q=new LinkedHashMap<>();
        q.put("client_id",CLIENT_ID);
        q.put("redirect_uri",REDIRECT_URI);
        q.put("response_type","session_token_code");
        q.put("scope","openid user user.mii user.email user.links[].id");
        q.put("session_token_code_challenge",challenge);
        q.put("session_token_code_challenge_method","S256");
        q.put("state",state);
        q.put("theme","login_form");
        return new LoginRequest(ACCOUNTS+"/connect/1.0.0/authorize?"+form(q),verifier,state);
    }

    public static Callback parseCallback(String callback) throws Exception {
        URI uri=new URI(callback.trim());
        String fragment=uri.getRawFragment();
        if(fragment==null||fragment.isBlank())throw new IllegalArgumentException("URL de retorno sem código");
        Map<String,String> values=parseForm(fragment);
        String code=values.get("session_token_code");
        if(code==null||code.isBlank())throw new IllegalArgumentException("session_token_code ausente");
        return new Callback(code,values.getOrDefault("state",""));
    }

    public static String exchangeSessionToken(String code,String verifier) throws Exception {
        LinkedHashMap<String,String> body=new LinkedHashMap<>();
        body.put("client_id",CLIENT_ID);
        body.put("session_token_code",code);
        body.put("session_token_code_verifier",verifier);
        JSONObject json=new JSONObject(request("POST",ACCOUNTS+"/connect/1.0.0/api/session_token",
            "application/x-www-form-urlencoded",form(body),null,null));
        String token=json.optString("session_token","");
        if(token.isBlank())throw new IOException("Nintendo não retornou session_token");
        return token;
    }

    public static List<Title> getPlayHistory(String sessionToken,String locale) throws Exception {
        AccessPair pair=getAccessPair(sessionToken);
        Exception first=null;
        try{return fetchHistory(pair.access,locale);}
        catch(Exception e){first=e;}
        if(pair.id!=null&&!pair.id.isBlank()){
            try{return fetchHistory(pair.id,locale);}
            catch(Exception ignored){}
        }
        throw first;
    }

    private static AccessPair getAccessPair(String sessionToken) throws Exception {
        JSONObject body=new JSONObject();
        body.put("client_id",CLIENT_ID);
        body.put("session_token",sessionToken);
        body.put("grant_type","urn:ietf:params:oauth:grant-type:jwt-bearer-session-token");
        JSONObject json=new JSONObject(request("POST",ACCOUNTS+"/connect/1.0.0/api/token",
            "application/json; charset=utf-8",body.toString(),null,null));
        String access=json.optString("access_token","");
        if(access.isBlank())throw new IOException("Nintendo não retornou access_token");
        return new AccessPair(access,json.optString("id_token",""));
    }

    private static List<Title> fetchHistory(String bearer,String locale) throws Exception {
        LinkedHashMap<String,String> headers=new LinkedHashMap<>();
        headers.put("Authorization","Bearer "+bearer);
        headers.put("Gentry-Locale",locale==null||locale.isBlank()?"pt-BR":locale);
        JSONObject root=new JSONObject(request("GET",API+"/api/v2.0/users/me/play_histories",null,null,headers,null));
        JSONArray a=root.optJSONArray("playHistories");
        ArrayList<Title> out=new ArrayList<>();
        if(a==null)return out;
        for(int i=0;i<a.length();i++){
            JSONObject o=a.optJSONObject(i);if(o==null)continue;
            String id=o.optString("titleId","");
            String name=o.optString("titleName","");
            if(id.isBlank()||name.isBlank())continue;
            String platform=o.optString("platform",o.optString("deviceType","Nintendo Switch"));
            out.add(new Title(
                id,name,platform,o.optString("imageUrl",""),
                o.optString("firstPlayedAt",""),o.optString("lastPlayedAt",""),
                Math.max(0,o.optLong("totalPlayedMinutes",0))
            ));
        }
        return out;
    }

    private static String request(String method,String url,String contentType,String body,Map<String,String> headers,Integer timeout) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setRequestMethod(method);c.setConnectTimeout(timeout==null?15000:timeout);c.setReadTimeout(timeout==null?30000:timeout);
        c.setRequestProperty("Accept","application/json");
        c.setRequestProperty("User-Agent",USER_AGENT);
        if(headers!=null)for(Map.Entry<String,String> e:headers.entrySet())c.setRequestProperty(e.getKey(),e.getValue());
        if(body!=null){
            c.setDoOutput(true);
            if(contentType!=null)c.setRequestProperty("Content-Type",contentType);
            try(OutputStream out=c.getOutputStream()){out.write(body.getBytes(StandardCharsets.UTF_8));}
        }
        int code=c.getResponseCode();
        InputStream stream=code>=200&&code<300?c.getInputStream():c.getErrorStream();
        String text=read(stream);
        c.disconnect();
        if(code<200||code>=300){
            String detail=text.length()>400?text.substring(0,400):text;
            throw new IOException("Nintendo HTTP "+code+(detail.isBlank()?"":": "+detail));
        }
        return text;
    }

    private static String read(InputStream in)throws IOException{
        if(in==null)return "";
        try(InputStream x=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[16384];int n;while((n=x.read(b))!=-1)out.write(b,0,n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String randomBase64(int bytes)throws Exception{
        byte[] b=new byte[bytes];new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String form(Map<String,String> values)throws Exception{
        StringBuilder out=new StringBuilder();
        for(Map.Entry<String,String> e:values.entrySet()){
            if(out.length()>0)out.append('&');
            out.append(URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8.name()));
            out.append('=');
            out.append(URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8.name()));
        }
        return out.toString();
    }

    private static Map<String,String> parseForm(String raw)throws Exception{
        LinkedHashMap<String,String> out=new LinkedHashMap<>();
        for(String part:raw.split("&")){
            int eq=part.indexOf('=');
            String k=eq<0?part:part.substring(0,eq);
            String v=eq<0?"":part.substring(eq+1);
            out.put(URLDecoder.decode(k,StandardCharsets.UTF_8.name()),URLDecoder.decode(v,StandardCharsets.UTF_8.name()));
        }
        return out;
    }
}
