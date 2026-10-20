package top.weixiansen574.hybridfilexfer.core;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.nio.DataByteChannel;

/**
 * Version 400: per-file control transaction, bounded parallel block window, durable-prefix ACKs.
 * Failed data channels are retired; their unacknowledged blocks go back to the shared scheduler.
 * A transfer may be resumed across processes only after comparing the persisted prefix to source.
 */
public final class ReliableTransfer {
    private static final int END=0, FILE=1, DIRECTORY=2, SOURCE_ERROR=3;
    private static final int BLOCK=10, FILE_END=11, ACK=12;
    private final TransferFileAccess fs;
    private final DataByteChannel control;
    private final List<TransferConnection> connections;
    private final LinkedBlockingDeque<ByteBuffer> buffers;
    private final TransferFileCallback callback;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile Thread owner;
    private volatile boolean finished;
    private final List<Thread> liveWorkers=new CopyOnWriteArrayList<>();
    private final TransferSummary summary = new TransferSummary();
    private TransferOptions options;
    private long totalBytes=-1,totalFiles=-1;
    private volatile String currentPath="";
    private volatile long currentSize,currentPrefix;
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t=new Thread(r,"HFX-timeouts"); t.setDaemon(true); return t;
    });

    public ReliableTransfer(TransferFileAccess fs,DataByteChannel control,List<TransferConnection> connections,
                            LinkedBlockingDeque<ByteBuffer> buffers,TransferFileCallback callback) {
        this.fs=fs; this.control=control; this.connections=connections; this.buffers=buffers; this.callback=callback;
    }
    public TransferSummary getSummary() { return summary; }
    public void cancel() {
        if (finished) return;
        cancelled.set(true);
        summary.cancelled=true;
        close(control);
        for (TransferConnection c:connections) close(c.channel);
        Thread t=owner;
        if (t!=null && t!=Thread.currentThread()) t.interrupt();
    }
    private void checkCancelled() throws IOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new IOException("Transfer cancelled");
    }
    private void notifySafely(Runnable action) { try { action.run(); } catch (RuntimeException ignored) { /* UI failure must not corrupt the wire. */ } }
    private void phase(String phase) { notifySafely(() -> callback.onPhase(phase)); }
    private void result(String path,String status,String message) { notifySafely(() -> callback.onFileResult(path,status,message)); }
    private void failed(String path,String message) {
        synchronized(summary) { summary.failedFiles++; summary.failures.add(path+": "+message); }
        result(path,"failed",message);
    }
    private synchronized void progress(long prefix) {
        currentPrefix=Math.max(currentPrefix,prefix);
        TransferProgress p=new TransferProgress();
        synchronized(summary) {
            p.transferredBytes=summary.transferredBytes;
            p.completedFiles=summary.completedFiles;
        }
        p.totalBytes=totalBytes; p.totalFiles=totalFiles; p.currentPath=currentPath;
        p.currentFileBytes=currentPrefix; p.currentFileSize=currentSize;
        notifySafely(() -> callback.onProgress(p));
    }
    private void finish(boolean upload,long started) {
        finished=true;
        timer.shutdownNow();
        long waitUntil=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        boolean interrupted=Thread.interrupted();
        for (Thread worker:liveWorkers) {
            if (!worker.isAlive()) continue;
            worker.interrupt();
            long left=TimeUnit.NANOSECONDS.toMillis(waitUntil-System.nanoTime());
            if (left<=0) break;
            try { worker.join(left); } catch (InterruptedException e) { interrupted=true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
        summary.cancelled=cancelled.get();
        notifySafely(() -> callback.onSummary(summary));
        if (summary.isSuccessful()) notifySafely(() -> callback.onComplete(upload,summary.transferredBytes,System.currentTimeMillis()-started));
        else notifySafely(() -> callback.onIncomplete());
    }

    public boolean send(List<RemoteFile> selected,Directory sourceRoot,Directory destinationRoot,
                        TransferOptions proposed) throws IOException {
        owner=Thread.currentThread();
        long started=System.currentTimeMillis();
        try {
            proposed.write(control);
            options=TransferOptions.read(control);
            control.writeUTF(destinationRoot.path);
            SourceWalker walker=new SourceWalker(selected,sourceRoot);
            List<Entry> scanned=null;
            if (options.prescan) {
                phase("scanning");
                scanned=new ArrayList<>();
                long bytes=0,count=0;
                Entry e;
                while ((e=walker.next())!=null) {
                    checkCancelled();
                    scanned.add(e);
                    if (scanned.size()>1000000) throw new IOException("Selection exceeds one million entries");
                    if (e.error==null && !e.file.isDirectory()) {
                        if (Long.MAX_VALUE-bytes<e.file.getSize()) throw new IOException("Selection size overflow");
                        bytes+=e.file.getSize(); count++;
                    }
                }
                totalBytes=bytes; totalFiles=count;
            }
            control.writeLong(totalBytes); control.writeLong(totalFiles);
            Iterator<Entry> entries=scanned==null?null:scanned.iterator();
            int id=0;
            while (true) {
                checkCancelled();
                Entry e=entries==null?walker.next():(entries.hasNext()?entries.next():null);
                if (e==null) break;
                if (++id<0) throw new IOException("Too many entries");
                if (e.error!=null) {
                    control.writeInt(SOURCE_ERROR); control.writeUTF(e.path); control.writeUTF(shortMessage(e.error));
                    failed(e.path,e.error); continue;
                }
                if (e.file.isDirectory()) {
                    control.writeInt(DIRECTORY); control.writeUTF(e.relative); control.writeLong(e.file.lastModified());
                    if (!control.readBoolean()) failed(e.path,control.readUTF());
                    continue;
                }
                sendFile(id,e);
            }
            control.writeInt(END);
            if (!control.readBoolean()) throw new IOException("Remote transfer did not finish");
            return true;
        } catch (IOException | RuntimeException e) {
            if (!cancelled.get()) failed(currentPath.isEmpty()?"Transfer":currentPath,shortMessage(e.toString()));
            close(control);
            for (TransferConnection c:connections) close(c.channel);
            return false;
        } finally { finish(true,started); owner=null; }
    }

    private void sendFile(int id,Entry entry) throws IOException {
        String path=entry.file.getPath();
        RemoteFile before;
        String hash="";
        try {
            before=fs.stat(path);
            if (before.isDirectory()) throw new IOException("Source changed into a directory");
            if (options.verifySha256 || options.resume || options.incremental) {
                phase("hashing-source");
                hash=TransferHashes.hash(fs,path,before.getSize());
            }
        } catch (IOException e) {
            control.writeInt(SOURCE_ERROR); control.writeUTF(path); control.writeUTF(shortMessage(e.toString()));
            failed(path,e.toString()); return;
        }
        String identity=TransferHashes.text(path+"\n"+before.getSize()+"\n"+before.lastModified()+"\n"+hash);
        currentPath=entry.relative; currentSize=before.getSize(); currentPrefix=0;
        control.writeInt(FILE); control.writeInt(id); control.writeUTF(entry.relative);
        control.writeUTF(identity); control.writeLong(currentSize); control.writeLong(before.lastModified()); control.writeUTF(hash);
        String status=control.readUTF(),message=control.readUTF();
        if ("skipped".equals(status)) { summary.skippedFiles++; result(entry.relative,status,message); return; }
        if (!"ready".equals(status)) { failed(entry.relative,message); return; }
        long offset=control.readLong();
        String prefixHash=control.readUTF();
        if (offset<0 || offset>currentSize) throw new IOException("Invalid resume offset");
        boolean prefixValid;
        try { prefixValid=TransferHashes.hash(fs,path,offset).equals(prefixHash); }
        catch (IOException e) { prefixValid=false; }
        control.writeBoolean(prefixValid);
        if (!prefixValid) {
            if (!control.readBoolean()) { failed(entry.relative,control.readUTF()); return; }
            offset=0;
        }
        if (!control.readBoolean()) { failed(entry.relative,control.readUTF()); return; }
        if (offset>0) { summary.resumedBytes+=offset; result(entry.relative,"resumed",Long.toString(offset)); }
        currentPrefix=offset;
        phase("transferring");
        String sourceFailure=null;
        boolean transferred=false;
        try (TransferFileAccess.Handle input=fs.openRead(path)) {
            transferred=sendBlocks(id,input.channel,offset,currentSize);
        } catch (IOException e) {
            sourceFailure=e.toString();
            // An open failure happens before workers exist; finish each frame stream cleanly
            // so one unreadable source does not consume all healthy channels on timeout.
            for (TransferConnection c:active()) {
                try (AutoCloseable ignored=deadline(c.channel)) {
                    c.channel.writeInt(FILE_END); c.channel.writeInt(id);
                    if (c.channel.readInt()!=FILE_END || c.channel.readInt()!=id) throw new IOException("Invalid file boundary");
                } catch (Exception failure) { channelFailure(c,failure); }
            }
        }
        int outcome=transferred?1:0;
        if (transferred) {
            try {
                RemoteFile after=fs.stat(path);
                if (after.getSize()!=before.getSize() || after.lastModified()!=before.lastModified())
                    throw new IOException("Source changed during transfer");
                if (!hash.isEmpty() && !hash.equals(TransferHashes.hash(fs,path,before.getSize())))
                    throw new IOException("Source content changed during transfer");
            } catch (IOException e) { outcome=2; sourceFailure=e.toString(); }
        }
        control.writeInt(outcome);
        control.writeUTF(sourceFailure==null?"":shortMessage(sourceFailure));
        boolean success=control.readBoolean();
        message=control.readUTF();
        if (success) {
            summary.completedFiles++;
            result(entry.relative,options.verifySha256?"verified":"completed",message);
        } else failed(entry.relative,message);
        progress(currentPrefix);
    }

    public boolean receive(TransferOptions configured,boolean explicit) throws IOException {
        owner=Thread.currentThread();
        long started=System.currentTimeMillis();
        try {
            TransferOptions proposed=TransferOptions.read(control);
            options=explicit?configured.copy():proposed;
            options.write(control);
            String root=control.readUTF();
            totalBytes=control.readLong(); totalFiles=control.readLong();
            if (totalBytes < -1 || totalFiles < -1) throw new IOException("Invalid transfer totals");
            Set<String> targets=new HashSet<>();
            while (true) {
                checkCancelled();
                int command=control.readInt();
                if (command==END) { control.writeBoolean(true); return true; }
                if (command==SOURCE_ERROR) { failed(control.readUTF(),control.readUTF()); continue; }
                if (command==DIRECTORY) {
                    String relative=control.readUTF(); long modified=control.readLong();
                    try { fs.createDirectory(root,relative,modified); control.writeBoolean(true); }
                    catch (IOException e) { control.writeBoolean(false); control.writeUTF(shortMessage(e.toString())); failed(relative,e.toString()); }
                } else if (command==FILE) {
                    int id=control.readInt();
                    String relative=control.readUTF(),identity=control.readUTF();
                    long size=control.readLong(),modified=control.readLong();
                    String hash=control.readUTF();
                    receiveFile(id,root,relative,identity,size,modified,hash,targets);
                } else throw new IOException("Unknown transfer command");
            }
        } catch (IOException | RuntimeException e) {
            if (!cancelled.get()) failed(currentPath.isEmpty()?"Transfer":currentPath,shortMessage(e.toString()));
            close(control);
            for (TransferConnection c:connections) close(c.channel);
            return false;
        } finally { finish(false,started); owner=null; }
    }

    private void receiveFile(int id,String root,String relative,String identity,long size,long modified,
                             String hash,Set<String> targets) throws IOException {
        TransferFileAccess.Prepared prepared;
        try {
            TransferPaths.validateRelative(relative);
            if (!targets.add(relative.toLowerCase(Locale.ROOT))) throw new IOException("Repeated destination path in one transfer");
            prepared=fs.prepare(root,relative,identity,size,modified,hash,options);
        } catch (IOException | RuntimeException e) {
            control.writeUTF("failed"); control.writeUTF(shortMessage(e.toString())); failed(relative,e.toString()); return;
        }
        if ("skipped".equals(prepared.status)) {
            control.writeUTF(prepared.status); control.writeUTF(prepared.message);
            summary.skippedFiles++; result(relative,"skipped",prepared.message); return;
        }
        if (!"ready".equals(prepared.status)) throw new IOException("Invalid prepared file status");
        String token=prepared.token;
        boolean committed=false;
        try {
            control.writeUTF(prepared.status); control.writeUTF(prepared.message);
            control.writeLong(prepared.offset); control.writeUTF(prepared.prefixHash);
            if (!control.readBoolean()) {
                fs.abandon(token,false); token="";
                TransferOptions fresh=options.copy(); fresh.resume=false;
                try { prepared=fs.prepare(root,relative,identity,size,modified,hash,fresh); token=prepared.token; control.writeBoolean(true); }
                catch (IOException e) { control.writeBoolean(false); control.writeUTF(shortMessage(e.toString())); failed(relative,e.toString()); return; }
            }
            TransferFileAccess.Handle output;
            try { output=fs.openStage(token); control.writeBoolean(true); }
            catch (IOException e) { control.writeBoolean(false); control.writeUTF(shortMessage(e.toString())); failed(relative,e.toString()); return; }
            currentPath=relative; currentSize=size; currentPrefix=prepared.offset;
            if (prepared.offset>0) { summary.resumedBytes+=prepared.offset; result(relative,"resumed",Long.toString(prepared.offset)); }
            phase("transferring");
            ReceiveState state=new ReceiveState(token,output.channel,prepared.offset,size);
            try (TransferFileAccess.Handle ignored=output) { receiveBlocks(id,state); }
            int outcome=control.readInt();
            String sourceMessage=control.readUTF();
            String failure=state.failure;
            if (outcome!=1) failure=sourceMessage.isEmpty()?"Transfer interrupted; verified prefix retained":sourceMessage;
            if (state.prefix!=size) failure="Missing file blocks; verified prefix retained";
            if (failure==null) {
                try { phase(options.verifySha256?"verifying":"committing"); fs.commit(token,options.verifySha256); committed=true; }
                catch (IOException e) { failure=e.toString(); }
            }
            if (outcome==2) { fs.abandon(token,false); token=""; }
            control.writeBoolean(failure==null);
            String completedMessage="File committed: "+prepared.target;
            control.writeUTF(failure==null?shortMessage(completedMessage):shortMessage(failure));
            if (failure==null) { summary.completedFiles++; result(relative,options.verifySha256?"verified":"completed",completedMessage); }
            else failed(relative,failure);
            progress(state.prefix);
        } finally {
            if (!committed && !token.isEmpty()) fs.abandon(token,options.resume);
        }
    }

    private List<TransferConnection> active() {
        List<TransferConnection> active=new ArrayList<>();
        for (TransferConnection connection:connections) if (connection.channel.isOpen()) active.add(connection);
        return active;
    }
    private ByteBuffer takeBuffer() {
        ByteBuffer b=buffers.poll();
        if (b==null || b.capacity()<FileBlock.BLOCK_SIZE) {
            if (b!=null) buffers.offer(b);
            return ByteBuffer.allocate(FileBlock.BLOCK_SIZE);
        }
        return b;
    }
    private static void close(DataByteChannel channel) { try { channel.close(); } catch (IOException ignored) {} }
    private AutoCloseable deadline(DataByteChannel channel) {
        return deadline(channel,1);
    }
    private AutoCloseable deadline(DataByteChannel channel,int multiplier) {
        ScheduledFuture<?> future=timer.schedule(() -> close(channel),(long)options.timeoutSeconds*multiplier,TimeUnit.SECONDS);
        return () -> future.cancel(false);
    }
    private void channelFailure(TransferConnection c,Exception e) {
        close(c.channel);
        notifySafely(() -> callback.onChannelError(c.iName,TransferFileCallback.ERROR_TYPE_EXCEPTION,e.toString()));
    }
    private static String shortMessage(String message) { return message.length()>4000?message.substring(0,4000):message; }
    private static void readFully(FileChannel channel,ByteBuffer data,long position) throws IOException {
        synchronized(channel) {
            channel.position(position);
            while (data.hasRemaining()) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
                if (channel.read(data)<0) throw new EOFException("Source shrank during transfer");
            }
        }
    }
    private static void writeFully(DataByteChannel channel,ByteBuffer data) throws IOException {
        while (data.hasRemaining()) channel.write(data);
    }
    private static void join(List<Thread> workers) throws IOException {
        for (Thread worker:workers) {
            try { worker.join(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Cancelled",e); }
        }
    }

    private boolean sendBlocks(int id,FileChannel input,long start,long size) throws IOException {
        List<TransferConnection> active=active();
        if (active.isEmpty()) return start==size;
        Scheduler scheduler=new Scheduler(start,size,active.size());
        List<Thread> threads=new ArrayList<>();
        for (TransferConnection c:active) {
            Thread thread=new Thread(() -> {
                ByteBuffer buffer=takeBuffer();
                Chunk chunk=null;
                try {
                    while ((chunk=scheduler.take())!=null) {
                        checkCancelled();
                        buffer.clear(); buffer.limit(chunk.length);
                        try { readFully(input,buffer,chunk.offset); }
                        catch (IOException e) { scheduler.abort(e.toString()); chunk=null; break; }
                        buffer.flip();
                        java.security.MessageDigest digest=TransferHashes.digest();
                        digest.update(buffer.duplicate());
                        byte[] hash=digest.digest();
                        try (AutoCloseable ignored=deadline(c.channel)) {
                            c.channel.writeInt(BLOCK); c.channel.writeInt(id);
                            c.channel.writeLong(chunk.offset); c.channel.writeInt(chunk.length); c.channel.write(hash);
                            writeFully(c.channel,buffer);
                            if (c.channel.readInt()!=ACK || c.channel.readInt()!=id || c.channel.readLong()!=chunk.offset)
                                throw new IOException("Invalid block acknowledgement");
                            boolean stored=c.channel.readBoolean();
                            long prefix=c.channel.readLong();
                            String error=c.channel.readUTF();
                            if (!stored) { scheduler.abort(error); chunk=null; break; }
                            if (prefix<start || prefix>size) throw new IOException("Invalid durable prefix");
                            c.addUploadedBytes(chunk.length);
                            synchronized(summary) { summary.transferredBytes+=chunk.length; }
                            scheduler.done(chunk,prefix);
                            progress(prefix);
                            chunk=null;
                        }
                    }
                    try (AutoCloseable ignored=deadline(c.channel)) {
                        c.channel.writeInt(FILE_END); c.channel.writeInt(id);
                        if (c.channel.readInt()!=FILE_END || c.channel.readInt()!=id) throw new IOException("Invalid file boundary");
                    }
                } catch (Exception e) {
                    scheduler.retire(chunk);
                    channelFailure(c,e);
                } finally { buffers.offer(buffer); liveWorkers.remove(Thread.currentThread()); }
            },"HFX-send-"+c.iName);
            thread.setDaemon(true); threads.add(thread); liveWorkers.add(thread); thread.start();
        }
        join(threads);
        return scheduler.success();
    }
    private void receiveBlocks(int id,ReceiveState state) throws IOException {
        List<Thread> threads=new ArrayList<>();
        for (TransferConnection c:active()) {
            Thread thread=new Thread(() -> {
                ByteBuffer buffer=takeBuffer();
                try {
                    while (true) {
                        checkCancelled();
                        int command;
                        // A healthy worker can be idle while the sender retires a stalled sibling.
                        // Give that channel time to receive the requeued block before declaring it dead.
                        try (AutoCloseable ignored=deadline(c.channel,3)) { command=c.channel.readInt(); }
                        try (AutoCloseable ignored=deadline(c.channel)) {
                            if (c.channel.readInt()!=id) throw new IOException("Wrong file identifier");
                            if (command==FILE_END) {
                                c.channel.writeInt(FILE_END); c.channel.writeInt(id); return;
                            }
                            if (command!=BLOCK) throw new IOException("Unknown data command");
                            long offset=c.channel.readLong(); int length=c.channel.readInt();
                            state.validate(offset,length);
                            byte[] expected=new byte[32]; c.channel.readFully(expected);
                            buffer.clear(); buffer.limit(length); c.channel.readFully(buffer); buffer.flip();
                            java.security.MessageDigest digest=TransferHashes.digest();
                            digest.update(buffer.duplicate());
                            if (!java.security.MessageDigest.isEqual(expected,digest.digest()))
                                throw new IOException("Block SHA-256 mismatch");
                            state.store(offset,buffer);
                            c.addDownloadedBytes(length);
                            c.channel.writeInt(ACK); c.channel.writeInt(id); c.channel.writeLong(offset);
                            c.channel.writeBoolean(state.failure==null); c.channel.writeLong(state.prefix);
                            c.channel.writeUTF(state.failure==null?"":shortMessage(state.failure));
                        }
                    }
                } catch (Exception e) { channelFailure(c,e); }
                finally { buffers.offer(buffer); liveWorkers.remove(Thread.currentThread()); }
            },"HFX-receive-"+c.iName);
            thread.setDaemon(true); threads.add(thread); liveWorkers.add(thread); thread.start();
        }
        join(threads);
    }

    private final class ReceiveState {
        final String token;
        final FileChannel output;
        final long start,size;
        final TreeMap<Long,Integer> pending=new TreeMap<>();
        volatile long prefix;
        volatile String failure;
        ReceiveState(String token,FileChannel output,long start,long size) {
            this.token=token; this.output=output; this.start=start; this.prefix=start; this.size=size;
        }
        synchronized void validate(long offset,int length) throws IOException {
            if (offset<start || length<=0 || length>FileBlock.BLOCK_SIZE || offset>size-length
                    || (offset-start)%FileBlock.BLOCK_SIZE!=0
                    || length!=Math.min(FileBlock.BLOCK_SIZE,size-offset)
                    || offset-prefix>(long)Math.max(1,connections.size())*FileBlock.BLOCK_SIZE*2)
                throw new IOException("Invalid or out-of-window block");
        }
        synchronized void store(long offset,ByteBuffer data) {
            if (failure!=null) return;
            try {
                if (offset<prefix || pending.containsKey(offset)) return; // ACK lost: replay is idempotent.
                int length=data.remaining();
                output.position(offset);
                while (data.hasRemaining()) output.write(data);
                output.force(false);
                pending.put(offset,length);
                long next=prefix;
                while (pending.containsKey(next)) next+=pending.remove(next);
                if (next>prefix) { fs.checkpoint(token,next); prefix=next; }
                synchronized(summary) { summary.transferredBytes+=length; }
                progress(prefix);
            } catch (IOException | RuntimeException e) { failure=e.toString(); }
        }
    }

    private static final class Chunk {
        final long offset;
        final int length;
        Chunk(long offset,int length) { this.offset=offset; this.length=length; }
    }
    private static final class Scheduler {
        final long size,window;
        long next,prefix,remaining;
        int alive;
        String failure;
        final ArrayDeque<Chunk> retries=new ArrayDeque<>();
        Scheduler(long start,long size,int channels) {
            this.next=start; this.prefix=start; this.size=size; this.remaining=size-start;
            this.alive=channels; this.window=(long)channels*FileBlock.BLOCK_SIZE*2;
        }
        synchronized Chunk take() throws InterruptedException {
            while (true) {
                if (failure!=null || remaining==0 || alive==0) return null;
                if (!retries.isEmpty()) return retries.removeFirst();
                if (next<size && next-prefix<window) {
                    Chunk c=new Chunk(next,(int)Math.min(FileBlock.BLOCK_SIZE,size-next));
                    next+=c.length; return c;
                }
                wait();
            }
        }
        synchronized void done(Chunk chunk,long acknowledgedPrefix) {
            remaining-=chunk.length;
            prefix=Math.max(prefix,acknowledgedPrefix); notifyAll();
        }
        synchronized void retire(Chunk chunk) {
            if (chunk!=null) retries.addFirst(chunk);
            alive--; notifyAll();
        }
        synchronized void abort(String reason) { failure=reason; notifyAll(); }
        synchronized boolean success() { return failure==null && remaining==0; }
    }

    private static final class Entry {
        RemoteFile file;
        String relative,path,error;
    }
    private final class SourceWalker {
        final ArrayDeque<Iterator<RemoteFile>> stack=new ArrayDeque<>();
        final TransferPaths mapper=new TransferPaths();
        final Directory root;
        final String canonicalRoot;
        final Set<String> visitedDirectories=new HashSet<>();
        SourceWalker(List<RemoteFile> selected,Directory root) throws IOException {
            stack.push(selected.iterator()); this.root=root;
            String resolved=fs.canonicalPath(root.path);
            canonicalRoot=resolved.endsWith(File.separator)?resolved:resolved+File.separator;
        }
        Entry next() throws IOException {
            while (!stack.isEmpty()) {
                checkCancelled();
                Iterator<RemoteFile> iterator=stack.peek();
                if (!iterator.hasNext()) { stack.pop(); continue; }
                RemoteFile file=iterator.next();
                Entry entry=new Entry(); entry.path=file.getPath();
                try {
                    entry.file=fs.stat(file.getPath());
                    String canonical=fs.canonicalPath(file.getPath());
                    if (!canonical.startsWith(canonicalRoot)) throw new IOException("Source symlink escapes the selected directory");
                    entry.relative=mapper.map(file.getPath(),root);
                    if (entry.file.isDirectory()) {
                        if (!visitedDirectories.add(canonical)) throw new IOException("Repeated directory or symbolic-link cycle");
                        if (stack.size()>512) throw new IOException("Directory nesting exceeds 512");
                        stack.push(fs.list(file.getPath()).iterator());
                    }
                } catch (IOException | RuntimeException e) { entry.error=e.toString(); }
                return entry;
            }
            return null;
        }
    }
}
