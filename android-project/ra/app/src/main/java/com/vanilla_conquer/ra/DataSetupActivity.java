package com.vanilla_conquer.ra;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
    private static final String TAG = "VanillaRA-Setup";
    private static final int REQUEST_DATA_TREE = 9001;
    private static final String PREFS = "vanilla_ra";
    private static final String PREF_TREE_URI = "data_tree_uri";
    private static final String PREF_WANT_CS = "want_cs";
    private static final String PREF_WANT_AM = "want_am";
    private static final String PREF_SETUP_DONE = "setup_done";
    /** stretch | fit1610 | fit43 | pixel */
    private static final String PREF_DISPLAY_MODE = "display_mode";
    private static final int BASE_WIDTH = 640;
    private static final int BASE_HEIGHT = 400;
    

    private static final String DEMO_PAGE =
            "https://www.moddb.com/games/cc-red-alert/downloads/command-conquer-red-alert-demo";

    private static final Set<String> CORE_WANTED = new HashSet<>(Arrays.asList(
            "redalert.mix", "main.mix", "local.mix", "conquer.mix",
            "hires1.mix", "lores1.mix",
            "keyboard.ini",
            "speech01.mix", "speech02.mix", "scores.mix", "scoresa.mix",
            "movies1.mix", "movies2.mix", "interior.mix"
    ));
    private static final Set<String> BLOCKED = new HashSet<>(Arrays.asList(
            "redalert.ini", "conquer.ini"
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
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        dataDir = new File(getFilesDir(), "ra");
        userDir = new File(getFilesDir(), "ra-user");
        dataDir.mkdirs();
        userDir.mkdirs();

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean forceSetup = getIntent() != null
                && getIntent().getBooleanExtra("force_setup", false);

        if (!forceSetup && hasGameData() && prefs.getBoolean(PREF_SETUP_DONE, false)) {
            // Re-apply flags from what is actually on disk
            syncExpansionFlagsFromDisk();
            applyDisplayIni("pixel_fill");
            launchGame();
            return;
        }

        View root = buildUi(prefs);
        setContentView(root);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom, left, right;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = bars.top; bottom = bars.bottom; left = bars.left; right = bars.right;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
                left = insets.getSystemWindowInsetLeft();
                right = insets.getSystemWindowInsetRight();
            }
            v.setPadding(left, top, right, bottom);
            return insets;
        });
        root.requestApplyInsets();
        refreshContinueState();
        updateFileStatus();
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
        title.setText("VanillaRA — Game Data");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Install Red Alert data from the official demo or your own game folder. "
                + "Expansions need EXPAND.MIX / EXPAND2.MIX from Counterstrike / Aftermath.");
        sub.setTextColor(0xFFCCCCCC);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        sub.setPadding(0, dp(8), 0, dp(16));
        root.addView(sub);

        root.addView(sectionLabel("Expansions (optional)"));

        cbCS = new CheckBox(this);
        cbCS.setText("Counterstrike — needs EXPAND.MIX");
        cbCS.setTextColor(Color.WHITE);
        cbCS.setChecked(prefs.getBoolean(PREF_WANT_CS, false));
        root.addView(cbCS);

        cbAM = new CheckBox(this);
        cbAM.setText("Aftermath — needs EXPAND2.MIX");
        cbAM.setTextColor(Color.WHITE);
        cbAM.setChecked(prefs.getBoolean(PREF_WANT_AM, false));
        root.addView(cbAM);

        TextView hint = new TextView(this);
        hint.setText("VanillaRA supports both expansions. Tick a box only if that MIX file "
                + "is in the folder you select. An Aftermath menu button with no missions "
                + "means EXPAND2.MIX is missing or incomplete.");
        hint.setTextColor(0xFF888888);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setPadding(0, dp(4), 0, dp(20));
        root.addView(hint);

        root.addView(sectionLabel("Data source"));

        btnDemo = primaryBtn("Download & install official demo");
        btnDemo.setOnClickListener(v -> downloadAndInstallDemo());
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

        root.addView(sectionLabel("Community Map Packs"));
        Button btnMaps = secondaryBtn("Browse & install map packs");
        btnMaps.setOnClickListener(v ->
                startActivity(new Intent(this, MapPacksActivity.class)));
        root.addView(btnMaps);

        btnContinue = primaryBtn("Continue to game");
        btnContinue.setOnClickListener(v -> {
            if (!hasGameData()) {
                Toast.makeText(this, "Install game data first", Toast.LENGTH_SHORT).show();
                return;
            }
            // Only enable expansions that are really present
            boolean cs = cbCS.isChecked() && hasFile("expand.mix");
            boolean am = cbAM.isChecked() && hasFile("expand2.mix");
            if (cbAM.isChecked() && !hasFile("expand2.mix")) {
                Toast.makeText(this,
                        "Aftermath checked but EXPAND2.MIX not found — menu button would be empty",
                        Toast.LENGTH_LONG).show();
            }
            if (cbCS.isChecked() && !hasFile("expand.mix")) {
                Toast.makeText(this,
                        "Counterstrike checked but EXPAND.MIX not found",
                        Toast.LENGTH_LONG).show();
            }
            // Strip mixes user does not want
            if (!cbCS.isChecked()) {
                deleteIfExists("EXPAND.MIX");
                deleteIfExists("expand.mix");
                cs = false;
            }
            if (!cbAM.isChecked()) {
                deleteIfExists("EXPAND2.MIX");
                deleteIfExists("expand2.mix");
                am = false;
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(PREF_WANT_CS, cbCS.isChecked())
                    .putBoolean(PREF_WANT_AM, cbAM.isChecked())
                    .putBoolean(PREF_SETUP_DONE, true)
                    .putString(PREF_DISPLAY_MODE, "pixel_fill")
                    .apply();
            applyExpansionIni(cs, am);
            applyDisplayIni("auto");
            launchGame();
        });
        root.addView(btnContinue);


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

    private void downloadAndInstallDemo() {
        if (busy) return;
        busy = true;
        refreshContinueState();
        progress.setIndeterminate(true);
        progress.setVisibility(View.VISIBLE);
        status.setText("Finding download link…");

        new Thread(() -> {
            File zipFile = new File(getCacheDir(), "ra1demo.zip");
            try {
                // Clean up any stale partial download
                if (zipFile.exists()) zipFile.delete();

                // Step 1: Fetch demo page
                String demoPage = httpGet(
                    "https://www.moddb.com/games/cc-red-alert/downloads/command-conquer-red-alert-demo");

                // Try multiple patterns for the /start/ link
                String startPath = null;
                String[][] startPatterns = {
                    {"href=[\"'](/start/[^\"']+)[\"']", "1"},   // href="/start/123"
                    {"href=[\"'](/downloads/start/[^\"']+)[\"']", "1"}, // alternate path
                    {"(/start/\\d+[^\"'\\s<>]*)", "1"},          // bare path
                    {"url=[\"'](/start/[^\"']+)[\"']", "1"},    // in JS
                };
                for (String[] pat : startPatterns) {
                    Matcher m = Pattern.compile(pat[0], Pattern.CASE_INSENSITIVE).matcher(demoPage);
                    if (m.find()) {
                        startPath = m.group(Integer.parseInt(pat[1]));
                        Log.d(TAG, "Start link matched: " + startPath);
                        break;
                    }
                }
                if (startPath == null) {
                    // Last resort: brute-force search for /start/ in the HTML
                    int idx = demoPage.indexOf("/start/");
                    if (idx > 0) {
                        int end = idx + 7;
                        while (end < demoPage.length() && !"\"' <>".contains(String.valueOf(demoPage.charAt(end)))) {
                            end++;
                        }
                        startPath = demoPage.substring(idx, end);
                        Log.d(TAG, "Brute-force start link: " + startPath);
                    }
                }
                if (startPath == null) {
                    throw new IOException("Demo start link not found on ModDB page");
                }
                if (!startPath.startsWith("/")) startPath = "/" + startPath;

                // Step 2: Scrape redirect page for mirror URL
                ui.post(() -> status.setText("Resolving mirror…"));
                String downloadPage = httpGet("https://www.moddb.com" + startPath);

                String mirrorUrl = null;
                String[][] mirrorPatterns = {
                    {"(https?://[^\"'\\s]*mirror[^\"'\\s]*)", "1"},
                    {"(https?://[^\"'\\s]*\\.zip[^\"'\\s]*)", "1"},
                    {"href=[\"'](https?://[^\"']+\\.zip)[\"']", "1"},
                    {"href=[\"'](https?://[^\"']*/download[^\"']*)[\"']", "1"},
                };
                for (String[] pat : mirrorPatterns) {
                    Matcher m = Pattern.compile(pat[0], Pattern.CASE_INSENSITIVE).matcher(downloadPage);
                    if (m.find()) {
                        mirrorUrl = m.group(Integer.parseInt(pat[1]));
                        Log.d(TAG, "Mirror matched: " + mirrorUrl);
                        break;
                    }
                }
                if (mirrorUrl == null) {
                    // Brute-force: look for any .zip URL
                    Matcher m = Pattern.compile("https?://[^\"'\\s<>]+\\.zip").matcher(downloadPage);
                    if (m.find()) {
                        mirrorUrl = m.group();
                        Log.d(TAG, "Brute-force mirror: " + mirrorUrl);
                    }
                }
                if (mirrorUrl == null) {
                    throw new IOException("Mirror link not found");
                }

                // Step 3: Download the zip
                ui.post(() -> status.setText("Downloading demo (~25 MB)…"));
                downloadFile(mirrorUrl, zipFile);

                // Step 4: Extract to temp dir
                ui.post(() -> status.setText("Extracting archive…"));
                File extractDir = new File(getCacheDir(), "ra1demo_extract");
                deleteRecursive(extractDir);
                extractDir.mkdirs();
                extractZip(zipFile, extractDir);

                // Step 5: Find .MIX files inside nested folders (ra95demo/INSTALL/…) and copy flat
                ui.post(() -> status.setText("Installing game files…"));
                int copied = findAndCopyGameFiles(extractDir, dataDir);
                if (copied == 0) {
                    throw new IOException("No game files found in archive");
                }

                // Step 6: Cleanup
                deleteRecursive(extractDir);
                zipFile.delete();

                ui.post(() -> {
                    busy = false;
                    progress.setVisibility(View.GONE);
                    status.setText("Demo installed! Copied " + copied + " files.");
                    updateFileStatus();
                    refreshContinueState();
                    if (hasFile("expand.mix")) cbCS.setChecked(true);
                    if (hasFile("expand2.mix")) cbAM.setChecked(true);
                });

            } catch (Exception e) {
                Log.e(TAG, "Demo download failed", e);
                ui.post(() -> {
                    busy = false;
                    progress.setVisibility(View.GONE);
                    status.setText("Download failed: " + e.getMessage()
                            + "\nUse Choose folder or open in browser instead.");
                    refreshContinueState();
                });
            }
        }, "demo-download").start();
    }

    private String httpGet(String urlString) throws IOException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (X11; Linux x86_64; rv:153.0) Gecko/20100101 Firefox/153.0");
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.5");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("Connection", "keep-alive");
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);

            int response = conn.getResponseCode();
            Log.d(TAG, "HTTP " + response + " for " + urlString);
            if (response >= 400) {
                throw new IOException("HTTP " + response);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    baos.write(buf, 0, n);
                }
            }
            String result = baos.toString("UTF-8");
            // Debug: log a snippet so you can verify what ModDB returns
            Log.d(TAG, "Response snippet: " + result.substring(0, Math.min(1500, result.length())));
            return result;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void downloadFile(String urlString, File dest) throws IOException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (X11; Linux x86_64; rv:153.0) Gecko/20100101 Firefox/153.0");
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.5");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);

            int response = conn.getResponseCode();
            if (response >= 400) {
                throw new IOException("HTTP " + response);
            }

            long total = conn.getContentLengthLong();
            long downloaded = 0;
            long lastUiUpdate = 0;

            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                    downloaded += n;

                    long now = System.currentTimeMillis();
                    if (total > 0 && now - lastUiUpdate > 500) {
                        lastUiUpdate = now;
                        final int pct = (int) (downloaded * 100 / total);
                        final String mb = String.format(Locale.US, "%.1f", downloaded / 1048576.0f);
                        ui.post(() -> status.setText("Downloading… " + pct + "% (" + mb + " MB)"));
                    }
                }
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Extract a zip file preserving folder structure. */
    private void extractZip(File zipFile, File destDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File outFile = new File(destDir, entry.getName());
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    outFile.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = zis.read(buf)) >= 0) {
                            fos.write(buf, 0, n);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    /** Recursively search extracted folders and copy game files flat into dataDir. */
    private int findAndCopyGameFiles(File searchDir, File targetDir) {
        int count = 0;
        File[] files = searchDir.listFiles();
        if (files == null) return 0;
        for (File f : files) {
            if (f.isDirectory()) {
                count += findAndCopyGameFiles(f, targetDir);
            } else {
                String name = f.getName();
                String lower = name.toLowerCase(Locale.US);
                if (CORE_WANTED.contains(lower) || lower.endsWith(".mix")
                        || lower.endsWith(".aud") || lower.endsWith(".vqa")
                        || lower.endsWith(".pkt") || lower.endsWith(".mpr")
                        || (lower.endsWith(".ini") && !BLOCKED.contains(lower))) {
                    try {
                        copyFile(f, new File(targetDir, name));
                        count++;
                        Log.i(TAG, "Installed " + name);
                    } catch (IOException e) {
                        Log.e(TAG, "Failed to copy " + name, e);
                    }
                }
            }
        }
        return count;
    }

    private void copyFile(File src, File dest) throws IOException {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
        }
    }


    private void updateFileStatus() {
        if (status == null) return;
        if (!hasGameData()) {
            status.setText("No game data yet. Use demo page or Choose folder.");
            return;
        }
        StringBuilder sb = new StringBuilder("Core data OK.");
        sb.append(hasFile("expand.mix") ? " EXPAND.MIX found." : " No EXPAND.MIX.");
        sb.append(hasFile("expand2.mix") ? " EXPAND2.MIX found." : " No EXPAND2.MIX.");
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
        boolean cs = wantCS && hasFile("expand.mix");
        boolean am = wantAM && hasFile("expand2.mix");
        applyExpansionIni(cs, am);
    }

    /** Write expansion flags — engine uses these for menu buttons. */
    private void applyExpansionIni(boolean cs, boolean am) {
        try {
            File ini = new File(userDir, "redalert.ini");
            String text = "";
            if (ini.exists()) {
                text = new String(readAll(ini), "UTF-8");
            }
            if (!text.contains("[Expansions]")) {
                text = text + "\n[Expansions]\n"
                        + "CounterstrikeEnabled=" + (cs ? "yes" : "no") + "\n"
                        + "AftermathEnabled=" + (am ? "yes" : "no") + "\n";
            } else {
                text = upsertIni(text, "Expansions", "CounterstrikeEnabled", cs ? "yes" : "no");
                text = upsertIni(text, "Expansions", "AftermathEnabled", am ? "yes" : "no");
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

    /** Calculate the largest integer scale of 640×400 that fits the screen in landscape. */
    private int calculateOptimalScale() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        // Game runs landscape: width is the longer edge
        int screenW = Math.max(dm.widthPixels, dm.heightPixels);
        int screenH = Math.min(dm.widthPixels, dm.heightPixels);

        int scale = Math.min(screenW / BASE_WIDTH, screenH / BASE_HEIGHT);
        if (scale < 1) scale = 1;

        Log.i(TAG, "Display " + screenW + "x" + screenH + " -> using " + scale + "x scale ("
                + (BASE_WIDTH * scale) + "x" + (BASE_HEIGHT * scale) + ")");
        return scale;
    
    }

    /** Apply video settings. mode="auto" detects optimal integer scale. */
    private void applyDisplayIni(String mode) {
        int scale;
        if ("auto".equals(mode) || mode == null || mode.isEmpty()) {
            scale = calculateOptimalScale();
        } else {
            try {
                scale = Integer.parseInt(mode);
            } catch (NumberFormatException e) {
                scale = calculateOptimalScale();
            }
        }

        int width = BASE_WIDTH * scale;
        int height = BASE_HEIGHT * scale;

        // Crisp fill: exact integer multiple, nearest-neighbor, fill screen
        boolean boxing = false;
        String scaler = "nearest";

        try {
            File ini = new File(userDir, "redalert.ini");
            String text = ini.exists() ? new String(readAll(ini), "UTF-8") : "";

            text = upsertIni(text, "Video", "Width", String.valueOf(width));
            text = upsertIni(text, "Video", "Height", String.valueOf(height));
            text = upsertIni(text, "Video", "Windowed", "no");
            text = upsertIni(text, "Video", "Boxing", boxing ? "yes" : "no");
            text = upsertIni(text, "Video", "BoxingAspectRatio", "16:10");
            text = upsertIni(text, "Video", "Scaler", scaler);
            text = upsertIni(text, "Video", "HardwareCursor", "no");
            text = upsertIni(text, "Video", "FrameLimit", "60");
            text = upsertIni(text, "Video", "DOSMode", "no");

            FileOutputStream fos = new FileOutputStream(ini);
            fos.write(text.getBytes("UTF-8"));
            fos.close();

            Log.i(TAG, "Display: scale=" + scale + "x res=" + width + "x" + height
                    + " boxing=" + boxing + " scaler=" + scaler);
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
                if (hasFile("expand.mix")) cbCS.setChecked(true);
                if (hasFile("expand2.mix")) cbAM.setChecked(true);
                boolean cs = cbCS.isChecked() && hasFile("expand.mix");
                boolean am = cbAM.isChecked() && hasFile("expand2.mix");
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
                            || lower.equals("expand.mix")
                            || lower.equals("expand2.mix")
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
        return hasFile("redalert.mix") || hasFile("main.mix") || hasFile("conquer.mix");
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
        File badIni = new File(dataDir, "redalert.ini");
        if (badIni.exists()) {
            // noinspection ResultOfMethodCallIgnored
            badIni.delete();
        }
        Intent i = new Intent(this, VanillaRAActivity.class);
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

