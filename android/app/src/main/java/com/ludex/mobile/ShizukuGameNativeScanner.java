package com.ludex.mobile;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import rikka.shizuku.Shizuku;

public final class ShizukuGameNativeScanner {
    public static final class Result {
        public final List<GameNativeScanner.ImportedGame> games;
        public final int shizukuUid;
        public final boolean privateStorageAccessible;
        Result(List<GameNativeScanner.ImportedGame> games,int uid,boolean privateAccess){
            this.games=games;this.shizukuUid=uid;this.privateStorageAccessible=privateAccess;
        }
    }

    private static final String[] ROOTS={
        "/data/user/0/app.gamenative",
        "/data/data/app.gamenative",
        "/storage/emulated/0/Android/data/app.gamenative"
    };

    private ShizukuGameNativeScanner(){}

    public static Result scan() throws Exception {
        if(!Shizuku.pingBinder())throw new IOException("Shizuku não está ativo");
        int uid=Shizuku.getUid();
        LinkedHashMap<String,GameNativeScanner.ImportedGame> out=new LinkedHashMap<>();
        boolean privateAccess=uid==0 && commandOk("test -r /data/user/0/app.gamenative");
        scanSteam(out);
        scanGeneric(out,"gog","*/GOG/games/common/*");
        scanGeneric(out,"epic","*/Epic/games/*");
        scanGeneric(out,"amazon","*/Amazon/games/*");
        return new Result(new ArrayList<>(out.values()),uid,privateAccess);
    }

    private static void scanSteam(Map<String,GameNativeScanner.ImportedGame> out)throws Exception{
        String roots=rootsForFind();
        String manifests=run("find "+roots+" -type f -name 'appmanifest_*.acf' 2>/dev/null");
        for(String raw:manifests.split("\\R")){
            String path=raw.trim();
            if(path.isEmpty())continue;
            String content=run("cat "+q(path)+" 2>/dev/null");
            String appid=valueFor(content,"appid"), title=valueFor(content,"name"), installDir=valueFor(content,"installdir");
            if(appid==null||title==null||installDir==null)continue;
            int slash=path.lastIndexOf('/');
            if(slash<=0)continue;
            String steamapps=path.substring(0,slash);
            if(commandOk("test -d "+q(steamapps+"/common/"+installDir))){
                out.put("steam:"+appid,new GameNativeScanner.ImportedGame("steam",appid,title));
            }
        }
    }

    private static void scanGeneric(Map<String,GameNativeScanner.ImportedGame> out,String provider,String pattern)throws Exception{
        String roots=rootsForFind();
        String dirs=run("find "+roots+" -type d -path "+q(pattern)+" 2>/dev/null");
        for(String raw:dirs.split("\\R")){
            String path=raw.trim();
            if(path.isEmpty())continue;
            String title=path.substring(path.lastIndexOf('/')+1);
            if(title.isEmpty()||title.startsWith(".")||title.equalsIgnoreCase("staging"))continue;
            out.put(provider+":"+slug(title),new GameNativeScanner.ImportedGame(provider,slug(title),title));
        }
    }

    private static String rootsForFind(){
        StringBuilder s=new StringBuilder();
        for(String root:ROOTS)s.append(q(root)).append(' ');
        s.append("/storage/*/Android/data/app.gamenative");
        return s.toString();
    }

    private static boolean commandOk(String cmd)throws Exception{
        CommandResult r=exec(new String[]{"sh","-c",cmd});
        return r.exitCode==0;
    }

    private static String run(String cmd)throws Exception{
        CommandResult r=exec(new String[]{"sh","-c",cmd});
        if(r.exitCode!=0 && r.stdout.trim().isEmpty())throw new IOException(r.stderr.isBlank()?"Comando Shizuku falhou ("+r.exitCode+")":r.stderr.trim());
        return r.stdout;
    }

    private static CommandResult exec(String[] cmd)throws Exception{
        Method m=Shizuku.class.getDeclaredMethod("newProcess",String[].class,String[].class,String.class);
        m.setAccessible(true);
        Object p=m.invoke(null,(Object)cmd,null,null);
        Method getIn=p.getClass().getMethod("getInputStream");
        Method getErr=p.getClass().getMethod("getErrorStream");
        Method waitFor=p.getClass().getMethod("waitFor");
        String out=read((InputStream)getIn.invoke(p));
        String err=read((InputStream)getErr.invoke(p));
        int code=(Integer)waitFor.invoke(p);
        return new CommandResult(code,out,err);
    }

    private static String read(InputStream in)throws IOException{
        try(InputStream x=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=x.read(b))!=-1)out.write(b,0,n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String q(String s){return "'"+s.replace("'","'\\''")+"'";}

    private static String valueFor(String content,String key){
        for(String line:content.split("\\R")){
            ArrayList<String> q=new ArrayList<>();boolean quote=false;StringBuilder s=new StringBuilder();
            for(int i=0;i<line.length();i++){
                char c=line.charAt(i);
                if(c=='"'){if(quote){q.add(s.toString());s.setLength(0);}quote=!quote;}
                else if(quote)s.append(c);
            }
            if(q.size()>=2&&q.get(0).equalsIgnoreCase(key))return q.get(1);
        }
        return null;
    }

    private static String slug(String s){
        String x=s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","-").replaceAll("(^-|-$)","");
        return x.isEmpty()?Integer.toHexString(s.hashCode()):x;
    }

    private static final class CommandResult{
        final int exitCode;final String stdout,stderr;
        CommandResult(int c,String o,String e){exitCode=c;stdout=o;stderr=e;}
    }
}
