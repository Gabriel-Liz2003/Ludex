package com.ludex.mobile;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.*;

public final class AndroidUpdater {
    private static final String RELEASES="https://api.github.com/repos/Gabriel-Liz2003/Ludex/releases?per_page=50";
    private static final String MAIN_GRADLE="https://raw.githubusercontent.com/Gabriel-Liz2003/Ludex/main/android/app/build.gradle";
    private AndroidUpdater(){}

    public static final class UpdateInfo {
        public final String version,apkUrl,shaUrl;
        UpdateInfo(String version,String apkUrl,String shaUrl){this.version=version;this.apkUrl=apkUrl;this.shaUrl=shaUrl;}
    }

    public static final class UpdateCheck {
        public final UpdateInfo update;
        public final String codeVersion;
        public final boolean releaseMissing;
        UpdateCheck(UpdateInfo update,String codeVersion,boolean releaseMissing){
            this.update=update;this.codeVersion=codeVersion;this.releaseMissing=releaseMissing;
        }
        public boolean isCurrent(){return update==null&&!releaseMissing;}
    }

    public static UpdateCheck check() throws Exception {
        String codeVersion=readMainVersion();
        JSONArray releases=new JSONArray(readText(RELEASES));
        UpdateInfo best=null;
        for(int i=0;i<releases.length();i++){
            JSONObject r=releases.getJSONObject(i);
            if(r.optBoolean("draft")||r.optBoolean("prerelease"))continue;
            String tag=r.optString("tag_name","");
            if(!tag.startsWith("android-v"))continue;
            String version=tag.substring("android-v".length());

            String apk=null,sha=null;
            JSONArray assets=r.optJSONArray("assets");
            if(assets!=null)for(int j=0;j<assets.length();j++){
                JSONObject a=assets.getJSONObject(j);
                String name=a.optString("name","");
                String url=a.optString("browser_download_url","");
                if(name.endsWith(".apk"))apk=url;
                else if(name.endsWith(".apk.sha256")||name.endsWith(".sha256"))sha=url;
            }
            if(apk==null||sha==null)continue;
            if(!newer(version,BuildConfig.VERSION_NAME))continue;
            if(best==null||newer(version,best.version))best=new UpdateInfo(version,apk,sha);
        }

        boolean missing=best==null && codeVersion!=null && newer(codeVersion,BuildConfig.VERSION_NAME);
        return new UpdateCheck(best,codeVersion,missing);
    }

    public static UpdateInfo checkLatest() throws Exception {
        return check().update;
    }

    public static File downloadAndVerify(Context context,UpdateInfo update) throws Exception {
        File dir=new File(context.getCacheDir(),"updates");
        if(!dir.exists()&&!dir.mkdirs())throw new IOException("Não foi possível criar pasta de atualização");
        File apk=new File(dir,"Ludex-Android-"+update.version+".apk");
        download(update.apkUrl,apk);
        String expected=readText(update.shaUrl).trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
        String actual=sha256(apk);
        if(expected.length()!=64||!expected.equals(actual)){
            apk.delete();
            throw new SecurityException("SHA-256 do APK não confere");
        }
        return apk;
    }

    private static String readMainVersion(){
        try{
            String gradle=readText(MAIN_GRADLE);
            Matcher m=Pattern.compile("versionName\\s+['\"]([^'\"]+)['\"]").matcher(gradle);
            return m.find()?m.group(1):null;
        }catch(Exception e){return null;}
    }

    static boolean newer(String remote,String local){
        int[] a=parse(remote),b=parse(local);
        int n=Math.max(a.length,b.length);
        for(int i=0;i<n;i++){
            int x=i<a.length?a[i]:0,y=i<b.length?b[i]:0;
            if(x!=y)return x>y;
        }
        return false;
    }

    private static int[] parse(String version){
        String clean=version==null?"":version.replaceAll("[^0-9.]","");
        String[] p=clean.split("\\.");
        int[] out=new int[p.length];
        for(int i=0;i<p.length;i++)try{out[i]=p[i].isEmpty()?0:Integer.parseInt(p[i]);}catch(Exception ignored){out[i]=0;}
        return out;
    }

    private static String readText(String url) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        HttpURLConnection c=open(url);
        try(InputStream in=c.getInputStream()){copy(in,out);}
        finally{c.disconnect();}
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static void download(String url,File target) throws Exception {
        HttpURLConnection c=open(url);
        try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(target)){copy(in,out);}
        finally{c.disconnect();}
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent","Ludex-Android/"+BuildConfig.VERSION_NAME);
        c.setRequestProperty("Accept","application/vnd.github+json");
        int code=c.getResponseCode();
        if(code<200||code>=400)throw new IOException("GitHub respondeu HTTP "+code);
        return c;
    }

    private static void copy(InputStream in,OutputStream out)throws IOException{
        byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
    }

    private static String sha256(File file)throws Exception{
        MessageDigest md=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(file)){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)md.update(b,0,n);}
        StringBuilder s=new StringBuilder();for(byte x:md.digest())s.append(String.format(Locale.ROOT,"%02x",x));return s.toString();
    }
}
