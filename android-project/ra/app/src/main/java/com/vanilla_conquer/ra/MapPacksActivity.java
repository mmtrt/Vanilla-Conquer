package com.vanilla_conquer.ra;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.view.WindowInsets;
import org.json.JSONArray;
import org.json.JSONObject;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Community multiplayer map packs (scraped from ra.afraid.org).
 * Opened from DataSetup; Back returns to setup.
 */
public class MapPacksActivity extends Activity {
    private static final String TAG = "VanillaRA-MapPacks";
    private static final String PREFS = "vanilla_ra";
    private static final String CACHE_FILE = "map_packs_cache.json";

    /** In-memory cache so reopening the screen does not re-scrape. */
    private static final ArrayList<MapPack> sCachedPacks = new ArrayList<>();
    private static boolean sCacheLoaded;

    private static final String[] MAP_PACK_LIST_URLS = {
            "http://ra.afraid.org/html/downloads/mappacks.html",
            "http://ra.afraid.org/html/downloads/mappacks-2.html",
            "http://ra.afraid.org/html/downloads/mappacks-3.html",
            "http://ra.afraid.org/html/downloads/mappacks-4.html",
            "http://ra.afraid.org/html/downloads/mappacks-5.html",
    };
    private static final String MAP_PACK_BASE_URL =
            "http://ra.afraid.org/download.php/mappacks/";

    private static class MapPack {
        final String filename;
        final String url;
        final String title;
        final String author;
        final String mapsInfo;
        final String dateAdded;
        final String description;
        boolean selected;
        boolean installed;

        MapPack(String filename, String title, String author,
                String mapsInfo, String dateAdded, String description) {
            this.filename = filename;
            this.url = MAP_PACK_BASE_URL + filename;
            this.title = title != null && !title.isEmpty() ? title : filename;
            this.author = author != null ? author : "";
            this.mapsInfo = mapsInfo != null ? mapsInfo : "";
            this.dateAdded = dateAdded != null ? dateAdded : "";
            this.description = description != null ? description : "";
        }
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayList<MapPack> mapPacks = new ArrayList<>();
    private File dataDir;
    private TextView status;
    private ProgressBar progress;
    private LinearLayout mapPackContainer;
    private Button btnDownloadMaps;
    private Button btnRefreshMaps;
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dataDir = new File(getFilesDir(), "ra");
        dataDir.mkdirs();

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

        View root = buildUi();
        setContentView(root);
        applyNotchPadding(root);

        // Prefer in-memory / disk cache; network only on first open or Refresh
        if (!sCachedPacks.isEmpty()) {
            mapPacks.clear();
            mapPacks.addAll(sCachedPacks);
            refreshMapPackList();
            status.setText("Found " + mapPacks.size() + " map pack(s). (cached)");
        } else if (loadCacheFromDisk()) {
            refreshMapPackList();
            status.setText("Found " + mapPacks.size() + " map pack(s). (cached)");
        } else {
            loadMapPackList(false);
        }
    }

    private void applyNotchPadding(View root) {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom, left, right;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = bars.top;
                bottom = bars.bottom;
                left = bars.left;
                right = bars.right;
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
    }

    private View buildUi() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(0xFF121212);

