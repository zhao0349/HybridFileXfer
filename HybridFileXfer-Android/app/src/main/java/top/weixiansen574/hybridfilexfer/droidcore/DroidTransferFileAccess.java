package top.weixiansen574.hybridfilexfer.droidcore;

import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import top.weixiansen574.hybridfilexfer.aidl.IIOService;
import top.weixiansen574.hybridfilexfer.core.TransferFileAccess;
import top.weixiansen574.hybridfilexfer.core.TransferOptions;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

/** No raw file contents cross Binder; descriptors and small control records do. */
public final class DroidTransferFileAccess implements TransferFileAccess {
    private final IIOService io;
    public DroidTransferFileAccess(IIOService io) { this.io=io; }
    private static void checked(String error) throws IOException { if (error!=null) throw new IOException(error); }
    @Override public String canonicalPath(String path) throws IOException {
        try {
            String[] value=io.canonicalTransferPath(path);
            if (value==null || value.length!=2 || !value[1].isEmpty()) throw new IOException("Cannot resolve source path: "+path);
            return value[0];
        } catch (RemoteException e) { throw new IOException(e); }
    }
    @Override public RemoteFile stat(String path) throws IOException {
        try {
            String[] a=io.describeTransferFile(path);
            if (a==null || a.length!=5) throw new IOException(a!=null && a.length==1?a[0]:"Cannot stat source");
            return new RemoteFile(a[0],a[1],Long.parseLong(a[2]),Long.parseLong(a[3]),Boolean.parseBoolean(a[4]));
        } catch (RemoteException | NumberFormatException e) { throw new IOException(e); }
    }
    @Override public List<RemoteFile> list(String path) throws IOException {
        try {
            int[] chunks=io.listFiles(path);
            if (chunks==null) throw new IOException("Cannot list directory: "+path);
            List<RemoteFile> files=new ArrayList<>();
            for (int chunk:chunks) files.addAll(io.getAndRemoveFileListSlice(chunk));
            java.util.Collections.sort(files,java.util.Comparator.comparing(RemoteFile::getName));
            return files;
        } catch (RemoteException e) { throw new IOException(e); }
    }
    @Override public Handle openRead(String path) throws IOException {
        try {
            ParcelFileDescriptor pfd=io.openTransferSource(path);
            if (pfd==null) throw new IOException("Cannot open source: "+path);
            ParcelFileDescriptor.AutoCloseInputStream in=new ParcelFileDescriptor.AutoCloseInputStream(pfd);
            return new Handle(in.getChannel(),in);
        } catch (RemoteException e) { throw new IOException(e); }
    }
    @Override public void createDirectory(String root,String relative,long modified) throws IOException {
        try { checked(io.createTransferDirectory(root,relative,modified)); }
        catch (RemoteException e) { throw new IOException(e); }
    }
    @Override public Prepared prepare(String root,String relative,String identity,long size,long modified,
                                       String hash,TransferOptions options) throws IOException {
        try {
            Prepared p=Prepared.decode(io.prepareTransferFile(root,relative,identity,size,modified,hash,
                    options.conflictPolicy.ordinal(),options.resume,options.incremental));
            if ("failed".equals(p.status)) throw new IOException(p.message);
            return p;
        } catch (RemoteException e) { throw new IOException(e); }
    }
    @Override public Handle openStage(String token) throws IOException {
        try {
            ParcelFileDescriptor pfd=io.openTransferStage(token);
            if (pfd==null) throw new IOException("Cannot open staging file");
            ParcelFileDescriptor.AutoCloseOutputStream out=new ParcelFileDescriptor.AutoCloseOutputStream(pfd);
            return new Handle(out.getChannel(),out);
        } catch (RemoteException e) { throw new IOException(e); }
    }
    @Override public void checkpoint(String token,long offset) throws IOException {
        try { checked(io.checkpointTransferFile(token,offset)); }
        catch (RemoteException e) { throw new IOException(e); }
    }
    @Override public void commit(String token,boolean verify) throws IOException {
        try { checked(io.commitTransferFile(token,verify)); }
        catch (RemoteException e) { throw new IOException(e); }
    }
    @Override public void abandon(String token,boolean keep) throws IOException {
        try { checked(io.abandonTransferFile(token,keep)); }
        catch (RemoteException e) { throw new IOException(e); }
    }
}
