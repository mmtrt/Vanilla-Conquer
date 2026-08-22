package com.vanilla_conquer.td;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.WindowManager;
import android.os.Build;
import android.provider.Settings;
import android.media.AudioManager;
import android.media.AudioFocusRequest;
import android.media.AudioAttributes;
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
 * VanillaTD SDL activity.
 *
 * Controls (Rusted Warfare style — absolute touch):
 *  - Tap              → left-click at finger
 *  - Double-tap       → double left-click
 *  - Drag             → selection box
 *  - Long-press       → right-click (or hold-drag to pan)
 *  - Two-finger drag  → pan (stops when movement stops)
 *  - Two-finger tap   → right-click (cancel selection)
 *  - Collapsible hotkey bar (Esc/KB + shortcuts), collapsed by default
 */
public class VanillaTDActivity extends SDLActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private final AudioManager.OnAudioFocusChangeListener focusListener =
            focusChange -> {
                // Do not call nativePause/nativeResume here — SDLActivity already
                // pauses/resumes on lifecycle. Extra pauses stack and hang restore.
            };

    private View hotkeyBar;
    private boolean hotkeysVisible = false; // collapsed by default

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        File dataDir = new File(getFilesDir(), "td");
        File userDir = new File(getFilesDir(), "td-user");
        dataDir.mkdirs();
        userDir.mkdirs();

        setenv("VANILLA_DATA_PATH", dataDir.getAbsolutePath());
        setenv("VANILLA_USER_PATH", userDir.getAbsolutePath());
        setenv("HOME", getFilesDir().getAbsolutePath());
        setenv("SDL_TOUCH_MOUSE_EVENTS", "0");
        setenv("SDL_MOUSE_TOUCH_EVENTS", "0");
        setenv("SDL_ANDROID_TRAP_BACK_BUTTON", "1");

        File dataIni = new File(dataDir, "conquer.ini");
        if (dataIni.exists()) {
            // noinspection ResultOfMethodCallIgnored
            dataIni.delete();
        }
        ensureDefaultOptions(userDir);
        ensureMobileInputSettings(userDir);

        setenv("SDL_VIDEO_MINIMIZE_ON_FOCUS_LOSS", "0");
        setenv("SDL_ANDROID_BLOCK_ON_PAUSE", "1"); // stop rendering when paused (battery)
        setenv("SDL_HINT_RENDER_VSYNC", "1"); // present on vsync; FrameLimit=60 keeps scroll/logic stable

        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        hideSystemUI();
        requestGameAudioFocus();
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
        if (root.findViewWithTag("td_overlays") != null) return;

        FrameLayout holder = new FrameLayout(this);
        holder.setTag("td_overlays");
        holder.setClickable(false);
        holder.setFocusable(false);
        root.addView(holder, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout barWrap = new LinearLayout(this);
        barWrap.setOrientation(LinearLayout.VERTICAL);
        barWrap.setGravity(Gravity.CENTER_HORIZONTAL);

        // Top: hotkey panel, then expand/collapse control under it
        hotkeyBar = buildHotkeyBar();
        hotkeyBar.setVisibility(View.GONE);
        barWrap.addView(hotkeyBar);

        // Collapsed ▼ expands down; expanded ▲ collapses up
        Button arrow = styledBtn("▼", 0xCC263238);
        arrow.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(36)));
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        barWrap.addView(arrow);

        arrow.setOnClickListener(v -> {
            hotkeysVisible = !hotkeysVisible;
            hotkeyBar.setVisibility(hotkeysVisible ? View.VISIBLE : View.GONE);
            arrow.setText(hotkeysVisible ? "▲" : "▼");
        });

        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        barLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        barLp.topMargin = dp(6);
        barLp.leftMargin = dp(6);
        barLp.rightMargin = dp(6);
        holder.addView(barWrap, barLp);
    }

    private LinearLayout buildHotkeyBar() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xAA1A1A1A);
        bg.setCornerRadius(dp(12));
        col.setBackground(bg);
        col.setPadding(dp(6), dp(6), dp(6), dp(6));

        // Row 0: Esc + KB + commands
        LinearLayout row0 = new LinearLayout(this);
        row0.setOrientation(LinearLayout.HORIZONTAL);
        row0.setGravity(Gravity.CENTER);
        row0.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Button esc = keyBtn("Esc", android.view.KeyEvent.KEYCODE_ESCAPE, dp(48), dp(40));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(48), dp(40));
        elp.leftMargin = dp(3);
        elp.rightMargin = dp(3);
        esc.setLayoutParams(elp);
        row0.addView(esc);

        Button kb = styledBtn("KB", 0xCC37474F);
        LinearLayout.LayoutParams kblp = new LinearLayout.LayoutParams(dp(48), dp(40));
        kblp.leftMargin = dp(3);
        kblp.rightMargin = dp(3);
        kb.setLayoutParams(kblp);
        kb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        kb.setOnClickListener(v -> showAndroidKeyboard());
        row0.addView(kb);

        for (Object[] it : new Object[][]{
                {"S", android.view.KeyEvent.KEYCODE_S},
                {"G", android.view.KeyEvent.KEYCODE_G},
                {"D", android.view.KeyEvent.KEYCODE_D},
                {"F", android.view.KeyEvent.KEYCODE_F},
                {"X", android.view.KeyEvent.KEYCODE_X},
                {"Q", android.view.KeyEvent.KEYCODE_Q},
                {"W", android.view.KeyEvent.KEYCODE_W},
                {"E", android.view.KeyEvent.KEYCODE_E},
        }) {
            Button b = keyBtn((String) it[0], (Integer) it[1], dp(40), dp(40));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
            lp.leftMargin = dp(3);
            lp.rightMargin = dp(3);
            b.setLayoutParams(lp);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            row0.addView(b);
        }
        col.addView(row0);

        col.addView(hotRow(new Object[][]{
                {"Ctrl", android.view.KeyEvent.KEYCODE_CTRL_LEFT},
                {"Alt", android.view.KeyEvent.KEYCODE_ALT_LEFT},
                {"Shf", android.view.KeyEvent.KEYCODE_SHIFT_LEFT},
                {"Tab", android.view.KeyEvent.KEYCODE_TAB},
                {"H", android.view.KeyEvent.KEYCODE_H},
                {"N", android.view.KeyEvent.KEYCODE_N},
                {"Spc", android.view.KeyEvent.KEYCODE_SPACE},
                {"Ent", android.view.KeyEvent.KEYCODE_ENTER},
        }));

        col.addView(hotRow(new Object[][]{
                {"1", android.view.KeyEvent.KEYCODE_1},
                {"2", android.view.KeyEvent.KEYCODE_2},
                {"3", android.view.KeyEvent.KEYCODE_3},
                {"4", android.view.KeyEvent.KEYCODE_4},
                {"5", android.view.KeyEvent.KEYCODE_5},
                {"6", android.view.KeyEvent.KEYCODE_6},
                {"7", android.view.KeyEvent.KEYCODE_7},
                {"8", android.view.KeyEvent.KEYCODE_8},
                {"9", android.view.KeyEvent.KEYCODE_9},
                {"0", android.view.KeyEvent.KEYCODE_0},
        }));

        return col;
    }

    private LinearLayout hotRow(Object[][] items) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(4);
        row.setLayoutParams(rowLp);
        for (Object[] it : items) {
            String label = (String) it[0];
            int key = (Integer) it[1];
            Button b = keyBtn(label, key, dp(40), dp(40));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
            lp.leftMargin = dp(3);
            lp.rightMargin = dp(3);
            b.setLayoutParams(lp);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            if (key == android.view.KeyEvent.KEYCODE_CTRL_LEFT
                    || key == android.view.KeyEvent.KEYCODE_ALT_LEFT
                    || key == android.view.KeyEvent.KEYCODE_SHIFT_LEFT) {
                b.setOnTouchListener((v, e) -> {
                    if (e.getAction() == MotionEvent.ACTION_DOWN) {
                        SDLActivity.onNativeKeyDown(key);
                    } else if (e.getAction() == MotionEvent.ACTION_UP
                            || e.getAction() == MotionEvent.ACTION_CANCEL) {
                        SDLActivity.onNativeKeyUp(key);
                    }
                    return true;
                });
            }
            row.addView(b);
        }
        return row;
    }

    private Button keyBtn(String label, int keyCode, int w, int h) {
        Button b = styledBtn(label, 0xCC37474F);
        b.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        b.setOnClickListener(v -> pulseKey(keyCode));
        if (keyCode >= android.view.KeyEvent.KEYCODE_0
                && keyCode <= android.view.KeyEvent.KEYCODE_9) {
            b.setOnLongClickListener(v -> {
                SDLActivity.onNativeKeyDown(android.view.KeyEvent.KEYCODE_CTRL_LEFT);
                pulseKey(keyCode);
                handler.postDelayed(
                        () -> SDLActivity.onNativeKeyUp(android.view.KeyEvent.KEYCODE_CTRL_LEFT), 60);
                return true;
            });
        }
        return b;
    }

    private void pulseKey(int keyCode) {
        SDLActivity.onNativeKeyDown(keyCode);
        handler.postDelayed(() -> SDLActivity.onNativeKeyUp(keyCode), 40);
    }

    private Button styledBtn(String label, int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        b.setPadding(dp(8), dp(6), dp(8), dp(6));
        return b;
    }

    private float[] mapTouch(float x, float y) {
        View surf = mSurface;
        int sw = (surf != null && surf.getWidth() > 0) ? surf.getWidth() : 1;
        int sh = (surf != null && surf.getHeight() > 0) ? surf.getHeight() : 1;
        SharedPreferences prefs = getSharedPreferences("vanilla_td", MODE_PRIVATE);
        String mode = prefs.getString("display_mode", "fill");
        int gw = prefs.getInt("res_w", -1);
        int gh = prefs.getInt("res_h", -1);
        if (!"letterbox".equals(mode) || gw <= 0 || gh <= 0) {
            return new float[]{x, y};
        }
        float scale = Math.min(sw / (float) gw, sh / (float) gh);
        float cw = Math.max(1f, gw * scale);
        float ch = Math.max(1f, gh * scale);
        float left = (sw - cw) * 0.5f;
        float top = (sh - ch) * 0.5f;
        float lx = (x - left) / cw;
        float ly = (y - top) / ch;
        if (lx < 0f) lx = 0f; else if (lx > 1f) lx = 1f;
        if (ly < 0f) ly = 0f; else if (ly > 1f) ly = 1f;
        return new float[]{lx * sw, ly * sh};
    }

    void absMove(float x, float y) {
        float[] m = mapTouch(x, y);
        SDLActivity.onNativeMouse(0, MotionEvent.ACTION_MOVE, m[0], m[1], false);
    }

    void leftDown(float x, float y) {
        float[] m = mapTouch(x, y);
        SDLActivity.onNativeMouse(0, MotionEvent.ACTION_MOVE, m[0], m[1], false);
        SDLActivity.onNativeMouse(1, MotionEvent.ACTION_DOWN, m[0], m[1], false);
    }

    void leftUp(float x, float y) {
        float[] m = mapTouch(x, y);
        SDLActivity.onNativeMouse(0, MotionEvent.ACTION_MOVE, m[0], m[1], false);
        SDLActivity.onNativeMouse(1, MotionEvent.ACTION_UP, m[0], m[1], false);
    }

    void rightClick(float x, float y) {
        float[] m = mapTouch(x, y);
        SDLActivity.onNativeMouse(0, MotionEvent.ACTION_MOVE, m[0], m[1], false);
        SDLActivity.onNativeMouse(2, MotionEvent.ACTION_DOWN, m[0], m[1], false);
        final float mx = m[0], my = m[1];
        handler.postDelayed(() ->
                SDLActivity.onNativeMouse(2, MotionEvent.ACTION_UP, mx, my, false), 40);
    }

    void leftClick(float x, float y) {
        absMove(x, y);
        handler.postDelayed(() -> {
            leftDown(x, y);
            handler.postDelayed(() -> leftUp(x, y), 40);
        }, 16);
    }

    void leftDoubleClick(float x, float y) {
        leftClick(x, y);
        handler.postDelayed(() -> leftClick(x, y), 100);
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
        if (hasFocus) {
            hideSystemUI();
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        requestGameAudioFocus();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Ensure SDL native state advances to RESUMED after Recents restore
        // (surface may already be ready; handleNativeState is idempotent).
        try {
            SDLActivity.handleNativeState();
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onPause() {
        abandonGameAudioFocus();
        // Battery: allow screen off while backgrounded
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onPause();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // Intentionally do not call nativePause here. SDLActivity already
        // pauses when backgrounded; extra nativePause calls stack and leave
        // the game hung when returning from Recents.
    }


    private void requestGameAudioFocus() {
        if (audioManager == null) {
            audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        }
        if (audioManager == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (audioFocusRequest == null) {
                    AudioAttributes attrs = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build();
                    audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                            .setAudioAttributes(attrs)
                            .setOnAudioFocusChangeListener(focusListener)
                            .setAcceptsDelayedFocusGain(true)
                            .build();
                }
                audioManager.requestAudioFocus(audioFocusRequest);
            } else {
                audioManager.requestAudioFocus(focusListener,
                        AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            }
        } catch (Throwable ignored) {}
    }

    private void abandonGameAudioFocus() {
        if (audioManager == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (audioFocusRequest != null) {
                    audioManager.abandonAudioFocusRequest(audioFocusRequest);
                }
            } else {
                audioManager.abandonAudioFocus(focusListener);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Rusted Warfare–style touch → mouse:
     * tap / double-tap / drag-select / long-press RMB / hold-drag pan /
     * two-finger pan (stops when still) / two-finger tap RMB.
     */
    private class TouchSurface extends SDLSurface {
        private static final int LONG_MS = 450;
        private static final float MOVE_PX = 24f;
        private static final int DOUBLE_MS = 320;

        private float downX, downY;
        private float lastX, lastY;
        private long downAt;
        private boolean moved;
        private boolean dragging;
        private boolean longFired;
        private boolean twoFinger;
        private boolean panMode;
        private boolean twoFingerMoved;
        private long twoFingerDownAt;
        private float twoFingerTapX, twoFingerTapY;
        private float lastPanX, lastPanY;
        private long lastTapAt;
        private float lastTapX, lastTapY;

        private final Runnable longCheck = new Runnable() {
            @Override public void run() {
                if (!moved && !dragging && !twoFinger && !longFired) {
                    longFired = true;
                    panMode = true; // hold-then-drag pans; pure hold+release = RMB on UP
                }
            }
        };

        TouchSurface(Context context) {
            super(context);
            setClickable(true);
            setFocusable(true);
            if (Build.VERSION.SDK_INT >= 24) {
                setPointerIcon(android.view.PointerIcon.getSystemIcon(
                        context, android.view.PointerIcon.TYPE_NULL));
            }
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            int action = event.getActionMasked();
            int count = event.getPointerCount();

            switch (action) {
                case MotionEvent.ACTION_DOWN: {
                    downX = lastX = event.getX();
                    downY = lastY = event.getY();
                    downAt = System.currentTimeMillis();
                    moved = false;
                    dragging = false;
                    longFired = false;
                    panMode = false;
                    twoFinger = false;
                    twoFingerMoved = false;
                    absMove(downX, downY);
                    handler.postDelayed(longCheck, LONG_MS);
                    return true;
                }
                case MotionEvent.ACTION_POINTER_DOWN: {
                    handler.removeCallbacks(longCheck);
                    if (dragging) {
                        leftUp(lastX, lastY);
                        dragging = false;
                    }
                    twoFinger = true;
                    longFired = true;
                    twoFingerMoved = false;
                    twoFingerDownAt = System.currentTimeMillis();
                    lastPanX = event.getX(0);
                    lastPanY = event.getY(0);
                    if (count > 1) {
                        lastPanX = (event.getX(0) + event.getX(1)) / 2f;
                        lastPanY = (event.getY(0) + event.getY(1)) / 2f;
                    }
                    twoFingerTapX = lastPanX;
                    twoFingerTapY = lastPanY;
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (twoFinger && count >= 2) {
                        float cx = (event.getX(0) + event.getX(1)) / 2f;
                        float cy = (event.getY(0) + event.getY(1)) / 2f;
                        float dx = cx - lastPanX;
                        float dy = cy - lastPanY;
                        if (Math.hypot(dx, dy) > 8f) {
                            twoFingerMoved = true;
                        }
                        lastPanX = cx;
                        lastPanY = cy;
                        if (twoFingerMoved) {
                            panViaEdge(dx, dy);
                        }
                        return true;
                    }
                    if (longFired && !dragging && !panMode) {
                        return true;
                    }
                    float x = event.getX();
                    float y = event.getY();
                    float dist = (float) Math.hypot(x - downX, y - downY);
                    if (panMode || twoFinger) {
                        float dx = x - lastX;
                        float dy = y - lastY;
                        panViaEdge(dx, dy);
                        lastX = x;
                        lastY = y;
                        moved = true;
                        return true;
                    }
                    absMove(x, y);
                    if (!moved && dist > MOVE_PX) {
                        moved = true;
                        handler.removeCallbacks(longCheck);
                        dragging = true;
                        leftDown(downX, downY);
                        absMove(x, y);
                    }
                    lastX = x;
                    lastY = y;
                    return true;
                }
                case MotionEvent.ACTION_POINTER_UP: {
                    if (count <= 2) {
                        long dt = System.currentTimeMillis() - twoFingerDownAt;
                        if (!twoFingerMoved && dt < 350) {
                            rightClick(twoFingerTapX, twoFingerTapY);
                        }
                        twoFinger = false;
                        longFired = true;
                        stopPan();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    handler.removeCallbacks(longCheck);
                    float x = event.getX();
                    float y = event.getY();
                    if (dragging) {
                        leftUp(x, y);
                        dragging = false;
                    } else if (panMode && !moved) {
                        rightClick(x, y);
                    } else if (!longFired && !twoFinger && !moved) {
                        long now = System.currentTimeMillis();
                        boolean isDouble = (now - lastTapAt) < DOUBLE_MS
                                && Math.hypot(x - lastTapX, y - lastTapY) < MOVE_PX * 2;
                        if (isDouble) {
                            leftDoubleClick(x, y);
                            lastTapAt = 0;
                        } else {
                            leftClick(x, y);
                            lastTapAt = now;
                            lastTapX = x;
                            lastTapY = y;
                        }
                    }
                    if (twoFinger || panMode) {
                        stopPan();
                    }
                    twoFinger = false;
                    panMode = false;
                    return true;
                }
            }
            return true;
        }

        /** Edge-scroll while moving; center cursor when still so pan stops. */
        private void panViaEdge(float dx, float dy) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0) w = 1;
            if (h <= 0) h = 1;
            if (Math.abs(dx) <= 2f && Math.abs(dy) <= 2f) {
                stopPan();
                return;
            }
            float ex = w / 2f;
            float ey = h / 2f;
            if (Math.abs(dx) > 2f) {
                ex = dx < 0 ? 1f : (w - 2f);
            }
            if (Math.abs(dy) > 2f) {
                ey = dy < 0 ? 1f : (h - 2f);
            }
            absMove(ex, ey);
        }

        private void stopPan() {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0) w = 1;
            if (h <= 0) h = 1;
            absMove(w / 2f, h / 2f);
        }
    }

    private void ensureDefaultOptions(File dir) {
        if (dir == null) return;
        dir.mkdirs();
        File ini = new File(dir, "conquer.ini");
        try {
            java.util.ArrayList<String> lines = new java.util.ArrayList<String>();
            if (ini.exists()) {
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(
                                new java.io.FileInputStream(ini),
                                java.nio.charset.StandardCharsets.UTF_8));
                String line;
                while ((line = br.readLine()) != null) lines.add(line);
                br.close();
            }

            boolean inOptions = false, inIntro = false, inExp = false;
            boolean sawOptions = false, sawIntro = false, sawExp = false;
            boolean haveGS = false, haveSR = false, havePlayIntro = false, haveAM = false;
            java.util.ArrayList<String> out = new java.util.ArrayList<String>();

            for (int i = 0; i < lines.size(); i++) {
                String raw = lines.get(i);
                String trimmed = raw.trim();
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    inOptions = trimmed.equalsIgnoreCase("[Options]");
                    inIntro = trimmed.equalsIgnoreCase("[Intro]");
                    inExp = trimmed.equalsIgnoreCase("[Expansions]");
                    if (inOptions) sawOptions = true;
                    if (inIntro) sawIntro = true;
                    if (inExp) sawExp = true;
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
                            // Keep player choice; default injected below is 5 at 60fps
                            out.add("ScrollRate=" + val);
                            haveSR = true;
                            continue;
                        }
                    }
                }
                if (inIntro) {
                    int eq = trimmed.indexOf('=');
                    if (eq > 0 && trimmed.substring(0, eq).trim().equalsIgnoreCase("PlayIntro")) {
                        out.add("PlayIntro=false");
                        havePlayIntro = true;
                        continue;
                    }
                }
                if (inExp) {
                    int eq = trimmed.indexOf('=');
                    if (eq > 0) {
                        String key = trimmed.substring(0, eq).trim();
                        if (key.equalsIgnoreCase("AftermathEnabled")
                                || key.equalsIgnoreCase("CounterstrikeEnabled")) {
                            // Preserve value chosen by DataSetupActivity
                            out.add(raw);
                            if (key.equalsIgnoreCase("AftermathEnabled")) haveAM = true;
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
                out = injectAfterSection(out, "[Options]",
                        haveGS ? null : "GameSpeed=1",
                        haveSR ? null : "ScrollRate=5");
            }

            if (!sawIntro) {
                out.add("");
                out.add("[Intro]");
                out.add("PlayIntro=false");
            } else if (!havePlayIntro) {
                out = injectAfterSection(out, "[Intro]", "PlayIntro=false", null);
            }

            // Expansions section is owned by DataSetupActivity — do not force flags here.
            // Only create a safe default if the whole section is missing.
            if (!sawExp) {
                out.add("");
                out.add("[Expansions]");
                out.add("CounterstrikeEnabled=no");
                out.add("AftermathEnabled=no");
            }

            // Unique LAN name so two devices are not both "Red-Alert"
            ensureUniqueHandle(out);

            java.io.FileOutputStream fos = new java.io.FileOutputStream(ini);
            for (int i = 0; i < out.size(); i++) {
                fos.write(out.get(i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                fos.write('\n');
            }
            fos.close();
        } catch (Exception ignored) {}
    }


    /**
     * Ensure [MultiPlayer] Handle is unique on this device.
     * Stock default "Red-Alert" collides when two phones join the same LAN game.
     */
    private void ensureUniqueHandle(java.util.ArrayList<String> out) {
        String desired = buildDefaultHandle();
        boolean inMp = false;
        boolean sawMp = false;
        boolean haveHandle = false;
        for (int i = 0; i < out.size(); i++) {
            String trimmed = out.get(i).trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                inMp = trimmed.equalsIgnoreCase("[MultiPlayer]");
                if (inMp) sawMp = true;
                continue;
            }
            if (inMp) {
                int eq = trimmed.indexOf('=');
                if (eq > 0 && trimmed.substring(0, eq).trim().equalsIgnoreCase("Handle")) {
                    String val = trimmed.substring(eq + 1).trim();
                    if (val.isEmpty()
                            || val.equalsIgnoreCase("Red-Alert")
                            || val.equalsIgnoreCase("Tiberian Dawn")
                            || val.equalsIgnoreCase("Player")) {
                        out.set(i, "Handle=" + desired);
                    }
                    haveHandle = true;
                }
            }
        }
        if (!sawMp) {
            out.add("");
            out.add("[MultiPlayer]");
            out.add("Handle=" + desired);
        } else if (!haveHandle) {
            java.util.ArrayList<String> injected =
                    injectAfterSection(out, "[MultiPlayer]", "Handle=" + desired, null);
            out.clear();
            out.addAll(injected);
        }
    }

    private String buildDefaultHandle() {
        String id = "";
        try {
            id = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Throwable ignored) {}
        if (id == null || id.length() < 4) {
            String model = Build.MODEL != null ? Build.MODEL.replaceAll("[^A-Za-z0-9]", "") : "RA";
            if (model.length() > 6) model = model.substring(0, 6);
            id = model + Integer.toHexString((int) (System.currentTimeMillis() & 0xffff));
        }
        String suffix = id.length() > 4 ? id.substring(id.length() - 4) : id;
        return "TD-" + suffix;
    }

    private static java.util.ArrayList<String> injectAfterSection(
            java.util.ArrayList<String> src, String section, String a, String b) {
        java.util.ArrayList<String> injected = new java.util.ArrayList<String>();
        boolean done = false;
        for (int i = 0; i < src.size(); i++) {
            String raw = src.get(i);
            injected.add(raw);
            if (!done && raw.trim().equalsIgnoreCase(section)) {
                if (a != null) injected.add(a);
                if (b != null) injected.add(b);
                done = true;
            }
        }
        return injected;
    }

    /** Toggle Android IME so the player-name (and other) edit boxes can receive text. */
    
    /**
     * Touch accuracy requires absolute mouse mapping.
     * Engine defaults: Mouse.RawInput=true uses only xrel (no scale) so a 1080p
     * screen overshoots a 640-wide game field; Video.Boxing letterboxes and
     * offsets the drawable without adjusting mouse. Force both off on Android.
     */
    private void ensureMobileInputSettings(File dir) {
        if (dir == null) return;
        dir.mkdirs();
        File ini = new File(dir, "conquer.ini");
        try {
            String text = "";
            if (ini.exists()) {
                byte[] raw = new byte[(int) ini.length()];
                java.io.FileInputStream in = new java.io.FileInputStream(ini);
                int off = 0;
                while (off < raw.length) {
                    int n = in.read(raw, off, raw.length - off);
                    if (n < 0) break;
                    off += n;
                }
                in.close();
                text = new String(raw, 0, off, "UTF-8");
            }
            text = upsertSectionKey(text, "Mouse", "RawInput", "no");
            text = upsertSectionKey(text, "Mouse", "Sensitivity", "100");

            // Prefer ini already written by DataSetup (auto / manual).
            // Only fill missing defaults if section empty.
            SharedPreferences prefs = getSharedPreferences("vanilla_td", MODE_PRIVATE);
            String mode = prefs.getString("display_mode", "fill");
            String scaler = prefs.getString("render_scaler", "nearest");
            int rw = prefs.getInt("res_w", -1);
            int rh = prefs.getInt("res_h", -1);
            boolean boxing = "letterbox".equals(mode);
            if (rw >= 0 && rh >= 0) {
                String aspect = (rw > 0 && rh > 0) ? (rw + ":" + rh) : "16:10";
                text = upsertSectionKey(text, "Video", "Width", String.valueOf(rw));
                text = upsertSectionKey(text, "Video", "Height", String.valueOf(rh));
                text = upsertSectionKey(text, "Video", "Boxing", boxing ? "yes" : "no");
                text = upsertSectionKey(text, "Video", "BoxingAspectRatio", aspect);
            }
            text = upsertSectionKey(text, "Video", "Windowed", "no");
            text = upsertSectionKey(text, "Video", "Scaler", scaler);
            text = upsertSectionKey(text, "Video", "HardwareCursor", "no");
            text = upsertSectionKey(text, "Video", "FrameLimit", "60");

            java.io.FileOutputStream out = new java.io.FileOutputStream(ini);
            out.write(text.getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) {}
    }

    private static String upsertSectionKey(String text, String section, String key, String value) {
        if (text == null) text = "";
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        boolean inSec = false;
        boolean sawSec = false;
        boolean wrote = false;
        for (String line : lines) {
            String tr = line.trim();
            if (tr.startsWith("[") && tr.endsWith("]")) {
                if (inSec && !wrote) {
                    out.append(key).append('=').append(value).append('\n');
                    wrote = true;
                }
                inSec = tr.equalsIgnoreCase("[" + section + "]");
                if (inSec) sawSec = true;
                out.append(line).append('\n');
                continue;
            }
            if (inSec) {
                int eq = tr.indexOf('=');
                if (eq > 0 && tr.substring(0, eq).trim().equalsIgnoreCase(key)) {
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
        if (!sawSec) {
            out.append('\n').append('[').append(section).append("]\n");
            out.append(key).append('=').append(value).append('\n');
        }
        return out.toString();
    }

    private void showAndroidKeyboard() {
        try {
            SDLActivity.showTextInput(0, 0, dp(200), dp(40));
        } catch (Throwable t1) {
            try {
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager)
                                getSystemService(INPUT_METHOD_SERVICE);
                View focus = getCurrentFocus();
                if (focus == null) focus = getWindow().getDecorView();
                focus.setFocusable(true);
                focus.setFocusableInTouchMode(true);
                focus.requestFocus();
                imm.showSoftInput(focus, android.view.inputmethod.InputMethodManager.SHOW_FORCED);
            } catch (Throwable ignored) {}
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
