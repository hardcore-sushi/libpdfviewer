package app.grapheneos.pdfviewer;

import android.content.pm.PackageInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import app.grapheneos.pdfviewer.databinding.PdfviewerBinding;
import app.grapheneos.pdfviewer.fragment.DocumentPropertiesFragment;
import app.grapheneos.pdfviewer.fragment.JumpToPageFragment;
import app.grapheneos.pdfviewer.fragment.PasswordPromptFragment;
import app.grapheneos.pdfviewer.ktx.ViewKt;
import app.grapheneos.pdfviewer.loader.DocumentPropertiesAsyncTaskLoader;
import app.grapheneos.pdfviewer.outline.OutlineFragment;
import app.grapheneos.pdfviewer.viewModel.PdfViewModel;

public class PdfViewer implements LoaderManager.LoaderCallbacks<List<CharSequence>> {
    private static final String TAG = "PdfViewer";

    private static final String KEY_PROPERTIES = "properties";
    private static final int MIN_WEBVIEW_RELEASE = 92;

    private static final String CONTENT_SECURITY_POLICY =
        "default-src 'none'; " +
        "form-action 'none'; " +
        "connect-src 'self'; " +
        "img-src blob: 'self'; " +
        "script-src 'self'; " +
        "style-src 'self'; " +
        "frame-ancestors 'none'; " +
        "base-uri 'none'";

    private static final String PERMISSIONS_POLICY =
        "accelerometer=(), " +
        "ambient-light-sensor=(), " +
        "autoplay=(), " +
        "battery=(), " +
        "camera=(), " +
        "clipboard-read=(), " +
        "clipboard-write=(), " +
        "display-capture=(), " +
        "document-domain=(), " +
        "encrypted-media=(), " +
        "fullscreen=(), " +
        "gamepad=(), " +
        "geolocation=(), " +
        "gyroscope=(), " +
        "hid=(), " +
        "idle-detection=(), " +
        "interest-cohort=(), " +
        "magnetometer=(), " +
        "microphone=(), " +
        "midi=(), " +
        "payment=(), " +
        "picture-in-picture=(), " +
        "publickey-credentials-get=(), " +
        "screen-wake-lock=(), " +
        "serial=(), " +
        "speaker-selection=(), " +
        "sync-xhr=(), " +
        "usb=(), " +
        "xr-spatial-tracking=()";

    private static final float MIN_ZOOM_RATIO = 0.2f;
    private static final float MAX_ZOOM_RATIO = 10f;
    private static final int MAX_RENDER_PIXELS = 1 << 23; // 8 mega-pixels
    private static final int ALPHA_LOW = 130;
    private static final int ALPHA_HIGH = 255;
    private static final int STATE_LOADED = 1;
    private static final int STATE_END = 2;
    private static final int PADDING = 10;

    private boolean webViewCrashed;
    public int mPage;
    public int mNumPages;
    private float mZoomRatio = 1f;
    private float mZoomFocusX = 0f;
    private float mZoomFocusY = 0f;
    private int mDocumentOrientationDegrees;
    private int mDocumentState;
    private String mEncryptedDocumentPassword;
    private List<CharSequence> mDocumentProperties;
    private ByteArrayInputStream mInputStream;

    private PdfviewerBinding binding;
    private TextView mTextView;
    private Toast mToast;

    AppCompatActivity activity;
    String fileName;
    Long fileSize;
    private PasswordPromptFragment mPasswordPromptFragment;
    public PdfViewModel viewModel;

    private class Channel {
        @JavascriptInterface
        public void setHasDocumentOutline(final boolean hasOutline) {
            viewModel.setHasOutline(hasOutline);
        }

        @JavascriptInterface
        public void setDocumentOutline(final String outline) {
            viewModel.parseOutlineString(outline);
        }

        @JavascriptInterface
        public int getPage() {
            return mPage;
        }

        @JavascriptInterface
        public float getZoomRatio() {
            return mZoomRatio;
        }

        @JavascriptInterface
        public void setZoomRatio(final float ratio) {
            mZoomRatio = Math.max(Math.min(ratio, MAX_ZOOM_RATIO), MIN_ZOOM_RATIO);
        }

        @JavascriptInterface
        public int getMaxRenderPixels() {
            return MAX_RENDER_PIXELS;
        }

        @JavascriptInterface
        public float getZoomFocusX() {
            return mZoomFocusX;
        }

        @JavascriptInterface
        public float getZoomFocusY() {
            return mZoomFocusY;
        }

        @JavascriptInterface
        public float getMinZoomRatio() {
            return MIN_ZOOM_RATIO;
        }

