package top.weixiansen574.hybridfilexfer.desktop;

import top.weixiansen574.hybridfilexfer.core.TransferOptions;
import top.weixiansen574.hybridfilexfer.core.TransferProgress;
import top.weixiansen574.hybridfilexfer.core.TransferSummary;
import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.prefs.Preferences;

import static top.weixiansen574.hybridfilexfer.desktop.DesktopText.*;

/** Desktop connection and transfer console; all widgets are confined to the EDT. */
public final class DesktopApp {
    private final JFrame frame = new JFrame(text("多轨快传", "HybridFileXfer"));
    private final JComboBox<String> mode = new JComboBox<>(new String[]{text("USB / ADB", "USB / ADB"), text("Wi-Fi / 网络", "Wi-Fi / Network")});
    private final JComboBox<Object> devices = new JComboBox<>();
    private final JTextField address = new JTextField(18), directory = new JTextField(28);
    private final JSpinner port = new JSpinner(new SpinnerNumberModel(5740, 1, 65535, 1));
    private final JSpinner localPort = new JSpinner(new SpinnerNumberModel(0, 0, 65535, 1));
    private final JPasswordField pairing = new JPasswordField(18);
    private final JCheckBox reconnect = new JCheckBox(text("等待手机并自动重新连接", "Wait for phone and reconnect automatically"), true);
    private final JCheckBox launchPhone = new JCheckBox(text("通过 ADB 打开手机 App（手机上确认）", "Open phone app through ADB (confirm on phone)"), true);
    private final JCheckBox burst = new JCheckBox(text("实验性 ADB burst mode", "Experimental ADB burst mode"));
    private final JCheckBox verify = new JCheckBox(text("校验 SHA-256", "Verify SHA-256"), true);
    private final JCheckBox resume = new JCheckBox(text("恢复匹配的未完成文件", "Resume matching partial files"), true);
    private final JCheckBox incremental = new JCheckBox(text("跳过已相同的文件", "Skip identical files"));
    private final JCheckBox prescan = new JCheckBox(text("传输前统计总量（大目录需要时间）", "Count totals before transfer (large folders take time)"));
    private final JCheckBox rememberHistory = new JCheckBox(text("保存最近 100 条摘要到本机", "Keep the latest 100 summaries on this computer"), true);
    private final JComboBox<String> conflict = new JComboBox<>(new String[]{text("保留两份", "Keep both"), text("跳过", "Skip"), text("覆盖", "Overwrite")});
    private final JButton connect = new JButton(text("等待并连接", "Wait & connect")), stop = new JButton(text("停止 / 断开", "Stop / disconnect"));
    private final JButton refresh = new JButton(text("刷新", "Refresh")), browse = new JButton(text("选择文件夹", "Choose folder"));
    private final JButton openPhone = new JButton(text("打开手机 App", "Open phone app"));
    private final JButton cancelTransfer = new JButton(text("取消本次传输", "Cancel transfer"));
    private final JTextArea status = new JTextArea(2, 50);
    private final JLabel phase = new JLabel(" "), file = new JLabel(" ");
    private final JProgressBar progress = new JProgressBar(0, 1000);
    private final JTextArea logs = new JTextArea();
    private final DefaultTableModel channels = readOnlyModel(text("通道", "Channel"), text("发送", "Upload"), text("接收", "Download"));
    private final DefaultTableModel history = readOnlyModel(text("时间", "Time"), text("方向", "Direction"), text("结果", "Result"), text("数据量", "Data"), text("耗时", "Duration"));
    private final ExecutorService utility = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "hfx-desktop-utility"); t.setDaemon(true); return t; });
    private final TransferHistory historyStore = new TransferHistory();
    private final List<String[]> historyRows = new ArrayList<>();
    private final List<JComponent> connectionControls = new ArrayList<>();
    private final ConnectionController controller;
    private final AtomicReference<TransferProgress> latestProgress = new AtomicReference<>();
    private final ConcurrentLinkedQueue<String> pendingLogs = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingLogCount = new AtomicInteger(), omittedLogs = new AtomicInteger();
    private final Timer progressTimer = new Timer(100, event -> { flushProgress(); flushLogs(); });
    private Preferences prefs;
    private boolean uploading, taskFailed, transferActive, disposalStarted;
    private volatile boolean closing;
    private long taskStarted, lastTransferredBytes;
    private String taskResult = "", sessionSecret = "";
    private volatile AdbBridge auxiliaryAdb;
    private int refreshGeneration;

    public static void launch(LaunchOptions options) {
        if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("No desktop display is available. Use --cli -c adb or --help.");
        SwingUtilities.invokeLater(() -> {
            try {
                configureLookAndFeel();
                DesktopApp app = new DesktopApp(options);
                app.frame.setVisible(true);
                app.refreshDevices();
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null, ConnectionController.friendlyError(e), "HybridFileXfer", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    public DesktopApp(LaunchOptions options) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Create the desktop on the Swing event thread.");
        controller = new ConnectionController(new ConnectionController.Listener() {
            @Override public void onState(ConnectionController.State state, String message) { ui(() -> updateState(state, message)); }
            @Override public void onLog(String message) {
                if (pendingLogCount.incrementAndGet() <= 1000) pendingLogs.add(message);
                else { pendingLogCount.decrementAndGet(); omittedLogs.incrementAndGet(); }
            }
            @Override public void onChannels(List<String> names) { ui(() -> { channels.setRowCount(0); for (String name : names) channels.addRow(new Object[]{name, "0 B/s", "0 B/s"}); }); }
            @Override public void onSpeed(List<TrafficInfo> values) {
                List<TrafficInfo> snapshot = new ArrayList<>();
                for (TrafficInfo info : values) snapshot.add(new TrafficInfo(info.iName, info.uploadTraffic, info.downloadTraffic));
                ui(() -> updateSpeeds(snapshot));
            }
            @Override public void onTransferStarted(boolean upload) { ui(() -> started(upload)); }
            @Override public void onProgress(TransferProgress value) {
                TransferProgress copy = new TransferProgress();
                copy.transferredBytes = value.transferredBytes; copy.totalBytes = value.totalBytes;
                copy.completedFiles = value.completedFiles; copy.totalFiles = value.totalFiles;
                copy.currentFileBytes = value.currentFileBytes; copy.currentFileSize = value.currentFileSize; copy.currentPath = value.currentPath;
                latestProgress.set(copy);
            }
            @Override public void onSummary(TransferSummary summary) { ui(() -> { flushProgress(); showSummary(summary); }); }
            @Override public void onComplete(boolean upload, long bytes, long millis) { ui(() -> { flushProgress(); completed(upload, bytes, millis); }); }
            @Override public void onTransferFailed(String message) { ui(() -> { flushProgress(); failed(message); }); }
            @Override public void onFinished() { ui(() -> { setBusy(false); if (closing) dispose(); }); }
        });
        try { if (!Boolean.getBoolean("hfx.smokeTest")) prefs = Preferences.userNodeForPackage(DesktopApp.class); } catch (SecurityException ignored) { }
        String downloads = Paths.get(System.getProperty("user.home"), "Downloads").toString();
        if (!Files.isDirectory(Paths.get(downloads))) downloads = System.getProperty("user.home");
        directory.setText("/".equals(options.directory) ? preference("directory", downloads) : options.directory);
        port.setEditor(new JSpinner.NumberEditor(port, "0"));
        localPort.setEditor(new JSpinner.NumberEditor(localPort, "0"));
        ((JSpinner.NumberEditor) port.getEditor()).getTextField().setColumns(6);
        ((JSpinner.NumberEditor) localPort.getEditor()).getTextField().setColumns(6);
        port.setPreferredSize(new Dimension(100, 28));
        localPort.setPreferredSize(new Dimension(100, 28));
        directory.setPreferredSize(new Dimension(390, 28));
        address.setPreferredSize(new Dimension(240, 28));
        pairing.setPreferredSize(new Dimension(240, 28));
        address.setText("adb".equals(options.connect) ? preference("address", "") : options.connect);
        mode.setSelectedIndex("adb".equals(options.connect) ? 0 : 1);
        port.setValue(options.port);
        localPort.setValue(options.localPort);
        pairing.setText(options.secret);
        burst.setSelected(options.burst);
        reconnect.setSelected(true);
        devices.addItem(text("自动选择唯一已授权手机", "Automatically select the only authorized phone"));
        if (options.device != null) devices.addItem(new AdbBridge.Device(options.device, "device", options.device));
        if (options.device != null) devices.setSelectedIndex(1);
        build();
        progressTimer.start();
        modeChanged();
        updateState(ConnectionController.State.IDLE, text("电脑已就绪。连接手机后点击“等待并连接”。", "Desktop is ready. Connect your phone and choose Wait & connect."));
        stop.setEnabled(false);
        cancelTransfer.setEnabled(false);
        utility.submit(() -> {
            try {
                List<String[]> rows = historyStore.load();
                ui(() -> { historyRows.addAll(rows); for (String[] row : rows) history.addRow(row); });
            } catch (Exception e) { ui(() -> appendLog(text("无法读取本地历史：", "Cannot read local history: ") + e.getMessage())); }
        });
    }
    private static DefaultTableModel readOnlyModel(String... columns) {
        return new DefaultTableModel(columns, 0) { @Override public boolean isCellEditable(int row, int column) { return false; } };
    }
    public static void configureLookAndFeel() throws Exception {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        String family = System.getProperty("os.name", "").toLowerCase().contains("win") ? "Microsoft YaHei UI" : Font.DIALOG;
        javax.swing.plaf.FontUIResource font = new javax.swing.plaf.FontUIResource(family, Font.PLAIN, 13);
        for (Object key : java.util.Collections.list(UIManager.getDefaults().keys())) {
            if (UIManager.get(key) instanceof javax.swing.plaf.FontUIResource) UIManager.put(key, font);
        }
    }
    private String preference(String key, String fallback) {
        try { return prefs == null ? fallback : prefs.get(key, fallback); } catch (SecurityException e) { return fallback; }
    }
    private void build() {
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setMinimumSize(new Dimension(920, 700));
        frame.setSize(1040, 840);
        frame.setLocationRelativeTo(null);
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(new EmptyBorder(18, 24, 16, 24));
        root.setBackground(new Color(246, 248, 250));
        JPanel top = new JPanel(); top.setOpaque(false); top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        JLabel title = new JLabel(text("多轨快传", "HybridFileXfer"));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 27f));
        top.add(title);
        top.add(Box.createVerticalStrut(5));
        top.add(new JLabel(text("先打开电脑，再连接手机。传输与重连都留在这个窗口。", "Open the desktop first, then connect your phone. Transfers and reconnections stay in this window.")));
        top.add(Box.createVerticalStrut(10));
        JPanel form = new JPanel(new GridBagLayout()); form.setOpaque(false);
        row(form, 0, text("连接方式", "Connection"), line(mode, devices, refresh));
        row(form, 1, text("手机地址 / 端口", "Phone address / port"), line(address, port));
        row(form, 2, text("电脑文件夹", "Computer folder"), line(directory, browse));
        row(form, 3, text("配对码", "Pairing code"), line(pairing, openPhone));
        row(form, 4, "", new JLabel(text("ADB 可自动生成配对信息；网络连接请填写手机显示的配对码。", "ADB can pair automatically. For network connections, enter the code shown on your phone.")));
        row(form, 5, "", line(reconnect, launchPhone));
        top.add(form);
        JPanel advanced = new JPanel(new GridBagLayout());
        row(advanced, 0, text("同名文件", "Existing files"), line(conflict, verify, resume));
        row(advanced, 1, "", line(incremental, prescan));
        row(advanced, 2, text("ADB 本地端口", "Local ADB port"), line(localPort, new JLabel(text("0 = 自动选择可用端口", "0 = choose an available port"))));
        row(advanced, 3, "", burst);
        row(advanced, 4, "", new JLabel(text("Burst 仅对本程序的 ADB 子进程设置，不会重启共享 ADB 服务。", "Burst applies to this app's ADB processes; it does not restart the shared ADB server.")));
        JButton more = new JButton(text("高级设置…", "Advanced settings…"));
        more.addActionListener(e -> JOptionPane.showMessageDialog(frame, advanced,
                text("传输与连接设置", "Transfer and connection settings"), JOptionPane.PLAIN_MESSAGE));
        top.add(line(more));
        top.add(Box.createVerticalStrut(10));
        connect.setFont(connect.getFont().deriveFont(Font.BOLD));
        top.add(line(connect, stop, cancelTransfer));
        top.add(Box.createVerticalStrut(8));
        status.setFont(status.getFont().deriveFont(Font.BOLD, 14f));
        status.setEditable(false); status.setOpaque(false); status.setLineWrap(true); status.setWrapStyleWord(true);
        status.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        top.add(status);
        top.add(Box.createVerticalStrut(8));
        progress.setStringPainted(true); progress.setString(text("尚无传输任务", "No transfer yet"));
        phase.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        file.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        top.add(progress); top.add(phase); top.add(file);
        for (Component child : top.getComponents()) if (child instanceof JComponent) ((JComponent) child).setAlignmentX(Component.LEFT_ALIGNMENT);
        root.add(top, BorderLayout.NORTH);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab(text("通道速度", "Channel speeds"), new JScrollPane(table(channels)));
        JPanel historyPanel = new JPanel(new BorderLayout());
        historyPanel.add(new JScrollPane(table(history)));
        historyPanel.add(rememberHistory, BorderLayout.SOUTH);
        tabs.addTab(text("最近传输", "Recent transfers"), historyPanel);
        JPanel diagnostic = new JPanel(new BorderLayout(4, 4));
        logs.setEditable(false); logs.setLineWrap(false); logs.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        diagnostic.add(new JScrollPane(logs));
        JButton saveLog = new JButton(text("保存诊断日志…", "Save diagnostic log…"));
        JButton clearLog = new JButton(text("清空显示", "Clear display"));
        saveLog.addActionListener(e -> saveLog()); clearLog.addActionListener(e -> logs.setText(""));
        diagnostic.add(line(saveLog, clearLog), BorderLayout.SOUTH);
        tabs.addTab(text("诊断日志", "Diagnostics"), diagnostic);
        root.add(tabs, BorderLayout.CENTER);
        root.add(new JLabel(text("文件仍在手机双栏界面中选择。断线重连不会自动重放传输任务。", "Select files in the phone's two-pane browser. Reconnecting never restarts a transfer silently.")), BorderLayout.SOUTH);
        frame.setContentPane(root);
        connectionControls.addAll(Arrays.asList(mode, devices, address, directory, port, localPort, pairing,
                refresh, browse, openPhone, reconnect, launchPhone, burst, conflict, verify, resume, incremental, prescan, more));
        connect.addActionListener(e -> begin());
        stop.addActionListener(e -> controller.close());
        cancelTransfer.addActionListener(e -> { cancelTransfer.setEnabled(false); controller.cancelTransfer(); phase.setText(text("已请求取消，正在安全结束任务…", "Cancellation requested. Finishing the task safely…")); });
        mode.addActionListener(e -> modeChanged());
        refresh.addActionListener(e -> refreshDevices());
        browse.addActionListener(e -> chooseFolder());
        openPhone.addActionListener(e -> requestPhone());
        frame.addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) { closeWindow(); } });
    }
    private JTable table(DefaultTableModel model) {
        JTable table = new JTable(model);
        table.setRowHeight(29);
        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(true);
        return table;
    }
    private static JPanel line(Component... components) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3)); panel.setOpaque(false);
        for (Component component : components) panel.add(component);
        return panel;
    }
    private static void row(JPanel form, int row, String title, Component component) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = row; c.anchor = GridBagConstraints.WEST; c.insets = new Insets(2, 0, 2, 10);
        form.add(new JLabel(title), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        form.add(component, c);
    }
    private void modeChanged() {
        boolean adb = mode.getSelectedIndex() == 0;
        devices.setEnabled(adb); refresh.setEnabled(adb); openPhone.setEnabled(adb);
        launchPhone.setEnabled(adb); address.setEnabled(!adb); localPort.setEnabled(adb); burst.setEnabled(adb);
    }
    private void setBusy(boolean busy) {
        for (JComponent control : connectionControls) control.setEnabled(!busy);
        connect.setEnabled(!busy); stop.setEnabled(busy);
        if (!busy) { modeChanged(); cancelTransfer.setEnabled(false); }
    }
    private String selectedDevice() {
        Object value = devices.getSelectedItem();
        return value instanceof AdbBridge.Device ? ((AdbBridge.Device) value).serial : null;
    }
    private String obtainSecret() {
        String secret = new String(pairing.getPassword()).trim();
        if (secret.isEmpty() && mode.getSelectedIndex() == 0 && launchPhone.isSelected()) {
            secret = ConnectionController.newPairingSecret();
            pairing.setText(secret);
        }
        sessionSecret = secret;
        return secret;
    }
    private void begin() {
        try {
            port.commitEdit(); localPort.commitEdit();
            TransferOptions transfer = new TransferOptions();
            transfer.conflictPolicy = conflict.getSelectedIndex() == 0 ? TransferOptions.ConflictPolicy.KEEP_BOTH
                    : conflict.getSelectedIndex() == 1 ? TransferOptions.ConflictPolicy.SKIP : TransferOptions.ConflictPolicy.OVERWRITE;
            transfer.verifySha256 = verify.isSelected(); transfer.resume = resume.isSelected();
            transfer.incremental = incremental.isSelected(); transfer.prescan = prescan.isSelected();
            ConnectionController.Settings settings = new ConnectionController.Settings(mode.getSelectedIndex() == 0 ? "adb" : address.getText(),
                    selectedDevice(), directory.getText().trim(), obtainSecret(), (Integer) port.getValue(),
                    (Integer) localPort.getValue(), reconnect.isSelected(), mode.getSelectedIndex() == 0 && launchPhone.isSelected(), burst.isSelected(), transfer);
            settings.validate();
            try {
                if (prefs != null) { prefs.put("directory", settings.directory); prefs.put("address", address.getText().trim()); }
            } catch (SecurityException e) { appendLog(text("无法保存设置，本次连接仍可继续。", "Settings could not be saved; this connection can still continue.")); }
            setBusy(true);
            controller.start(settings);
        } catch (Exception e) { setBusy(false); showError(e); }
    }
    private void refreshDevices() {
        int generation = ++refreshGeneration;
        String selected = selectedDevice();
        refresh.setEnabled(false);
        utility.submit(() -> {
            if (closing) return;
            try (AdbBridge adb = new AdbBridge(false)) {
                auxiliaryAdb = adb;
                List<AdbBridge.Device> found = adb.devices();
                ui(() -> {
                    if (generation != refreshGeneration || controller.isRunning()) return;
                    devices.removeAllItems(); devices.addItem(text("自动选择唯一已授权手机", "Automatically select the only authorized phone"));
                    boolean retained = false;
                    for (AdbBridge.Device device : found) {
                        devices.addItem(device);
                        if (device.serial.equals(selected)) { devices.setSelectedItem(device); retained = true; }
                    }
                    if (selected != null && !retained) {
                        AdbBridge.Device missing = new AdbBridge.Device(selected, "offline", selected);
                        devices.addItem(missing); devices.setSelectedItem(missing);
                    }
                    appendLog(text("检测到设备：", "Devices detected: ") + found.size());
                });
            } catch (Exception e) { ui(() -> appendLog(ConnectionController.friendlyError(e))); }
            finally { auxiliaryAdb = null; ui(() -> refresh.setEnabled(!controller.isRunning() && mode.getSelectedIndex() == 0)); }
        });
    }
    private void requestPhone() {
        String selected = selectedDevice();
        String secret = obtainSecret();
        if (secret.isEmpty()) { secret = ConnectionController.newPairingSecret(); pairing.setText(secret); sessionSecret = secret; }
        final String code = secret;
        final int serverPort = (Integer) port.getValue();
        final boolean burstEnabled = burst.isSelected();
        openPhone.setEnabled(false);
        utility.submit(() -> {
            if (closing) return;
            try (AdbBridge adb = new AdbBridge(burstEnabled)) {
                auxiliaryAdb = adb;
                AdbBridge.Device device = ConnectionController.selectDevice(adb.devices(), selected);
                adb.launchPhone(device.serial, code, serverPort);
                ui(() -> updateState(ConnectionController.State.IDLE, text("手机 App 已打开，请在手机上确认，然后点击连接。", "Phone app opened. Confirm on the phone, then connect.")));
            } catch (Exception e) { ui(() -> { if (!closing) showError(e); }); }
            finally { auxiliaryAdb = null; ui(() -> openPhone.setEnabled(!controller.isRunning() && mode.getSelectedIndex() == 0)); }
        });
    }
    private void chooseFolder() {
        JFileChooser chooser = new JFileChooser(directory.getText());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) directory.setText(chooser.getSelectedFile().getAbsolutePath());
    }
    private void updateState(ConnectionController.State state, String message) {
        status.setText("● " + message);
        status.setForeground(state == ConnectionController.State.ERROR ? new Color(174, 40, 42)
                : state == ConnectionController.State.CONNECTED || state == ConnectionController.State.TRANSFERRING
                ? new Color(19, 118, 80) : new Color(43, 76, 122));
        if (state == ConnectionController.State.STOPPING) stop.setEnabled(false);
    }
    private void started(boolean upload) {
        uploading = upload; taskStarted = System.currentTimeMillis(); taskFailed = false; transferActive = true; taskResult = ""; lastTransferredBytes = 0;
        progress.setIndeterminate(true); progress.setString(text("正在准备传输…", "Preparing transfer…"));
        phase.setText(" "); file.setText(" "); cancelTransfer.setEnabled(true);
    }
    private void showProgress(TransferProgress value) {
        lastTransferredBytes = value.transferredBytes;
        if (value.totalBytes > 0) {
            progress.setIndeterminate(false);
            progress.setValue((int) Math.min(1000, value.transferredBytes * 1000.0 / value.totalBytes));
            progress.setString(size(value.transferredBytes) + " / " + size(value.totalBytes));
        } else {
            progress.setIndeterminate(transferActive);
            progress.setString(size(value.transferredBytes) + text(" 已完成（总量未预先统计）", " completed (total not pre-counted)"));
        }
        phase.setText(text("已完成文件：", "Completed files: ") + value.completedFiles
                + (value.totalFiles >= 0 ? " / " + value.totalFiles : "")
                + "   ·   " + duration(System.currentTimeMillis() - taskStarted));
        file.setText(value.currentPath == null ? " " : value.currentPath);
        file.setToolTipText(value.currentPath);
    }
    private void flushProgress() {
        TransferProgress value = latestProgress.getAndSet(null);
        if (value != null) showProgress(value);
    }
    private void flushLogs() {
        StringBuilder batch = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            String message = pendingLogs.poll();
            if (message == null) break;
            pendingLogCount.decrementAndGet();
            if (batch.length() > 0) batch.append('\n');
            batch.append(message);
        }
        int omitted = omittedLogs.getAndSet(0);
        if (omitted > 0) batch.append("\n").append(omitted).append(text(" 条重复诊断事件未显示；传输结果见摘要。", " diagnostic events omitted from the display; see the transfer summary."));
        if (batch.length() > 0) appendLog(batch.toString());
    }
    private void updateSpeeds(List<TrafficInfo> values) {
        for (TrafficInfo value : values) {
            for (int row = 0; row < channels.getRowCount(); row++) {
                if (value.iName.equals(channels.getValueAt(row, 0))) {
                    channels.setValueAt(size(value.uploadTraffic) + "/s", row, 1);
                    channels.setValueAt(size(value.downloadTraffic) + "/s", row, 2);
                    break;
                }
            }
        }
    }
    private void showSummary(TransferSummary summary) {
        lastTransferredBytes = summary.transferredBytes;
        taskFailed |= summary.failedFiles > 0 || summary.cancelled;
        taskResult = text("完成 ", "Completed ") + summary.completedFiles + text(" · 跳过 ", " · skipped ") + summary.skippedFiles
                + text(" · 失败 ", " · failed ") + summary.failedFiles
                + (summary.warnings.isEmpty() ? "" : text(" · 提醒 ", " · warnings ") + summary.warnings.size())
                + (summary.cancelled ? text(" · 已取消", " · cancelled") : "");
        phase.setText(taskResult);
        appendLog(taskResult);
        for (String failure : summary.failures) appendLog(failure);
        for (String warning : summary.warnings) appendLog(text("提醒：", "Warning: ") + warning);
    }
    private void completed(boolean upload, long bytes, long millis) {
        transferActive = false; cancelTransfer.setEnabled(false); progress.setIndeterminate(false);
        if (!taskFailed) progress.setValue(1000);
        String result = taskResult.isEmpty() ? taskFailed ? text("未完成", "Incomplete") : text("完成", "Completed") : taskResult;
        progress.setString(result);
        phase.setText(size(bytes) + " · " + duration(millis) + " · " + size(millis <= 0 ? 0 : (long) (bytes * 1000.0 / millis)) + "/s");
        addHistory(upload, result, bytes, millis);
        Toolkit.getDefaultToolkit().beep();
    }
    private void failed(String message) {
        taskFailed = true;
        appendLog(message);
        phase.setText(message);
        if (transferActive) {
            transferActive = false; progress.setIndeterminate(false); progress.setString(text("未完成", "Incomplete"));
            addHistory(uploading, taskResult.isEmpty() ? text("未完成", "Incomplete") : taskResult, lastTransferredBytes, System.currentTimeMillis() - taskStarted);
        }
        cancelTransfer.setEnabled(false);
    }
    private void addHistory(boolean upload, String result, long bytes, long millis) {
        String[] row = {LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                upload ? text("电脑 → 手机", "Computer → Phone") : text("手机 → 电脑", "Phone → Computer"),
                result, size(bytes), duration(millis)};
        historyRows.add(0, row); history.insertRow(0, row);
        while (historyRows.size() > 100) historyRows.remove(historyRows.size() - 1);
        while (history.getRowCount() > 100) history.removeRow(history.getRowCount() - 1);
        if (rememberHistory.isSelected()) {
            List<String[]> snapshot = new ArrayList<>(historyRows);
            utility.submit(() -> { try { historyStore.save(snapshot); } catch (Exception e) { ui(() -> appendLog("History: " + e.getMessage())); } });
        }
    }
    private void appendLog(String message) {
        if (message == null) return;
        if (!sessionSecret.isEmpty()) message = message.replace(sessionSecret, "[redacted]");
        logs.append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "  " + message + "\n");
        if (logs.getDocument().getLength() > 200000) logs.replaceRange("", 0, logs.getDocument().getLength() - 160000);
        logs.setCaretPosition(logs.getDocument().getLength());
    }
    private void saveLog() {
        flushLogs();
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("HybridFileXfer-diagnostics.txt"));
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        File output = chooser.getSelectedFile();
        if (output.exists() && JOptionPane.showConfirmDialog(frame, text("覆盖已有日志文件？", "Replace the existing log file?"),
                "HybridFileXfer", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        String content = logs.getText();
        utility.submit(() -> {
            try { Files.write(output.toPath(), content.getBytes(StandardCharsets.UTF_8)); ui(() -> appendLog(text("日志已保存。", "Log saved."))); }
            catch (Exception e) { ui(() -> showError(e)); }
        });
    }
    private void showError(Throwable error) {
        String message = ConnectionController.friendlyError(error);
        appendLog(message);
        JOptionPane.showMessageDialog(frame, message, text("请检查连接设置", "Check connection settings"), JOptionPane.WARNING_MESSAGE);
    }
    private void closeWindow() {
        if (transferActive && JOptionPane.showConfirmDialog(frame, text("当前传输将停止。关闭窗口？", "The active transfer will stop. Close the window?"),
                "HybridFileXfer", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        closing = true;
        AdbBridge adb = auxiliaryAdb; if (adb != null) adb.close();
        if (controller.isRunning()) controller.close(); else dispose();
    }
    private void dispose() {
        if (disposalStarted) return;
        disposalStarted = true;
        progressTimer.stop();
        pairing.setText(""); sessionSecret = "";
        // Drain queued local history writes before removing the last AWT window.
        utility.submit(() -> SwingUtilities.invokeLater(frame::dispose));
        utility.shutdown();
    }
    private void ui(Runnable action) { SwingUtilities.invokeLater(() -> { if (frame.isDisplayable() || !closing) action.run(); }); }

    /** Used by the visual smoke test without connecting to a real device. */
    public JFrame windowForTesting() { return frame; }
}
