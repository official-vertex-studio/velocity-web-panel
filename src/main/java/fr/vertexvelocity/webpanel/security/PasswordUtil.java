package fr.vertexvelocity.webpanel.security;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.util.Base64;
public final class PasswordUtil {
 private static final SecureRandom RANDOM=new SecureRandom(); private PasswordUtil(){}
 public static String newSalt(){byte[] b=new byte[16];RANDOM.nextBytes(b);return Base64.getEncoder().encodeToString(b);}
 public static String hash(String password,String salt){try{PBEKeySpec spec=new PBEKeySpec(password.toCharArray(),Base64.getDecoder().decode(salt),210_000,256);byte[] out=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();spec.clearPassword();return Base64.getEncoder().encodeToString(out);}catch(Exception e){throw new IllegalStateException("Password hashing unavailable",e);}}
 public static boolean verify(String password,String salt,String expected){try{return java.security.MessageDigest.isEqual(hash(password,salt).getBytes(java.nio.charset.StandardCharsets.UTF_8),expected.getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception e){return false;}}
}
