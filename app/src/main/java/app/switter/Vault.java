package app.switter;

import android.content.*;
import android.security.keystore.*;
import android.util.Base64;
import org.json.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.security.*;
import java.util.*;

public final class Vault {
    private final android.content.SharedPreferences prefs;
    public final List<Blog> blogs=new ArrayList<>();
    public String ownId="",theme="system";
    private String salt="",hash="";
    private int failures=0;
    private long retryAt=0;
    public Vault(Context c) throws Exception {
        prefs=c.getSharedPreferences("vault",Context.MODE_PRIVATE);
        String raw=prefs.getString("data","");
        if(!raw.isEmpty()){
            String[] parts=raw.split(":"); Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));
            JSONObject o=new JSONObject(new String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),java.nio.charset.StandardCharsets.UTF_8));
            JSONArray a=o.optJSONArray("blogs");if(a!=null)for(int i=0;i<a.length();i++)blogs.add(new Blog(a.getJSONObject(i)));
            ownId=o.optString("ownId");theme=o.optString("theme","system");salt=o.optString("salt");hash=o.optString("hash");
            failures=o.optInt("failures");retryAt=o.optLong("retryAt");
        }
    }
    private SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias("switter-v1"))return (SecretKey)store.getKey("switter-v1",null);
        KeyGenerator gen=KeyGenerator.getInstance("AES","AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder("switter-v1",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return gen.generateKey();
    }
    public synchronized void save() throws Exception {
        JSONArray a=new JSONArray();for(Blog b:blogs)a.put(b.json());
        JSONObject o=new JSONObject().put("blogs",a).put("ownId",ownId).put("theme",theme).put("salt",salt).put("hash",hash)
            .put("failures",failures).put("retryAt",retryAt);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        String value=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal(o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),Base64.NO_WRAP);
        if(!prefs.edit().putString("data",value).commit())throw new java.io.IOException("Не удалось сохранить настройки");
    }
    public Blog own(){for(Blog b:blogs)if(b.id.equals(ownId))return b;return null;}
    public boolean protectedByPin(){return !hash.isEmpty();}
    private static byte[] derive(String pin,byte[] salt) throws Exception {
        PBEKeySpec spec=new PBEKeySpec(pin.toCharArray(),salt,160000,256);
        try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}finally{spec.clearPassword();}
    }
    public void setPin(String pin) throws Exception {
        if(!pin.matches("[0-9]{4,12}"))throw new IllegalArgumentException("PIN должен содержать 4–12 цифр");
        byte[] s=new byte[32];new SecureRandom().nextBytes(s);salt=Base64.encodeToString(s,Base64.NO_WRAP);
        hash=Base64.encodeToString(derive(pin,s),Base64.NO_WRAP);failures=0;retryAt=0;save();
    }
    public long remainingSeconds(){return Math.max(0,(retryAt-System.currentTimeMillis()+999)/1000);}
    public boolean unlock(String pin) throws Exception {
        if(remainingSeconds()>0)return false;
        boolean ok=MessageDigest.isEqual(Base64.decode(hash,Base64.NO_WRAP),derive(pin,Base64.decode(salt,Base64.NO_WRAP)));
        if(ok){failures=0;retryAt=0;} else {failures++;if(failures>=5)retryAt=System.currentTimeMillis()+Math.min(900000,30000L*(1L<<Math.min(5,failures-5)));}
        save();return ok;
    }
    public void removePin() throws Exception {salt="";hash="";failures=0;retryAt=0;save();}
}
