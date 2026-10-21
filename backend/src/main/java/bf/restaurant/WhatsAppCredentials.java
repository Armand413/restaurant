package bf.restaurant;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Encryption key belongs to the server secret manager, never to PostgreSQL or Flutter. */
@Component
class WhatsAppCredentials {
    private final JdbcTemplate db;
    private final byte[] key;
    WhatsAppCredentials(JdbcTemplate db,@Value("${meta.encryption-key:}") String encoded) {
        this.db=db;
        byte[] decoded;
        try { decoded=Base64.getDecoder().decode(encoded); } catch(IllegalArgumentException e) { decoded=new byte[0]; }
        key=decoded;
    }
    boolean ready() { return key.length==32; }
    Map<String,Object> active() {
        var rows=db.queryForList("select * from whatsapp_credentials where id=1");
        return rows.isEmpty()?Map.of():rows.getFirst();
    }
    String encrypt(String value) {
        if(!ready())throw new IllegalStateException("Clé de chiffrement serveur indisponible");
        try {
            byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
            cipher.updateAAD("restaurant-whatsapp-v1".getBytes(StandardCharsets.UTF_8));
            byte[] payload=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] combined=new byte[iv.length+payload.length];System.arraycopy(iv,0,combined,0,iv.length);System.arraycopy(payload,0,combined,iv.length,payload.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch(Exception e) { throw new IllegalStateException("Chiffrement impossible"); }
    }
    String decrypt(String encoded) {
        if(!ready())throw new IllegalStateException("Clé de chiffrement serveur indisponible");
        try {
            byte[] data=Base64.getDecoder().decode(encoded);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Arrays.copyOfRange(data,0,12)));
            cipher.updateAAD("restaurant-whatsapp-v1".getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(data,12,data.length-12),StandardCharsets.UTF_8);
        } catch(Exception e) { throw new IllegalStateException("Déchiffrement impossible"); }
    }
}
