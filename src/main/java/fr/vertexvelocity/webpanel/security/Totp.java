package fr.vertexvelocity.webpanel.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Locale;

public final class Totp {
 private static final String ALPHABET="ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
 private Totp(){}
 public static String newBase32Secret(){byte[] b=new byte[20];new SecureRandom().nextBytes(b);StringBuilder out=new StringBuilder();int buffer=0,bits=0;for(byte value:b){buffer=(buffer<<8)|(value&255);bits+=8;while(bits>=5){out.append(ALPHABET.charAt((buffer>>(bits-5))&31));bits-=5;}}if(bits>0)out.append(ALPHABET.charAt((buffer<<(5-bits))&31));return out.toString();}
 public static boolean verify(String secret,String code){if(code==null||!code.matches("[0-9]{6}"))return false;long step=System.currentTimeMillis()/1000/30;for(long drift=-1;drift<=1;drift++)try{if(generate(secret,step+drift).equals(code))return true;}catch(Exception ignored){}return false;}
 private static String generate(String secret,long step)throws Exception{byte[] key=decodeBase32(secret);Mac mac=Mac.getInstance("HmacSHA1");mac.init(new SecretKeySpec(key,"HmacSHA1"));byte[] hash=mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());int offset=hash[hash.length-1]&15;int binary=((hash[offset]&127)<<24)|((hash[offset+1]&255)<<16)|((hash[offset+2]&255)<<8)|(hash[offset+3]&255);return String.format(Locale.ROOT,"%06d",binary%1_000_000);}
 private static byte[] decodeBase32(String value){String s=value.toUpperCase(Locale.ROOT).replace("=","").replace(" ","");byte[] out=new byte[s.length()*5/8];int buffer=0,bits=0,index=0;for(char c:s.toCharArray()){int n=ALPHABET.indexOf(c);if(n<0)throw new IllegalArgumentException("Invalid base32 secret");buffer=(buffer<<5)|n;bits+=5;if(bits>=8){out[index++]=(byte)((buffer>>(bits-8))&255);bits-=8;}}return java.util.Arrays.copyOf(out,index);}
 public static String uri(String username,String secret){return "otpauth://totp/Vertex%20Velocity:"+java.net.URLEncoder.encode(username,java.nio.charset.StandardCharsets.UTF_8)+"?secret="+secret+"&issuer=Vertex%20Velocity&algorithm=SHA1&digits=6&period=30";}
}