        @JavascriptInterface
        public float getMaxZoomRatio() {
            return MAX_ZOOM_RATIO;
        }

        @JavascriptInterface
        public int getDocumentOrientationDegrees() {
            return mDocumentOrientationDegrees;
        }

        @JavascriptInterface
        public void setNumPages(int numPages) {
            mNumPages = numPages;
            activity.runOnUiThread(activity::invalidateOptionsMenu);
        }

        @JavascriptInterface
        public void setDocumentProperties(final String properties) {
            if (mDocumentProperties != null) {
                throw new SecurityException("mDocumentProperties not null");
            }

            final Bundle args = new Bundle();
            args.putString(KEY_PROPERTIES, properties);
            activity.runOnUiThread(() -> LoaderManager.getInstance(PdfViewer.this.activity).restartLoader(DocumentPropertiesAsyncTaskLoader.ID, args, PdfViewer.this));
        }

        @JavascriptInterface
        public void showPasswordPrompt() {
            if (!getPasswordPromptFragment().isAdded()) {
                getPasswordPromptFragment().show(activity.getSupportFragmentManager(), PasswordPromptFragment.class.getName());
            }
            viewModel.passwordMissing();
        }

        @JavascriptInterface
        public void invalidPassword() {
            activity.runOnUiThread(() -> viewModel.invalid());
        }

        @JavascriptInterface
        public void onLoaded() {
            viewModel.validated();
            if (getPasswordPromptFragment().isAdded()) {
                getPasswordPromptFragment().dismiss();
            }
        }

        @JavascriptInterface
        public String getPassword() {
            return mEncryptedDocumentPassword != null ? mEncryptedDocumentPassword : "";
        }
    }

    private void showWebViewCrashed() {
        binding.webviewAlertTitle.setText(activity.getString(R.string.webview_crash_title));
        binding.webviewAlertMessage.setText(activity.getString(R.string.webview_crash_message));
        binding.webviewAlertLayout.setVisibility(View.VISIBLE);
        binding.webviewAlertReload.setVisibility(View.VISIBLE);
        binding.webview.setVisibility(View.GONE);
    }

    public PdfViewer(@NonNull AppCompatActivity activity) {
        this.activity = activity;
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);

        binding = PdfviewerBinding.inflate(activity.getLayoutInflater());
        activity.setContentView(binding.getRoot());
        activity.setSupportActionBar(binding.toolbar);
        viewModel = new ViewModelProvider(activity, ViewModelProvider.AndroidViewModelFactory.getInstance(activity.getApplication())).get(PdfViewModel.class);

        viewModel.getOutline().observe(activity, requested -> {
            if (requested instanceof PdfViewModel.OutlineStatus.Requested) {
                viewModel.setLoadingOutline();
                binding.webview.evaluateJavascript("getDocumentOutline()", null);
            }
        });

        activity.getSupportFragmentManager().setFragmentResultListener(OutlineFragment.RESULT_KEY, activity,
                (requestKey, result) -> {
            final int newPage = result.getInt(OutlineFragment.PAGE_KEY, -1);
            if (viewModel.shouldAbortOutline()) {
                Log.d(TAG, "aborting outline operations");
                binding.webview.evaluateJavascript("abortDocumentOutline()", null);
                viewModel.clearOutline();
            } else {
                onJumpToPageInDocument(newPage);
            }
        });

        // Margins for the toolbar are needed, so that content of the toolbar
        // is not covered by a system button navigation bar when in landscape.
        KtUtilsKt.applySystemBarMargins(binding.toolbar, false);

        binding.webview.setBackgroundColor(Color.TRANSPARENT);

        final WebSettings settings = binding.webview.getSettings();
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccess(false);
        settings.setBlockNetworkLoads(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setJavaScriptEnabled(true);
        settings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);
        settings.setMinimumFontSize(1);

        CookieManager.getInstance().setAcceptCookie(false);

        binding.webview.addJavascriptInterface(new Channel(), "channel");

