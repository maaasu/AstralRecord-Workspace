package net.astralrecord.mastereditor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONException;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLException;

public final class MainActivity extends Activity {
    private static final int BACKGROUND = 0xff11131f;
    private static final int SURFACE = 0xff1b1e2f;
    private static final int OUTLINE = 0xff3c405d;
    private static final int TEXT = 0xfff4f2ff;
    private static final int MUTED = 0xffbfc1d7;
    private static final int ACCENT = 0xffb7acff;
    private static final int WARNING = 0xffffcbaf;
    private static final long SESSION_CHECK_INTERVAL = 3000;
    private static final long REFRESH_ON_RESUME_AFTER = 25 * 60 * 1000;
    private static final int IMPORT_REQUEST = 10;

    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable sessionCheck = new Runnable() {
        @Override
        public void run() {
            if (hasEditorDocument() && !authenticating && !manualRetryRequired) {
                editor.evaluateJavascript("window.astralMobileSessionExpired === true", expired -> {
                    if (resumed && "true".equals(expired)) {
                        refreshSession();
                    }
                });
            }
            handler.postDelayed(this, SESSION_CHECK_INTERVAL);
        }
    };

    private SharedPreferences preferences;
    private EditorOrigin origin;
    private String deviceId;
    private LinearLayout root;
    private FrameLayout editorArea;
    private LinearLayout statusRow;
    private TextView statusText;
    private Button statusRetry;
    private EditText addressInput;
    private EditText codeInput;
    private TextView pairStatus;
    private Button pairButton;
    private WebView editor;
    private ValueCallback<Uri[]> importCallback;
    private long lastSession;
    private boolean editorDocumentReady;
    private boolean authenticating;
    private boolean manualRetryRequired;
    private boolean handlingBack;
    private boolean resumed;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("editor-device", MODE_PRIVATE);
        String savedAddress = preferences.getString("origin", null);
        deviceId = preferences.getString("deviceId", null);
        if (savedAddress != null && deviceId != null) {
            try {
                origin = EditorOrigin.parse(savedAddress);
            } catch (IllegalArgumentException invalidAddress) {
                deviceId = null;
            }
        }

