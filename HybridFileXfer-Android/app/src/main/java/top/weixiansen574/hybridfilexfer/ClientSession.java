package top.weixiansen574.hybridfilexfer;

import android.content.Context;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import top.weixiansen574.hybridfilexfer.aidl.IIOService;
import top.weixiansen574.hybridfilexfer.core.Utils;
import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;
import top.weixiansen574.hybridfilexfer.droidcore.DroidHFXClient;
import top.weixiansen574.hybridfilexfer.tasks.ClientTask;
import top.weixiansen574.hybridfilexfer.tasks.ConnectServerTask;

/** Receiving-side session survives closing/recreating its status Activity. */
public final class ClientSession {
    public interface Listener { void onSessionChanged(); }
    public static final class Channel {
        public String event = "";
        public long uploadSpeed, downloadSpeed;
    }
    private static ClientSession instance;
    public static synchronized ClientSession get(Context context) {
        if (instance == null) instance = new ClientSession(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final IoServiceConnector connector;
    private DroidHFXClient client;
    private Listener listener;
    private int generation;
    private String lastTransferReport = "";
    public boolean active;
    public boolean connecting;
    public String state = "";
    public final Map<String, Channel> channels = new LinkedHashMap<>();

    private ClientSession(Context context) {
        this.context = context;
        connector = new IoServiceConnector(context);
    }
    public void attach(Listener listener) { this.listener = listener; changed(); }
    public void detach(Listener listener) { if (this.listener == listener) this.listener = null; }
    private void changed() { if (listener != null) listener.onSessionChanged(); }
    private void state(String message) { state = message; changed(); }

    public void start(int mode, String address, int port, String home, String pairing) {
        if (active) return;
        int attempt = ++generation;
        active = true;
        connecting = true;
        lastTransferReport = "";
        channels.clear();
        state(context.getString(R.string.starting_io_service));
        try {
            SessionService.acquire(context, "client", this::stop);
        } catch (RuntimeException e) {
            fail(e.toString());
            return;
        }
        connector.bind(mode, new IoServiceConnector.Callback() {
            @Override public void onReady(IIOService io) {
                if (attempt != generation || !active) return;
                client = new DroidHFXClient(address, port, home, io, context);
                client.configureConnection(pairing, port, port, 10000);
                new ConnectServerTask(new ConnectServerTask.Callback() {
                    private boolean current() { return generation == attempt && active; }
                    @Override public void onConnectSuccess(List<String> names) {
                        if (!current()) return;
                        connecting = false;
                        for (String name : names) channels.put(name, new Channel());
                        state(context.getString(R.string.waiting_for_tasks));
                        runClient(attempt);
                    }
                    @Override public void onConnectingControlChannel(String address, int port) {
                        if (current()) state(context.getString(R.string.connecting_controller, address));
                    }
                    @Override public void onVersionMismatch(int local, int remote) {
                        if (current()) fail(context.getString(R.string.Inconsistent_protocol_versions, local, remote));
                    }
                    @Override public void onConnectControlFailed() {
                        if (current()) fail(context.getString(R.string.control_channel_connection_failed));
                    }
                    @Override public void onConnectingTransferChannel(String name, InetAddress address, InetAddress bind) {
                        if (current()) state(context.getString(R.string.connecting_transfer_channel, name,
                                address.getHostAddress(), bind == null ? "" : bind.getHostAddress()));
                    }
                    @Override public void onConnectTransferChannelFailed(String name, InetAddress address, Exception error) {
                        if (current()) fail(error.toString());
                    }
                    @Override public void onOOM(int count, int total, long maxMemory, String arch) {
                        if (current()) fail(context.getString(R.string.failed_to_create_buffer_block, count, total, maxMemory));
                    }
                    @Override public void onRemoteOOM() {
                        if (current()) fail(context.getString(R.string.c_remote_oom_message));
                    }
                    @Override public void onError(Throwable error) {
                        if (current()) fail(error.toString());
                    }
                }, client).execute();
            }
            @Override public void onFailure(String error) {
                if (attempt == generation) fail(error);
            }
        });
    }

    private void runClient(int attempt) {
        new ClientTask(new ClientTask.Callback() {
            private boolean current() { return generation == attempt && active; }
            private void event(String name, String message) {
                if (!current()) return;
                Channel channel = channels.get(name);
                if (channel != null) channel.event = message;
                changed();
            }
            @Override public void onReceiving() {
                if (current()) {
                    lastTransferReport = "";
                    state(context.getString(R.string.zheng_zai_jie_shou_wen_jian));
                }
            }
            @Override public void onSending() {
                if (current()) {
                    lastTransferReport = "";
                    state(context.getString(R.string.zheng_zai_fa_song_wen_jian));
                }
            }
            @Override public void onExit() { if (current()) stop(); }
            @Override public void onFileUploading(String name, String path, long size, long total) {
                event(name, "↑ " + path + " " + Utils.formatFileSize(size) + "/" + Utils.formatFileSize(total));
            }
            @Override public void onFileDownloading(String name, String path, long size, long total) {
                event(name, "↓ " + path + " " + Utils.formatFileSize(size) + "/" + Utils.formatFileSize(total));
            }
            @Override public void onSpeedInfo(List<TrafficInfo> traffic) {
                if (!current()) return;
                for (TrafficInfo info : traffic) {
                    Channel channel = channels.get(info.iName);
                    if (channel != null) {
                        channel.uploadSpeed = info.uploadTraffic;
                        channel.downloadSpeed = info.downloadTraffic;
                    }
                }
                changed();
            }
            @Override public void onChannelComplete(String name, long traffic, long time) {
                event(name, context.getString(R.string.transfer_completed, Utils.formatSpeed(traffic * 1000 / Math.max(1, time))));
            }
            @Override public void onChannelError(String name, int type, String message) { event(name, message); }
            @Override public void onReadFileError(String message) { if (current()) state(message); }
            @Override public void onWriteFileError(String message) { if (current()) state(message); }
            @Override public void onComplete(boolean upload, long traffic, long time) {
                if (!current()) return;
                for (Channel channel : channels.values()) channel.uploadSpeed = channel.downloadSpeed = 0;
                state((upload ? "↑ " : "↓ ") + Utils.formatFileSize(traffic) + " · " + Utils.formatTime(time)
                        + "\n" + lastTransferReport);
            }
            @Override public void onProgress(top.weixiansen574.hybridfilexfer.core.TransferProgress progress) {
                if (current()) state(Utils.formatFileSize(progress.transferredBytes) + " / "
                        + (progress.totalBytes < 0 ? "?" : Utils.formatFileSize(progress.totalBytes))
                        + " · " + progress.completedFiles + " / " + (progress.totalFiles < 0 ? "?" : progress.totalFiles)
                        + "\n" + progress.currentPath);
            }
            @Override public void onPhase(String phase) { if (current()) state(phase); }
            @Override public void onSummary(top.weixiansen574.hybridfilexfer.core.TransferSummary summary) {
                if (!current()) return;
                lastTransferReport = TransferSession.formatSummary(context, summary);
                LocalHistory.transfer(context, lastTransferReport);
            }
            @Override public void onIncomplete() {
                if (current()) state(context.getString(R.string.chuan_shu_shi_fa_sheng_yi_chang)
                        + "\n" + lastTransferReport);
            }
            @Override public void onComplete() {
                // This is task completion, distinct from one completed transfer above.
                if (current()) stop();
            }
            @Override public void onError(Throwable error) { if (current()) fail(error.toString()); }
        }, client).execute();
    }

    private void fail(String message) { stop(); state(message); }
    public void stop() {
        generation++;
        active = false;
        connecting = false;
        for (Channel channel : channels.values()) channel.uploadSpeed = channel.downloadSpeed = 0;
        if (client != null) {
            client.close();
            client = null;
        }
        connector.close();
        SessionService.release(context, "client");
        state(context.getString(R.string.lian_jie_yi_guan_bi));
    }
}
