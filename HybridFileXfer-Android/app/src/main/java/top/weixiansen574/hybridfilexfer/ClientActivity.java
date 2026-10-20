package top.weixiansen574.hybridfilexfer;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import java.util.LinkedHashMap;
import java.util.Map;
import top.weixiansen574.hybridfilexfer.core.Utils;

/** UI only: transport and root bindings belong to ClientSession. */
public class ClientActivity extends AppCompatActivity implements ClientSession.Listener {
    private ClientSession session;
    private TextView state, upload, download;
    private LinearLayout connections;
    private final Map<String, View> channelViews = new LinkedHashMap<>();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_client);
        state = findViewById(R.id.state);
        upload = findViewById(R.id.upload_speed);
        download = findViewById(R.id.download_speed);
        connections = findViewById(R.id.connections);
        session = ClientSession.get(this);
        session.attach(this);
        if (!session.active) {
            Bundle extras = getIntent().getExtras();
            if (extras == null || !extras.containsKey("pairing_secret")) {
                state.setText(R.string.no_active_session);
                return;
            }
            String secret = extras.getString("pairing_secret", "");
            getIntent().removeExtra("pairing_secret");
            session.start(extras.getInt("io_mode"), extras.getString("controller_ip"),
                    extras.getInt("server_port", 5740), extras.getString("home_dir"), secret);
        }
    }

    @Override public void onSessionChanged() {
        state.setText(session.state);
        long totalUpload = 0, totalDownload = 0;
        for (Map.Entry<String, ClientSession.Channel> entry : session.channels.entrySet()) {
            View item = channelViews.get(entry.getKey());
            if (item == null) {
                item = LayoutInflater.from(this).inflate(R.layout.item_net_interface_status, connections, false);
                ((TextView) item.findViewById(R.id.channel_name)).setText(entry.getKey());
                ((ImageView) item.findViewById(R.id.channel_icon)).setImageDrawable(
                        ContextCompat.getDrawable(this, NetCardIcon.matchIdForIName(entry.getKey())));
                connections.addView(item);
                channelViews.put(entry.getKey(), item);
            }
            ClientSession.Channel value = entry.getValue();
            ((TextView) item.findViewById(R.id.upload_speed)).setText(Utils.formatSpeed(value.uploadSpeed));
            ((TextView) item.findViewById(R.id.download_speed)).setText(Utils.formatSpeed(value.downloadSpeed));
            ((TextView) item.findViewById(R.id.txv_transfer_event)).setText(value.event);
            totalUpload += value.uploadSpeed;
            totalDownload += value.downloadSpeed;
        }
        upload.setText(Utils.formatSpeed(totalUpload));
        download.setText(Utils.formatSpeed(totalDownload));
        invalidateOptionsMenu();
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, R.string.ting_zhi_fu_wu).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }
    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            if (!session.active) { finish(); return true; }
            new AlertDialog.Builder(this).setMessage(R.string.confirm_stop_session)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.ok, (dialog, which) -> { session.stop(); finish(); }).show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
    @Override protected void onStart() {
        super.onStart();
        if (session != null) session.attach(this);
    }
    @Override protected void onStop() {
        if (session != null) session.detach(this);
        super.onStop();
    }

    @Override protected void onDestroy() {
        session.detach(this);
        super.onDestroy();
    }
}
