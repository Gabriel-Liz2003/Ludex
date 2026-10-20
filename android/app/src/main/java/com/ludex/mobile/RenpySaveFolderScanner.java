package com.ludex.mobile;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import androidx.documentfile.provider.DocumentFile;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Recovers evidence of past Ren'Py games from a folder chosen by the user.
 * Does not access Android/data, deserialize saves or upload file contents.
 */
public final class RenpySaveFolderScanner {
    private static final int MAX_GAME_FOLDERS=500;
    private static final int MAX_FILES=3000;
    private static final int MAX_DEPTH=4;
    private static final long MAX_SAVE_BYTES=32L*1024*1024;

    public static final class GameFolder {
        public final String folderKey,title;
        public final long maxRuntimeSeconds;
        public final int savesFound,savesWithRuntime;
        public final boolean hasPersistent;

        GameFolder(String folderKey,String title,long maxRuntimeSeconds,
                   int savesFound,int savesWithRuntime,boolean hasPersistent){
            this.folderKey=folderKey;
            this.title=title;
            this.maxRuntimeSeconds=maxRuntimeSeconds;
            this.savesFound=savesFound;
            this.savesWithRuntime=savesWithRuntime;
            this.hasPersistent=hasPersistent;
        }
    }

    public static final class Result {
        public final List<GameFolder> games=new ArrayList<>();
        public int filesExamined,skippedSaves;
        public boolean limitReached;
    }

    private static final class FolderState {
        int saves,valid;
        long runtime;
        boolean persistent;
    }

    private RenpySaveFolderScanner(){}

    public static Result scan(Context context,Uri treeUri){
        DocumentFile root=DocumentFile.fromTreeUri(context,treeUri);
        if(root==null||!root.isDirectory()||!root.canRead())throw new IllegalArgumentException("Pasta inacessível. Selecione RenPy_Saves novamente.");
        Result result=new Result();
        ContentResolver resolver=context.getContentResolver();
        DocumentFile[] children=root.listFiles();
        for(DocumentFile folder:children){
            if(result.games.size()>=MAX_GAME_FOLDERS||result.filesExamined>=MAX_FILES){
                result.limitReached=true;
                break;
            }
            if(!folder.isDirectory())continue;
            FolderState state=new FolderState();
            inspectFolder(resolver,folder,0,state,result);
            if(state.saves>0||state.persistent){
                String rawName=folder.getName();
                if(rawName==null||rawName.trim().isEmpty())continue;
                result.games.add(new GameFolder(
                    folder.getUri().toString(),
                    displayName(rawName),
                    state.runtime,
                    state.saves,
                    state.valid,
                    state.persistent
                ));
            }
        }
        return result;
    }

    private static void inspectFolder(ContentResolver resolver,DocumentFile folder,int depth,
                                      FolderState state,Result result){
        if(depth>MAX_DEPTH||result.filesExamined>=MAX_FILES){
            result.limitReached=true;
            return;
        }
        for(DocumentFile child:folder.listFiles()){
            if(result.filesExamined>=MAX_FILES){result.limitReached=true;return;}
            if(child.isDirectory()){
                inspectFolder(resolver,child,depth+1,state,result);
                continue;
            }
            if(!child.isFile())continue;
            result.filesExamined++;
            String filename=child.getName();
            if(filename==null)continue;
            String lower=filename.toLowerCase(Locale.ROOT);
            if("persistent".equals(lower)||"persistent.new".equals(lower)){
                state.persistent=true;
                continue;
            }
            if(!lower.endsWith(".save"))continue;
            state.saves++;
            long size=child.length();
            if(size>MAX_SAVE_BYTES){result.skippedSaves++;continue;}
            try(InputStream in=resolver.openInputStream(child.getUri())){
                long seconds=RenpySaveReader.readRuntimeSeconds(in);
                state.runtime=Math.max(state.runtime,seconds);
                state.valid++;
            }catch(Exception e){
                // Some Ren'Py versions/ports don't store _game_runtime metadata.
                result.skippedSaves++;
            }
        }
    }

    private static String displayName(String folder){
        String name=folder.trim()
            .replaceAll("[-_][0-9]{6,}$","")
            .replace('_',' ')
            .trim();
        return name.isEmpty()?folder:name;
    }
}
