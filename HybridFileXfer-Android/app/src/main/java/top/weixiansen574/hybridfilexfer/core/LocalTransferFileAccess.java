package top.weixiansen574.hybridfilexfer.core;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.*;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

/** Owns only .hfx-* staging files; user sources and pre-existing destination files survive failure. */
public abstract class LocalTransferFileAccess implements TransferFileAccess {
    private static final String MAGIC = "HybridFileXfer-stage-4";
    private final Map<String,Stage> stages = new HashMap<>();
    private volatile boolean cancellationRequested;
    @Override public void beginSession() { cancellationRequested=false; }
    // Binder cancellation must not wait for the monitor held by prepare/commit while hashing.
    @Override public void cancelPendingOperations() { cancellationRequested=true; }
    private static final class Stage {
        File root, target, part, meta, lockPath;
        String identity, hash;
        long size, modified, offset;
        boolean overwrite;
        RandomAccessFile lockFile;
        FileLock lock;
    }

    @Override public RemoteFile stat(String path) throws IOException {
        File file = checkedSource(path);
        if (!file.exists()) throw new FileNotFoundException(path);
        if (!file.isFile() && !file.isDirectory()) throw new IOException("Not a regular file or directory: " + path);
        return new RemoteFile(file);
    }
    @Override public List<RemoteFile> list(String path) throws IOException {
        File dir = checkedSource(path);
        File[] files = dir.listFiles();
        if (files == null) throw new IOException("Cannot list directory: " + path);
        Arrays.sort(files, Comparator.comparing(File::getName));
        List<RemoteFile> result = new ArrayList<>();
        for (File file : files) result.add(new RemoteFile(file));
        return result;
    }
    private static File checkedSource(String path) throws IOException { return new File(path).getAbsoluteFile(); }
    protected File physicalPath(File file) throws IOException { return file.getCanonicalFile(); }
    @Override public String canonicalPath(String path) throws IOException { return physicalPath(new File(path)).getPath(); }
    @Override public Handle openRead(String path) throws IOException {
        File file = checkedSource(path);
        if (!file.isFile()) throw new IOException("Not a readable regular file: " + path);
        FileInputStream stream = new FileInputStream(file);
        return new Handle(stream.getChannel(), stream);
    }
    protected File resolve(File root, String relative) throws IOException {
        TransferPaths.validateRelative(relative);
        File target = new File(root, relative.replace('/', File.separatorChar)).getAbsoluteFile();
        File canonical = physicalPath(target);
        String boundary = physicalPath(root).getPath();
        if (!boundary.endsWith(File.separator)) boundary += File.separator;
        if (!canonical.getPath().startsWith(boundary) || !canonical.equals(target))
            throw new IOException("Destination escapes the selected directory or follows a symbolic link");
        return target;
    }
    private File root(String path) throws IOException {
        File root = physicalPath(new File(path));
        if (!root.isDirectory()) throw new IOException("Destination directory does not exist: " + root);
        return root;
    }
    private static void mkdirs(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create directory: " + dir);
    }
    @Override public void createDirectory(String root, String relative, long modified) throws IOException {
        File target = resolve(root(root), relative);
        if (target.exists() && !target.isDirectory()) throw new IOException("A file occupies the directory name: " + target);
        mkdirs(target);
        if (modified > 0) target.setLastModified(modified);
    }
    @Override public synchronized Prepared prepare(String rootPath, String relative, String identity, long size,
                                                   long modified, String hash, TransferOptions options) throws IOException {
        options.validate();
        if (cancellationRequested) throw new IOException("Cancelled");
        if (size < 0 || identity == null || !identity.matches("[0-9a-f]{64}")
                || (!hash.isEmpty() && !hash.matches("[0-9a-f]{64}")))
            throw new IOException("Invalid file metadata");
        File root = root(rootPath), target = resolve(root, relative);
        mkdirs(target.getParentFile());
        // Recheck after directory creation; never follow an existing target symlink.
        target = resolve(root, relative);
        Prepared prepared = new Prepared();
        prepared.target = target.getPath();
        if (target.exists()) {
            if (!target.isFile()) throw new IOException("A directory occupies the file name: " + target);
            if (options.incremental && !hash.isEmpty() && target.length() == size
                    && hash.equals(hashLocal(target, size))) {
                prepared.status = "skipped";
                prepared.message = "Existing file has identical SHA-256 content";
                return prepared;
            }
            if (options.conflictPolicy == TransferOptions.ConflictPolicy.SKIP) {
                prepared.status = "skipped";
                prepared.message = "Existing file preserved by skip policy; content was not assumed identical";
                return prepared;
            }
            if (options.conflictPolicy == TransferOptions.ConflictPolicy.KEEP_BOTH) {
                File original = target;
                for (int n=1; target.exists(); n++) {
                    if (n>100000) throw new IOException("Too many conflicting destination names");
                    String name = original.getName();
                    int dot = name.lastIndexOf('.');
                    String suffix = " (" + n + ")";
                    String candidate = dot > 0 ? name.substring(0,dot)+suffix+name.substring(dot) : name+suffix;
                    target = new File(original.getParentFile(), candidate);
                }
                prepared.target = target.getPath();
            }
        }
        String id = TransferHashes.text(target.getCanonicalPath() + "\n" + identity);
        Stage s = new Stage();
        s.root=root; s.target=target; s.identity=identity; s.hash=hash; s.size=size; s.modified=modified;
        s.overwrite=options.conflictPolicy==TransferOptions.ConflictPolicy.OVERWRITE;
        s.part=new File(target.getParentFile(), ".hfx-"+id+".part");
        s.meta=new File(target.getParentFile(), ".hfx-"+id+".meta");
        s.lockPath=new File(target.getParentFile(), ".hfx-"+TransferHashes.text(target.getCanonicalPath())+".lock");
        assertOwnedPath(s.part); assertOwnedPath(s.meta); assertOwnedPath(s.lockPath);
        s.lockFile = new RandomAccessFile(s.lockPath,"rw");
        try {
            try { s.lock=s.lockFile.getChannel().tryLock(); }
            catch (java.nio.channels.OverlappingFileLockException e) { throw new IOException("Destination is already being transferred",e); }
            if (s.lock==null) throw new IOException("Destination is already being transferred");
            if (s.meta.exists()) {
                Properties p=load(s.meta);
                if (!MAGIC.equals(p.getProperty("magic")) || !identity.equals(p.getProperty("identity"))
                        || !Long.toString(size).equals(p.getProperty("size"))
                        || !Long.toString(modified).equals(p.getProperty("modified"))
                        || !hash.equals(p.getProperty("hash")))
                    throw new IOException("Staging metadata does not match; existing files were preserved");
                try { s.offset=Long.parseLong(p.getProperty("offset")); }
                catch (RuntimeException e) { throw new IOException("Invalid staging checkpoint",e); }
                if (s.offset<0 || s.offset>size || !s.part.isFile() || s.part.length()<s.offset)
                    throw new IOException("Damaged staging checkpoint; existing files were preserved");
            } else {
                if (s.part.exists()) throw new IOException("Unrecognized partial file; preserved for inspection");
                if (!s.part.createNewFile()) throw new IOException("Cannot create staging file");
            }
            if (!options.resume) s.offset=0;
            try (RandomAccessFile file=new RandomAccessFile(s.part,"rw")) { file.setLength(s.offset); file.getChannel().force(true); }
            save(s);
            prepared.offset=s.offset;
            prepared.prefixHash=hashLocal(s.part,s.offset);
            prepared.token=UUID.randomUUID().toString();
            stages.put(prepared.token,s);
            return prepared;
        } catch (IOException | RuntimeException e) {
            release(s);
            throw e;
        }
    }
    private void assertOwnedPath(File file) throws IOException {
        if (!physicalPath(file).equals(file.getAbsoluteFile())) throw new IOException("Staging path is a symbolic link");
    }
    public synchronized String stagingPath(String token) throws IOException { return stage(token).part.getPath(); }
    @Override public synchronized Handle openStage(String token) throws IOException {
        Stage s=stage(token); assertOwnedPath(s.part);
        RandomAccessFile file=new RandomAccessFile(s.part,"rw");
        return new Handle(file.getChannel(),file);
    }
    @Override public synchronized void checkpoint(String token,long offset) throws IOException {
        Stage s=stage(token);
        if (offset<s.offset || offset>s.size || offset>s.part.length()) throw new IOException("Invalid durable prefix");
        s.offset=offset;
        save(s);
    }
    @Override public synchronized void commit(String token,boolean verifySha256) throws IOException {
        Stage s=stage(token);
        if (s.offset!=s.size || s.part.length()!=s.size) throw new IOException("Incomplete file cannot be committed");
        assertOwnedPath(s.part); assertOwnedPath(s.target);
        if (verifySha256 && (s.hash.isEmpty() || !s.hash.equals(hashLocal(s.part,s.size))))
            throw new IOException("Final SHA-256 verification failed; partial file retained");
        if (cancellationRequested || Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
        if (!s.overwrite && s.target.exists()) throw new IOException("Destination appeared during transfer; preserved");
        if (s.modified>0 && !s.part.setLastModified(s.modified)) throw new IOException("Cannot preserve modification time");
        atomicMove(s.part,s.target,s.overwrite);
        stages.remove(token);
        try { if (!s.meta.delete() && s.meta.exists()) throw new IOException("Committed, but cannot remove staging metadata"); }
        finally { release(s); }
    }
    @Override public synchronized void abandon(String token,boolean keep) throws IOException {
        Stage s=stages.remove(token);
        if (s==null) return;
        try {
            if (!keep) {
                if (!s.part.delete() && s.part.exists()) throw new IOException("Cannot remove partial file");
                if (!s.meta.delete() && s.meta.exists()) throw new IOException("Cannot remove partial metadata");
            }
        } finally { release(s); }
    }
    private Stage stage(String token) throws IOException {
        Stage s=stages.get(token);
        if (s==null) throw new IOException("Unknown staging token");
        return s;
    }
    private String hashLocal(File file,long length) throws IOException {
        try (FileInputStream in=new FileInputStream(file)) {
            return TransferHashes.hash(in.getChannel(),length,() -> cancellationRequested);
        }
    }
    private static Properties load(File file) throws IOException {
        if (file.length()>16384) throw new IOException("Oversized staging metadata");
        Properties p=new Properties();
        try (InputStream in=new FileInputStream(file)) { p.load(in); }
        return p;
    }
    private void save(Stage s) throws IOException {
        Properties p=new Properties();
        p.setProperty("magic",MAGIC); p.setProperty("identity",s.identity);
        p.setProperty("size",Long.toString(s.size)); p.setProperty("modified",Long.toString(s.modified));
        p.setProperty("hash",s.hash); p.setProperty("offset",Long.toString(s.offset));
        File temporary=new File(s.meta.getParentFile(),s.meta.getName()+"."+UUID.randomUUID()+".new");
        try {
            if (!temporary.createNewFile()) throw new IOException("Cannot create checkpoint");
            try (FileOutputStream out=new FileOutputStream(temporary)) { p.store(out,"HFX durable prefix"); out.getChannel().force(true); }
            atomicMove(temporary,s.meta,true);
        } finally { if (temporary.exists()) temporary.delete(); }
    }
    protected abstract void atomicMove(File source,File target,boolean replace) throws IOException;
    private static void release(Stage s) throws IOException {
        try { if (s.lock!=null && s.lock.isValid()) s.lock.release(); }
        finally { if (s.lockFile!=null) s.lockFile.close(); }
        // Keep the tiny lock inode: deleting it allows two processes to lock different inodes.
    }
}
