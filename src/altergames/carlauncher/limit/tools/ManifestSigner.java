package altergames.carlauncher.limit.tools;

import altergames.carlauncher.limit.core.PackSigning;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;

/**
 * Narzędzie do podpisywania spisu paczek (używane w automacie GitHub Actions i na komputerze, nie w aplikacji).
 * Polecenia:
 *   genkey klucz-prywatny.hex klucz-publiczny.hex   tworzy parę kluczy ECDSA P-256 (prywatny PKCS#8, publiczny X.509, oba szesnastkowo)
 *   sign klucz-prywatny.hex manifest.json manifest.sig   podpisuje spis (podpis DER szesnastkowo)
 *   verify klucz-publiczny.hex manifest.json manifest.sig   sprawdza podpis (kod wyjścia 0 = dobry, 1 = zły)
 */
public final class ManifestSigner {
  private static final Charset UTF8 = Charset.forName("UTF-8");

  private ManifestSigner() { }

  public static KeyPair generate() throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
    g.initialize(new ECGenParameterSpec("secp256r1"));
    return g.generateKeyPair();
  }

  public static String sign(PrivateKey key, byte[] data) throws Exception {
    Signature s = Signature.getInstance(PackSigning.ALGORITHM);
    s.initSign(key);
    s.update(data);
    return PackSigning.toHex(s.sign());
  }

  public static PrivateKey privateKey(String hex) throws Exception {
    return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(PackSigning.fromHex(hex.trim())));
  }

  private static String read(String path) throws IOException { return new String(Files.readAllBytes(new File(path).toPath()), UTF8); }

  private static void write(String path, String text) throws IOException {
    FileOutputStream o = new FileOutputStream(path);
    try { o.write(text.getBytes(UTF8)); } finally { o.close(); }
  }

  public static void main(String[] a) throws Exception {
    if (a.length == 3 && a[0].equals("genkey")) {
      KeyPair kp = generate();
      write(a[1], PackSigning.toHex(kp.getPrivate().getEncoded()) + "\n");
      write(a[2], PackSigning.toHex(kp.getPublic().getEncoded()) + "\n");
      System.out.println("klucze zapisane: " + a[1] + " (PRYWATNY, nie publikować) i " + a[2]);
    } else if (a.length == 4 && a[0].equals("sign")) {
      byte[] data = Files.readAllBytes(new File(a[2]).toPath());
      write(a[3], sign(privateKey(read(a[1])), data) + "\n");
      System.out.println("podpisano " + a[2] + " -> " + a[3]);
    } else if (a.length == 4 && a[0].equals("verify")) {
      byte[] data = Files.readAllBytes(new File(a[2]).toPath());
      byte[] sig = PackSigning.signatureFromFileText(Files.readAllBytes(new File(a[3]).toPath()));
      boolean ok = PackSigning.verifierFor(read(a[1])).ok(data, sig);
      System.out.println(ok ? "podpis poprawny" : "PODPIS ZŁY");
      System.exit(ok ? 0 : 1);
    } else {
      System.err.println("użycie: ManifestSigner genkey prywatny.hex publiczny.hex | sign prywatny.hex manifest.json manifest.sig | verify publiczny.hex manifest.json manifest.sig");
      System.exit(2);
    }
  }
}
