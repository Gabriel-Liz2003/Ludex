package com.ludex.mobile;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import org.json.*;
import java.time.Instant;
import java.util.*;

public final class LudexDb extends SQLiteOpenHelper {
    public static final class GameRow {
        public String id,title,platform,source,packageName,status;
        public boolean installed,favorite,gameNative,eden,emulated;
        public long seconds,updatedAt;
    }
    public static final class SyncResult { public int inserted,updated,skipped; }
    public static final class GameNativeLaunch {
        public final String provider,externalId;
        GameNativeLaunch(String provider,String externalId){this.provider=provider;this.externalId=externalId;}
    }
    public static final class GameNativeSteamLink {
        public final String gameId,appId;
        GameNativeSteamLink(String gameId,String appId){this.gameId=gameId;this.appId=appId;}
    }
    public static final class EdenLaunch {
        public final String packageName,uri,programId;
        EdenLaunch(String packageName,String uri,String programId){this.packageName=packageName;this.uri=uri;this.programId=programId;}
    }
    public static final class EmulatorLaunch {
        public final String packageName,uri,platformId;
        EmulatorLaunch(String packageName,String uri,String platformId){this.packageName=packageName;this.uri=uri;this.platformId=platformId;}
    }
    public static final class NintendoInfo {
        public final String titleId,imageUrl,platform;
        NintendoInfo(String titleId,String imageUrl,String platform){this.titleId=titleId;this.imageUrl=imageUrl;this.platform=platform;}
    }

