package ru.antiyotazapret.yotatetherttl.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.snackbar.Snackbar;

import net.orange_box.storebox.StoreBox;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.DateFormat;
import java.util.Date;
import java.util.Set;

import ru.antiyotazapret.yotatetherttl.Preferences;
import ru.antiyotazapret.yotatetherttl.R;
import ru.antiyotazapret.yotatetherttl.TtlApplication;
import ru.antiyotazapret.yotatetherttl.services.ChangeTask;
import ru.antiyotazapret.yotatetherttl.services.CheckDeviceTask;
import ru.antiyotazapret.yotatetherttl.services.Task;
import ru.antiyotazapret.yotatetherttl.services.UpdateBlockListTask;
import ru.antiyotazapret.yotatetherttl.services.UpdateTtlTask;

public class MainActivity extends AppCompatActivity {

    Toolbar toolbar;
    TextView currentTtlView;
    SwipeRefreshLayout swipeRefreshLayout;
    TextView ttlScopeTextView;
    TextView refreshedAtTextView;

    private Preferences preferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        preferences = StoreBox.create(this, Preferences.class);

        setContentView(R.layout.main);
        toolbar = findViewById(R.id.toolbar);
        currentTtlView = findViewById(R.id.current_ttl_view);
        swipeRefreshLayout = findViewById(R.id.swipe_refresh);
        ttlScopeTextView = findViewById(R.id.current_ttl_scope);
        refreshedAtTextView = findViewById(R.id.refreshed_at);

        Button applyTtlButton = findViewById(R.id.apply_ttl_method_button);
        Button refreshListButton = findViewById(R.id.refresh_list_button);
        Button tetheringSettingsButton = findViewById(R.id.open_tethering_settings_button);

        applyTtlButton.setOnClickListener(v -> ttlClicked());
        refreshListButton.setOnClickListener(v -> refreshClicked());
        tetheringSettingsButton.setOnClickListener(v -> openTetheringSettingsButton());

        String version = getAppVersion();

        toolbar.setTitle(R.string.app_name);
        toolbar.setSubtitle(getString(R.string.main_version, version));
        setSupportActionBar(toolbar);

        //Настраиваем выполнение OnRefreshListener для activity:
        swipeRefreshLayout.setOnRefreshListener(new RefreshListener());

        // Настраиваем цветовую тему значка обновления:
        swipeRefreshLayout.setColorSchemeResources(
                R.color.light_blue,
                R.color.middle_blue,
                R.color.deep_blue);

        new CheckDeviceTask().attach(new Task.OnResult<CheckDeviceTask.DeviceCheckResult>() {
            @Override
            public void onResult(CheckDeviceTask.DeviceCheckResult r) {
                if (!r.hasRoot) {
                    createDialog(R.string.root_no_root_rights, R.string.root_no_root_rights_message, R.string.root_exit, false);
                }

                if (!r.hasIptables) {
                    createDialog(R.string.root_no_iptables, R.string.root_no_iptables_message, R.string.root_ok, true);
                }

                updateTtl();
                updateTime();
            }

            @Override
            public void onError(Exception e) {
                createDialog(R.string.root_could_not_check, R.string.root_could_not_check_message, R.string.root_exit, false);
            }
        }).runInBackground(null);

    }

    private void createDialog(int title, int message, int button, final boolean cancelable) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(button,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                if (!cancelable) {
                                    MainActivity.this.finish();
                                }
                            }
                        })
                .setCancelable(cancelable)
                .create()
                .show();
    }

    private String getAppVersion() {
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            return packageInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.action_4pda) {
            Uri uri = Uri.parse(getString(R.string.app_web_address));
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            startActivity(intent);
            return true;
        }

        if (itemId == R.id.action_settings) {
            Intent settings = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(settings);
            return true;
        }

        return false;
    }

    /**
     * Событие нажатия кнопки задания TTL
     */
    void ttlClicked() {
        applyTtl();
    }

    private void applyTtl() {

        new ChangeTask().attach(new Task.OnResult<Void>() {
            @Override
            public void onResult(Void r) {
                updateTtl();
                makeSnackbar(R.string.toast_ttl_applied);
            }

            @Override
            public void onError(Exception e) {
                makeSnackbar(R.string.toast_fatal_error_applying_ttl);
            }
        }).execute(new ChangeTask.ChangeTaskParameters(preferences, this));
    }

    void makeSnackbar(int resid) {
        Snackbar.make(swipeRefreshLayout, getResources().getText(resid), Snackbar.LENGTH_LONG).show();
    }

    void refreshClicked() {

        swipeRefreshLayout.setRefreshing(true);
        new UpdateBlockListTask().attach(new Task.OnResult<Set<String>>() {
            @Override
            public void onResult(Set<String> r) {
                preferences.setBans(r);
                preferences.setBansUpdated(System.currentTimeMillis());
                updateTime();
                makeSnackbar(R.string.toast_refreshed);
                swipeRefreshLayout.setRefreshing(false);
            }

            @Override
            public void onError(Exception e) {
                makeSnackbar(R.string.toast_error_refreshing);
                swipeRefreshLayout.setRefreshing(false);
            }
        }).execute(preferences.getBanlistURL());
    }

    /**
     * Открытие настроек тетеринга
     */
    void openTetheringSettingsButton() {
        Intent tetherSettings = new Intent();
        tetherSettings.setClassName("com.android.settings", "com.android.settings.TetherSettings");
        startActivity(tetherSettings);
    }

    private void updateTtl() {

        new UpdateTtlTask().attach(new Task.OnResult<UpdateTtlTask.TtlStatus>() {
            @Override
            public void onResult(UpdateTtlTask.TtlStatus ttlStatus) {
                if (ttlStatus == null) {
                    currentTtlView.setText("?");
                    return;
                }
                if (ttlStatus.forced) {
                    ttlScopeTextView.setText(getResources().getText(R.string.main_ttl_iptables));
                } else if (ttlStatus.workaround) {
                    ttlScopeTextView.setText(getResources().getText(R.string.main_ttl_iptables_workaround));
                } else {
                    ttlScopeTextView.setText(getResources().getText(R.string.main_ttl_this_device));
                }
                currentTtlView.setText(String.valueOf(ttlStatus.ttl));
                swipeRefreshLayout.setRefreshing(false);
            }

            @Override
            public void onError(Exception e) {
                TtlApplication.Loge(e.toString());
                swipeRefreshLayout.setRefreshing(false);
            }
        }).runInBackground(new Object());

    }

    public void updateTime() {
        long time = preferences.getBansUpdated();

        if (time == 0) {
            refreshedAtTextView.setText(R.string.never);
        } else {
            DateFormat format = DateFormat.getDateInstance();
            refreshedAtTextView.setText(format.format(new Date(time)));
        }

    }


    private class RefreshListener implements SwipeRefreshLayout.OnRefreshListener {
        @Override
        public void onRefresh() {
            updateTtl();
        }
    }

}