        root = vertical();
        root.setBackgroundColor(BACKGROUND);
        applyInsets();
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::navigateBack);
        }

        if (origin == null || deviceId == null) {
            showPairing(deepLinkAddress(getIntent()));
        } else {
            showEditorShell();
            refreshSession();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String address = deepLinkAddress(intent);
        if (address != null && origin == null && addressInput != null && !authenticating) {
            addressInput.setText(address);
            codeInput.setText("");
            codeInput.requestFocus();
        }
    }

    private String deepLinkAddress(Intent intent) {
        Uri data = intent.getData();
        if (!Intent.ACTION_VIEW.equals(intent.getAction()) || data == null
                || !"astralrecord-editor".equals(data.getScheme()) || !"connect".equals(data.getHost())
                || data.getUserInfo() != null || data.getPort() != -1
                || (data.getPath() != null && !data.getPath().isEmpty()) || data.getFragment() != null) {
            return null;
        }
        String address = data.getQueryParameter("origin");
        try {
            return address == null ? null : EditorOrigin.parse(address).address();
        } catch (IllegalArgumentException invalidAddress) {
            return null;
        }
    }

    @SuppressWarnings("deprecation") // Android 11–14 need opt-in to the Android 15 inset behavior.
    private void applyInsets() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                root.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return insets;
            });
        }
    }

    private void showPairing(String prefilledAddress) {
        root.removeAllViews();
        editorArea = null;
        ScrollView surface = new ScrollView(this);
        surface.setFillViewport(true);
        root.addView(surface, new LinearLayout.LayoutParams(-1, -1));

        LinearLayout center = vertical();
        center.setGravity(Gravity.CENTER);
        center.setPadding(dp(20), dp(24), dp(20), dp(28));
        surface.addView(center, new FrameLayout.LayoutParams(-1, -2));

        LinearLayout panel = vertical();
        panel.setPadding(dp(22), dp(22), dp(22), dp(22));
        panel.setBackground(box(SURFACE, 22, OUTLINE));
        center.addView(panel, new LinearLayout.LayoutParams(-1, -2));

        center.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = Math.min(dp(480), right - left - center.getPaddingLeft() - center.getPaddingRight());
            if (width > 0 && panel.getLayoutParams().width != width) {
                panel.getLayoutParams().width = width;
                panel.requestLayout();
            }
        });

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.editor_icon);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        panel.addView(icon, new LinearLayout.LayoutParams(dp(54), dp(54)));

        TextView title = label("マスターデータエディター", 24, TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setAccessibilityHeading(true);
        addWithTopMargin(panel, title, 10);
        addWithTopMargin(panel, label("Tailscaleに接続し、PCのエディターに表示された接続先とペアリングコードを入力します。", 15, MUTED), 8);

        addressInput = input("https://pc.tailnet.ts.net:8443",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, 300);
        addressInput.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        addField(panel, "接続先", addressInput, 24);
        if (prefilledAddress != null) {
            addressInput.setText(prefilledAddress);
        }

        codeInput = input("8桁のコード", InputType.TYPE_CLASS_NUMBER, 8);
        codeInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        codeInput.setSaveEnabled(false);
        codeInput.setOnEditorActionListener((field, action, event) -> {
            if (action == EditorInfo.IME_ACTION_GO) {
                pair();
                return true;
            }
            return false;
        });
        addField(panel, "ペアリングコード", codeInput, 16);

        pairStatus = label("", 14, WARNING);
        pairStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        pairStatus.setVisibility(View.GONE);
        addWithTopMargin(panel, pairStatus, 10);

        pairButton = button("接続する", true);
        pairButton.setOnClickListener(view -> pair());
        addWithTopMargin(panel, pairButton, 18);
        surface.setFocusableInTouchMode(true);
        surface.requestFocus();
    }

    private void pair() {
        if (authenticating) {
            return;
        }
        EditorOrigin entered;
        try {
            entered = EditorOrigin.parse(addressInput.getText().toString());
        } catch (IllegalArgumentException error) {
            showPairStatus("PCに表示された https://…ts.net の接続先を入力してください。", true);
            addressInput.requestFocus();
            return;
        }
        String code = codeInput.getText().toString();
        if (!code.matches("[0-9]{8}")) {
            showPairStatus("ペアリングコードは半角数字8桁です。", true);
            codeInput.requestFocus();
            return;
        }
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(codeInput.getWindowToken(), 0);
        setPairingBusy(true);
        showPairStatus("PCに接続しています…", false);
        network.execute(() -> {
            try {
                String pairedId = SessionClient.pair(entered, code, deviceName());
                runOnUiThread(() -> {
                    if (isDestroyed()) {
                        return;
                    }
                    origin = entered;
                    deviceId = pairedId;
                    preferences.edit().putString("origin", entered.address()).putString("deviceId", pairedId).apply();
                    setPairingBusy(false);
                    codeInput.setText("");
                    showEditorShell();
                    refreshSession();
                });
            } catch (IOException | JSONException | GeneralSecurityException | RuntimeException error) {
                runOnUiThread(() -> {
                    if (isDestroyed()) {
                        return;
                    }
                    setPairingBusy(false);
                    showPairStatus(errorMessage(error, true), true);
                });
            }
        });
    }

    private void setPairingBusy(boolean busy) {
        authenticating = busy;
        pairButton.setEnabled(!busy);
        addressInput.setEnabled(!busy);
        codeInput.setEnabled(!busy);
    }

    private void showPairStatus(String message, boolean error) {
        pairStatus.setTextColor(error ? WARNING : MUTED);
        pairStatus.setText(message);
        pairStatus.setVisibility(View.VISIBLE);
    }

    private String deviceName() {
        String name = Build.MODEL.trim().replaceAll("[\\p{Cntrl}]", "");
        return name.isEmpty() ? "Android" : name.substring(0, Math.min(name.length(), 64));
    }

    private void showEditorShell() {
        root.removeAllViews();
        addressInput = null;
        codeInput = null;
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(6), 0, dp(8), 0);
        toolbar.setBackgroundColor(SURFACE);
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));

        Button back = button("‹", false);
        back.setTextSize(28);
        back.setPadding(0, 0, 0, 0);
        back.setContentDescription("前の画面に戻る");
        back.setOnClickListener(view -> navigateBack());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView title = label("AstralRecord", 16, TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setAccessibilityHeading(true);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

        Button settings = button("接続設定", false);
        settings.setOnClickListener(view -> showConnectionSettings());
        toolbar.addView(settings, new LinearLayout.LayoutParams(-2, -2));

        statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(dp(16), dp(4), dp(8), dp(4));
        statusRow.setBackgroundColor(0xff26283b);
        statusText = label("", 14, TEXT);
        statusText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        statusRow.addView(statusText, new LinearLayout.LayoutParams(0, -2, 1));
        statusRetry = button("再接続", false);
        statusRetry.setOnClickListener(view -> refreshSession());
        statusRow.addView(statusRetry, new LinearLayout.LayoutParams(-2, -2));
        root.addView(statusRow, new LinearLayout.LayoutParams(-1, -2));
        statusRow.setVisibility(View.GONE);

        editorArea = new FrameLayout(this);
        root.addView(editorArea, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void showStatus(String message, boolean retry) {
        statusText.setText(message);
        statusRetry.setVisibility(retry ? View.VISIBLE : View.GONE);
        statusRow.setVisibility(View.VISIBLE);
    }

    private void refreshSession() {
        if (authenticating || origin == null || deviceId == null) {
            return;
        }
        authenticating = true;
        showStatus("PCとの接続を確認しています…", false);
        EditorOrigin currentOrigin = origin;
        String currentId = deviceId;
        network.execute(() -> {
            try {
                String token = SessionClient.openSession(currentOrigin, currentId);
                runOnUiThread(() -> {
                    if (!isDestroyed()) {
                        applySessionCookie(currentOrigin, token);
                    }
                });
            } catch (IOException | JSONException | GeneralSecurityException | RuntimeException error) {
                runOnUiThread(() -> {
                    if (!isDestroyed()) {
                        sessionFailed(errorMessage(error, false));
                    }
                });
            }
        });
    }

    private void applySessionCookie(EditorOrigin currentOrigin, String token) {
        try {
            CookieManager cookies = CookieManager.getInstance();
            cookies.setAcceptCookie(true);
            cookies.setCookie(currentOrigin.address(), "__Host-AstralMobile=" + token
                    + "; Path=/; Secure; HttpOnly; SameSite=Strict", accepted -> {
                if (isDestroyed()) {
                    return;
                }
                authenticating = false;
                if (!Boolean.TRUE.equals(accepted)) {
                    sessionFailed("アプリ内ブラウザーで接続情報を設定できませんでした。");
                    return;
                }
                manualRetryRequired = false;
                lastSession = SystemClock.elapsedRealtime();
                if (hasEditorDocument()) {
                    editor.evaluateJavascript("window.astralMobileSessionExpired = false", null);
                    statusRow.setVisibility(View.GONE);
                } else {
                    loadEditor();
                }
            });
        } catch (RuntimeException error) {
            sessionFailed("Android System WebViewを有効にしてから再接続してください。");
        }
    }

    private void loadEditor() {
        try {
            if (editor == null) {
                editor = EditorWebView.open(this, origin);
                editorArea.addView(editor, new FrameLayout.LayoutParams(-1, -1));
            }
            editor.setVisibility(View.INVISIBLE);
            showStatus("編集画面を読み込んでいます…", false);
            editor.loadUrl(origin.editorUrl());
        } catch (RuntimeException error) {
            sessionFailed("Android System WebViewを有効にしてから再接続してください。");
        }
    }

    void editorPageReady(WebView source) {
        if (source == editor) {
            editorDocumentReady = true;
            statusRow.setVisibility(View.GONE);
            editor.setVisibility(View.VISIBLE);
        }
    }

    void editorPageFailed(WebView source, boolean tls) {
        if (source == editor) {
            editorDocumentReady = false;
            source.setVisibility(View.INVISIBLE);
            sessionFailed(tls ? "接続先のHTTPS証明書を確認できませんでした。"
                    : "編集画面に接続できませんでした。PCとTailscaleの状態を確認してください。");
        }
    }

    void editorProcessGone(WebView source) {
        if (source == editor) {
            editorArea.removeView(source);
            editor = null;
            editorDocumentReady = false;
            finishImport(null);
            source.destroy();
            sessionFailed("編集画面が停止しました。再接続して編集を再開してください。");
        }
    }

    private void sessionFailed(String message) {
        authenticating = false;
        manualRetryRequired = true;
        showStatus(message, true);
    }

    private boolean hasEditorDocument() {
        return editorDocumentReady && editor != null && origin != null && origin.contains(editor.getUrl());
    }

    private String errorMessage(Exception error, boolean pairing) {
        if (error instanceof SessionClient.HttpFailure) {
            switch (((SessionClient.HttpFailure) error).status) {
                case 400:
                case 401:
                    return pairing ? "コードが違うか、有効期限が切れています。PCでコードを発行し直してください。"
                            : "端末の登録が無効です。接続設定から解除し、PCで新しいコードを発行してください。";
                case 403:
                    return "PCが接続を許可していません。PCのAndroid接続設定を確認してください。";
                case 404:
                    return "この接続先でAndroidエディターが起動していることを確認してください。";
                case 429:
                    return "接続が混み合っています。少し待ってから再接続してください。";
                default:
                    return "PCが接続を受け付けられませんでした。Android接続設定を確認してください。";
            }
        }
        if (error instanceof SSLException) {
            return "接続先のHTTPS証明書を確認できませんでした。";
        }
        if (error instanceof GeneralSecurityException) {
            return "端末の鍵を利用できません。接続を解除してペアリングし直してください。";
        }
        return "PCに接続できませんでした。接続先とTailscaleの状態を確認してください。";
    }

    private void showConnectionSettings() {
        if (origin == null || authenticating) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("接続設定")
                .setMessage(origin.address())
                .setPositiveButton("閉じる", null)
                .setNegativeButton("再接続", (dialog, button) -> refreshSession())
                .setNeutralButton("解除", (dialog, button) -> confirmForgetConnection())
                .show();
    }

    private void confirmForgetConnection() {
        if (!hasEditorDocument()) {
            showForgetDialog(false);
            return;
        }
        editor.evaluateJavascript("typeof window.astralEditorHasUnsavedChanges !== 'function'"
                + " || window.astralEditorHasUnsavedChanges()", dirty -> {
            if (!isDestroyed()) {
                showForgetDialog(!"false".equals(dirty));
            }
        });
    }

    private void showForgetDialog(boolean dirty) {
        new AlertDialog.Builder(this)
                .setTitle("この端末の接続を解除")
                .setMessage(dirty
                        ? "未保存の編集があります。保存する場合は編集画面に戻ってください。再接続にはPCで発行したコードが必要です。"
                        : "次に使うときは、PCで発行したコードでペアリングします。")
                .setNegativeButton("編集に戻る", null)
                .setPositiveButton("接続を解除", (dialog, button) -> forgetConnection())
                .show();
    }

    private void forgetConnection() {
        if (authenticating || origin == null) {
            return;
        }
        authenticating = true;
        showStatus("接続情報を削除しています…", false);
        network.execute(() -> {
            try {
                SessionClient.removeDeviceKey();
                runOnUiThread(() -> {
                    if (isDestroyed()) {
                        return;
                    }
                    String previousAddress = origin.address();
                    destroyEditor();
                    try {
                        CookieManager.getInstance().setCookie(previousAddress,
                                "__Host-AstralMobile=; Max-Age=0; Path=/; Secure; HttpOnly; SameSite=Strict", null);
                    } catch (RuntimeException ignored) {
                        // Android can remove or update the WebView provider while this Activity is open.
                    }
                    preferences.edit().clear().apply();
                    origin = null;
                    deviceId = null;
                    authenticating = false;
                    showPairing(previousAddress);
                });
            } catch (IOException | GeneralSecurityException | RuntimeException error) {
                runOnUiThread(() -> {
                    if (!isDestroyed()) {
                        sessionFailed("端末の接続情報を削除できませんでした。接続設定からやり直してください。");
                    }
                });
            }
        });
    }

    private void navigateBack() {
        if (handlingBack) {
            return;
        }
        if (!hasEditorDocument()) {
            moveTaskToBack(true);
            return;
        }
        handlingBack = true;
        editor.evaluateJavascript("typeof window.astralEditorBack === 'function' && window.astralEditorBack()", used -> {
            handlingBack = false;
            if (!isDestroyed() && !"true".equals(used)) {
                moveTaskToBack(true);
            }
        });
    }

    @SuppressWarnings("deprecation") // Android 8–12 dispatch back through Activity.
    @Override
    public void onBackPressed() {
        navigateBack();
    }

    @SuppressWarnings("deprecation") // Android Activity delivers the document picker result.
    boolean chooseImportFile(ValueCallback<Uri[]> callback) {
        if (!hasEditorDocument() || importCallback != null) {
            callback.onReceiveValue(null);
            return true;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("*/*")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/*", "application/json", "application/yaml",
                        "application/x-yaml", "application/octet-stream"});
        importCallback = callback;
        try {
            startActivityForResult(intent, IMPORT_REQUEST);
        } catch (android.content.ActivityNotFoundException unavailable) {
            finishImport(null);
        }
        return true;
    }

    @SuppressWarnings("deprecation") // No AndroidX dependency is needed for the document picker.
    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == IMPORT_REQUEST) {
            Uri selected = result == RESULT_OK && data != null ? data.getData() : null;
            finishImport(selected != null && "content".equals(selected.getScheme()) ? new Uri[]{ selected } : null);
        }
    }

    private void finishImport(Uri[] files) {
        if (importCallback != null) {
            importCallback.onReceiveValue(files);
            importCallback = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (editor != null) {
            editor.onResume();
        }
        if (origin != null && !manualRetryRequired
                && (lastSession == 0 || SystemClock.elapsedRealtime() - lastSession >= REFRESH_ON_RESUME_AFTER)) {
            refreshSession();
        }
        handler.postDelayed(sessionCheck, SESSION_CHECK_INTERVAL);
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacks(sessionCheck);
        if (editor != null) {
            editor.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        destroyEditor();
        network.shutdownNow();
        super.onDestroy();
    }

    private void destroyEditor() {
        finishImport(null);
        if (editor != null) {
            editorArea.removeView(editor);
            editor.destroy();
            editor = null;
        }
        editorDocumentReady = false;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView label(String text, int size, int color) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(color);
        return label;
    }

    private Button button(String text, boolean primary) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextColor(primary ? 0xff211c3c : TEXT);
        button.setTextSize(14);
        button.setMinHeight(dp(48));
        button.setMinWidth(dp(48));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(primary ? 0x33211c3c : 0x35ffffff),
                box(primary ? ACCENT : Color.TRANSPARENT, 12, 0), box(Color.WHITE, 12, 0)));
        return button;
    }

    private EditText input(String hint, int type, int length) {
        EditText input = new EditText(this);
        input.setId(View.generateViewId());
        input.setSingleLine(true);
        input.setInputType(type);
        input.setHint(hint);
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setTextSize(16);
        input.setMinHeight(dp(52));
        input.setPadding(dp(14), dp(8), dp(14), dp(8));
        input.setFilters(new InputFilter[]{ new InputFilter.LengthFilter(length) });
        android.graphics.drawable.StateListDrawable fieldBackground = new android.graphics.drawable.StateListDrawable();
        fieldBackground.addState(new int[]{android.R.attr.state_focused}, box(BACKGROUND, 12, ACCENT));
        fieldBackground.addState(new int[0], box(BACKGROUND, 12, OUTLINE));
        input.setBackground(fieldBackground);
        return input;
    }

    private void addField(LinearLayout panel, String text, EditText field, int topMargin) {
        TextView name = label(text, 14, TEXT);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        name.setLabelFor(field.getId());
        addWithTopMargin(panel, name, topMargin);
        addWithTopMargin(panel, field, 6);
    }

    private void addWithTopMargin(LinearLayout parent, View child, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(margin);
        parent.addView(child, params);
    }

    private GradientDrawable box(int color, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (stroke != 0) {
            drawable.setStroke(dp(1), stroke);
        }
        return drawable;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }
}
