package com.ludex.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SecretStore {
    private static final String ALIAS="ludex.android.secrets";
    private static final String PREFS="ludex-secure";
    private SecretStore(){}

    public static void put(Context context,String key,String value) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,getOrCreateKey());
        byte[] encrypted=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        SharedPreferences.Editor e=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit();
        e.putString(key+".iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP));
        e.putString(key+".data",Base64.encodeToString(encrypted,Base64.NO_WRAP));
        e.apply();
    }

    public static String get(Context context,String key) {
        try{
            SharedPreferences p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
            String iv=p.getString(key+".iv",null),data=p.getString(key+".data",null);
            if(iv==null||data==null)return "";
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,getOrCreateKey(),new GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(data,Base64.NO_WRAP)),StandardCharsets.UTF_8);
        }catch(Exception e){return "";}
    }

    public static void remove(Context context,String key){
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().remove(key+".iv").remove(key+".data").apply();
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        java.security.Key key=ks.getKey(ALIAS,null);
        if(key instanceof SecretKey)return (SecretKey)key;
        KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
        return gen.generateKey();
    }
}
