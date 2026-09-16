package com.ludex.mobile;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import rikka.shizuku.Shizuku;

public final class EdenShortcutScanner {
    public static final String STANDARD_PACKAGE="dev.eden.eden_emulator";
    public static final String OPTIMIZED_PACKAGE="com.miHoYo.Yuanshen";
    public static final String ACTIVITY="org.yuzu.yuzu_emu.activities.EmulationActivity";

    public static final class ShortcutGame {
        public final String packageName,title,uri;
        ShortcutGame(String packageName,String title,String uri){
            this.packageName=packageName;this.title=title;this.uri=uri;
        }
    }

    private static final Pattern ID=Pattern.compile("\\bid=([^,}\\n]+)");
    private static final Pattern LABEL=Pattern.compile("(?:shortLabel|title|label)=([^,}\\n]+)",Pattern.CASE_INSENSITIVE);
    private static final Pattern DATA=Pattern.compile("(?:\\bdat=|\\bdata=)([^\\s,}\\]]+)",Pattern.CASE_INSENSITIVE);

    private EdenShortcutScanner(){}

    public static List<ShortcutGame> scan() throws Exception {
        if(!Shizuku.pingBinder())throw new IOException("Shizuku não está ativo");
        String dump=run("dumpsys shortcut");
        LinkedHashMap<String,ShortcutGame> out=new LinkedHashMap<>();
        parsePackage(dump,STANDARD_PACKAGE,out);
        parsePackage(dump,OPTIMIZED_PACKAGE,out);
        return new ArrayList<>(out.values());
    }

    private static void parsePackage(String dump,String pkg,Map<String,ShortcutGame> out){
        int cursor=0;
        while(true){
            int marker=dump.indexOf("ShortcutInfo",cursor);
            if(marker<0)break;
            int next=dump.indexOf("ShortcutInfo",marker+12);
            int end=next<0?Math.min(dump.length(),marker+2200):Math.min(dump.length(),next);
            String block=dump.substring(marker,end);
            String context=dump.substring(Math.max(0,marker-1200),Math.min(dump.length(),end+500));
            cursor=marker+12;
            if(!context.contains(pkg))continue;

            Matcher dm=DATA.matcher(block);
            if(!dm.find())continue;
            String uri=clean(dm.group(1));
            if(uri.isBlank()||(!uri.startsWith("content:")&&!uri.startsWith("file:")))continue;

            String title="";
            Matcher lm=LABEL.matcher(block);
            if(lm.find())title=clean(lm.group(1));
            if(title.isBlank()){
                Matcher im=ID.matcher(block);
                if(im.find())title=clean(im.group(1));
            }
            if(title.isBlank())title="Jogo Eden";
            out.put(pkg+"|"+uri,new ShortcutGame(pkg,title,uri));
        }
    }

    private static String clean(String value){
        String x=value==null?"":value.trim();
        if((x.startsWith("\"")&&x.endsWith("\""))||(x.startsWith("'")&&x.endsWith("'")))x=x.substring(1,x.length()-1);
        return x.replace("\\n"," ").trim();
    }

    private static String run(String cmd)throws Exception{
        CommandResult r=exec(new String[]{"sh","-c",cmd});
        if(r.exitCode!=0&&r.stdout.trim().isEmpty())throw new IOException(r.stderr.isBlank()?"dumpsys shortcut falhou ("+r.exitCode+")":r.stderr.trim());
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

    private static final class CommandResult{
        final int exitCode;final String stdout,stderr;
        CommandResult(int c,String o,String e){exitCode=c;stdout=o;stderr=e;}
    }
}