    public LudexDb(Context c){super(c,"ludex-mobile.db",null,10);}
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE games(id TEXT PRIMARY KEY,title TEXT NOT NULL,platform TEXT NOT NULL DEFAULT 'Android',source TEXT NOT NULL DEFAULT 'android',package_name TEXT UNIQUE,installed INTEGER NOT NULL DEFAULT 0,favorite INTEGER NOT NULL DEFAULT 0,status TEXT NOT NULL DEFAULT 'Quero jogar',updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE play_sessions(id TEXT PRIMARY KEY,game_id TEXT NOT NULL,package_name TEXT,started_at INTEGER NOT NULL,ended_at INTEGER NOT NULL,duration_seconds INTEGER NOT NULL,device TEXT NOT NULL DEFAULT 'android',provider TEXT NOT NULL DEFAULT 'android')");
        db.execSQL("CREATE INDEX idx_sessions_game ON play_sessions(game_id,started_at)");
        db.execSQL("CREATE TABLE imported_playtime(game_id TEXT NOT NULL,provider TEXT NOT NULL,seconds INTEGER NOT NULL DEFAULT 0,updated_at INTEGER NOT NULL,PRIMARY KEY(game_id,provider))");
        db.execSQL("CREATE INDEX idx_imported_playtime_game ON imported_playtime(game_id)");
        db.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
        db.execSQL("CREATE TABLE gamenative_games(game_id TEXT PRIMARY KEY,provider TEXT NOT NULL,external_id TEXT NOT NULL,title TEXT NOT NULL,updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE eden_games(game_id TEXT PRIMARY KEY,package_name TEXT NOT NULL,launch_uri TEXT NOT NULL,title TEXT NOT NULL,program_id TEXT NOT NULL DEFAULT '',updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE emulator_games(game_id TEXT PRIMARY KEY,emulator_package TEXT NOT NULL,platform_id TEXT NOT NULL,launch_uri TEXT NOT NULL,title TEXT NOT NULL,updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE nintendo_games(game_id TEXT PRIMARY KEY,title_id TEXT NOT NULL,image_url TEXT NOT NULL DEFAULT '',platform TEXT NOT NULL DEFAULT 'Nintendo Switch',first_played_at TEXT NOT NULL DEFAULT '',last_played_at TEXT NOT NULL DEFAULT '',updated_at INTEGER NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldV,int newV){
        if(oldV<2)db.execSQL("CREATE INDEX IF NOT EXISTS idx_sessions_game ON play_sessions(game_id,started_at)");
        if(oldV<3)db.execSQL("UPDATE games SET updated_at=strftime('%s','now')*1000 WHERE updated_at=0");
        if(oldV<4){
            db.execSQL("CREATE TABLE IF NOT EXISTS imported_playtime(game_id TEXT NOT NULL,provider TEXT NOT NULL,seconds INTEGER NOT NULL DEFAULT 0,updated_at INTEGER NOT NULL,PRIMARY KEY(game_id,provider))");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_imported_playtime_game ON imported_playtime(game_id)");
        }
        if(oldV<6)db.execSQL("CREATE TABLE IF NOT EXISTS gamenative_games(game_id TEXT PRIMARY KEY,provider TEXT NOT NULL,external_id TEXT NOT NULL,title TEXT NOT NULL,updated_at INTEGER NOT NULL)");
        if(oldV<7)db.execSQL("CREATE TABLE IF NOT EXISTS eden_games(game_id TEXT PRIMARY KEY,package_name TEXT NOT NULL,launch_uri TEXT NOT NULL,title TEXT NOT NULL,updated_at INTEGER NOT NULL)");
        if(oldV<8){
            try{db.execSQL("ALTER TABLE eden_games ADD COLUMN program_id TEXT NOT NULL DEFAULT ''");}catch(Exception ignored){}
        }
        if(oldV<9)db.execSQL("CREATE TABLE IF NOT EXISTS emulator_games(game_id TEXT PRIMARY KEY,emulator_package TEXT NOT NULL,platform_id TEXT NOT NULL,launch_uri TEXT NOT NULL,title TEXT NOT NULL,updated_at INTEGER NOT NULL)");
        if(oldV<10)db.execSQL("CREATE TABLE IF NOT EXISTS nintendo_games(game_id TEXT PRIMARY KEY,title_id TEXT NOT NULL,image_url TEXT NOT NULL DEFAULT '',platform TEXT NOT NULL DEFAULT 'Nintendo Switch',first_played_at TEXT NOT NULL DEFAULT '',last_played_at TEXT NOT NULL DEFAULT '',updated_at INTEGER NOT NULL)");
    }

    public String upsertAndroidGame(String pkg,String title,boolean installed){
        String id="android:"+pkg; long now=System.currentTimeMillis();
        ContentValues v=new ContentValues();v.put("id",id);v.put("title",title);v.put("platform","Android");v.put("source","android");v.put("package_name",pkg);v.put("installed",installed?1:0);v.put("updated_at",now);
        getWritableDatabase().insertWithOnConflict("games",null,v,SQLiteDatabase.CONFLICT_IGNORE);
        ContentValues u=new ContentValues();u.put("title",title);u.put("package_name",pkg);u.put("installed",installed?1:0);u.put("updated_at",now);
        getWritableDatabase().update("games",u,"id=?",new String[]{id});return id;
    }
    public void markAllAndroidUninstalled(){ContentValues v=new ContentValues();v.put("installed",0);getWritableDatabase().update("games",v,"source='android'",null);}
    public boolean hasAndroidGame(String pkg){try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM games WHERE id=? LIMIT 1",new String[]{"android:"+pkg})){return c.moveToFirst();}}

    public List<GameRow> listGames(){
        ArrayList<GameRow> out=new ArrayList<>();
        String sql="SELECT g.id,g.title,g.platform,g.source,g.package_name,"+
            "CASE WHEN g.installed=1 OR EXISTS(SELECT 1 FROM gamenative_games gn WHERE gn.game_id=g.id) OR EXISTS(SELECT 1 FROM eden_games eg WHERE eg.game_id=g.id) OR EXISTS(SELECT 1 FROM emulator_games em WHERE em.game_id=g.id) THEN 1 ELSE 0 END,"+
            "g.favorite,g.status,g.updated_at,"+
            "MAX(COALESCE((SELECT SUM(duration_seconds) FROM play_sessions s WHERE s.game_id=g.id),0),COALESCE((SELECT SUM(seconds) FROM imported_playtime p WHERE p.game_id=g.id),0)) total,"+
            "EXISTS(SELECT 1 FROM gamenative_games gn WHERE gn.game_id=g.id),"+
            "EXISTS(SELECT 1 FROM eden_games eg WHERE eg.game_id=g.id),"+
            "EXISTS(SELECT 1 FROM emulator_games em WHERE em.game_id=g.id) "+
            "FROM games g ORDER BY g.title COLLATE NOCASE";
        try(Cursor c=getReadableDatabase().rawQuery(sql,null)){while(c.moveToNext()){GameRow g=new GameRow();g.id=c.getString(0);g.title=c.getString(1);g.platform=c.getString(2);g.source=c.getString(3);g.packageName=c.isNull(4)?null:c.getString(4);g.installed=c.getInt(5)!=0;g.favorite=c.getInt(6)!=0;g.status=c.getString(7);g.updatedAt=c.getLong(8);g.seconds=c.getLong(9);g.gameNative=c.getInt(10)!=0;g.eden=c.getInt(11)!=0;g.emulated=g.eden||c.getInt(12)!=0;out.add(g);}}
        return out;
    }

    public int syncGameNativeGames(List<GameNativeScanner.ImportedGame> games){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();int count=0;
        try{
            db.delete("gamenative_games",null,null);
            ContentValues off=new ContentValues();off.put("installed",0);
            db.update("games",off,"source='gamenative'",null);
            long now=System.currentTimeMillis();
            for(GameNativeScanner.ImportedGame item:games){
                String gameId=null;
                try(Cursor cur=db.rawQuery("SELECT id FROM games WHERE source!='android' AND lower(trim(title))=lower(trim(?)) ORDER BY CASE WHEN source='gamenative' THEN 1 ELSE 0 END LIMIT 1",new String[]{item.title})){
                    if(cur.moveToFirst())gameId=cur.getString(0);
                }
                if(gameId==null){
                    gameId="gamenative:"+item.provider+":"+item.externalId;
                    ContentValues g=new ContentValues();g.put("id",gameId);g.put("title",item.title);g.put("platform","PC");g.put("source","gamenative");g.put("installed",0);g.put("updated_at",now);
                    db.insertWithOnConflict("games",null,g,SQLiteDatabase.CONFLICT_IGNORE);
                }
                ContentValues link=new ContentValues();link.put("game_id",gameId);link.put("provider",item.provider);link.put("external_id",item.externalId);link.put("title",item.title);link.put("updated_at",now);
                db.insertWithOnConflict("gamenative_games",null,link,SQLiteDatabase.CONFLICT_REPLACE);
                count++;
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        return count;
    }

    public int syncEdenGames(List<EdenLibraryScanner.ImportedGame> games){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();int count=0;
        try{
            db.delete("eden_games",null,null);
            ContentValues off=new ContentValues();off.put("installed",0);
            db.update("games",off,"source='eden'",null);
            long now=System.currentTimeMillis();
            for(EdenLibraryScanner.ImportedGame item:games){
                String gameId=null;
                try(Cursor cur=db.rawQuery("SELECT id FROM games WHERE source!='android' AND lower(trim(title))=lower(trim(?)) ORDER BY CASE WHEN source='eden' THEN 1 ELSE 0 END LIMIT 1",new String[]{item.title})){
                    if(cur.moveToFirst())gameId=cur.getString(0);
                }
                if(gameId==null){
                    gameId="eden:"+Integer.toHexString((item.packageName+"|"+item.uri).hashCode());
                    ContentValues g=new ContentValues();g.put("id",gameId);g.put("title",item.title);g.put("platform","Nintendo Switch");g.put("source","eden");g.put("installed",0);g.put("updated_at",now);
                    db.insertWithOnConflict("games",null,g,SQLiteDatabase.CONFLICT_IGNORE);
                }
                ContentValues link=new ContentValues();link.put("game_id",gameId);link.put("package_name",item.packageName);link.put("launch_uri",item.uri);link.put("title",item.title);link.put("program_id",item.programId);link.put("updated_at",now);
                db.insertWithOnConflict("eden_games",null,link,SQLiteDatabase.CONFLICT_REPLACE);
                count++;
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        return count;
    }

    public EdenLaunch getEdenLaunch(String gameId){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT package_name,launch_uri,program_id FROM eden_games WHERE game_id=? LIMIT 1",new String[]{gameId})){
            if(c.moveToFirst())return new EdenLaunch(c.getString(0),c.getString(1),c.getString(2));
        }
        return null;
    }

    public Map<String,String> listEdenProgramIds(String packageName){
        LinkedHashMap<String,String> out=new LinkedHashMap<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT game_id,program_id FROM eden_games WHERE package_name=? AND program_id!=''",new String[]{packageName})){
            while(c.moveToNext())out.put(c.getString(0),c.getString(1));
        }
        return out;
    }

    public void recordSession(String gameId,String packageName,long startedAt,long endedAt,String provider){
        if(gameId==null||startedAt<=0||endedAt<=startedAt)return;
        long seconds=(endedAt-startedAt)/1000L;
        if(seconds<5)return;
        ContentValues v=new ContentValues();
        v.put("id",UUID.randomUUID().toString());v.put("game_id",gameId);v.put("package_name",packageName);
        v.put("started_at",startedAt);v.put("ended_at",endedAt);v.put("duration_seconds",seconds);
        v.put("device","android");v.put("provider",provider==null?"emulator":provider);
        getWritableDatabase().insertWithOnConflict("play_sessions",null,v,SQLiteDatabase.CONFLICT_IGNORE);
    }

    public int syncEmulatorGames(EmulatorRegistry.Emulator emulator,List<EmulatorLibraryScanner.ImportedGame> games){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();int count=0;
        try{
            ContentValues off=new ContentValues();off.put("installed",0);
            db.update("games",off,"id IN (SELECT game_id FROM emulator_games WHERE emulator_package=?)",new String[]{emulator.packageName});
            db.delete("emulator_games","emulator_package=?",new String[]{emulator.packageName});
            long now=System.currentTimeMillis();
            for(EmulatorLibraryScanner.ImportedGame item:games){
                String gameId="emulator:"+emulator.platformId+":"+Integer.toHexString((emulator.packageName+"|"+item.uri).hashCode());
                ContentValues g=new ContentValues();g.put("id",gameId);g.put("title",item.title);g.put("platform",emulator.platform);g.put("source","emulator");g.put("installed",0);g.put("updated_at",now);
                db.insertWithOnConflict("games",null,g,SQLiteDatabase.CONFLICT_IGNORE);
                ContentValues u=new ContentValues();u.put("title",item.title);u.put("platform",emulator.platform);u.put("source","emulator");u.put("updated_at",now);
                db.update("games",u,"id=?",new String[]{gameId});
                ContentValues link=new ContentValues();link.put("game_id",gameId);link.put("emulator_package",emulator.packageName);link.put("platform_id",emulator.platformId);link.put("launch_uri",item.uri);link.put("title",item.title);link.put("updated_at",now);
                db.insertWithOnConflict("emulator_games",null,link,SQLiteDatabase.CONFLICT_REPLACE);
                count++;
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        return count;
    }

    public EmulatorLaunch getEmulatorLaunch(String gameId){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT emulator_package,launch_uri,platform_id FROM emulator_games WHERE game_id=? LIMIT 1",new String[]{gameId})){
            if(c.moveToFirst())return new EmulatorLaunch(c.getString(0),c.getString(1),c.getString(2));
        }
        return null;
    }

    public GameNativeLaunch getGameNativeLaunch(String gameId){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT provider,external_id FROM gamenative_games WHERE game_id=? LIMIT 1",new String[]{gameId})){
            if(c.moveToFirst())return new GameNativeLaunch(c.getString(0),c.getString(1));
        }
        return null;
    }

    public List<GameNativeSteamLink> listGameNativeSteamLinks(){
        ArrayList<GameNativeSteamLink> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT game_id,external_id FROM gamenative_games WHERE lower(provider)='steam'",null)){
            while(c.moveToNext())out.add(new GameNativeSteamLink(c.getString(0),c.getString(1)));
        }
        return out;
    }

    public int syncNintendoPlayHistory(List<NintendoPlayActivityClient.Title> titles){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();int count=0;long now=System.currentTimeMillis();
        try{
            for(NintendoPlayActivityClient.Title item:titles){
                String platform=item.platform==null||item.platform.isBlank()?"Nintendo Switch":item.platform;
                String gameId="nintendo:"+item.titleId+":"+Integer.toHexString(platform.toLowerCase(Locale.ROOT).hashCode());
                ContentValues g=new ContentValues();
                g.put("id",gameId);g.put("title",item.titleName);g.put("platform",platform);g.put("source","nintendo");g.put("installed",0);g.put("updated_at",now);
                db.insertWithOnConflict("games",null,g,SQLiteDatabase.CONFLICT_IGNORE);
                ContentValues gu=new ContentValues();gu.put("title",item.titleName);gu.put("platform",platform);gu.put("source","nintendo");gu.put("updated_at",now);
                db.update("games",gu,"id=?",new String[]{gameId});

                ContentValues n=new ContentValues();
                n.put("game_id",gameId);n.put("title_id",item.titleId);n.put("image_url",item.imageUrl==null?"":item.imageUrl);
                n.put("platform",platform);n.put("first_played_at",item.firstPlayedAt==null?"":item.firstPlayedAt);
                n.put("last_played_at",item.lastPlayedAt==null?"":item.lastPlayedAt);n.put("updated_at",now);
                db.insertWithOnConflict("nintendo_games",null,n,SQLiteDatabase.CONFLICT_REPLACE);

                ContentValues p=new ContentValues();p.put("game_id",gameId);p.put("provider","nintendo-account");
                p.put("seconds",Math.max(0,item.totalPlayedMinutes)*60L);p.put("updated_at",now);
                db.insertWithOnConflict("imported_playtime",null,p,SQLiteDatabase.CONFLICT_REPLACE);
                count++;
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        return count;
    }

    public NintendoInfo getNintendoInfo(String gameId){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT title_id,image_url,platform FROM nintendo_games WHERE game_id=? LIMIT 1",new String[]{gameId})){
            if(c.moveToFirst())return new NintendoInfo(c.getString(0),c.getString(1),c.getString(2));
        }
        return null;
    }

    public void setImportedPlaytime(String game,String provider,long seconds){
        ContentValues v=new ContentValues();v.put("game_id",game);v.put("provider",provider);v.put("seconds",seconds);v.put("updated_at",System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("imported_playtime",null,v,SQLiteDatabase.CONFLICT_IGNORE);
        getWritableDatabase().execSQL("UPDATE imported_playtime SET seconds=MAX(seconds,?),updated_at=? WHERE game_id=? AND provider=?",new Object[]{seconds,System.currentTimeMillis(),game,provider});
    }
    public void replaceImportedPlaytime(String game,String provider,long seconds){
        ContentValues v=new ContentValues();v.put("game_id",game);v.put("provider",provider);v.put("seconds",Math.max(0,seconds));v.put("updated_at",System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("imported_playtime",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    public void setFavorite(String id,boolean value){ContentValues v=new ContentValues();v.put("favorite",value?1:0);v.put("updated_at",System.currentTimeMillis());getWritableDatabase().update("games",v,"id=?",new String[]{id});}
    public void setStatus(String id,String status){ContentValues v=new ContentValues();v.put("status",status);v.put("updated_at",System.currentTimeMillis());getWritableDatabase().update("games",v,"id=?",new String[]{id});}
    public void setSetting(String key,String value){ContentValues v=new ContentValues();v.put("key",key);v.put("value",value);getWritableDatabase().insertWithOnConflict("settings",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
    public String getSetting(String key,String fallback){try(Cursor c=getReadableDatabase().rawQuery("SELECT value FROM settings WHERE key=?",new String[]{key})){return c.moveToFirst()?c.getString(0):fallback;}}
    public long getSettingLong(String key,long fallback){try{return Long.parseLong(getSetting(key,Long.toString(fallback)));}catch(Exception e){return fallback;}}

    public JSONObject exportBundle() throws JSONException {
        JSONObject root=new JSONObject();root.put("format","ludex-mobile-sync");root.put("version",1);root.put("exported_at_ms",System.currentTimeMillis());
        root.put("games",gamesJson());root.put("sessions",sessionsJson());root.put("imported_playtime",importedPlaytimeJson());
        return root;
    }
    private JSONArray gamesJson() throws JSONException {
        JSONArray a=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT id,title,platform,source,package_name,installed,favorite,status,updated_at FROM games",null)){while(c.moveToNext()){JSONObject o=new JSONObject();o.put("id",c.getString(0));o.put("title",c.getString(1));o.put("platform",c.getString(2));o.put("source",c.getString(3));if(!c.isNull(4))o.put("package_name",c.getString(4));o.put("installed",c.getInt(5));o.put("favorite",c.getInt(6));o.put("status",c.getString(7));o.put("updated_at_ms",c.getLong(8));a.put(o);}}return a;
    }
    private JSONArray sessionsJson() throws JSONException {
        JSONArray a=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT id,game_id,package_name,started_at,ended_at,duration_seconds,device,provider FROM play_sessions",null)){while(c.moveToNext()){JSONObject o=new JSONObject();o.put("id",c.getString(0));o.put("game_id",c.getString(1));if(!c.isNull(2))o.put("package_name",c.getString(2));o.put("started_at_ms",c.getLong(3));o.put("ended_at_ms",c.getLong(4));o.put("duration_seconds",c.getLong(5));o.put("device",c.getString(6));o.put("provider",c.getString(7));a.put(o);}}return a;
    }
    private JSONArray importedPlaytimeJson() throws JSONException {
        JSONArray a=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT game_id,provider,seconds,updated_at FROM imported_playtime",null)){while(c.moveToNext()){JSONObject o=new JSONObject();o.put("game_id",c.getString(0));o.put("provider",c.getString(1));o.put("seconds",c.getLong(2));o.put("updated_at_ms",c.getLong(3));a.put(o);}}return a;
    }

    public SyncResult importBundle(JSONObject root) throws JSONException {
        String format=root.optString("format");int version=root.optInt("version",0);
        if(version!=1||(!"ludex-mobile-sync".equals(format)&&!"ludex-backup".equals(format)))throw new JSONException("Formato/versão não suportado");
        JSONObject data="ludex-backup".equals(format)?root.optJSONObject("data"):root;if(data==null)data=root;
        SyncResult result=new SyncResult();SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            JSONArray gs=data.optJSONArray("games");if(gs!=null)for(int i=0;i<gs.length();i++)importGame(db,gs.getJSONObject(i),result);
            JSONArray ss=data.optJSONArray("sessions");if(ss!=null)for(int i=0;i<ss.length();i++)importSession(db,ss.getJSONObject(i));
            JSONArray ip=data.optJSONArray("imported_playtime");if(ip!=null)for(int i=0;i<ip.length();i++)importPlaytime(db,ip.getJSONObject(i));
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        return result;
    }

    private void importGame(SQLiteDatabase db,JSONObject g,SyncResult result){
        String id=g.optString("id"),title=g.optString("title");if(id.isEmpty()||title.isEmpty()){result.skipped++;return;}
        long remote=parseTime(g,"updated_at","updated_at_ms");
        long local=-1;try(Cursor c=db.rawQuery("SELECT updated_at FROM games WHERE id=?",new String[]{id})){if(c.moveToFirst())local=c.getLong(0);}
        if(local>=0&&remote>0&&remote<=local){result.skipped++;return;}
        ContentValues v=new ContentValues();v.put("id",id);v.put("title",title);v.put("platform",g.optString("platform","PC"));v.put("source",g.optString("source","sync"));
        if(g.has("package_name")&&!g.isNull("package_name"))v.put("package_name",g.optString("package_name"));
        v.put("installed",g.optInt("installed",0));v.put("favorite",g.optInt("favorite",0));v.put("status",g.optString("status","Quero jogar"));v.put("updated_at",remote>0?remote:System.currentTimeMillis());
        if(local<0){db.insertWithOnConflict("games",null,v,SQLiteDatabase.CONFLICT_IGNORE);result.inserted++;}
        else{v.remove("id");db.update("games",v,"id=?",new String[]{id});result.updated++;}
    }
    private void importSession(SQLiteDatabase db,JSONObject s){
        String id=s.optString("id"),game=s.optString("game_id");if(id.isEmpty()||game.isEmpty())return;
        long start=parseTime(s,"started_at","started_at_ms"),end=parseTime(s,"ended_at","ended_at_ms");if(start<=0||end<start)return;
        ContentValues v=new ContentValues();v.put("id",id);v.put("game_id",game);if(s.has("package_name"))v.put("package_name",s.optString("package_name",null));v.put("started_at",start);v.put("ended_at",end);v.put("duration_seconds",s.optLong("duration_seconds",Math.max(0,(end-start)/1000)));v.put("device",s.optString("device","sync"));v.put("provider",s.optString("provider","sync"));db.insertWithOnConflict("play_sessions",null,v,SQLiteDatabase.CONFLICT_IGNORE);
    }
    private void importPlaytime(SQLiteDatabase db,JSONObject x){
        String game=x.optString("game_id"),provider=x.optString("provider");if(game.isEmpty()||provider.isEmpty())return;long seconds=Math.max(0,x.optLong("seconds"));long updated=parseTime(x,"updated_at","updated_at_ms");
        ContentValues v=new ContentValues();v.put("game_id",game);v.put("provider",provider);v.put("seconds",seconds);v.put("updated_at",updated>0?updated:System.currentTimeMillis());db.insertWithOnConflict("imported_playtime",null,v,SQLiteDatabase.CONFLICT_IGNORE);db.execSQL("UPDATE imported_playtime SET seconds=MAX(seconds,?),updated_at=MAX(updated_at,?) WHERE game_id=? AND provider=?",new Object[]{seconds,updated,game,provider});
    }
    private static long parseTime(JSONObject o,String isoKey,String msKey){
        if(o.has(msKey))return o.optLong(msKey,0);
        String iso=o.optString(isoKey,"");if(iso.isEmpty())return 0;try{return Instant.parse(iso).toEpochMilli();}catch(Exception e){return 0;}
    }
}
