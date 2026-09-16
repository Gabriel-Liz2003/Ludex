package com.ludex.mobile;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import rikka.shizuku.Shizuku;

public final class GameNativeShortcutScanner {
    public static final class ShortcutGame {
        public final String source;
        public final String appId;
        public final String title;
        public ShortcutGame(String source,String appId,String title){
            this.source=source;this.appId=appId;this.title=title;
        }
        public GameNativeScanner.ImportedGame asImportedGame(){
            return new GameNativeScanner.ImportedGame(source.toLowerCase(Locale.ROOT),appId,title);
        }
    }

    private static final Pattern ID=Pattern.compile("\\bid=game_(\\d+)",Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE=Pattern.compile("(?:shortLabel|title|label)=([^,}\\n]+)",Pattern.CASE_INSENSITIVE);
    private static final Pattern SOURCE=Pattern.compile("game_source[^A-Z0-9_]+(?:String\\()?([A-Z_]+)",Pattern.CASE_INSENSITIVE);
    private static final Pattern EXTRA_APP_ID=Pattern.compile("app_id[^0-9]+(\\d+)",Pattern.CASE_INSENSITIVE);
    private static final Set<String> SOURCES=new HashSet<>(Arrays.asList("STEAM","GOG","EPIC","AMAZON","CUSTOM_GAME"));

    private GameNativeShortcutScanner(){}

    public static List<ShortcutGame> scan() throws Exception {
        if(!Shizuku.pingBinder())throw new IOException("Shizuku não está ativo");
        String dump=run("dumpsys shortcut");
        LinkedHashMap<String,ShortcutGame> out=new LinkedHashMap<>();
        Matcher idMatcher=ID.matcher(dump);
        while(idMatcher.find()){
            int start=Math.max(0,dump.lastIndexOf("ShortcutInfo",idMatcher.start()));
            int next=dump.indexOf("ShortcutInfo",idMatcher.end());
            int end=next<0?Math.min(dump.length(),idMatcher.end()+1200):Math.min(dump.length(),next);
            String block=dump.substring(start,end);
            String context=dump.substring(Math.max(0,start-1000),Math.min(dump.length(),end+300));
            if(!context.contains("app.gamenative"))continue;

            String id=idMatcher.group(1);
            Matcher extraId=EXTRA_APP_ID.matcher(block);
            if(extraId.find())id=extraId.group(1);

            String title=null;
            Matcher titleMatcher=TITLE.matcher(block);
            if(titleMatcher.find())title=clean(titleMatcher.group(1));
            if(title==null||title.isBlank()||"***".equals(title))title="GameNative "+id;

            String source="STEAM";
            Matcher sourceMatcher=SOURCE.matcher(block);
            if(sourceMatcher.find()){
                String candidate=sourceMatcher.group(1).toUpperCase(Locale.ROOT);
                if(SOURCES.contains(candidate))source=candidate;
            }

            out.put(source+":"+id,new ShortcutGame(source,id,title));
        }
        return new ArrayList<>(out.values());
    }

    private static String clean(String value){
        String x=value.trim();
        if((x.startsWith(""")&&x.endsWith("""))||(x.startsWith("'")&&x.endsWith("'")))x=x.substring(1,x.length()-1);
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
