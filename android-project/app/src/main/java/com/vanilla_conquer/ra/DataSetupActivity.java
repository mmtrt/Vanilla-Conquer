package com.vanilla_conquer.ra;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Bootstrap activity: ensures MIX data is present in app-private storage,
 * then launches VanillaRAActivity (SDL).
 */
public class DataSetupActivity extends Activity {
    private static final String TAG = "VanillaRA-Setup";
    private static final int REQUEST_DATA_TREE = 9001;
    private static final String PREFS = "vanilla_ra";
    private static final String PREF_TREE_URI = "data_tree_uri";

    private static final Set<String> WANTED = new HashSet<>(Arrays.asList(
            "redalert.mix", "main.mix", "local.mix", "conquer.mix",
            "hires1.mix", "lores1.mix", "expand.mix", "expand2.mix",
            "keyboard.ini",
            "speech01.mix", "speech02.mix", "scores.mix", "scoresa.mix",
            "movies1.mix", "movies2.mix", "interior.mix"
    ));
    /** Never copy these into the data dir — they can alter boot / menu flow. */
    private static final Set<String> BLOCKED = new HashSet<>(Arrays.asList(
            "redalert.ini", "conquer.ini"
    ));

    private File dataDir;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(48, 48, 48, 48);
        status.setText("Checking game data…");
        setContentView(status);

        dataDir = new File(getFilesDir(), "ra");
        File userDir = new File(getFilesDir(), "ra-user");
        dataDir.mkdirs();
        userDir.mkdirs();

        if (hasGameData()) {
            launchGame();
        } else {
            trySavedTreeOrPrompt();
        }
    }

    private boolean hasGameData() {
        String[] names = {
            "REDALERT.MIX", "redalert.mix", "MAIN.MIX", "main.mix",
            "CONQUER.MIX", "conquer.mix"
        };
        for (String n : names) {
            if (new File(dataDir, n).exists()) return true;
        }
        return false;
    }

    private void launchGame() {
        // Ensure no leftover config in data dir steers boot into a mission
        File badIni = new File(dataDir, "redalert.ini");
        if (badIni.exists()) {
            // noinspection ResultOfMethodCallIgnored
            badIni.delete();
        }

        status.setText("Starting VanillaRA…");
        Intent i = new Intent(this, VanillaRAActivity.class);
        // Keep it in the same task; do not finish until the game activity is up.
        startActivity(i);
        // Small delay so the new activity can take focus before we tear down.
        status.postDelayed(this::finish, 500);
    }

    private void trySavedTreeOrPrompt() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = prefs.getString(PREF_TREE_URI, null);
        if (saved != null) {
            Uri tree = Uri.parse(saved);
            if (stillHavePermission(tree)) {
                status.setText("Copying from saved folder…");
                final Uri t = tree;
                new Thread(() -> {
                    int n = copyFromTree(t);
                    runOnUiThread(() -> {
                        if (hasGameData()) {
                            Toast.makeText(this, "Ready (" + n + " files)", Toast.LENGTH_SHORT).show();
                            launchGame();
                        } else {
                            showPickerDialog();
                        }
                    });
                }).start();
                return;
            }
        }
        showPickerDialog();
    }

    private void showPickerDialog() {
        status.setText("Game data required.");
        new AlertDialog.Builder(this)
                .setTitle("Game data required")
                .setMessage(
                        "VanillaRA needs your Red Alert data files (REDALERT.MIX, MAIN.MIX, …).\n\n" +
                        "Choose the folder that contains them. Files are copied into " +
                        "app-private storage.\n\n" +
                        "The APK does not include copyrighted game data.")
                .setPositiveButton("Choose folder", (d, w) -> openTreePicker())
                .setNegativeButton("Exit", (d, w) -> finish())
                .setCancelable(false)
                .show();
    }

    private void openTreePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_DATA_TREE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_DATA_TREE) return;

        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            Toast.makeText(this, "No folder selected", Toast.LENGTH_LONG).show();
            showPickerDialog();
            return;
        }

        Uri tree = data.getData();
        final int takeFlags = data.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(tree, takeFlags);
        } catch (SecurityException e) {
            Log.w(TAG, "persist permission failed", e);
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PREF_TREE_URI, tree.toString())
                .apply();

        status.setText("Copying data files…");
        final Uri t = tree;
        new Thread(() -> {
            int count = copyFromTree(t);
            runOnUiThread(() -> {
                if (hasGameData()) {
                    Toast.makeText(this, "Copied " + count + " file(s)", Toast.LENGTH_SHORT).show();
                    launchGame();
                } else {
                    new AlertDialog.Builder(this)
                            .setTitle("No MIX files found")
                            .setMessage("The folder did not contain REDALERT.MIX or MAIN.MIX.")
                            .setPositiveButton("Try again", (d, w) -> openTreePicker())
                            .setNegativeButton("Exit", (d, w) -> finish())
                            .setCancelable(false)
                            .show();
                }
            });
        }).start();
    }

    private boolean stillHavePermission(Uri tree) {
        for (UriPermission p : getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(tree) && p.isReadPermission()) return true;
        }
        return false;
    }

    private int copyFromTree(Uri treeUri) {
        int copied = 0;
        try {
            String docId = DocumentsContract.getTreeDocumentId(treeUri);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId);
            copied = walkAndCopy(treeUri, children, 0);
        } catch (Exception e) {
            Log.e(TAG, "copyFromTree", e);
        }
        Log.i(TAG, "Copied " + copied + " → " + dataDir);
        return copied;
    }

    private int walkAndCopy(Uri treeUri, Uri childrenUri, int depth) {
        if (depth > 3) return 0;
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
                    count += walkAndCopy(treeUri, sub, depth + 1);
                } else {
                    String lower = name.toLowerCase(Locale.US);
                    if (BLOCKED.contains(lower)) {
                        continue;
                    }
                    if (WANTED.contains(lower) || lower.endsWith(".mix")
                            || lower.endsWith(".aud")
                            || lower.endsWith(".vqa")
                            || (lower.endsWith(".ini") && !BLOCKED.contains(lower))) {
                        Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                        if (copyUri(fileUri, new File(dataDir, name))) {
                            count++;
                            Log.i(TAG, "Copied " + name);
                        }
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
}
