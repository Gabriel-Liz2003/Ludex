package com.ludex.mobile;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

public final class EpicLibraryClient {
    private static final String CLIENT_ID="34a02cf8f4414e29b15921876da36f9a";
    private static final String CLIENT_SECRET="daafbccc737745039dffe53d94fc76cf";
    private static final String TOKEN_URL="https://account-public-service-prod03.ol.epicgames.com/account/api/oauth/token";
    private static final String LIBRARY_URL="https://library-service.live.use1a.on.epicgames.com/library/api/public/items";
    private static final String PLAYTIME_URL="https://library-service.live.use1a.on.epicgames.com/library/api/public/playtime/account";
    private static final String CATALOG_URL="https://catalog-public-service-prod06.ol.epicgames.com/catalog/api/shared/namespace";
    private static final String REDIRECT_URL="https://www.epicgames.com/id/api/redirect";
    private static final String USER_AGENT="UELauncher/11.0.1-14907503+++Portal+Release-Live Windows/10.0.19041.1.256.64bit";

    public static final class Credentials {
        public final String accessToken,refreshToken,accountId,displayName;
        public final long expiresAtMs;

        public Credentials(String accessToken,String refreshToken,String accountId,String displayName,long expiresAtMs){
            this.accessToken=accessToken==null?"":accessToken;
            this.refreshToken=refreshToken==null?"":refreshToken;
            this.accountId=accountId==null?"":accountId;
            this.displayName=displayName==null?"":displayName;
            this.expiresAtMs=expiresAtMs;
        }
    }

    public static final class LibraryGame {
        public final String appName,namespace,catalogItemId,title,imageUrl;
        public final long seconds;

        LibraryGame(String appName,String namespace,String catalogItemId,String title,String imageUrl,long seconds){
            this.appName=appName;
            this.namespace=namespace;
            this.catalogItemId=catalogItemId;
            this.title=title;
            this.imageUrl=imageUrl==null?"":imageUrl;
            this.seconds=Math.max(0,seconds);
        }
    }

    private static final class RawItem {
        final String appName,namespace,catalogItemId,country;
        RawItem(String appName,String namespace,String catalogItemId,String country){
            this.appName=appName;this.namespace=namespace;this.catalogItemId=catalogItemId;this.country=country;
        }
    }

    private EpicLibraryClient(){}

    public static String loginUrl(){
        String redirect=REDIRECT_URL+"?clientId="+CLIENT_ID+"&responseType=code";
        try{
            return "https://www.epicgames.com/id/login?redirectUrl="+URLEncoder.encode(redirect,StandardCharsets.UTF_8.name());
        }catch(Exception e){
            return redirect;
        }
    }

    public static String extractAuthorizationCode(String raw) throws Exception {
        String value=raw==null?"":raw.trim();
        if(value.isEmpty())throw new IllegalArgumentException("Código da Epic vazio");

        if(value.startsWith("{")){
            JSONObject json=new JSONObject(value);
            String code=json.optString("authorizationCode",json.optString("code","")).trim();
            if(!code.isEmpty())return code;
            throw new IllegalArgumentException("JSON sem authorizationCode");
        }

        if(value.startsWith("http://")||value.startsWith("https://")){
            URI uri=new URI(value);
            String query=uri.getRawQuery();
            if(query!=null){
                for(String part:query.split("&")){
                    int eq=part.indexOf('=');
                    String key=eq<0?part:part.substring(0,eq);
                    String val=eq<0?"":part.substring(eq+1);
                    key=URLDecoder.decode(key,StandardCharsets.UTF_8.name());
                    if("code".equals(key)||"authorizationCode".equals(key)){
                        String decoded=URLDecoder.decode(val,StandardCharsets.UTF_8.name()).trim();
                        if(!decoded.isEmpty())return decoded;
                    }
                }
            }
        }

        return value;
    }

    public static Credentials exchangeAuthorizationCode(String rawCode) throws Exception {
        String code=extractAuthorizationCode(rawCode);
        LinkedHashMap<String,String> form=new LinkedHashMap<>();
        form.put("grant_type","authorization_code");
        form.put("code",code);
        form.put("token_type","eg1");
        return oauth(form);
    }

