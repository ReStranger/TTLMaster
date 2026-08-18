package ru.antiyotazapret.yotatetherttl.ui;

import android.app.Activity;
import android.os.Bundle;
import android.util.TypedValue;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.FragmentManager;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import ru.antiyotazapret.yotatetherttl.R;

@SuppressWarnings("ALL")
public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.settings);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        ViewCompat.setOnApplyWindowInsetsListener(toolbar, (v, insets) -> {
            v.setPadding(0, insets.getInsets(WindowInsetsCompat.Type.systemBars()).top, 0, 0);
            return insets;
        });
        toolbar.setTitle(R.string.action_settings);
        toolbar.setClickable(true);
        toolbar.setNavigationIcon(getResIdFromAttribute(this));
        toolbar.setNavigationOnClickListener(v -> finish());

        FragmentManager fragmentManager = getSupportFragmentManager();
        if (fragmentManager.findFragmentById(R.id.settings_container) == null) {
            fragmentManager.beginTransaction()
                    .replace(R.id.settings_container, new SettingsFragment())
                    .commit();
        }

    }

    private static int getResIdFromAttribute(final Activity activity) {
        int[] attrs = new int[] { androidx.appcompat.R.attr.homeAsUpIndicator };
        if (attrs[0] == 0) {
            return 0;
        }
        final TypedValue typedvalueattr = new TypedValue();
        activity.getTheme().resolveAttribute(androidx.appcompat.R.attr.homeAsUpIndicator, typedvalueattr, true);
        return typedvalueattr.resourceId;
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preferences, rootKey);

            Preference restartPreference = findPreference(getString(R.string.prefs_misc_restart_key));
            if (restartPreference != null) {
                restartPreference.setOnPreferenceClickListener(preference -> {
                    requireActivity().finishAffinity();
                    System.exit(1);
                    return true;
                });
            }
        }
    }

}
