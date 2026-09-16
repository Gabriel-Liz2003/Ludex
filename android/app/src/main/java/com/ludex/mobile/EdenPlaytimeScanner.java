package com.ludex.mobile;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import rikka.shizuku.Shizuku;

public final class EdenPlaytimeScanner {
    private EdenPlaytimeScanner(){}

    public static Map<String,Long> read(String packageName) throws Exception {
        if(!Shizuku.pingBinder())throw new IOException("Shizuku não está ativo");
        String[] paths={
            "/storage/emulated/0/Android/data/"+packageName+"/files/play_time/playtime.bin",
            "/sdcard/Android/data/"+packageName+"/files/play_time/playtime.bin"
        };
        Exception last=null;
        for(String path:paths){
            try{
                byte[] data=runBinary(new String[]{"sh","-c","cat '"+path.replace("'","'\\''")+"'"});
                if(data.length==0)continue;
                return parse(data);
            }catch(Exception e){last=e;}
        }
        if(last!=null)throw last;
        return Collections.emptyMap();
    }

    static Map<String,Long> parse(byte[] data){
        LinkedHashMap<String,Long> out=new LinkedHashMap<>();
        ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        while(b.remaining()>=16){
            long programId=b.getLong();
            long seconds=b.getLong();
            if(programId==0)continue;
            out.put(Long.toUnsignedString(programId),Math.max(0,seconds));
        }
        return out;
    }

    private static byte[] runBinary(String[] cmd)throws Exception{
        Method m=Shizuku.class.getDeclaredMethod("newProcess",String[].class,String[].class,String.class);
        m.setAccessible(true);
        Object p=m.invoke(null,(Object)cmd,null,null);
        Method getIn=p.getClass().getMethod("getInputStream");
        Method getErr=p.getClass().getMethod("getErrorStream");
        Method waitFor=p.getClass().getMethod("waitFor");
        byte[] out=readBytes((InputStream)getIn.invoke(p));
        byte[] err=readBytes((InputStream)getErr.invoke(p));
        int code=(Integer)waitFor.invoke(p);
        if(code!=0&&out.length==0)throw new IOException(new String(err).trim().isEmpty()?"Não consegui ler playtime.bin ("+code+")":new String(err).trim());
        return out;
    }

    private static byte[] readBytes(InputStream in)throws IOException{
        try(InputStream x=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=x.read(b))!=-1)out.write(b,0,n);return out.toByteArray();
        }
    }
}