    public static Credentials refresh(String refreshToken) throws Exception {
        if(refreshToken==null||refreshToken.isBlank())throw new IllegalArgumentException("Refresh token da Epic vazio");
        LinkedHashMap<String,String> form=new LinkedHashMap<>();
        form.put("grant_type","refresh_token");
        form.put("refresh_token",refreshToken.trim());
        form.put("token_type","eg1");
        return oauth(form);
    }

    private static Credentials oauth(Map<String,String> form) throws Exception {
        String basic=Base64.getEncoder().encodeToString((CLIENT_ID+":"+CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
        String text=request("POST",TOKEN_URL,"Basic "+basic,"application/x-www-form-urlencoded",encodeForm(form));
        JSONObject json=new JSONObject(text);
        if(json.has("errorCode")){
            throw new IOException(json.optString("errorMessage",json.optString("errorCode","Falha no login Epic")));
        }
        String access=json.optString("access_token","");
        String refresh=json.optString("refresh_token","");
        String account=json.optString("account_id","");
        if(access.isBlank()||refresh.isBlank()||account.isBlank())throw new IOException("Epic não retornou credenciais completas");
        return new Credentials(
            access,
            refresh,
            account,
            json.optString("displayName",""),
            parseExpiry(json)
        );
    }

    public static List<LibraryGame> getOwnedLibrary(Credentials credentials) throws Exception {
        if(credentials==null||credentials.accessToken.isBlank()||credentials.accountId.isBlank()){
            throw new IllegalArgumentException("Sessão Epic inválida");
        }

        List<RawItem> raw=fetchLibrary(credentials.accessToken);
        Map<String,Long> playtime=fetchPlaytime(credentials.accessToken,credentials.accountId);
        ArrayList<LibraryGame> out=new ArrayList<>();

        LinkedHashMap<String,List<RawItem>> groups=new LinkedHashMap<>();
        for(RawItem item:raw){
            String key=item.namespace+"\n"+(item.country.isBlank()?"BR":item.country);
            groups.computeIfAbsent(key,k->new ArrayList<>()).add(item);
        }

        for(List<RawItem> group:groups.values()){
            for(int start=0;start<group.size();start+=20){
                List<RawItem> chunk=group.subList(start,Math.min(start+20,group.size()));
                JSONObject catalog=null;
                try{catalog=fetchCatalog(credentials.accessToken,chunk);}catch(Exception ignored){}

                for(RawItem item:chunk){
                    JSONObject data=catalog==null?null:catalog.optJSONObject(item.catalogItemId);
                    if(data!=null&&data.has("mainGameItem"))continue;

                    String title=data==null?item.appName:data.optString("title",item.appName).trim();
                    if(title.isEmpty())title=item.appName;
                    String image=data==null?"":bestImage(data.optJSONArray("keyImages"));
                    long seconds=playtime.getOrDefault(item.appName,0L);
                    out.add(new LibraryGame(item.appName,item.namespace,item.catalogItemId,title,image,seconds));
                }
            }
        }

        LinkedHashMap<String,LibraryGame> unique=new LinkedHashMap<>();
        for(LibraryGame game:out)unique.put(game.appName,game);
        return new ArrayList<>(unique.values());
    }

    private static List<RawItem> fetchLibrary(String accessToken) throws Exception {
        ArrayList<RawItem> out=new ArrayList<>();
        String cursor=null;
        HashSet<String> seenCursors=new HashSet<>();

        do{
            StringBuilder url=new StringBuilder(LIBRARY_URL).append("?includeMetadata=true");
            if(cursor!=null&&!cursor.isBlank())url.append("&cursor=").append(enc(cursor));
            JSONObject json=new JSONObject(request("GET",url.toString(),"Bearer "+accessToken,null,null));
            JSONArray records=json.optJSONArray("records");
            if(records!=null){
                for(int i=0;i<records.length();i++){
                    JSONObject record=records.optJSONObject(i);
                    if(record==null)continue;
                    String appName=record.optString("appName","").trim();
                    String namespace=record.optString("namespace","").trim();
                    String catalog=record.optString("catalogItemId","").trim();
                    String sandbox=record.optString("sandboxType","");
                    if(appName.isEmpty()||namespace.isEmpty()||catalog.isEmpty())continue;
                    if("ue".equalsIgnoreCase(namespace)||"89efe5924d3d467c839449ab6ab52e7f".equalsIgnoreCase(namespace))continue;
                    if("PRIVATE".equalsIgnoreCase(sandbox)||"1".equals(appName))continue;
                    JSONArray platforms=record.optJSONArray("platform");
                    if(platforms!=null&&platforms.length()>0){
                        boolean windows=false;
                        for(int j=0;j<platforms.length();j++){
                            String platform=platforms.optString(j,"");
                            if("Windows".equalsIgnoreCase(platform)||"Win32".equalsIgnoreCase(platform)){windows=true;break;}
                        }
                        if(!windows)continue;
                    }
                    out.add(new RawItem(appName,namespace,catalog,record.optString("country","BR")));
                }
            }

            JSONObject metadata=json.optJSONObject("responseMetadata");
            String next=metadata==null?"":metadata.optString("nextCursor","").trim();
            if(next.isEmpty()||!seenCursors.add(next))cursor=null;
            else cursor=next;
        }while(cursor!=null);

        return out;
    }

    private static Map<String,Long> fetchPlaytime(String accessToken,String accountId) throws Exception {
        LinkedHashMap<String,Long> out=new LinkedHashMap<>();
        String text=request("GET",PLAYTIME_URL+"/"+encPath(accountId)+"/all","Bearer "+accessToken,null,null);
        JSONArray array=new JSONArray(text);
        for(int i=0;i<array.length();i++){
            JSONObject item=array.optJSONObject(i);
            if(item==null)continue;
            String artifact=item.optString("artifactId","").trim();
            if(artifact.isEmpty())continue;
            out.put(artifact,Math.max(0,item.optLong("totalTime",0)));
        }
        return out;
    }

    private static JSONObject fetchCatalog(String accessToken,List<RawItem> items) throws Exception {
        if(items==null||items.isEmpty())return new JSONObject();
        RawItem first=items.get(0);
        String country=first.country==null||first.country.isBlank()?"BR":first.country;
        StringBuilder url=new StringBuilder(CATALOG_URL)
            .append('/').append(encPath(first.namespace))
            .append("/bulk/items?");
        for(RawItem item:items)url.append("id=").append(enc(item.catalogItemId)).append('&');
        url.append("includeDLCDetails=true&includeMainGameDetails=true")
            .append("&country=").append(enc(country))
            .append("&locale=pt-BR");
        return new JSONObject(request("GET",url.toString(),"Bearer "+accessToken,null,null));
    }

    private static String bestImage(JSONArray images){
        if(images==null)return "";
        String fallback="";
        String[] preferred={"DieselGameBoxTall","OfferImageTall","DieselGameBox","OfferImageWide","Thumbnail"};
        for(String type:preferred){
            for(int i=0;i<images.length();i++){
                JSONObject image=images.optJSONObject(i);if(image==null)continue;
                String url=image.optString("url","");
                if(fallback.isEmpty()&&!url.isBlank())fallback=url;
                if(type.equalsIgnoreCase(image.optString("type",""))&&!url.isBlank())return url;
            }
        }
        return fallback;
    }

    private static long parseExpiry(JSONObject json){
        Object raw=json.opt("expires_at");
        if(raw instanceof Number){
            long value=((Number)raw).longValue();
            return value<10_000_000_000L?value*1000L:value;
        }
        if(raw instanceof String){
            String value=((String)raw).trim();
            if(!value.isEmpty()){
                try{return Instant.parse(value).toEpochMilli();}catch(Exception ignored){}
                try{
                    long numeric=Long.parseLong(value);
                    return numeric<10_000_000_000L?numeric*1000L:numeric;
                }catch(Exception ignored){}
            }
        }
        return System.currentTimeMillis()+Math.max(60,json.optLong("expires_in",7200))*1000L;
    }

    private static String request(String method,String url,String authorization,String contentType,String body) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(45000);
        c.setRequestProperty("Accept","application/json");
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
            String detail=text.length()>300?text.substring(0,300):text;
            throw new IOException("Epic HTTP "+code+(detail.isBlank()?"":": "+detail));
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

    private static String encPath(String value) throws UnsupportedEncodingException {
        return enc(value).replace("+","%20");
    }

    private static String read(InputStream in)throws IOException{
        if(in==null)return "";
        try(InputStream x=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[16384];int n;while((n=x.read(b))!=-1)out.write(b,0,n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