        binding.webview.setWebViewClient(new WebViewClient() {
            private WebResourceResponse fromAsset(final String mime, final String path) {
                try {
                    InputStream inputStream = activity.getAssets().open(path.substring(1));
                    return new WebResourceResponse(mime, null, inputStream);
                } catch (IOException e) {
                    return null;
                }
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (!"GET".equals(request.getMethod())) {
                    return null;
                }

                final Uri url = request.getUrl();
                if (!"localhost".equals(url.getHost())) {
                    return null;
                }

                final String path = url.getPath();
                Log.d(TAG, "path " + path);

                if ("/placeholder.pdf".equals(path)) {
                    mInputStream.reset();
                    return new WebResourceResponse("application/pdf", null, mInputStream);
                }

                if ("/viewer/index.html".equals(path)) {
                    final WebResourceResponse response = fromAsset("text/html", path);
                    HashMap<String, String> headers = new HashMap<>();
                    headers.put("Content-Security-Policy", CONTENT_SECURITY_POLICY);
                    headers.put("Permissions-Policy", PERMISSIONS_POLICY);
                    headers.put("X-Content-Type-Options", "nosniff");
                    response.setResponseHeaders(headers);
                    return response;
                }

                if ("/viewer/main.css".equals(path)) {
                    return fromAsset("text/css", path);
                }

                if ("/viewer/js/index.js".equals(path) || "/viewer/js/worker.js".equals(path) ||
                        "/viewer/wasm/openjpeg_nowasm_fallback.js".equals(path)) {
                    return fromAsset("application/javascript", path);
                }

                if ("/viewer/wasm/openjpeg.wasm".equals(path) ||
                        "/viewer/wasm/qcms_bg.wasm".equals(path)) {
                    return fromAsset("application/wasm", path);
                }

                if (path != null && path.matches("^/viewer/iccs/.*\\.icc$")) {
                    return fromAsset("application/vnd.iccprofile", path);
                }

                if (path != null && path.matches("^/viewer/cmaps/.*\\.bcmap$")) {
                    return fromAsset("application/octet-stream", path);
                }

                if (path != null && path.matches("^/viewer/standard_fonts/.*\\.pfb$")) {
                    return fromAsset("application/octet-stream", path);
                }

                if (path != null && path.matches("^/viewer/standard_fonts/.*\\.ttf$")) {
                    return fromAsset("font/sfnt", path);
                }

                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                mDocumentState = STATE_LOADED;
                activity.invalidateOptionsMenu();
                loadPdfWithPassword(mEncryptedDocumentPassword);
            }

            @RequiresApi(api = Build.VERSION_CODES.O)
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                if (detail.didCrash()) {
                    webViewCrashed = true;
                    showWebViewCrashed();
                    activity.invalidateOptionsMenu();
                    purgeWebView();
                    return true;
                }
                return false;
            }
        });

        GestureHelper.attach(activity, binding.webview,
                new GestureHelper.GestureListener() {
                    @Override
                    public boolean onTapUp() {
                        binding.webview.evaluateJavascript("isTextSelected()", selection -> {
                            if (!Boolean.parseBoolean(selection)) {
                                if (activity.getSupportActionBar().isShowing()) {
                                    hideSystemUi();
                                } else {
                                    showSystemUi();
                                }
                            }
                        });
                        return true;
                    }

                    @Override
                    public void onZoom(float scaleFactor, float focusX, float focusY) {
                        zoom(scaleFactor, focusX, focusY, false);
                    }

                    @Override
                    public void onZoomEnd() {
                        zoomEnd();
                    }
                });

        mTextView = new TextView(activity);
        mTextView.setBackgroundColor(Color.DKGRAY);
        mTextView.setTextColor(ColorStateList.valueOf(Color.WHITE));
        mTextView.setTextSize(18);
        mTextView.setPadding(PADDING, 0, PADDING, 0);

        binding.webviewAlertReload.setOnClickListener(v -> {
            webViewCrashed = false;
            activity.recreate();
        });

        if (webViewCrashed) {
            showWebViewCrashed();
        }
    }

    private void purgeWebView() {
        binding.webview.removeJavascriptInterface("channel");
        binding.getRoot().removeView(binding.webview);
        binding.webview.destroy();
    }

    public void onDestroy() {
        purgeWebView();
        maybeCloseInputStream();
    }

    void maybeCloseInputStream() {
        InputStream stream = mInputStream;
        if (stream == null) {
            return;
        }
        mInputStream = null;
        try {
            stream.close();
        } catch (IOException ignored) {}
    }

    private PasswordPromptFragment getPasswordPromptFragment() {
        if (mPasswordPromptFragment == null) {
            final Fragment fragment = activity.getSupportFragmentManager().findFragmentByTag(PasswordPromptFragment.class.getName());
            if (fragment != null) {
                mPasswordPromptFragment = (PasswordPromptFragment) fragment;
            } else {
                mPasswordPromptFragment = new PasswordPromptFragment(this);
            }
        }
        return mPasswordPromptFragment;
    }

    private void setToolbarTitleWithDocumentName() {
        String documentName = getCurrentDocumentName();
        if (documentName != null && !documentName.isEmpty()) {
            activity.getSupportActionBar().setTitle(documentName);
        } else {
            activity.getSupportActionBar().setTitle(R.string.app_name);
        }
    }

    public void onResume() {
        if (!webViewCrashed) {
            // The user could have left the activity to update the WebView
            activity.invalidateOptionsMenu();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (getWebViewRelease() >= MIN_WEBVIEW_RELEASE) {
                    binding.webviewAlertLayout.setVisibility(View.GONE);
                    binding.webview.setVisibility(View.VISIBLE);
                } else {
                    binding.webview.setVisibility(View.GONE);
                    binding.webviewAlertTitle.setText(activity.getString(R.string.webview_out_of_date_title));
                    binding.webviewAlertMessage.setText(activity.getString(R.string.webview_out_of_date_message, getWebViewRelease(), MIN_WEBVIEW_RELEASE));
                    binding.webviewAlertLayout.setVisibility(View.VISIBLE);
                }
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private int getWebViewRelease() {
        PackageInfo webViewPackage = WebView.getCurrentWebViewPackage();
        String webViewVersionName = webViewPackage.versionName;
        return Integer.parseInt(webViewVersionName.substring(0, webViewVersionName.indexOf(".")));
    }

    @NonNull
    @Override
    public Loader<List<CharSequence>> onCreateLoader(int id, Bundle args) {
        return new DocumentPropertiesAsyncTaskLoader(activity, args.getString(KEY_PROPERTIES), mNumPages, fileName, fileSize);
    }

    @Override
    public void onLoadFinished(@NonNull Loader<List<CharSequence>> loader, List<CharSequence> data) {
        mDocumentProperties = data;
        activity.invalidateOptionsMenu();
        setToolbarTitleWithDocumentName();
        LoaderManager.getInstance(activity).destroyLoader(DocumentPropertiesAsyncTaskLoader.ID);
    }

    @Override
    public void onLoaderReset(@NonNull Loader<List<CharSequence>> loader) {
        mDocumentProperties = null;
    }

    public void loadPdf(ByteArrayInputStream inputStream, String fileName, Long fileSize) {
        mDocumentState = 0;
        mPage = 1;
        mDocumentProperties = null;
        mInputStream = inputStream;
        this.fileName = fileName;
        this.fileSize = fileSize;
        showSystemUi();
        activity.invalidateOptionsMenu();
        binding.webview.loadUrl("https://localhost/viewer/index.html");
    }

    public void loadPdfWithPassword(final String password) {
        mEncryptedDocumentPassword = password;
        binding.webview.evaluateJavascript("loadDocument()", null);
    }

    private void renderPage(final int zoom) {
        binding.webview.evaluateJavascript("onRenderPage(" + zoom + ")", null);
    }

    private void documentOrientationChanged(final int orientationDegreesOffset) {
        mDocumentOrientationDegrees = (mDocumentOrientationDegrees + orientationDegreesOffset) % 360;
        if (mDocumentOrientationDegrees < 0) {
            mDocumentOrientationDegrees += 360;
        }
        renderPage(0);
    }

    private void zoom(float scaleFactor, float focusX, float focusY, boolean end) {
        mZoomRatio = Math.min(Math.max(mZoomRatio * scaleFactor, MIN_ZOOM_RATIO), MAX_ZOOM_RATIO);
        mZoomFocusX = focusX;
        mZoomFocusY = focusY;
        renderPage(end ? 1 : 2);
        activity.invalidateOptionsMenu();
    }

    private void zoomEnd() {
        renderPage(1);
    }

    private static void enableDisableMenuItem(MenuItem item, boolean enable) {
        item.setEnabled(enable);
        if (item.getIcon() != null) {
            item.getIcon().setAlpha(enable ? ALPHA_HIGH : ALPHA_LOW);
        }
    }

    public void onJumpToPageInDocument(final int selected_page) {
        if (selected_page >= 1 && selected_page <= mNumPages && mPage != selected_page) {
            mPage = selected_page;
            renderPage(0);
            showPageNumber();
            activity.invalidateOptionsMenu();
        }
    }

    private void showSystemUi() {
        ViewKt.showSystemUi(binding.getRoot(), activity.getWindow());
        activity.getSupportActionBar().show();
    }

    private void hideSystemUi() {
        ViewKt.hideSystemUi(binding.getRoot(), activity.getWindow());
        activity.getSupportActionBar().hide();
    }

    private void showPageNumber() {
        if (mToast != null) {
            mToast.cancel();
        }
        mTextView.setText(String.format("%s/%s", mPage, mNumPages));
        mToast = new Toast(activity);
        mToast.setGravity(Gravity.BOTTOM | Gravity.END, PADDING, PADDING);
        mToast.setDuration(Toast.LENGTH_SHORT);
        mToast.setView(mTextView);
        mToast.show();
    }

    public void onCreateOptionMenu(@NonNull Menu menu) {
        MenuInflater inflater = activity.getMenuInflater();
        inflater.inflate(R.menu.pdf_viewer, menu);
        if (BuildConfig.DEBUG) {
            inflater.inflate(R.menu.pdf_viewer_debug, menu);
        }
    }

    public boolean onPrepareOptionsMenu(@NonNull Menu menu) {
        final ArrayList<Integer> ids = new ArrayList<>(Arrays.asList(R.id.action_jump_to_page,
                R.id.action_next, R.id.action_previous, R.id.action_first, R.id.action_last,
                R.id.action_rotate_clockwise, R.id.action_rotate_counterclockwise,
                R.id.action_view_document_properties, R.id.action_outline));
        if (BuildConfig.DEBUG) {
            ids.add(R.id.debug_action_toggle_text_layer_visibility);
            ids.add(R.id.debug_action_crash_webview);
        }
        if (mDocumentState < STATE_LOADED) {
            for (final int id : ids) {
                final MenuItem item = menu.findItem(id);
                if (item.isVisible()) {
                    item.setVisible(false);
                }
            }
        } else if (mDocumentState == STATE_LOADED) {
            for (final int id : ids) {
                final MenuItem item = menu.findItem(id);
                if (!item.isVisible()) {
                    item.setVisible(true);
                }
            }
            mDocumentState = STATE_END;
        }

        enableDisableMenuItem(menu.findItem(R.id.action_next), mPage < mNumPages);
        enableDisableMenuItem(menu.findItem(R.id.action_previous), mPage > 1);
        enableDisableMenuItem(menu.findItem(R.id.action_view_document_properties),
                mDocumentProperties != null);

        menu.findItem(R.id.action_outline).setVisible(viewModel.hasOutline());

        if (webViewCrashed) {
            for (final int id : ids) {
                enableDisableMenuItem(menu.findItem(id), false);
            }
        }

        return true;
    }

    public boolean onOptionsItemSelected(MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == R.id.action_previous) {
            onJumpToPageInDocument(mPage - 1);
            return true;
        } else if (itemId == R.id.action_next) {
            onJumpToPageInDocument(mPage + 1);
            return true;
        } else if (itemId == R.id.action_first) {
            onJumpToPageInDocument(1);
            return true;
        } else if (itemId == R.id.action_last) {
            onJumpToPageInDocument(mNumPages);
            return true;
        } else if (itemId == R.id.action_rotate_clockwise) {
            documentOrientationChanged(90);
            return true;
        } else if (itemId == R.id.action_rotate_counterclockwise) {
            documentOrientationChanged(-90);
            return true;
        } else if (itemId == R.id.action_outline) {
            OutlineFragment outlineFragment =
                    OutlineFragment.newInstance(mPage, getCurrentDocumentName());
            activity.getSupportFragmentManager().beginTransaction()
                    .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                    // fullscreen fragment, since content root view == activity's root view
                    .add(android.R.id.content, outlineFragment)
                    .addToBackStack(null)
                    .commit();
            return true;
        } else if (itemId == R.id.action_view_document_properties) {
            DocumentPropertiesFragment
                .newInstance(mDocumentProperties)
                .show(activity.getSupportFragmentManager(), DocumentPropertiesFragment.TAG);
            return true;
        } else if (itemId == R.id.action_jump_to_page) {
            new JumpToPageFragment(this)
                .show(activity.getSupportFragmentManager(), JumpToPageFragment.TAG);
            return true;
        } else if (itemId == R.id.debug_action_toggle_text_layer_visibility) {
            binding.webview.evaluateJavascript("toggleTextLayerVisibility()", null);
            return true;
        } else if (itemId == R.id.debug_action_crash_webview) {
            binding.webview.loadUrl("chrome://crash");
            return true;
        }

        return false;
    }

    private String getCurrentDocumentName() {
        if (mDocumentProperties == null || mDocumentProperties.isEmpty()) return "";
        String fileName = "";
        String title = "";
        for (CharSequence property : mDocumentProperties) {
            if (property.toString().startsWith("File name:")) {
                fileName = property.toString().replace("File name:", "");
            }
            if (property.toString().startsWith("Title:-")) {
                title = property.toString().replace("Title:-", "");
            }
        }
        return fileName.length() > 2 ? fileName : title;
    }
}
