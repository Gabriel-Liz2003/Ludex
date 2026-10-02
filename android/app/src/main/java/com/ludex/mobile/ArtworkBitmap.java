package com.ludex.mobile;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;

final class ArtworkBitmap {
    private static final int MAX_EDGE=640;
    private ArtworkBitmap(){}

    static Bitmap decode(File file){
        if(file==null||!file.isFile()||file.length()==0)return null;
        try{
            BitmapFactory.Options bounds=new BitmapFactory.Options();
            bounds.inJustDecodeBounds=true;
            BitmapFactory.decodeFile(file.getAbsolutePath(),bounds);
            int width=Math.max(1,bounds.outWidth),height=Math.max(1,bounds.outHeight);
            int sample=1;
            while(Math.max(width/sample,height/sample)>MAX_EDGE*2)sample*=2;

            BitmapFactory.Options opts=new BitmapFactory.Options();
            opts.inSampleSize=Math.max(1,sample);
            return BitmapFactory.decodeFile(file.getAbsolutePath(),opts);
        }catch(Exception e){
            return null;
        }
    }
}
