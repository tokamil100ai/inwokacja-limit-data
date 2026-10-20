package altergames.carlauncher.limit.core;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

/**
 * Podpis cyfrowy spisu paczek (manifest.json). Podpisany spis wskazuje, jakie paczki są prawdziwe (rozmiar, CRC32 i SHA-256), więc
 * ktoś, kto zmieni pliki na hostingu albo przejmie jego konto, nie podsunie aplikacji innych danych: nie ma klucza prywatnego,
 * który leży poza hostingiem. Algorytm: ECDSA P-256 z SHA-256 (jest w każdym Androidzie od API 21 i w Javie 8). Klucze i podpisy
 * są zapisywane szesnastkowo, bo java.util.Base64 w Androidzie jest dopiero od API 26. Czysta Java, bez Androida.
 */
public final class PackSigning {
  public static final String ALGORITHM = "SHA256withECDSA";

  /** Sprawdza podpis spisu. Nie zgłasza wyjątków: każda niezgodność to false. */
  public interface Verifier {
    boolean ok(byte[] manifest, byte[] signature);
  }

  private PackSigning() { }

  /** Weryfikator dla klucza publicznego (X.509 DER zapisany szesnastkowo). Zły albo pusty klucz odrzuca wszystko (nic nie jest aktualizowane). */
  public static Verifier verifierFor(final String publicKeyHex) {
    PublicKey k = null;
    try {
      k = publicKey(publicKeyHex);
    } catch (GeneralSecurityException e) {
      k = null;
    } catch (RuntimeException e) {
      k = null;
    }
    final PublicKey key = k;
    return new Verifier() {
      @Override public boolean ok(byte[] manifest, byte[] signature) { return key != null && verify(manifest, signature, key); }
    };
  }

  public static PublicKey publicKey(String hex) throws GeneralSecurityException {
    byte[] der = fromHex(hex.trim());
    return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
  }

  /** Podpis w formie bajtów DER; text to zapis szesnastkowy z pliku manifest.sig (białe znaki na końcu są ignorowane). */
  public static boolean verify(byte[] data, byte[] sigDer, PublicKey key) {
    try {
      if (data == null || sigDer == null || sigDer.length == 0 || sigDer.length > 200) return false;
      Signature s = Signature.getInstance(ALGORITHM);
      s.initVerify(key);
      s.update(data);
      return s.verify(sigDer);
    } catch (GeneralSecurityException e) {
      return false;
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** Zawartość pliku manifest.sig (hex) na bajty podpisu; null przy złym formacie. */
  public static byte[] signatureFromFileText(byte[] fileContent) {
    try {
      return fromHex(new String(fileContent, "UTF-8").trim());
    } catch (java.io.UnsupportedEncodingException e) {
      return null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** SHA-256 skrócone do pierwszych 128 bitów (32 znaki szesnastkowe): tyle zapisuje spis przy każdej paczce. */
  public static String shortSha256(byte[] digest) { return toHex(digest).substring(0, 32); }

  public static MessageDigest newSha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String toHex(byte[] b) {
    char[] h = "0123456789abcdef".toCharArray();
    char[] out = new char[b.length * 2];
    for (int i = 0; i < b.length; i++) { out[2 * i] = h[(b[i] >> 4) & 15]; out[2 * i + 1] = h[b[i] & 15]; }
    return new String(out);
  }

  public static byte[] fromHex(String s) {
    if (s == null || s.length() % 2 != 0 || s.length() > 4096) throw new IllegalArgumentException("zły zapis szesnastkowy");
    byte[] out = new byte[s.length() / 2];
    for (int i = 0; i < out.length; i++) {
      int hi = Character.digit(s.charAt(2 * i), 16), lo = Character.digit(s.charAt(2 * i + 1), 16);
      if (hi < 0 || lo < 0) throw new IllegalArgumentException("zły znak w zapisie szesnastkowym");
      out[i] = (byte) (hi * 16 + lo);
    }
    return out;
  }
}
