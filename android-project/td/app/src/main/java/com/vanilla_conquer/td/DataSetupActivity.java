package com.vanilla_conquer.td;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Game-data setup:
 *  - Official demo: open download page, then Choose folder
 *  - Choose local folder (SAF)
 *  - Optional Counterstrike / Aftermath (only enabled if MIX files present)
 */
public class DataSetupActivity extends Activity {
    private static final String TAG = "VanillaTD-Setup";
    private static final int REQUEST_DATA_TREE = 9001;
    private static final String PREFS = "vanilla_td";
    private static final String PREF_TREE_URI = "data_tree_uri";
    private static final String PREF_WANT_CS = "want_cs";
    private static final String PREF_WANT_AM = "want_am";
    private static final String PREF_SETUP_DONE = "setup_done";
    /** auto | fill | letterbox | stretch */
    private static final String PREF_DISPLAY_MODE = "display_mode";
    private static final String PREF_RENDER_SCALER = "render_scaler";
    private static final String PREF_RES_W = "res_w";
    private static final String PREF_RES_H = "res_h";
    /** auto | 1066x480 | 640x400 | 800x600 | native */
    private static final String PREF_RES_PRESET = "res_preset";
    

    private static final String DEMO_PAGE =
            "https://cncnet.org/command-and-conquer";

    private static final Set<String> CORE_WANTED = new HashSet<>(Arrays.asList(
            "conquer.mix", "main.mix", "local.mix", "conquer.mix",
            "hires1.mix", "lores1.mix",
            "keyboard.ini",
            "speech01.mix", "speech02.mix", "scores.mix", "scoresa.mix",
            "movies1.mix", "movies2.mix", "interior.mix"
    ));
    private static final Set<String> BLOCKED = new HashSet<>(Arrays.asList(
            "conquer.ini", "conquer.ini"
    ));

    private File dataDir;
    private File userDir;
    private TextView status;
    private ProgressBar progress;
    private CheckBox cbCS;
    private CheckBox cbAM;
    private Button btnDemo;
    private Button btnFolder;
    private Button btnContinue;
    private RadioGroup rgDisplay;
    private RadioGroup rgResolution;
    private RadioGroup rgScaler;
    private EditText etCustomW;
    private EditText etCustomH;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        dataDir = new File(getFilesDir(), "td");
        userDir = new File(getFilesDir(), "td-user");
        dataDir.mkdirs();
        userDir.mkdirs();

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean forceSetup = getIntent() != null
                && getIntent().getBooleanExtra("force_setup", false);

        if (!forceSetup && hasGameData() && prefs.getBoolean(PREF_SETUP_DONE, false)) {
            // Re-apply flags from what is actually on disk
            syncExpansionFlagsFromDisk();
            applyDisplayIniFromPrefs();
            launchGame();
            return;
        }

