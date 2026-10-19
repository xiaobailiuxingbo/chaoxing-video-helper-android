package local.mr.videohelper;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** API secret encrypted with a non-exportable Android Keystore key. */
public final class KeyVault {
    private static final String ALIAS="video_helper_deepseek";
    private static SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias(ALIAS))return (SecretKey)store.getKey(ALIAS,null);
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    public static boolean exists(Context c){return AppState.prefs(c).contains("api_secret");}
    public static void save(Context c,String raw) throws Exception {
        String value=raw.trim();
        if(value.length()<12 || value.length()>512 || !value.matches("[A-Za-z0-9_\\-]+"))throw new IllegalArgumentException("Key 格式不正确");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        String encrypted=Base64.encodeToString(cipher.doFinal(value.getBytes("UTF-8")),Base64.NO_WRAP);
        AppState.prefs(c).edit().putString("api_secret",encrypted).putString("api_iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)).commit();
    }
    public static String read(Context c) throws Exception {
        if(!exists(c))return "";
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(AppState.prefs(c).getString("api_iv",""),Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(AppState.prefs(c).getString("api_secret",""),Base64.NO_WRAP)),"UTF-8");
    }
    public static void clear(Context c){AppState.prefs(c).edit().remove("api_secret").remove("api_iv").putBoolean("quiz_enabled",false).commit();}
}
