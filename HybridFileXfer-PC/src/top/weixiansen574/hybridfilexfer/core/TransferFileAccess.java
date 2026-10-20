package top.weixiansen574.hybridfilexfer.core;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.List;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

/** Filesystem operations are performed by the privileged IO process on Android. */
public interface TransferFileAccess {
    final class Handle implements Closeable {
        public final FileChannel channel;
        private final Closeable owner;
        public Handle(FileChannel channel, Closeable owner) { this.channel = channel; this.owner = owner; }
        @Override public void close() throws IOException { owner.close(); }
    }
    final class Prepared {
        public String status = "ready", token = "", target = "", prefixHash = "", message = "";
        public long offset;
        public String[] encode() {
            return new String[]{status, token, target, Long.toString(offset), prefixHash, message};
        }
        public static Prepared decode(String[] a) throws IOException {
            if (a == null || a.length != 6) throw new IOException("Invalid IO service response");
            Prepared p = new Prepared();
            p.status = a[0]; p.token = a[1]; p.target = a[2];
            try { p.offset = Long.parseLong(a[3]); } catch (NumberFormatException e) { throw new IOException(e); }
            p.prefixHash = a[4]; p.message = a[5];
            return p;
        }
    }
    RemoteFile stat(String path) throws IOException;
    String canonicalPath(String path) throws IOException;
    List<RemoteFile> list(String path) throws IOException;
    Handle openRead(String path) throws IOException;
    void createDirectory(String root, String relative, long modified) throws IOException;
    Prepared prepare(String root, String relative, String identity, long size, long modified,
                     String hash, TransferOptions options) throws IOException;
    Handle openStage(String token) throws IOException;
    void checkpoint(String token, long offset) throws IOException;
    void commit(String token, boolean verifySha256) throws IOException;
    void abandon(String token, boolean keep) throws IOException;
}
