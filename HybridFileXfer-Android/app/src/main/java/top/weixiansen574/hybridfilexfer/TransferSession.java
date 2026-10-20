package top.weixiansen574.hybridfilexfer;

import android.content.Context;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import top.weixiansen574.hybridfilexfer.core.TransferOptions;
import top.weixiansen574.hybridfilexfer.core.TransferProgress;
import top.weixiansen574.hybridfilexfer.core.TransferSummary;
import top.weixiansen574.hybridfilexfer.core.Utils;
import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;
import top.weixiansen574.hybridfilexfer.droidcore.HFXServer;
import top.weixiansen574.hybridfilexfer.tasks.BTransferFileCallback;
import top.weixiansen574.hybridfilexfer.tasks.SendFilesToRemoteTask;
import top.weixiansen574.hybridfilexfer.tasks.SendFilesToShelfTask;

/** Keeps worker callbacks independent of windows, including while the UI is in the background. */
public final class TransferSession implements BTransferFileCallback {
    public interface Listener { void onTransferChanged(); }
    private static TransferSession current;
    public static TransferSession current() { return current; }
    public static TransferSession start(Context context, HFXServer server, boolean upload,
                                        List<RemoteFile> files, Directory local, Directory remote,
                                        TransferOptions options) {
        if (current != null && current.running) throw new IllegalStateException("A transfer is already running");
        TransferSession session = new TransferSession(context, server, upload, local, remote);
        current = session;
        server.setTransferOptions(options);
        if (upload) new SendFilesToRemoteTask(session, server, files, local, remote).execute();
        else new SendFilesToShelfTask(session, server, files, local, remote).execute();
        return session;
    }
    private final Context context;
    private final HFXServer server;
    private final String source, destination;
    private Listener listener;
    private final long startedAt = android.os.SystemClock.elapsedRealtime();
    public final boolean upload;
    public final List<String> channelNames;
    public final Map<String, String> events = new LinkedHashMap<>();
    public List<TrafficInfo> speeds = new ArrayList<>();
    public TransferProgress progress;
    public TransferSummary summary;
    public boolean running = true;
    public boolean acknowledged;
    public String status = "";
    public long traffic, elapsed;

    private TransferSession(Context context, HFXServer server, boolean upload, Directory local, Directory remote) {
        this.context = context.getApplicationContext();
        this.server = server;
        this.upload = upload;
        source = upload ? local.path : remote.path;
        destination = upload ? remote.path : local.path;
        channelNames = server.getConnectionListINames();
    }
    public void attach(Listener listener) { this.listener = listener; changed(); }
    public void detach(Listener listener) { if (this.listener == listener) this.listener = null; }
    private void changed() { if (listener != null) listener.onTransferChanged(); }
    public void cancel() {
        if (running) {
            status = context.getString(R.string.cancelling_transfer);
            server.cancelTransfer();
            changed();
        }
    }
    @Override public void onFileUploading(String name, String path, long size, long total) {
        events.put(name, "↑ " + path + " · " + Utils.formatFileSize(size) + "/" + Utils.formatFileSize(total));
        changed();
    }
    @Override public void onFileDownloading(String name, String path, long size, long total) {
        events.put(name, "↓ " + path + " · " + Utils.formatFileSize(size) + "/" + Utils.formatFileSize(total));
        changed();
    }
    @Override public void onSpeedInfo(List<TrafficInfo> speeds) { this.speeds = new ArrayList<>(speeds); changed(); }
    @Override public void onChannelComplete(String name, long traffic, long time) {
        events.put(name, Utils.formatSpeed(traffic * 1000 / Math.max(1, time))); changed();
    }
    @Override public void onChannelError(String name, int type, String message) { events.put(name, message); changed(); }
    @Override public void onReadFileError(String message) { status = message; changed(); }
    @Override public void onWriteFileError(String message) { status = message; changed(); }
    @Override public void onProgress(TransferProgress progress) { this.progress = progress; changed(); }
    @Override public void onPhase(String phase) { status = phase; changed(); }
    @Override public void onFileResult(String path, String result, String message) {
        status = result + ": " + path + (message == null || message.isEmpty() ? "" : "\n" + message);
        changed();
    }
    @Override public void onSummary(TransferSummary summary) { this.summary = summary; changed(); }
    @Override public void onComplete(boolean upload, long traffic, long time) {
        this.traffic = traffic;
        elapsed = time;
        finish(context.getString(R.string.transfer_finished));
    }
    @Override public void onIncomplete() { finish(context.getString(R.string.chuan_shu_shi_fa_sheng_yi_chang)); }
    @Override public void onError(Throwable error) { finish(error.toString()); }

    public String resultText() {
        String text = status + "\n" + source + " → " + destination + "\n"
                + Utils.formatFileSize(traffic) + " · " + Utils.formatTime(elapsed);
        if (summary != null) text += "\n" + formatSummary(context, summary);
        return text;
    }

    static String formatSummary(Context context, TransferSummary summary) {
        StringBuilder text = new StringBuilder(context.getString(R.string.transfer_result_counts,
                summary.completedFiles, summary.skippedFiles, summary.failedFiles));
        if (summary.cancelled) text.append('\n').append(context.getString(R.string.transfer_cancelled));
        for (String failure : summary.failures) text.append('\n').append(failure);
        if (!summary.warnings.isEmpty()) {
            text.append('\n').append(context.getString(R.string.transfer_cleanup_warnings, summary.warnings.size()));
            for (String warning : summary.warnings) text.append('\n').append(warning);
        }
        return text.toString();
    }
    private void finish(String message) {
        if (!running) return;
        running = false;
        if (summary != null) traffic = summary.transferredBytes;
        else if (progress != null) traffic = progress.transferredBytes;
        if (elapsed == 0) elapsed = android.os.SystemClock.elapsedRealtime() - startedAt;
        status = message;
        LocalHistory.transfer(context, resultText());
        changed();
    }
}
