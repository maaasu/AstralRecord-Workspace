package net.astralrecord.mastereditor;

import android.graphics.Bitmap;
import android.net.http.SslError;
import android.os.Message;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.util.Collections;

final class EditorWebView extends WebViewClient {
    private final MainActivity activity;
    private final EditorOrigin origin;
    private boolean rejectedPage;

    private EditorWebView(MainActivity activity, EditorOrigin origin) {
        this.activity = activity;
        this.origin = origin;
    }

    static WebView open(MainActivity activity, EditorOrigin origin) {
        WebView view = new WebView(activity);
        view.setBackgroundColor(0xff11131f);
        WebView.setWebContentsDebuggingEnabled(false);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(true);
        settings.setGeolocationEnabled(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false);

        view.setWebViewClient(new EditorWebView(activity, origin));
        view.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView source, boolean dialog, boolean userGesture, Message result) {
                return false;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.deny();
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String requester, GeolocationPermissions.Callback callback) {
                callback.invoke(requester, false, false);
            }

            @Override
            public boolean onShowFileChooser(WebView source, android.webkit.ValueCallback<android.net.Uri[]> callback,
                    FileChooserParams params) {
                if (params.getMode() != FileChooserParams.MODE_OPEN || params.isCaptureEnabled()) {
                    callback.onReceiveValue(null);
                    return true;
                }
                return activity.chooseImportFile(callback);
            }
        });
        view.setDownloadListener((url, agent, disposition, mime, length) ->
                Toast.makeText(activity, "ダウンロードはPCのエディターで行ってください。", Toast.LENGTH_SHORT).show());
        return view;
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        return !request.isForMainFrame() || !origin.contains(request.getUrl().toString())
                || !"/".equals(request.getUrl().getPath())
                || !"android".equals(request.getUrl().getQueryParameter("mobile"));
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        if (origin.contains(request.getUrl().toString())) {
            return null;
        }
        return new WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden",
                Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
    }

    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
        rejectedPage = !origin.contains(url);
        if (rejectedPage) {
            view.stopLoading();
            activity.editorPageFailed(view, false);
        }
    }

    @Override
    public void onPageCommitVisible(WebView view, String url) {
        if (!rejectedPage && origin.contains(url)) {
            activity.editorPageReady(view);
        }
    }

    @Override
    public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
        if (request.isForMainFrame()) {
            rejectedPage = true;
            activity.editorPageFailed(view, error.getErrorCode() == ERROR_FAILED_SSL_HANDSHAKE);
        }
    }

    @Override
    public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
        if (request.isForMainFrame()) {
            rejectedPage = true;
            activity.editorPageFailed(view, false);
        }
    }

    @Override
    public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
        handler.cancel();
        if (error.getUrl().equals(view.getUrl())) {
            rejectedPage = true;
            activity.editorPageFailed(view, true);
        }
    }

    @Override
    public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
        activity.editorProcessGone(view);
        return true;
    }
}
