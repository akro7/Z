package com.fs4ip.delta;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.entity.pm.InstallResult;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    // ── Constants ────────────────────────────────────────────────────────────
    private static final String TAG            = "AKRO_Main";
    private static final String PREFS          = "akro_prefs";
    private static final String PREF_KEY       = "lic_key";
    private static final String KEY_FILE       = ".tiger_k";
    private static final String MENU_FLAG      = ".menu_enabled";
    private static final String TARGET_PKG     = "com.miniclip.eightballpool";
    private static final String CONNECT_URL    = "http://akrogoxi.x10.mx/connect";
    private static final String TELEGRAM_URL   = "https://t.me/A_KOJ0";
    private static final String DEV_NAME       = "Dev Akro";
    private static final String DEV_FLAG       = "🇪🇬";

    // Colors
    private static final int C_BG         = 0xFF0A0A0F;
    private static final int C_CARD       = 0xFF12121A;
    private static final int C_BLUE       = 0xFF2979FF;
    private static final int C_RED        = 0xFFFF1744;
    private static final int C_TEXT       = 0xFFEEEEEE;
    private static final int C_HINT       = 0xFF555566;
    private static final int C_SUCCESS    = 0xFF00E676;
    private static final int C_ORANGE     = 0xFFFF9100;

    // ── State ────────────────────────────────────────────────────────────────
    private SharedPreferences prefs;
    private String savedKey      = "";
    private boolean isLoggedIn   = false;
    private boolean isGameReady  = false;   // true only after confirmed install in virtual space
    private String expiryText    = "";

    // ── UI references ─────────────────────────────────────────────────────────
    private FrameLayout  rootFrame;
    private LinearLayout loginPanel, mainPanel;
    private EditText     etKey;
    private TextView     tvStatus, tvExpiry;
    private Switch       swMenu;
    private ProgressBar  progressBar;
    private View         btnRetrieve, btnLaunch;
    private TextView     tvRetrieveStatus;

    private final Handler       handler  = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // ────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        getWindow().setNavigationBarColor(C_BG);
        getWindow().setStatusBarColor(C_BG);

        prefs    = getSharedPreferences(PREFS, MODE_PRIVATE);
        savedKey = prefs.getString(PREF_KEY, "");

        buildUI();

        if (!savedKey.isEmpty()) {
            showStatus("Verifying session…", C_HINT);
            silentVerify(savedKey);
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }

    // ────────────────────────────────────────────────────────────────────────
    // UI build
    // ────────────────────────────────────────────────────────────────────────

    private void buildUI() {
        rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(C_BG);
        setContentView(rootFrame);

        // Background image
        try {
            Bitmap bg = BitmapFactory.decodeResource(getResources(), R.drawable.bg_akro);
            ImageView bgView = new ImageView(this);
            bgView.setImageBitmap(bg);
            bgView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            bgView.setAlpha(0.35f);
            rootFrame.addView(bgView, matchAll());
        } catch (Exception ignored) {}

        // Gradient overlay
        View overlay = new View(this);
        GradientDrawable grad = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0xCC0A0A0F, 0xEE0A0A0F, 0xFF0A0A0F}
        );
        overlay.setBackground(grad);
        rootFrame.addView(overlay, matchAll());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        rootFrame.addView(scroll, matchAll());

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(20), dp(60), dp(20), dp(32));
        scroll.addView(content);

        buildHeader(content);
        buildLoginPanel(content);
        buildMainPanel(content);

        tvStatus = new TextView(this);
        tvStatus.setGravity(Gravity.CENTER);
        tvStatus.setTextSize(12f);
        tvStatus.setPadding(0, dp(8), 0, 0);
        content.addView(tvStatus, fullW());

        updatePanels();
    }

    private void buildHeader(LinearLayout parent) {
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.VERTICAL);
        titleRow.setGravity(Gravity.CENTER);

        TextView crown = new TextView(this);
        crown.setText("👑");
        crown.setTextSize(32f);
        crown.setGravity(Gravity.CENTER);
        titleRow.addView(crown, fullW());

        space(titleRow, 8);

        TextView titleLeft = new TextView(this);
        titleLeft.setText("AKRO");
        titleLeft.setTextColor(C_BLUE);
        titleLeft.setTextSize(38f);
        titleLeft.setTypeface(null, Typeface.BOLD);
        titleLeft.setGravity(Gravity.CENTER);
        titleLeft.setLetterSpacing(0.15f);

        TextView titleRight = new TextView(this);
        titleRight.setText(" LOADER");
        titleRight.setTextColor(C_RED);
        titleRight.setTextSize(38f);
        titleRight.setTypeface(null, Typeface.BOLD);
        titleRight.setGravity(Gravity.CENTER);
        titleRight.setLetterSpacing(0.15f);

        LinearLayout titleLine = new LinearLayout(this);
        titleLine.setOrientation(LinearLayout.HORIZONTAL);
        titleLine.setGravity(Gravity.CENTER);
        titleLine.addView(titleLeft);
        titleLine.addView(titleRight);
        titleRow.addView(titleLine, fullW());

        TextView sub = new TextView(this);
        sub.setText("8 Ball Pool Mod Engine");
        sub.setTextColor(C_HINT);
        sub.setTextSize(12f);
        sub.setGravity(Gravity.CENTER);
        sub.setLetterSpacing(0.1f);
        titleRow.addView(sub, fullW());

        space(titleRow, 6);

        TextView devBadge = new TextView(this);
        devBadge.setText(DEV_FLAG + "  " + DEV_NAME + "  ·  @A_KOJ0");
        devBadge.setTextColor(0xFF888899);
        devBadge.setTextSize(11f);
        devBadge.setGravity(Gravity.CENTER);
        devBadge.setOnClickListener(v -> openUrl(TELEGRAM_URL));
        titleRow.addView(devBadge, fullW());

        parent.addView(titleRow, fullW());
        space(parent, 32);
    }

    private void buildLoginPanel(LinearLayout parent) {
        loginPanel = new LinearLayout(this);
        loginPanel.setOrientation(LinearLayout.VERTICAL);

        LinearLayout card = card();

        TextView lbl = label("LICENSE KEY");
        card.addView(lbl, fullW());
        space(card, 8);

        etKey = new EditText(this);
        etKey.setHint("Enter your license key…");
        etKey.setHintTextColor(C_HINT);
        etKey.setTextColor(C_TEXT);
        etKey.setTextSize(14f);
        etKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        etKey.setBackground(inputBg());
        etKey.setPadding(dp(14), dp(12), dp(14), dp(12));
        if (!savedKey.isEmpty()) etKey.setText(savedKey);
        card.addView(etKey, fullW());

        space(card, 16);

        View btnLogin = neonButton("VERIFY & ACTIVATE", C_BLUE);
        btnLogin.setOnClickListener(v -> onLoginClick());
        card.addView(btnLogin, fullW());

        space(card, 12);

        View div = new View(this);
        div.setBackgroundColor(0xFF222233);
        card.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        space(card, 12);

        LinearLayout buyRow = new LinearLayout(this);
        buyRow.setOrientation(LinearLayout.HORIZONTAL);
        buyRow.setGravity(Gravity.CENTER);

        TextView noKey = new TextView(this);
        noKey.setText("No key?  ");
        noKey.setTextColor(C_HINT);
        noKey.setTextSize(13f);
        buyRow.addView(noKey);

        TextView buyKey = new TextView(this);
        buyKey.setText("Contact @A_KOJ0 on Telegram");
        buyKey.setTextColor(C_BLUE);
        buyKey.setTextSize(13f);
        buyKey.setOnClickListener(v -> openUrl(TELEGRAM_URL));
        buyRow.addView(buyKey);

        card.addView(buyRow, fullW());
        loginPanel.addView(card, fullW());
        parent.addView(loginPanel, fullW());
    }

    private void buildMainPanel(LinearLayout parent) {
        mainPanel = new LinearLayout(this);
        mainPanel.setOrientation(LinearLayout.VERTICAL);
        mainPanel.setVisibility(View.GONE);

        // ── Activated status card ──────────────────────────────────────
        LinearLayout statusCard = card();
        statusCard.setOrientation(LinearLayout.HORIZONTAL);
        statusCard.setGravity(Gravity.CENTER_VERTICAL);

        TextView avatar = new TextView(this);
        avatar.setText("⚡");
        avatar.setTextSize(28f);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(circleBg(0x33FF1744));
        avatar.setPadding(dp(12), dp(12), dp(12), dp(12));
        statusCard.addView(avatar, new LinearLayout.LayoutParams(dp(56), dp(56)));

        space(statusCard, 14);

        LinearLayout statusInfo = new LinearLayout(this);
        statusInfo.setOrientation(LinearLayout.VERTICAL);
        statusInfo.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView statusTitle = new TextView(this);
        statusTitle.setText("ACTIVATED");
        statusTitle.setTextColor(C_SUCCESS);
        statusTitle.setTextSize(13f);
        statusTitle.setTypeface(null, Typeface.BOLD);
        statusTitle.setLetterSpacing(0.1f);
        statusInfo.addView(statusTitle, fullW());

        tvExpiry = new TextView(this);
        tvExpiry.setTextColor(C_HINT);
        tvExpiry.setTextSize(11f);
        statusInfo.addView(tvExpiry, fullW());

        statusCard.addView(statusInfo);
        mainPanel.addView(statusCard, fullW());

        space(mainPanel, 14);

        // ── Menu toggle card ───────────────────────────────────────────
        LinearLayout toggleCard = card();
        toggleCard.setOrientation(LinearLayout.HORIZONTAL);
        toggleCard.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout toggleInfo = new LinearLayout(this);
        toggleInfo.setOrientation(LinearLayout.VERTICAL);
        toggleInfo.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView toggleTitle = new TextView(this);
        toggleTitle.setText("SHOW MOD MENU");
        toggleTitle.setTextColor(C_TEXT);
        toggleTitle.setTextSize(14f);
        toggleTitle.setTypeface(null, Typeface.BOLD);
        toggleInfo.addView(toggleTitle, fullW());

        TextView toggleSub = new TextView(this);
        toggleSub.setText("Display menu overlay inside the game");
        toggleSub.setTextColor(C_HINT);
        toggleSub.setTextSize(11f);
        toggleInfo.addView(toggleSub, fullW());

        toggleCard.addView(toggleInfo);

        swMenu = new Switch(this);
        swMenu.setChecked(true);
        swMenu.setThumbTintList(android.content.res.ColorStateList.valueOf(C_BLUE));
        swMenu.setTrackTintList(android.content.res.ColorStateList.valueOf(0x662979FF));
        toggleCard.addView(swMenu);

        mainPanel.addView(toggleCard, fullW());

        space(mainPanel, 14);

        // ── Retrieve status label ──────────────────────────────────────
        tvRetrieveStatus = new TextView(this);
        tvRetrieveStatus.setGravity(Gravity.CENTER);
        tvRetrieveStatus.setTextSize(11f);
        tvRetrieveStatus.setPadding(0, 0, 0, dp(6));
        tvRetrieveStatus.setVisibility(View.GONE);
        mainPanel.addView(tvRetrieveStatus, fullW());

        // ── Progress bar ───────────────────────────────────────────────
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        progressBar.getIndeterminateDrawable().setTint(C_ORANGE);
        mainPanel.addView(progressBar, fullW());

        space(mainPanel, 8);

        // ── RETRIEVE button — shown when game not yet in virtual space ─
        btnRetrieve = neonButton("⬇   RETRIEVE 8 BALL POOL", C_ORANGE);
        btnRetrieve.setVisibility(View.GONE);
        btnRetrieve.setOnClickListener(v -> startRetrieve());
        mainPanel.addView(btnRetrieve, fullW());

        space(mainPanel, 8);

        // ── LAUNCH button — shown only after game is retrieved ─────────
        btnLaunch = neonButton("▶   LAUNCH 8 BALL POOL", C_RED);
        btnLaunch.setVisibility(View.GONE);
        btnLaunch.setOnClickListener(v -> verifyThenLaunch());
        mainPanel.addView(btnLaunch, fullW());

        space(mainPanel, 16);

        View div2 = new View(this);
        div2.setBackgroundColor(0xFF222233);
        mainPanel.addView(div2, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        space(mainPanel, 14);

        View btnLogout = outlineButton("Log Out", C_HINT);
        btnLogout.setOnClickListener(v -> logout());
        mainPanel.addView(btnLogout, fullW());

        parent.addView(mainPanel, fullW());
    }

    // ────────────────────────────────────────────────────────────────────────
    // Login flow
    // ────────────────────────────────────────────────────────────────────────

    private void onLoginClick() {
        String key = etKey.getText().toString().trim();
        if (key.isEmpty()) { toast("Enter your license key"); return; }
        showStatus("Connecting…", C_HINT);
        setLoading(true);
        executor.execute(() -> {
            String r = post(key);
            handler.post(() -> {
                setLoading(false);
                if (isOk(r)) onLoginSuccess(key, r);
                else { showStatus("❌  " + parseError(r), C_RED); toast(parseError(r)); }
            });
        });
    }

    private void silentVerify(String key) {
        executor.execute(() -> {
            String r = post(key);
            handler.post(() -> {
                if (isOk(r)) onLoginSuccess(key, r);
                else showStatus("", 0);
            });
        });
    }

    private void onLoginSuccess(String key, String json) {
        isLoggedIn = true;
        savedKey   = key;
        prefs.edit().putString(PREF_KEY, key).apply();
        writeTigerKey(key);
        expiryText = parseField(json, "expiry");
        tvExpiry.setText(expiryText.isEmpty() ? "Key valid" : "Expires: " + expiryText);
        showStatus("", 0);
        updatePanels();
        // Immediately check if game is already inside virtual space
        checkGameRetrieved();
    }

    private void logout() {
        new AlertDialog.Builder(this)
            .setTitle("Log Out")
            .setMessage("Are you sure you want to log out?")
            .setPositiveButton("Log Out", (d, w) -> {
                isLoggedIn  = false;
                isGameReady = false;
                savedKey    = "";
                prefs.edit().remove(PREF_KEY).apply();
                deleteTigerKey();
                showStatus("", 0);
                updatePanels();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ────────────────────────────────────────────────────────────────────────
    // Game retrieval — MUST succeed before launch is allowed
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Check if game is already installed in virtual space.
     * If yes: mark ready and show LAUNCH.
     * If no:  show RETRIEVE button.
     */
    private void checkGameRetrieved() {
        executor.execute(() -> {
            boolean installed = false;
            try {
                installed = BlackBoxCore.get().isInstalled(TARGET_PKG, 0);
            } catch (Throwable t) {
                Log.e(TAG, "isInstalled check failed: " + t.getMessage());
            }
            final boolean ready = installed;
            handler.post(() -> {
                isGameReady = ready;
                updateGameButtons();
                if (!ready) {
                    setRetrieveStatus("Game not retrieved yet — tap Retrieve first", C_ORANGE);
                }
            });
        });
    }

    /**
     * Retrieve = install the game's package into BlackBox virtual space.
     *
     * SOURCE MIRROR: BlackBoxManager.installPackage(packageName)
     *   → SamuraiEngineCore.installPackageAsUser(packageName, 0)
     *   → poll isInstalled() up to 100 × 100ms
     *
     * KEY FIX: pass TARGET_PKG (package name) directly to installPackageAsUser,
     * NOT an APK path — BlackBoxCore handles sourceDir resolution internally.
     */
    private void startRetrieve() {
        // Guard: game must be on device
        try {
            getPackageManager().getPackageInfo(TARGET_PKG, 0);
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            toast("8 Ball Pool is not installed — install it from Play Store first");
            return;
        }

        setLoading(true);
        setBtnRetrieveEnabled(false);
        setRetrieveStatus("Retrieving game…", C_ORANGE);

        executor.execute(() -> {
            try {
                // ── Step 1: already inside virtual space? ──────────────────────────
                if (BlackBoxCore.get().isInstalled(TARGET_PKG, 0)) {
                    handler.post(() -> {
                        setLoading(false);
                        isGameReady = true;
                        updateGameButtons();
                        setRetrieveStatus("Game ready ✓", C_SUCCESS);
                    });
                    return;
                }

                // ── Step 2: install by package name (source pattern) ───────────────
                // BlackBoxCore.installPackageAsUser(packageName, userId) internally
                // resolves sourceDir from PackageManager — same as SamuraiEngineCore.
                InstallResult result = BlackBoxCore.get().installPackageAsUser(TARGET_PKG, 0);

                if (result == null || !result.isSuccess()) {
                    String err = result != null ? result.getError() : "unknown error";
                    Log.e(TAG, "installPackageAsUser failed: " + err);
                    handler.post(() -> {
                        setLoading(false);
                        setBtnRetrieveEnabled(true);
                        setRetrieveStatus("Retrieve failed: " + err, C_RED);
                        toast("Retrieve failed: " + err);
                    });
                    return;
                }

                // ── Step 3: poll confirmation — mirror BlackBoxManager exactly ──────
                boolean confirmed = false;
                for (int i = 0; i < 100 && !confirmed; i++) {
                    confirmed = BlackBoxCore.get().isInstalled(TARGET_PKG, 0);
                    if (!confirmed) {
                        try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                    }
                }

                final boolean ok = confirmed;
                handler.post(() -> {
                    setLoading(false);
                    if (ok) {
                        isGameReady = true;
                        updateGameButtons();
                        setRetrieveStatus("Game ready ✓  — tap Launch", C_SUCCESS);
                        toast("Retrieval complete");
                    } else {
                        setBtnRetrieveEnabled(true);
                        setRetrieveStatus("Install timed out — try again", C_RED);
                    }
                });

            } catch (Throwable t) {
                Log.e(TAG, "startRetrieve exception: " + t.getMessage(), t);
                handler.post(() -> {
                    setLoading(false);
                    setBtnRetrieveEnabled(true);
                    setRetrieveStatus("Error: " + t.getMessage(), C_RED);
                });
            }
        });
    }

    // ────────────────────────────────────────────────────────────────────────
    // Launch flow — only reachable after isGameReady == true
    // ────────────────────────────────────────────────────────────────────────

    private void verifyThenLaunch() {
        if (!isLoggedIn)  { toast("Please login first");    return; }
        if (!isGameReady) { toast("Retrieve the game first"); return; }
        writeMenuToggle(swMenu.isChecked());
        setLoading(true);
        showStatus("Verifying…", C_HINT);
        executor.execute(() -> {
            String r = post(savedKey);
            handler.post(() -> {
                setLoading(false);
                if (isOk(r)) launchGame();
                else { showStatus("❌  Key expired", C_RED); logout(); }
            });
        });
    }

    /**
     * Launch via BlackBoxCore.launchApk — mirrors BlackBoxManager.launch(packageName).
     * BlackBoxCore.launchApk internally calls getLaunchIntent + startActivity.
     */
    private void launchGame() {
        showStatus("", 0);
        try {
            boolean launched = BlackBoxCore.get().launchApk(TARGET_PKG, 0);
            if (launched) {
                // Move LOADER to background so the game is in the foreground.
                // Without this, on some devices the LOADER's task stays active
                // on top of the game task, causing the "stuck on black screen"
                // or "immediately back to launcher" behaviour.
                // Mirrors Samurai: after launchApk() the host UI goes to back.
                moveTaskToBack(true);
            } else {
                toast("Launch failed — try retrieving again");
                isGameReady = false;
                updateGameButtons();
                setRetrieveStatus("Re-retrieve required", C_ORANGE);
            }
        } catch (Throwable t) {
            Log.e(TAG, "launchApk exception: " + t.getMessage(), t);
            toast("Launch error: " + t.getMessage());
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // UI state helpers
    // ────────────────────────────────────────────────────────────────────────

    private void updatePanels() {
        loginPanel.setVisibility(isLoggedIn ? View.GONE    : View.VISIBLE);
        mainPanel .setVisibility(isLoggedIn ? View.VISIBLE : View.GONE);
        if (isLoggedIn) updateGameButtons();
    }

    /**
     * Controls Retrieve vs Launch button visibility.
     * RETRIEVE visible when logged in but game not yet in virtual space.
     * LAUNCH   visible only when game is confirmed installed.
     */
    private void updateGameButtons() {
        if (btnRetrieve == null || btnLaunch == null) return;
        if (isGameReady) {
            btnRetrieve.setVisibility(View.GONE);
            btnLaunch  .setVisibility(View.VISIBLE);
        } else {
            btnRetrieve.setVisibility(View.VISIBLE);
            btnLaunch  .setVisibility(View.GONE);
        }
    }

    private void setLoading(boolean on) {
        if (progressBar != null)
            progressBar.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private void showStatus(String msg, int color) {
        if (tvStatus == null) return;
        tvStatus.setText(msg);
        tvStatus.setTextColor(color);
    }

    private void setRetrieveStatus(String msg, int color) {
        if (tvRetrieveStatus == null) return;
        tvRetrieveStatus.setText(msg);
        tvRetrieveStatus.setTextColor(color);
        tvRetrieveStatus.setVisibility(msg.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void setBtnRetrieveEnabled(boolean enabled) {
        if (btnRetrieve != null) btnRetrieve.setEnabled(enabled);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void openUrl(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception ignored) {}
    }

    // ────────────────────────────────────────────────────────────────────────
    // File helpers
    // ────────────────────────────────────────────────────────────────────────

    private void writeTigerKey(String key) {
        try (FileOutputStream o = new FileOutputStream(new File(getFilesDir(), KEY_FILE))) {
            o.write(key.getBytes());
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void deleteTigerKey() {
        try { new File(getFilesDir(), KEY_FILE).delete(); } catch (Exception ignored) {}
    }

    private void writeMenuToggle(boolean on) {
        try (FileOutputStream o = new FileOutputStream(new File(getFilesDir(), MENU_FLAG))) {
            o.write(on ? "1".getBytes() : "0".getBytes());
        } catch (IOException ignored) {}
    }

    // ────────────────────────────────────────────────────────────────────────
    // Network
    // ────────────────────────────────────────────────────────────────────────

    private String post(String key) {
        try {
            String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            String body = "game=" + Uri.encode("8BallPool")
                        + "&user_key=" + Uri.encode(key)
                        + "&serial=" + Uri.encode(androidId != null ? androidId : "");
            HttpURLConnection c = (HttpURLConnection) new URL(CONNECT_URL).openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
            c.getOutputStream().write(body.getBytes());
            int code = c.getResponseCode();
            InputStream is = code == 200 ? c.getInputStream() : c.getErrorStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = br.readLine()) != null) sb.append(l);
            String result = sb.toString();
            Log.d(TAG, "post() httpCode=" + code + " response=" + result);
            return result;
        } catch (Exception e) {
            Log.e(TAG, "post() FAILED: " + e, e);
            return null;
        }
    }

    private boolean isOk(String r) {
        if (r == null) return false;
        try {
            JSONObject j = new JSONObject(r);
            if (isStatusOk(j)) return true;
            JSONObject d = j.optJSONObject("data");
            return d != null && isStatusOk(d);
        } catch (Exception e) {
            Log.e(TAG, "isOk() JSON parse failed: " + r, e);
            return false;
        }
    }

    private boolean isStatusOk(JSONObject j) {
        Object status = j.opt("status");
        if (status instanceof Boolean) return (Boolean) status;
        if (status == null) return false;
        String s = String.valueOf(status).trim().toLowerCase();
        return s.equals("true") || s.equals("success") || s.equals("active")
            || s.equals("valid") || s.equals("ok") || s.equals("1");
    }

    private String parseField(String j, String f) {
        if (j == null) return "";
        try {
            int i = j.indexOf("\"" + f + "\":");
            if (i < 0) return "";
            int s = j.indexOf("\"", i + f.length() + 3) + 1;
            int e = j.indexOf("\"", s);
            return j.substring(s, e);
        } catch (Exception x) { return ""; }
    }

    private String parseError(String r) {
        if (r == null) return "No server response";
        try {
            JSONObject j = new JSONObject(r);
            String m = j.optString("message", j.optString("reason", ""));
            if (!m.isEmpty()) return m;
        } catch (Exception ignored) {}
        return "Invalid key";
    }

    // ────────────────────────────────────────────────────────────────────────
    // UI factories
    // ────────────────────────────────────────────────────────────────────────

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_CARD);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), 0xFF1E1E2E);
        c.setBackground(bg);
        return c;
    }

    private View neonButton(String text, int color) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(15f);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setLetterSpacing(0.08f);
        btn.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(12));
        btn.setBackground(bg);
        btn.setOnTouchListener((v, e) -> {
            if (e.getAction() == android.view.MotionEvent.ACTION_DOWN)
                btn.setAlpha(0.75f);
            else if (e.getAction() == android.view.MotionEvent.ACTION_UP
                     || e.getAction() == android.view.MotionEvent.ACTION_CANCEL)
                btn.setAlpha(1f);
            return false;
        });
        return btn;
    }

    private View outlineButton(String text, int color) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextColor(color);
        btn.setTextSize(13f);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.TRANSPARENT);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), color);
        btn.setBackground(bg);
        return btn;
    }

    private android.graphics.drawable.Drawable inputBg() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF0D0D18);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), 0xFF333355);
        return bg;
    }

    private android.graphics.drawable.Drawable circleBg(int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(color);
        return bg;
    }

    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(C_HINT);
        tv.setTextSize(11f);
        tv.setLetterSpacing(0.12f);
        tv.setTypeface(null, Typeface.BOLD);
        return tv;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams fullW() {
        return new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private FrameLayout.LayoutParams matchAll() {
        return new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        );
    }

    private void space(LinearLayout p, int dpVal) {
        View v = new View(this);
        p.addView(v, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(dpVal)
        ));
    }
}