        setContentView(buildUi(prefs));
        refreshContinueState();
        updateFileStatus();
    }


    private void addRadio(RadioGroup group, String label, String tag, String selected) {
        RadioButton rb = new RadioButton(this);
        rb.setText(label);
        rb.setTextColor(Color.WHITE);
        rb.setTag(tag);
        rb.setId(View.generateViewId());
        group.addView(rb);
        if (tag.equals(selected)) group.check(rb.getId());
    }

    private String selectedTag(RadioGroup group, String fallback) {
        if (group == null) return fallback;
        int id = group.getCheckedRadioButtonId();
        if (id == -1) return fallback;
        View v = group.findViewById(id);
        if (v == null || v.getTag() == null) return fallback;
        return String.valueOf(v.getTag());
    }

    private View buildUi(SharedPreferences prefs) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFF121212);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(28), dp(24), dp(28));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("VanillaTD — Game Data");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Install Tiberian Dawn data from the freeware C&C download or your own game folder. "
                + "Covert Ops needs GENERAL.MIX / SC-W#### / Covert from Counterstrike / Aftermath.");
        sub.setTextColor(0xFFCCCCCC);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        sub.setPadding(0, dp(8), 0, dp(16));
        root.addView(sub);

        root.addView(sectionLabel("Expansions (optional)"));

        cbCS = new CheckBox(this);
        cbCS.setText("Covert Operations (optional)");
        cbCS.setTextColor(Color.WHITE);
        cbCS.setChecked(prefs.getBoolean(PREF_WANT_CS, false));
        root.addView(cbCS);

        cbAM = new CheckBox(this);
        cbAM.setText("Include all found .MIX files");
        cbAM.setTextColor(Color.WHITE);
        cbAM.setChecked(prefs.getBoolean(PREF_WANT_AM, false));
        root.addView(cbAM);

        TextView hint = new TextView(this);
        hint.setText("VanillaTD supports both expansions. Tick a box only if that MIX file "
                + "is in the folder you select. An Aftermath menu button with no missions "
                + "means SC-W#### / Covert is missing or incomplete.");
        hint.setTextColor(0xFF888888);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setPadding(0, dp(4), 0, dp(20));
        root.addView(hint);

        root.addView(sectionLabel("Data source"));

        btnDemo = primaryBtn("Get freeware C&C (opens download page)");
        btnDemo.setOnClickListener(v -> openDemoHelp());
        root.addView(btnDemo);

        btnFolder = secondaryBtn("Choose folder on this device");
        btnFolder.setOnClickListener(v -> pickFolder());
        root.addView(btnFolder);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
        plp.topMargin = dp(16);
        root.addView(progress, plp);

        status = new TextView(this);
        status.setTextColor(0xFFAAAAAA);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setPadding(0, dp(10), 0, dp(10));
        root.addView(status);


        root.addView(sectionLabel("Resolution"));
        rgResolution = new RadioGroup(this);
        rgResolution.setOrientation(RadioGroup.VERTICAL);
        String resPreset = prefs.getString(PREF_RES_PRESET, "auto");
        addRadio(rgResolution, "Auto (detect best for this device)", "auto", resPreset);
        addRadio(rgResolution, "640 × 400 (original)", "640x400", resPreset);
        addRadio(rgResolution, "640 × 480", "640x480", resPreset);
        addRadio(rgResolution, "800 × 600", "800x600", resPreset);
        addRadio(rgResolution, "1024 × 768", "1024x768", resPreset);
        addRadio(rgResolution, "1066 × 480", "1066x480", resPreset);
        addRadio(rgResolution, "1280 × 720", "1280x720", resPreset);
        addRadio(rgResolution, "Custom…", "custom", resPreset);
        root.addView(rgResolution);

        LinearLayout customRow = new LinearLayout(this);
        customRow.setOrientation(LinearLayout.HORIZONTAL);
        customRow.setPadding(0, dp(4), 0, dp(8));
        etCustomW = new EditText(this);
        etCustomW.setHint("Width");
        etCustomW.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etCustomW.setTextColor(Color.WHITE);
        etCustomW.setHintTextColor(0xFF888888);
        etCustomW.setText(String.valueOf(prefs.getInt(PREF_RES_W, 1066) > 0 ? prefs.getInt(PREF_RES_W, 1066) : 1066));
        etCustomH = new EditText(this);
        etCustomH.setHint("Height");
        etCustomH.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etCustomH.setTextColor(Color.WHITE);
        etCustomH.setHintTextColor(0xFF888888);
        etCustomH.setText(String.valueOf(prefs.getInt(PREF_RES_H, 480) > 0 ? prefs.getInt(PREF_RES_H, 480) : 480));
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        half.setMargins(0, 0, dp(8), 0);
        customRow.addView(etCustomW, half);
        customRow.addView(etCustomH, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(customRow);
        TextView customHint = new TextView(this);
        customHint.setText("Custom is used only when “Custom…” is selected. Engine accepts free Width×Height.");
        customHint.setTextColor(0xFF888888);
        customHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        customHint.setPadding(0, 0, 0, dp(12));
        root.addView(customHint);


        root.addView(sectionLabel("Render mode"));
        rgDisplay = new RadioGroup(this);
        rgDisplay.setOrientation(RadioGroup.VERTICAL);
        String disp = prefs.getString(PREF_DISPLAY_MODE, "fill");
        addRadio(rgDisplay, "Fill screen (no letterbox)", "fill", disp);
        addRadio(rgDisplay, "Letterbox (keep aspect)", "letterbox", disp);
        addRadio(rgDisplay, "Stretch", "stretch", disp);
        root.addView(rgDisplay);

        root.addView(sectionLabel("Scaler"));
        rgScaler = new RadioGroup(this);
        rgScaler.setOrientation(RadioGroup.VERTICAL);
        String sc = prefs.getString(PREF_RENDER_SCALER, "nearest");
        addRadio(rgScaler, "Nearest (sharp pixels)", "nearest", sc);
        addRadio(rgScaler, "Linear (smooth)", "linear", sc);
        root.addView(rgScaler);

        btnContinue = primaryBtn("Continue to game");
        btnContinue.setOnClickListener(v -> {
            if (!hasGameData()) {
                Toast.makeText(this, "Install game data first", Toast.LENGTH_SHORT).show();
                return;
            }
            // Only enable expansions that are really present
            boolean cs = cbCS.isChecked() && hasFile("general.mix");
            boolean am = cbAM.isChecked() && hasFile("updatec.mix");
            if (cbAM.isChecked() && !hasFile("updatec.mix")) {
                Toast.makeText(this,
                        "Aftermath checked but SC-W#### / Covert not found — menu button would be empty",
                        Toast.LENGTH_LONG).show();
            }
            if (cbCS.isChecked() && !hasFile("general.mix")) {
                Toast.makeText(this,
                        "Counterstrike checked but GENERAL.MIX not found",
                        Toast.LENGTH_LONG).show();
            }
            // Strip mixes user does not want
            if (!cbCS.isChecked()) {
                deleteIfExists("GENERAL.MIX");
                deleteIfExists("general.mix");
                cs = false;
            }
            if (!cbAM.isChecked()) {
                deleteIfExists("SC-W#### / Covert");
                deleteIfExists("updatec.mix");
                am = false;
            }
            String rp = selectedTag(rgResolution, "auto");
            String mode = selectedTag(rgDisplay, "fill");
            String scaler = selectedTag(rgScaler, "nearest");
            int rw, rh;
            if ("640x400".equals(rp)) { rw = 640; rh = 400; }
            else if ("640x480".equals(rp)) { rw = 640; rh = 480; }
            else if ("800x600".equals(rp)) { rw = 800; rh = 600; }
            else if ("1024x768".equals(rp)) { rw = 1024; rh = 768; }
            else if ("1066x480".equals(rp)) { rw = 1066; rh = 480; }
            else if ("1280x720".equals(rp)) { rw = 1280; rh = 720; }
            else if ("custom".equals(rp)) {
                try { rw = Integer.parseInt(etCustomW.getText().toString().trim()); }
                catch (Exception e) { rw = 1066; }
                try { rh = Integer.parseInt(etCustomH.getText().toString().trim()); }
                catch (Exception e) { rh = 480; }
                if (rw < 320) rw = 320;
                if (rh < 200) rh = 200;
                if (rw > 3840) rw = 3840;
                if (rh > 2160) rh = 2160;
            } else { rp = "auto"; rw = -1; rh = -1; }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(PREF_WANT_CS, cbCS.isChecked())
                    .putBoolean(PREF_WANT_AM, cbAM.isChecked())
                    .putBoolean(PREF_SETUP_DONE, true)
                    .putString(PREF_RES_PRESET, rp)
                    .putString(PREF_DISPLAY_MODE, mode)
                    .putString(PREF_RENDER_SCALER, scaler)
                    .putInt(PREF_RES_W, rw)
                    .putInt(PREF_RES_H, rh)
                    .apply();
            applyExpansionIni(cs, am);
            applyDisplayIniFromPrefs();
            launchGame();
        });
        root.addView(btnContinue);

        Button changeDisp = secondaryBtn("Save display & expansions only");
        changeDisp.setOnClickListener(v -> {
            if (!hasGameData()) {
                android.widget.Toast.makeText(this, "Install game data first", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            btnContinue.performClick();
        });
        root.addView(changeDisp);

        Button reset = secondaryBtn("Clear installed data");
        reset.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Clear data?")
                .setMessage("Deletes copied MIX files from app storage.")
                .setPositiveButton("Clear", (d, w) -> {
                    deleteRecursive(dataDir);
                    dataDir.mkdirs();
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putBoolean(PREF_SETUP_DONE, false).apply();
                    updateFileStatus();
                    refreshContinueState();
                })
                .setNegativeButton("Cancel", null)
                .show());
        root.addView(reset);

        return scroll;
    }

    private void openDemoHelp() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(DEMO_PAGE)));
        } catch (Exception e) {
            Toast.makeText(this, "Could not open browser", Toast.LENGTH_SHORT).show();
        }
        new AlertDialog.Builder(this)
                .setTitle("Install official demo")
                .setMessage("1. Download C&C freeware files from the page that opened\n"
                        + "2. Extract it with the Files app\n"
                        + "3. Tap Choose folder and select the folder that contains the .MIX files\n\n"
                        + "Note: the demo does not include Counterstrike/Aftermath expansions.")
                .setPositiveButton("Choose folder", (d, w) -> pickFolder())
                .setNegativeButton("OK", null)
                .show();
    }

    private void updateFileStatus() {
        if (status == null) return;
        if (!hasGameData()) {
            status.setText("No game data yet. Use demo page or Choose folder.");
            return;
        }
        StringBuilder sb = new StringBuilder("Core data OK.");
        sb.append(hasFile("general.mix") ? " GENERAL.MIX found." : " No GENERAL.MIX.");
        sb.append(hasFile("updatec.mix") ? " SC-W#### / Covert found." : " No SC-W#### / Covert.");
        status.setText(sb.toString());
    }

    private void refreshContinueState() {
        if (btnContinue != null) {
            btnContinue.setEnabled(hasGameData() && !busy);
            btnContinue.setAlpha(hasGameData() && !busy ? 1f : 0.5f);
        }
        if (btnDemo != null) btnDemo.setEnabled(!busy);
        if (btnFolder != null) btnFolder.setEnabled(!busy);
    }

    private void syncExpansionFlagsFromDisk() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean wantCS = prefs.getBoolean(PREF_WANT_CS, false);
        boolean wantAM = prefs.getBoolean(PREF_WANT_AM, false);
        boolean cs = wantCS && hasFile("general.mix");
        boolean am = wantAM && hasFile("updatec.mix");
        applyExpansionIni(cs, am);
    }

    /** Write expansion flags — engine uses these for menu buttons. */
    private void applyExpansionIni(boolean cs, boolean am) {
        try {
            File ini = new File(userDir, "conquer.ini");
            String text = "";
            if (ini.exists()) {
                text = new String(readAll(ini), "UTF-8");
            }
            if (!text.contains("[Expansions]")) {
                text = text + "\n[Expansions]\n"
                        + "CovertOpsEnabled=" + (cs ? "yes" : "no") + "\n"
                        + "CovertOpsEnabled=" + (am ? "yes" : "no") + "\n";
            } else {
                text = upsertIni(text, "Expansions", "CovertOpsEnabled", cs ? "yes" : "no");
                text = upsertIni(text, "Expansions", "CovertOpsEnabled", am ? "yes" : "no");
            }
            // Always force PlayIntro off here too
            if (!text.contains("[Intro]")) {
                text = text + "\n[Intro]\nPlayIntro=false\n";
            } else {
                text = upsertIni(text, "Intro", "PlayIntro", "false");
            }
            FileOutputStream fos = new FileOutputStream(ini);
            fos.write(text.getBytes("UTF-8"));
            fos.close();
            Log.i(TAG, "Expansions CS=" + cs + " AM=" + am);
        } catch (Exception e) {
            Log.w(TAG, "ini", e);
        }
    }

    private void applyDisplayIniFromPrefs() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String preset = prefs.getString(PREF_RES_PRESET, "auto");
        if ("auto".equals(preset) || prefs.getInt(PREF_RES_W, -1) < 0) {
            applyDisplayIni("auto");
        } else {
            applyDisplayIni(prefs.getString(PREF_DISPLAY_MODE, "fill"));
        }
    }

    /** Apply video settings. mode="auto" uses fork device detection. */
    private void applyDisplayIni(String mode) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenW = Math.max(dm.widthPixels, dm.heightPixels);
        int screenH = Math.min(dm.widthPixels, dm.heightPixels);
        float screenRatio = (float) screenW / screenH;

        int width;
        int height;
        boolean boxing;
        String scaler = prefs.getString(PREF_RENDER_SCALER, "nearest");
        String render = prefs.getString(PREF_DISPLAY_MODE, "fill");

        if ("auto".equals(mode)) {
            // Fork logic: 16:10 → integer scale of 640×400; else native
            if (Math.abs(screenRatio - 1.6f) < 0.2f) {
                int scale = Math.min(screenW / 640, screenH / 400);
                if (scale < 1) scale = 1;
                width = 640 * scale;
                height = 400 * scale;
                boxing = false;
                Log.i(TAG, "auto 16:10 → " + scale + "x (" + width + "x" + height + ")");
            } else {
                width = 0;
                height = 0;
                boxing = false;
                Log.i(TAG, "auto ultrawide " + screenRatio + " → native");
            }
        } else {
            width = prefs.getInt(PREF_RES_W, 1066);
            height = prefs.getInt(PREF_RES_H, 480);
            if (width < 0) width = 1066;
            if (height < 0) height = 480;
            boxing = "letterbox".equals(render);
            // stretch/fill: no boxing
            Log.i(TAG, "manual res " + width + "x" + height + " render=" + render);
        }

        String aspect = (width > 0 && height > 0) ? (width + ":" + height) : "16:10";
        try {
            File ini = new File(userDir, "conquer.ini");
            String text = ini.exists() ? new String(readAll(ini), "UTF-8") : "";
            text = upsertIni(text, "Video", "Width", String.valueOf(width));
            text = upsertIni(text, "Video", "Height", String.valueOf(height));
            text = upsertIni(text, "Video", "Windowed", "no");
            text = upsertIni(text, "Video", "Boxing", boxing ? "yes" : "no");
            text = upsertIni(text, "Video", "BoxingAspectRatio", aspect);
            text = upsertIni(text, "Video", "Scaler", scaler);
            text = upsertIni(text, "Video", "HardwareCursor", "no");
            text = upsertIni(text, "Video", "FrameLimit", "60");
            text = upsertIni(text, "Video", "DOSMode", "no");
            text = upsertIni(text, "Mouse", "RawInput", "no");
            FileOutputStream fos = new FileOutputStream(ini);
            fos.write(text.getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            Log.w(TAG, "display ini", e);
        }
    }

    private static String upsertIni(String text, String section, String key, String value) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        boolean inSec = false;
        boolean wrote = false;
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("[") && t.endsWith("]")) {
                if (inSec && !wrote) {
                    out.append(key).append('=').append(value).append('\n');
                    wrote = true;
                }
                inSec = t.equalsIgnoreCase("[" + section + "]");
                out.append(line).append('\n');
                continue;
            }
            if (inSec) {
                int eq = t.indexOf('=');
                if (eq > 0 && t.substring(0, eq).trim().equalsIgnoreCase(key)) {
                    out.append(key).append('=').append(value).append('\n');
                    wrote = true;
                    continue;
                }
            }
            out.append(line).append('\n');
        }
        if (inSec && !wrote) {
            out.append(key).append('=').append(value).append('\n');
        }
        return out.toString();
    }

    private void pickFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_DATA_TREE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_DATA_TREE || resultCode != RESULT_OK || data == null) return;
        Uri tree = data.getData();
        if (tree == null) return;
        final int takeFlags = data.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(tree, takeFlags);
        } catch (Exception ignored) {}
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PREF_TREE_URI, tree.toString()).apply();

        status.setText("Copying files…");
        progress.setVisibility(View.VISIBLE);
        busy = true;
        refreshContinueState();

        final boolean wantCS = cbCS.isChecked();
        final boolean wantAM = cbAM.isChecked();
        new Thread(() -> {
            int n = walkAndCopy(tree, DocumentsContract.buildChildDocumentsUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree)), 0, wantCS, wantAM);
            ui.post(() -> {
                busy = false;
                progress.setVisibility(View.GONE);
                updateFileStatus();
                status.setText(status.getText() + " Copied " + n + " files.");
                // Auto-tick boxes if files appeared
                if (hasFile("general.mix")) cbCS.setChecked(true);
                if (hasFile("updatec.mix")) cbAM.setChecked(true);
                boolean cs = cbCS.isChecked() && hasFile("general.mix");
                boolean am = cbAM.isChecked() && hasFile("updatec.mix");
                applyExpansionIni(cs, am);
                refreshContinueState();
            });
        }, "copy-tree").start();
    }

    private int walkAndCopy(Uri treeUri, Uri childrenUri, int depth, boolean wantCS, boolean wantAM) {
        if (depth > 8) return 0;
        int count = 0;
        Cursor c = null;
        try {
            c = getContentResolver().query(childrenUri,
                    new String[]{
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE
                    }, null, null, null);
            if (c == null) return 0;
            while (c.moveToNext()) {
                String id = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                if (name == null) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    Uri sub = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, id);
                    count += walkAndCopy(treeUri, sub, depth + 1, wantCS, wantAM);
                } else {
                    String lower = name.toLowerCase(Locale.US);
                    if (BLOCKED.contains(lower)) continue;
                    // Always copy expansion mixes if present so user can enable later;
                    // prune on Continue if unchecked.
                    boolean want = CORE_WANTED.contains(lower)
                            || lower.equals("general.mix")
                            || lower.equals("updatec.mix")
                            || lower.endsWith(".mix")
                            || lower.endsWith(".aud")
                            || lower.endsWith(".vqa")
                            || lower.endsWith(".pkt")
                            || (lower.endsWith(".ini") && !BLOCKED.contains(lower));
                    if (!want) continue;
                    Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                    if (copyUri(fileUri, new File(dataDir, name))) {
                        count++;
                        Log.i(TAG, "Copied " + name);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "walk", e);
        } finally {
            if (c != null) c.close();
        }
        return count;
    }

    private boolean copyUri(Uri src, File dest) {
        try (InputStream in = getContentResolver().openInputStream(src);
             OutputStream out = new FileOutputStream(dest)) {
            if (in == null) return false;
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "copy " + dest.getName(), e);
            return false;
        }
    }

    private boolean hasGameData() {
        return hasFile("conquer.mix") || hasFile("main.mix") || hasFile("conquer.mix");
    }

    private boolean hasFile(String name) {
        // Case-insensitive search in dataDir
        File direct = new File(dataDir, name);
        if (direct.exists()) return true;
        File[] list = dataDir.listFiles();
        if (list == null) return false;
        String want = name.toLowerCase(Locale.US);
        for (File f : list) {
            if (f.getName().toLowerCase(Locale.US).equals(want)) return true;
        }
        return false;
    }

    private void deleteIfExists(String name) {
        File f = new File(dataDir, name);
        if (f.exists()) {
            // noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        File[] list = dataDir.listFiles();
        if (list == null) return;
        String want = name.toLowerCase(Locale.US);
        for (File x : list) {
            if (x.getName().toLowerCase(Locale.US).equals(want)) {
                // noinspection ResultOfMethodCallIgnored
                x.delete();
            }
        }
    }

    private void launchGame() {
        File badIni = new File(dataDir, "conquer.ini");
        if (badIni.exists()) {
            // noinspection ResultOfMethodCallIgnored
            badIni.delete();
        }
        Intent i = new Intent(this, VanillaTDActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        finish();
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteRecursive(k);
        }
        // noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static byte[] readAll(File f) throws Exception {
        byte[] buf = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
        }
        return buf;
    }

    private TextView sectionLabel(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(0xFF90CAF9);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(8), 0, dp(8));
        return tv;
    }

    private Button primaryBtn(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1565C0);
        bg.setCornerRadius(dp(8));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private Button secondaryBtn(String label) {
        Button b = primaryBtn(label);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF2A2A2A);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), 0xFF555555);
        b.setBackground(bg);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