        // Top bar: Back + title
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(12), dp(12), dp(12), dp(8));

        Button back = secondaryBtn("← Back");
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        back.setLayoutParams(backLp);
        back.setOnClickListener(v -> finish());
        top.addView(back);

        TextView title = new TextView(this);
        title.setText("Community Map Packs");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(12), 0, 0, 0);
        top.addView(title);
        outer.addView(top);

        TextView hint = new TextView(this);
        hint.setText("Maps from ra.afraid.org — multiplayer .mpr packs. "
                + "Installed packs land in the game data folder.");
        hint.setTextColor(0xFFAAAAAA);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setPadding(dp(16), 0, dp(16), dp(8));
        outer.addView(hint);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        plp.leftMargin = dp(16);
        plp.rightMargin = dp(16);
        outer.addView(progress, plp);

        status = new TextView(this);
        status.setTextColor(0xFFAAAAAA);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setPadding(dp(16), dp(8), dp(16), dp(4));
        status.setText("Loading map pack list…");
        outer.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        outer.addView(scroll, slp);

        mapPackContainer = new LinearLayout(this);
        mapPackContainer.setOrientation(LinearLayout.VERTICAL);
        mapPackContainer.setPadding(dp(12), dp(4), dp(12), dp(12));
        scroll.addView(mapPackContainer);

        // Bottom actions
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(12), dp(8), dp(12), dp(12));

        btnRefreshMaps = secondaryBtn("Refresh list");
        btnRefreshMaps.setOnClickListener(v -> loadMapPackList(true));
        bottom.addView(btnRefreshMaps);

        btnDownloadMaps = primaryBtn("Install selected packs");
        btnDownloadMaps.setEnabled(false);
        btnDownloadMaps.setOnClickListener(v -> downloadSelectedMapPacks());
        bottom.addView(btnDownloadMaps);

        outer.addView(bottom);
        return outer;
    }

    private void loadMapPackList(boolean forceNetwork) {
        if (busy) return;
        if (!forceNetwork && !sCachedPacks.isEmpty()) {
            mapPacks.clear();
            mapPacks.addAll(sCachedPacks);
            refreshMapPackList();
            status.setText("Found " + mapPacks.size() + " map pack(s). (cached)");
            return;
        }
        busy = true;
        updateBusyUi();
        if (btnRefreshMaps != null) {
            btnRefreshMaps.setEnabled(false);
            btnRefreshMaps.setText("Loading…");
        }
        status.setText("Fetching map pack list…");
        progress.setIndeterminate(true);
        progress.setVisibility(View.VISIBLE);

        new Thread(() -> {
            try {
                LinkedHashMap<String, MapPack> byFile = new LinkedHashMap<>();
                Pattern namePat = Pattern.compile(
                        "<span\\s+class=\"name\">\\s*([^<]+)</span>", Pattern.CASE_INSENSITIVE);
                Pattern mapsPat = Pattern.compile(
                        "Maps\\s+Included:\\s*</b>\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
                Pattern datePat = Pattern.compile(
                        "Date\\s+Added:\\s*</b>\\s*([^<\\r\\n]+)", Pattern.CASE_INSENSITIVE);
                Pattern authorPat = Pattern.compile(
                        "Author:\\s*</b>\\s*([^<\\r\\n]+)", Pattern.CASE_INSENSITIVE);
                Pattern filePat = Pattern.compile(
                        "download\\.php/mappacks/([^\"'\\s>]+\\.zip)", Pattern.CASE_INSENSITIVE);
                Pattern descPat = Pattern.compile(
                        "Description:\\s*</B>\\s*([^<]+)", Pattern.CASE_INSENSITIVE);

                for (String listUrl : MAP_PACK_LIST_URLS) {
                    String html;
                    try {
                        html = httpGet(listUrl);
                    } catch (Exception e) {
                        Log.w(TAG, "list page failed " + listUrl, e);
                        continue;
                    }

                    String[] parts = html.split("(?i)<DIV\\s+class=\"ItemBlock\">");
                    for (int i = 1; i < parts.length; i++) {
                        String block = parts[i];
                        Matcher fm = filePat.matcher(block);
                        if (!fm.find()) continue;
                        String filename = fm.group(1).trim();
                        String key = filename.toLowerCase(Locale.US);
                        if (byFile.containsKey(key)) continue;

                        String name = matchGroup(namePat, block);
                        String maps = matchGroup(mapsPat, block);
                        String date = matchGroup(datePat, block);
                        String author = matchGroup(authorPat, block);
                        String desc = matchGroup(descPat, block);
                        if (name.isEmpty()) name = filename.replace(".zip", "").replace('_', ' ');

                        byFile.put(key, new MapPack(filename, name, author, maps, date, desc));
                    }
                }

                mapPacks.clear();
                mapPacks.addAll(byFile.values());
                sCachedPacks.clear();
                sCachedPacks.addAll(mapPacks);
                sCacheLoaded = true;
                saveCacheToDisk(mapPacks);

                ui.post(() -> {
                    busy = false;
                    progress.setVisibility(View.GONE);
                    refreshMapPackList();
                    if (btnRefreshMaps != null) {
                        btnRefreshMaps.setEnabled(true);
                        btnRefreshMaps.setText("Refresh list");
                    }
                    status.setText(mapPacks.isEmpty()
                            ? "No packs found. Check network and try Refresh."
                            : ("Found " + mapPacks.size() + " map pack(s)."));
                    updateBusyUi();
                });
            } catch (Exception e) {
                Log.e(TAG, "load list", e);
                ui.post(() -> {
                    busy = false;
                    progress.setVisibility(View.GONE);
                    if (btnRefreshMaps != null) {
                        btnRefreshMaps.setEnabled(true);
                        btnRefreshMaps.setText("Retry");
                    }
                    status.setText("Failed: " + e.getMessage());
                    updateBusyUi();
                });
            }
        }, "map-list").start();
    }

    private static String matchGroup(Pattern p, String block) {
        Matcher m = p.matcher(block);
        return m.find() ? m.group(1).trim() : "";
    }

    private void refreshMapPackList() {
        mapPackContainer.removeAllViews();
        if (mapPacks.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No map packs loaded yet.");
            empty.setTextColor(0xFF888888);
            empty.setPadding(dp(8), dp(8), dp(8), dp(8));
            mapPackContainer.addView(empty);
            updateDownloadButtonState();
            return;
        }

        ArrayList<MapPack> ordered = new ArrayList<>(mapPacks);
        for (MapPack p : ordered) {
            p.installed = isMapPackInstalled(p.filename);
        }
        Collections.sort(ordered, (a, b) -> {
            if (a.installed != b.installed) return a.installed ? 1 : -1;
            return a.title.compareToIgnoreCase(b.title);
        });

        for (MapPack pack : ordered) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);
            int pad = dp(10);
            row.setPadding(pad, dp(10), pad, dp(10));
            GradientDrawable rowBg = new GradientDrawable();
            rowBg.setCornerRadius(dp(8));
            rowBg.setColor(pack.installed ? 0xFF1B2E1B : 0xFF1E1E1E);
            rowBg.setStroke(dp(1), pack.installed ? 0xFF2E7D32 : 0xFF333333);
            row.setBackground(rowBg);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.topMargin = dp(6);
            row.setLayoutParams(rowLp);

            CheckBox cb = new CheckBox(this);
            cb.setChecked(pack.installed || pack.selected);
            cb.setEnabled(!pack.installed && !busy);
            cb.setOnCheckedChangeListener((v, checked) -> {
                if (pack.installed) {
                    ((CheckBox) v).setChecked(true);
                    return;
                }
                pack.selected = checked;
                updateDownloadButtonState();
            });
            row.addView(cb);

            LinearLayout textCol = new LinearLayout(this);
            textCol.setOrientation(LinearLayout.VERTICAL);
            textCol.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView title = new TextView(this);
            title.setText(pack.title);
            title.setTextColor(pack.installed ? 0xFFA5D6A7 : Color.WHITE);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            textCol.addView(title);

            StringBuilder meta = new StringBuilder();
            if (!pack.mapsInfo.isEmpty()) {
                meta.append("Maps Included: ").append(pack.mapsInfo);
            }
            if (!pack.dateAdded.isEmpty()) {
                if (meta.length() > 0) meta.append('\n');
                meta.append("Date Added: ").append(pack.dateAdded);
            }
            if (!pack.author.isEmpty()) {
                if (meta.length() > 0) meta.append('\n');
                meta.append("Author: ").append(pack.author);
            }
            if (pack.installed) {
                if (meta.length() > 0) meta.append('\n');
                meta.append("Status: installed");
            }
            if (meta.length() == 0) meta.append(pack.filename);

            TextView sub = new TextView(this);
            sub.setText(meta.toString());
            sub.setTextColor(pack.installed ? 0xFF81C784 : 0xFFAAAAAA);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            sub.setPadding(0, dp(3), 0, 0);
            textCol.addView(sub);

            row.addView(textCol);
            mapPackContainer.addView(row);
        }
        updateDownloadButtonState();
    }

    private void updateDownloadButtonState() {
        if (btnDownloadMaps == null) return;
        boolean any = false;
        for (MapPack p : mapPacks) {
            if (p.selected && !p.installed) {
                any = true;
                break;
            }
        }
        btnDownloadMaps.setEnabled(any && !busy);
        btnDownloadMaps.setAlpha(any && !busy ? 1f : 0.5f);
    }

    private void updateBusyUi() {
        if (btnRefreshMaps != null) btnRefreshMaps.setEnabled(!busy);
        updateDownloadButtonState();
    }

    private boolean isMapPackInstalled(String filename) {
        String key = "map_pack_" + filename.replace(".", "_");
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(key, false);
    }

    private void markMapPackInstalled(String filename) {
        String key = "map_pack_" + filename.replace(".", "_");
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(key, true).apply();
    }

    private void downloadSelectedMapPacks() {
        int selectedCount = 0;
        for (MapPack p : mapPacks) {
            if (p.selected && !p.installed) selectedCount++;
        }
        if (selectedCount == 0) return;

        busy = true;
        updateBusyUi();
        progress.setIndeterminate(false);
        progress.setMax(selectedCount);
        progress.setProgress(0);
        progress.setVisibility(View.VISIBLE);
        status.setText("Installing map packs…");

        new Thread(() -> {
            int done = 0;
            int totalMaps = 0;
            for (MapPack pack : mapPacks) {
                if (!pack.selected || pack.installed) continue;
                ui.post(() -> status.setText("Downloading: " + pack.title));
                File zipFile = new File(getCacheDir(), pack.filename);
                try {
                    if (zipFile.exists()) // noinspection ResultOfMethodCallIgnored
                        zipFile.delete();
                    downloadFile(pack.url, zipFile);

                    File extractDir = new File(getCacheDir(),
                            "mappack_" + pack.filename.replace(".zip", ""));
                    deleteRecursive(extractDir);
                    // noinspection ResultOfMethodCallIgnored
                    extractDir.mkdirs();
                    extractZip(zipFile, extractDir);

                    int maps = findAndCopyMaps(extractDir, dataDir);
                    deleteRecursive(extractDir);
                    // noinspection ResultOfMethodCallIgnored
                    zipFile.delete();

                    markMapPackInstalled(pack.filename);
                    totalMaps += maps;
                    Log.i(TAG, "Installed " + pack.filename + " maps=" + maps);
                } catch (Exception e) {
                    Log.e(TAG, "Failed " + pack.filename, e);
                }
                done++;
                final int d = done;
                ui.post(() -> progress.setProgress(d));
            }

            final int finalDone = done;
            final int finalTotalMaps = totalMaps;
            ui.post(() -> {
                busy = false;
                progress.setVisibility(View.GONE);
                status.setText("Installed " + finalTotalMaps + " map file(s) from "
                        + finalDone + " pack(s).");
                refreshMapPackList();
                updateBusyUi();
                Toast.makeText(this, "Map packs installed", Toast.LENGTH_SHORT).show();
            });
        }, "map-pack-download").start();
    }

    /** Prefer .mpr maps; also copy mix/ini that ship with some packs. */
    private int findAndCopyMaps(File searchDir, File targetDir) {
        int count = 0;
        File[] files = searchDir.listFiles();
        if (files == null) return 0;
        for (File f : files) {
            if (f.isDirectory()) {
                count += findAndCopyMaps(f, targetDir);
            } else {
                String lower = f.getName().toLowerCase(Locale.US);
                if (lower.endsWith(".mpr") || lower.endsWith(".mix")
                        || lower.endsWith(".ini") || lower.endsWith(".pkt")) {
                    try {
                        copyFile(f, new File(targetDir, f.getName()));
                        count++;
                    } catch (IOException e) {
                        Log.e(TAG, "copy " + f.getName(), e);
                    }
                }
            }
        }
        return count;
    }


    private boolean loadCacheFromDisk() {
        File f = new File(getFilesDir(), CACHE_FILE);
        if (!f.exists() || f.length() == 0) return false;
        try {
            byte[] raw = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int off = 0;
                while (off < raw.length) {
                    int n = in.read(raw, off, raw.length - off);
                    if (n < 0) break;
                    off += n;
                }
            }
            JSONArray arr = new JSONArray(new String(raw, "UTF-8"));
            ArrayList<MapPack> loaded = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                loaded.add(new MapPack(
                        o.optString("filename", ""),
                        o.optString("title", ""),
                        o.optString("author", ""),
                        o.optString("mapsInfo", ""),
                        o.optString("dateAdded", ""),
                        o.optString("description", "")));
            }
            if (loaded.isEmpty()) return false;
            mapPacks.clear();
            mapPacks.addAll(loaded);
            sCachedPacks.clear();
            sCachedPacks.addAll(loaded);
            sCacheLoaded = true;
            return true;
        } catch (Exception e) {
            Log.w(TAG, "cache load", e);
            return false;
        }
    }

    private void saveCacheToDisk(ArrayList<MapPack> packs) {
        try {
            JSONArray arr = new JSONArray();
            for (MapPack p : packs) {
                JSONObject o = new JSONObject();
                o.put("filename", p.filename);
                o.put("title", p.title);
                o.put("author", p.author);
                o.put("mapsInfo", p.mapsInfo);
                o.put("dateAdded", p.dateAdded);
                o.put("description", p.description);
                arr.put(o);
            }
            File f = new File(getFilesDir(), CACHE_FILE);
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(arr.toString().getBytes("UTF-8"));
            }
        } catch (Exception e) {
            Log.w(TAG, "cache save", e);
        }
    }

    private String httpGet(String urlString) throws IOException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36");
            conn.setRequestProperty("Accept", "text/html,*/*");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            int response = conn.getResponseCode();
            if (response >= 400) throw new IOException("HTTP " + response);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) baos.write(buf, 0, n);
            }
            return baos.toString("UTF-8");
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
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36");
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);
            int response = conn.getResponseCode();
            if (response >= 400) throw new IOException("HTTP " + response);
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void extractZip(File zipFile, File destDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File outFile = new File(destDir, entry.getName());
                if (entry.isDirectory()) {
                    // noinspection ResultOfMethodCallIgnored
                    outFile.mkdirs();
                } else {
                    // noinspection ResultOfMethodCallIgnored
                    outFile.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = zis.read(buf)) >= 0) fos.write(buf, 0, n);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private void copyFile(File src, File dest) throws IOException {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        }
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
