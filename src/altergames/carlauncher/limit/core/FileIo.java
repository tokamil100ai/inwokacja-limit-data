package altergames.carlauncher.limit.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Odczyt całego (małego) pliku bez java.nio.file, które w Androidzie jest dopiero od API 26. */
final class FileIo {
  static final long MAX = 16L * 1024 * 1024;

  private FileIo() { }

  static byte[] readAll(File f) throws IOException {
    if (f.length() > MAX) throw new IOException("plik za duży: " + f.length());
    InputStream in = new FileInputStream(f);
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(16, f.length()));
      byte[] buf = new byte[16 * 1024];
      int n;
      while ((n = in.read(buf)) > 0) {
        out.write(buf, 0, n);
        if (out.size() > MAX) throw new IOException("plik za duży");
      }
      return out.toByteArray();
    } finally {
      try { in.close(); } catch (IOException ignored) { /* nic */ }
    }
  }
}
