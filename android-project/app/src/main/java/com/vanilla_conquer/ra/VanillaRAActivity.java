package com.vanilla_conquer.ra;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLSurface;

import java.io.File;

/**
 * Controls:
 *  - Touch = trackpad (relative)
 *  - Bottom-left: LMB, RMB, Scroll Up / Down
 *  - Top-left: Esc
 *
 * Button presses do NOT move the cursor (relative 0,0 + button state only).
 */
public class VanillaRAActivity extends SDLActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());

    private float cursorX = 0f;
    private float cursorY = 0f;
    private int surfaceW = 0;
    private int surfaceH = 0;
    private boolean cursorSeeded = false;

    private static final int BTN_LEFT  = MotionEvent.BUTTON_PRIMARY;
    private static final int BTN_RIGHT = MotionEvent.BUTTON_SECONDARY;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        File dataDir = new File(getFilesDir(), "ra");
        File userDir = new File(getFilesDir(), "ra-user");
        dataDir.mkdirs();
        userDir.mkdirs();

        setenv("VANILLA_DATA_PATH", dataDir.getAbsolutePath());
        setenv("VANILLA_USER_PATH", userDir.getAbsolutePath());
        setenv("HOME", getFilesDir().getAbsolutePath());
        setenv("SDL_TOUCH_MOUSE_EVENTS", "0");
        setenv("SDL_MOUSE_TOUCH_EVENTS", "0");
        setenv("SDL_ANDROID_TRAP_BACK_BUTTON", "1");

        // A redalert.ini in the *data* dir (from SAF copy or an older build)
        // can force odd boot behaviour (e.g. jumping straight into a mission).
        // User config belongs only under VANILLA_USER_PATH.
        File dataIni = new File(dataDir, "redalert.ini");
        if (dataIni.exists()) {
            // noinspection ResultOfMethodCallIgnored
            dataIni.delete();
        }
        ensureDefaultOptions(userDir);

        super.onCreate(savedInstanceState);
        handler.postDelayed(this::installOverlays, 200);
    }

    @Override
    protected SDLSurface createSDLSurface(Context context) {
        return new TouchSurface(context);
    }

    @Override
    protected String getMainFunction() { return "main"; }

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL2", "openal", "main" };
    }

    private void installOverlays() {
        ViewGroup root = findViewById(android.R.id.content);
        if (root == null) {
            handler.postDelayed(this::installOverlays, 150);
            return;
        }
        if (root.findViewWithTag("ra_overlays") != null) return;

        FrameLayout holder = new FrameLayout(this);
        holder.setTag("ra_overlays");
        root.addView(holder, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // --- Top-left: Esc ---
        Button esc = makeKeyBtn("Esc", android.view.KeyEvent.KEYCODE_ESCAPE);
        FrameLayout.LayoutParams escLp = new FrameLayout.LayoutParams(dp(72), dp(48));
        escLp.gravity = Gravity.TOP | Gravity.START;
        escLp.leftMargin = dp(10);
        escLp.topMargin = dp(10);
        holder.addView(esc, escLp);

        // --- Bottom-left: LMB, RMB, Scroll ---
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.addView(makeMouseBtn("LMB", true));
        col.addView(makeMouseBtn("RMB", false));

        // Scroll wheel row
        LinearLayout scrollRow = new LinearLayout(this);
        scrollRow.setOrientation(LinearLayout.HORIZONTAL);
        scrollRow.setGravity(Gravity.CENTER);
        scrollRow.addView(makeScrollBtn("▲", 1f));
        scrollRow.addView(makeScrollBtn("▼", -1f));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollLp.topMargin = dp(4);
        col.addView(scrollRow, scrollLp);

        FrameLayout.LayoutParams colLp = new FrameLayout.LayoutParams(
                dp(100), FrameLayout.LayoutParams.WRAP_CONTENT);
        colLp.gravity = Gravity.BOTTOM | Gravity.START;
        colLp.leftMargin = dp(10);
        colLp.bottomMargin = dp(12);
        holder.addView(col, colLp);

        hideSystemUI();
    }

    private Button makeKeyBtn(String label, int keyCode) {
        Button b = styledBtn(label, 0xCC424242);
        b.setOnClickListener(v -> {
            SDLActivity.onNativeKeyDown(keyCode);
            handler.postDelayed(() -> SDLActivity.onNativeKeyUp(keyCode), 40);
        });
        return b;
    }

    private View makeMouseBtn(String label, boolean left) {
        Button b = styledBtn(label, left ? 0xCC1976D2 : 0xCCC62828);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60));
        lp.setMargins(0, dp(3), 0, dp(3));
        b.setLayoutParams(lp);
        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mouseButton(left, true);
                    v.setAlpha(0.6f);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mouseButton(left, false);
                    v.setAlpha(1f);
                    return true;
            }
            return true;
        });
        return b;
    }

    private View makeScrollBtn(String label, float wheelY) {
        Button b = styledBtn(label, 0xCC546E7A);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> scrollWheel(wheelY));
        return b;
    }

    private Button styledBtn(String label, int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(4), 0, dp(4), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), 0xFFFFFFFF);
        b.setBackground(bg);
        b.setTextColor(Color.WHITE);
        return b;
    }

    void updateSurfaceSize(int w, int h) {
        if (w > 0) surfaceW = w;
        if (h > 0) surfaceH = h;
        if (!cursorSeeded && surfaceW > 0 && surfaceH > 0) {
            cursorX = surfaceW / 2f;
            cursorY = surfaceH / 2f;
            cursorSeeded = true;
            SDLActivity.onNativeMouse(0, MotionEvent.ACTION_MOVE, cursorX, cursorY, false);
        }
    }

    void moveCursorBy(float dx, float dy) {
        if (surfaceW > 0 && surfaceH > 0) {
            cursorX = clamp(cursorX + dx, 0, surfaceW - 1);
            cursorY = clamp(cursorY + dy, 0, surfaceH - 1);
        } else {
            cursorX += dx;
            cursorY += dy;
        }
        SDLActivity.onNativeMouse(0, MotionEvent.ACTION_MOVE, dx, dy, true);
    }

    /** Click without moving cursor — relative 0,0 + button state only. */
    void mouseButton(boolean left, boolean down) {
        int btn = left ? BTN_LEFT : BTN_RIGHT;
        if (down) {
            SDLActivity.onNativeMouse(btn, MotionEvent.ACTION_DOWN, 0f, 0f, true);
        } else {
            SDLActivity.onNativeMouse(0, MotionEvent.ACTION_UP, 0f, 0f, true);
        }
    }

    /** Vertical mouse wheel. wheelY > 0 = up, < 0 = down. */
    void scrollWheel(float wheelY) {
        // ACTION_SCROLL = 8; x = horizontal, y = vertical (SDL_androidmouse.c)
        SDLActivity.onNativeMouse(0, MotionEvent.ACTION_SCROLL, 0f, wheelY, false);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    private class TouchSurface extends SDLSurface {
        private float lastTouchX, lastTouchY;
        private boolean tracking;

        TouchSurface(Context context) { super(context); }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 && mWidth > 0) w = (int) mWidth;
            if (h <= 0 && mHeight > 0) h = (int) mHeight;
            updateSurfaceSize(w, h);

            float x = event.getX();
            float y = event.getY();

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastTouchX = x;
                    lastTouchY = y;
                    tracking = true;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (tracking) {
                        float dx = x - lastTouchX;
                        float dy = y - lastTouchY;
                        lastTouchX = x;
                        lastTouchY = y;
                        if (dx != 0f || dy != 0f) moveCursorBy(dx, dy);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    tracking = false;
                    return true;
            }
            return true;
        }
    }




    /**
     * Ensure user redalert.ini has mobile-friendly defaults and does NOT
     * trigger the install/first-run path.
     *
     * Vanilla-Conquer: PlayIntro default true → Special.IsFromInstall →
     * Select_Game() auto-picks SEL_START_NEW_GAME (skips main menu).
     * Always force PlayIntro=false so boot lands on the main menu.
     */
    private void ensureDefaultOptions(File dir) {
        if (dir == null) return;
        dir.mkdirs();
        File ini = new File(dir, "redalert.ini");
        try {
            java.util.ArrayList<String> lines = new java.util.ArrayList<String>();
            if (ini.exists()) {
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(
                                new java.io.FileInputStream(ini),
                                java.nio.charset.StandardCharsets.UTF_8));
                String line;
                while ((line = br.readLine()) != null) {
                    lines.add(line);
                }
                br.close();
            }

            boolean inOptions = false;
            boolean inIntro = false;
            boolean sawOptions = false;
            boolean sawIntro = false;
            boolean haveGS = false;
            boolean haveSR = false;
            boolean havePlayIntro = false;
            java.util.ArrayList<String> out = new java.util.ArrayList<String>();

            for (int i = 0; i < lines.size(); i++) {
                String raw = lines.get(i);
                String trimmed = raw.trim();
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    inOptions = trimmed.equalsIgnoreCase("[Options]");
                    inIntro = trimmed.equalsIgnoreCase("[Intro]");
                    if (inOptions) sawOptions = true;
                    if (inIntro) sawIntro = true;
                    out.add(raw);
                    continue;
                }
                if (inOptions) {
                    int eq = trimmed.indexOf('=');
                    if (eq > 0) {
                        String key = trimmed.substring(0, eq).trim();
                        String val = trimmed.substring(eq + 1).trim();
                        if (key.equalsIgnoreCase("GameSpeed")) {
                            if (val.equals("3") || val.equals("5")) val = "1";
                            out.add("GameSpeed=" + val);
                            haveGS = true;
                            continue;
                        }
                        if (key.equalsIgnoreCase("ScrollRate")) {
                            if (val.equals("3")) val = "5";
                            out.add("ScrollRate=" + val);
                            haveSR = true;
                            continue;
                        }
                    }
                }
                if (inIntro) {
                    int eq = trimmed.indexOf('=');
                    if (eq > 0) {
                        String key = trimmed.substring(0, eq).trim();
                        if (key.equalsIgnoreCase("PlayIntro")) {
                            out.add("PlayIntro=false");
                            havePlayIntro = true;
                            continue;
                        }
                    }
                }
                out.add(raw);
            }

            if (!sawOptions) {
                out.add("");
                out.add("[Options]");
                out.add("GameSpeed=1");
                out.add("ScrollRate=5");
            } else if (!haveGS || !haveSR) {
                java.util.ArrayList<String> injected = new java.util.ArrayList<String>();
                boolean done = false;
                for (int i = 0; i < out.size(); i++) {
                    String raw = out.get(i);
                    injected.add(raw);
                    if (!done && raw.trim().equalsIgnoreCase("[Options]")) {
                        if (!haveGS) injected.add("GameSpeed=1");
                        if (!haveSR) injected.add("ScrollRate=5");
                        done = true;
                    }
                }
                out = injected;
            }

            if (!sawIntro) {
                out.add("");
                out.add("[Intro]");
                out.add("PlayIntro=false");
            } else if (!havePlayIntro) {
                java.util.ArrayList<String> injected = new java.util.ArrayList<String>();
                boolean done = false;
                for (int i = 0; i < out.size(); i++) {
                    String raw = out.get(i);
                    injected.add(raw);
                    if (!done && raw.trim().equalsIgnoreCase("[Intro]")) {
                        injected.add("PlayIntro=false");
                        done = true;
                    }
                }
                out = injected;
            }

            java.io.FileOutputStream fos = new java.io.FileOutputStream(ini);
            for (int i = 0; i < out.size(); i++) {
                fos.write(out.get(i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                fos.write('\n');
            }
            fos.close();
        } catch (Exception ignored) {
        }
    }

    private static void setenv(String key, String value) {
        try {
            Class<?> osClass = Class.forName("android.system.Os");
            java.lang.reflect.Method m = osClass.getMethod(
                    "setenv", String.class, String.class, boolean.class);
            m.invoke(null, key, value, true);
        } catch (Exception e) {
            System.setProperty(key, value);
        }
        try { SDLActivity.nativeSetenv(key, value); } catch (Throwable ignored) {}
    }
}
