package com.ludex.mobile;

import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads only the JSON metadata from Ren'Py .save ZIP archives.
 * Never deserializes untrusted Python pickle / game-state entries.
 */
public final class RenpySaveReader {
    private RenpySaveReader(){}
    private static final int JSON_LIMIT=128*1024;

    public static long readRuntimeSeconds(InputStream input) throws IOException {
        if(input==null)throw new IOException("Arquivo não encontrado");
        try(ZipInputStream zip=new ZipInputStream(new BufferedInputStream(input))){
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null){
                if(!"json".equals(entry.getName())){
                    zip.closeEntry();
                    continue;
                }
                if(entry.getSize()>JSON_LIMIT)throw new IOException("Metadados grandes demais");
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                byte[] buffer=new byte[4096];
                int n;
                while((n=zip.read(buffer))!=-1){
                    if(bytes.size()+n>JSON_LIMIT)throw new IOException("Metadados grandes demais");
                    bytes.write(buffer,0,n);
                }
                try{
                    JSONObject metadata=new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
                    Object runtime=metadata.opt("_game_runtime");
                    if(runtime==null||runtime==JSONObject.NULL)throw new IOException("Este save não tem _game_runtime");
                    double value=Double.parseDouble(runtime.toString());
                    if(!Double.isFinite(value)||value<0)throw new IOException("Tempo de save inválido");
                    return Math.min(Long.MAX_VALUE,(long)value);
                }catch(org.json.JSONException|NumberFormatException e){
                    throw new IOException("Metadados de save inválidos",e);
                }
            }
        }
        throw new IOException("Não é um save Ren'Py moderno com metadados JSON");
    }
}
