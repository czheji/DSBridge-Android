package wendu.dsbridge;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
//import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import androidx.annotation.Keep;
import androidx.appcompat.app.AlertDialog;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;


/**
 * Created by du on 16/12/29.
 */

@SuppressWarnings("unused")
public class DWebView extends WebView {
    private static final String BRIDGE_NAME = "_dsbridge";
    private static final String LOG_TAG = "dsBridge";
    private static boolean isDebug = false;
    private final Map<String, JsObject> javaScriptNamespaceInterfaces = new HashMap<>();
    private String APP_CACHE_DIRNAME;
    private int callID = 0;
    private WebChromeClient webChromeClient;
    private volatile boolean alertBoxBlock = true;
    private JavascriptCloseWindowListener javascriptCloseWindowListener = null;
    private ArrayList<CallInfo> callInfoList;
    private final InnerJavascriptInterface innerJavascriptInterface = new InnerJavascriptInterface();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private class InnerJavascriptInterface {

        @SuppressWarnings("RegExpRedundantEscape")
        private void PrintDebugInfo(String error) {
            Log.d(LOG_TAG, error);
            if (isDebug) {
                evaluateJavascript(String.format("alert('%s')", "DEBUG ERR MSG:\\n" + error.replaceAll("\\'", "\\\\'")));
            }
        }

        @SuppressWarnings("rawtypes")
        @Keep
        @JavascriptInterface
        public String call(String methodName, String argStr) {
            String error = "Js bridge  called, but can't find a corresponded " +
                    "JavascriptInterface object , please check your code!";
            String[] nameStr = parseNamespace(methodName.trim());
            methodName = nameStr[1];
            JsObject jsb = javaScriptNamespaceInterfaces.get(nameStr[0]);
            JSONObject ret = new JSONObject();
            try {
                ret.put("code", -1);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            if (jsb == null) {
                PrintDebugInfo(error);
                return ret.toString();
            }
            Object arg=null;
            Method method;
            String callback = null;

            try {
                JSONObject args = new JSONObject(argStr);
                if (args.has("_dscbstub")) {
                    callback = args.getString("_dscbstub");
                }
                if(args.has("data")) {
                    arg = args.get("data");
                }
            } catch (JSONException e) {
                error = String.format("The argument of \"%s\" must be a JSON object string!", methodName);
                PrintDebugInfo(error);
                e.printStackTrace();
                return ret.toString();
            }

            boolean async = false;
            method = jsb.getMethod(methodName, true);
            if(method!=null){
                async = true;
            }
            else{
                method = jsb.getMethod(methodName, false);
            }

            if (method == null) {
                error = "Not find method \"" + methodName + "\" implementation! please check if the  signature or namespace of the method is right ";
                PrintDebugInfo(error);
                return ret.toString();
            }


            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
                JavascriptInterface annotation = method.getAnnotation(JavascriptInterface.class);
                if (annotation == null) {
                    error = "Method " + methodName + " is not invoked, since  " +
                            "it is not declared with JavascriptInterface annotation! ";
                    PrintDebugInfo(error);
                    return ret.toString();
                }
            }

            Object retData;
//            method.setAccessible(true);
            try {
                if (async) {
                    final String cb = callback;
                    method.invoke(jsb.getObject(), arg, new CompletionHandler() {

                        @Override
                        public void complete(Object retValue) {
                            completeOrError(retValue, true);
                        }

                        @Override
                        public void complete() {
                            completeOrError(null, true);
                        }

                        @Override
                        public void setProgressData(Object value) {
                            completeOrError(value, false);
                        }

                        @Override
                        public void error(Throwable throwable) {
                            completeOrError(throwable, true);
                        }

                        private void completeOrError(Object retValue, boolean complete) {
                            try {
                                JSONObject ret = new JSONObject();
                                ret.put("code", 0);
                                if(retValue instanceof Throwable){
                                    ret.put("_error", ((Throwable) retValue).getMessage());
                                }
                                else{
                                    ret.put("data", retValue);
                                }
                                if (cb != null) {
                                    String script = String.format("%s(%s.data);", cb, ret);
                                    if (complete) {
                                        script += "delete window." + cb;
                                    }
                                    evaluateJavascript(script);
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }

                        }

                    });
                } else {
                    retData = method.invoke(jsb.getObject(), arg);
                    ret.put("code", 0);
                    ret.put("data", retData);
                    return ret.toString();
                }
            } catch (Exception e) {
                e.printStackTrace();
                error = String.format("Call failed：The parameter of \"%s\" in Java is invalid.", methodName);
                PrintDebugInfo(error);
                try {
                    ret.put("_error", error);
                } catch (JSONException ex) {
                    ex.printStackTrace();
                }
                return ret.toString();
            }
            return ret.toString();
        }

    }

    @SuppressWarnings("rawtypes")
    Map<Integer, OnReturnValue> handlerMap = new HashMap<>();

    public interface JavascriptCloseWindowListener {
        /**
         * @return If true, close the current activity, otherwise, do nothing.
         */
        boolean onClose();
    }


    @SuppressWarnings("DeprecatedIsStillUsed")
    @Deprecated
    public interface FileChooser {
        @SuppressWarnings("rawtypes")
        void openFileChooser(ValueCallback valueCallback, String acceptType);

        @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
        void openFileChooser(ValueCallback<Uri> valueCallback,
                             String acceptType, String capture);
    }

    public DWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public DWebView(Context context) {
        super(context);
        init();
    }

    /**
     * Set debug mode. if in debug mode, some errors will be prompted by a dialog
     * and the exception caused by the native handlers will not be captured.
     *
     * @param enabled debug mode
     */
    public static void setWebContentsDebuggingEnabled(boolean enabled) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(enabled);
        }
        isDebug = enabled;
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void init() {
        APP_CACHE_DIRNAME = getContext().getFilesDir().getAbsolutePath() + "/webcache";
        WebSettings settings = getSettings();
        settings.setDomStorageEnabled(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        settings.setAllowFileAccess(false);
        settings.setAppCacheEnabled(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setJavaScriptEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setAppCachePath(APP_CACHE_DIRNAME);
        settings.setUseWideViewPort(true);
        super.setWebChromeClient(mWebChromeClient);
        addInternalJavascriptObject();
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.JELLY_BEAN) {
            super.addJavascriptInterface(innerJavascriptInterface, BRIDGE_NAME);
        } else {
            // add dsbridge tag in lower android version
            settings.setUserAgentString(settings.getUserAgentString() + " _dsbridge");
        }
    }


    private String[] parseNamespace(String method) {
        int pos = method.lastIndexOf('.');
        String namespace = "";
        if (pos != -1) {
            namespace = method.substring(0, pos);
            method = method.substring(pos + 1);
        }
        return new String[]{namespace, method};
    }

    @Keep
    private void addInternalJavascriptObject() {
        addJavascriptObject(new Object() {

            @Keep
            @JavascriptInterface
            public boolean hasNativeMethod(Object args) throws JSONException {
                JSONObject jsonObject = (JSONObject) args;
                String methodName = jsonObject.getString("name").trim();
                String type = jsonObject.getString("type").trim();
                String[] nameStr = parseNamespace(methodName);
                JsObject obj = javaScriptNamespaceInterfaces.get(nameStr[0]);
                if(obj!=null){
                    boolean async = false;
                    boolean hasMethod = false;
                    if(obj.hasMethod(nameStr[1], true)){
                        hasMethod = true;
                        async = true;
                    }
                    if(!hasMethod){
                        hasMethod = obj.hasMethod(nameStr[1], false);
                    }
                    if(hasMethod){
                        return "all".equals(type) || (async && "asyn".equals(type) || (!async && "syn".equals(type)));
                    }
                }
                return false;
            }
            @SuppressWarnings("Convert2Lambda")
            @Keep
            @JavascriptInterface
            public String closePage(Object object) {
                runOnMainThread(new Runnable() {
                    @Override
                    public void run() {
                        if (javascriptCloseWindowListener == null
                                || javascriptCloseWindowListener.onClose()) {
                            Context context = getContext();
                            if (context instanceof Activity) {
                                ((Activity)context).onBackPressed();
                            }
                        }
                    }
                });
                return null;
            }

            @Keep
            @JavascriptInterface
            public void disableJavascriptDialogBlock(Object object) throws JSONException {
                JSONObject jsonObject = (JSONObject) object;
                alertBoxBlock = !jsonObject.getBoolean("disable");
            }

            @Keep
            @JavascriptInterface
            public void dsinit(Object jsonObject) {
                DWebView.this.dispatchStartupQueue();
            }

            @SuppressWarnings("Convert2Lambda")
            @Keep
            @JavascriptInterface
            public void returnValue(final Object obj){
                runOnMainThread(new Runnable() {
                    @SuppressWarnings({"rawtypes", "unchecked"})
                    @Override
                    public void run() {
                        JSONObject jsonObject = (JSONObject) obj;
                        Object data = null;
                        try {
                            int id = jsonObject.getInt("id");
                            boolean isCompleted = jsonObject.getBoolean("complete");
                            OnReturnValue handler = handlerMap.get(id);
                            if (jsonObject.has("data")) {
                                data = jsonObject.get("data");
                            }
                            if (handler != null) {
                                handler.onValue(data);
                                if (isCompleted) {
                                    handlerMap.remove(id);
                                }
                            }
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    }
                });
            }

        }, "_dsb");
    }

    private void _evaluateJavascript(String script) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            DWebView.super.evaluateJavascript(script, null);
        } else {
            super.loadUrl("javascript:" + script);
        }
    }

    /**
     * This method can be called in any thread, and if it is not called in the main thread,
     * it will be automatically distributed to the main thread.
     *
     * @param script script
     */
    @SuppressWarnings("Convert2Lambda")
    public void evaluateJavascript(final String script) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                _evaluateJavascript(script);
            }
        });
    }

    /**
     * This method can be called in any thread, and if it is not called in the main thread,
     * it will be automatically distributed to the main thread.
     *
     * @param url url from load
     */
    @SuppressWarnings("Convert2Lambda")
    @Override
    public void loadUrl(final String url) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                if (url == null || !url.startsWith("javascript:")) {
                    callInfoList = new ArrayList<>();
                }
                DWebView.super.loadUrl(url);
            }
        });
    }

    /**
     * This method can be called in any thread, and if it is not called in the main thread,
     * it will be automatically distributed to the main thread.
     *
     * @param url url for load
     * @param additionalHttpHeaders other headers
     */
    @SuppressWarnings("Convert2Lambda")
    @Override
    public void loadUrl(final String url, final Map<String, String> additionalHttpHeaders) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                if (url == null || !url.startsWith("javascript:")) {
                    callInfoList = new ArrayList<>();
                }
                DWebView.super.loadUrl(url, additionalHttpHeaders);
            }
        });
    }

    @SuppressWarnings("Convert2Lambda")
    @Override
    public void reload() {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                callInfoList = new ArrayList<>();
                DWebView.super.reload();
            }
        });
    }

    /**
     * set a listener for javascript closing the current activity.
     */
    public void setJavascriptCloseWindowListener(JavascriptCloseWindowListener listener) {
        javascriptCloseWindowListener = listener;
    }


    @SuppressWarnings("NullableProblems")
    private static class CallInfo {
        private final String data;
        private final int callbackId;
        private final String method;

        CallInfo(String handlerName, int id, Object[] args) {
            if (args == null) args = new Object[0];
            data = new JSONArray(Arrays.asList(args)).toString();
            callbackId = id;
            method = handlerName;
        }

        @Override
        public String toString() {
            JSONObject jo = new JSONObject();
            try {
                jo.put("method", method);
                jo.put("callbackId", callbackId);
                jo.put("data", data);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return jo.toString();
        }
    }

    private synchronized void dispatchStartupQueue() {
        if (callInfoList != null) {
            for (CallInfo info : callInfoList) {
                dispatchJavascriptCall(info);
            }
            callInfoList = null;
        }
    }

    private void dispatchJavascriptCall(CallInfo info) {
        evaluateJavascript(String.format("window._handleMessageFromNative(%s)", info.toString()));
    }

    public synchronized <T> void callHandler(String method, Object[] args, final OnReturnValue<T> handler) {

        CallInfo callInfo = new CallInfo(method, ++callID, args);
        if (handler != null) {
            handlerMap.put(callInfo.callbackId, handler);
        }

        if (callInfoList != null) {
            callInfoList.add(callInfo);
        } else {
            dispatchJavascriptCall(callInfo);
        }

    }

    public void callHandler(String method, Object[] args) {
        callHandler(method, args, null);
    }

    public <T> void callHandler(String method, OnReturnValue<T> handler) {
        callHandler(method, null, handler);
    }


    /**
     * Test whether the handler exist in javascript
     *
     * @param handlerName javascript handler name
     * @param existCallback callback while exists
     */
    public void hasJavascriptMethod(String handlerName, OnReturnValue<Boolean> existCallback) {
        callHandler("_hasJavascriptMethod", new Object[]{handlerName}, existCallback);
    }

    /**
     * Add a java object which implemented the javascript interfaces to dsBridge with namespace.
     * Remove the object using {@link #removeJavascriptObject(String) removeJavascriptObject(String)}
     *
     * @param object object with @JavascriptInterface annotation methods
     * @param namespace if null or empty, the object have no namespace.
     */
    public void addJavascriptObject(Object object, String namespace) {
        if (namespace == null) {
            namespace = "";
        }
        if (object != null) {
            javaScriptNamespaceInterfaces.put(namespace, new JsObject(namespace, object));
        }
    }

    /**
     * remove the javascript object with supplied namespace.
     *
     * @param namespace method namespace
     */
    public void removeJavascriptObject(String namespace) {
        if (namespace == null) {
            namespace = "";
        }
        javaScriptNamespaceInterfaces.remove(namespace);

    }


    public void disableJavascriptDialogBlock(boolean disable) {
        alertBoxBlock = !disable;
    }

    @Override
    public void setWebChromeClient(WebChromeClient client) {
        webChromeClient = client;
    }

    private final WebChromeClient mWebChromeClient = new WebChromeClient() {

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            if (webChromeClient != null) {
                webChromeClient.onProgressChanged(view, newProgress);
            } else {
                super.onProgressChanged(view, newProgress);
            }
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            if (webChromeClient != null) {
                webChromeClient.onReceivedTitle(view, title);
            } else {
                super.onReceivedTitle(view, title);
            }
        }

        @Override
        public void onReceivedIcon(WebView view, Bitmap icon) {
            if (webChromeClient != null) {
                webChromeClient.onReceivedIcon(view, icon);
            } else {
                super.onReceivedIcon(view, icon);
            }
        }

        @Override
        public void onReceivedTouchIconUrl(WebView view, String url, boolean precomposed) {
            if (webChromeClient != null) {
                webChromeClient.onReceivedTouchIconUrl(view, url, precomposed);
            } else {
                super.onReceivedTouchIconUrl(view, url, precomposed);
            }
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (webChromeClient != null) {
                webChromeClient.onShowCustomView(view, callback);
            } else {
                super.onShowCustomView(view, callback);
            }
        }

        public void onShowCustomView(View view, int requestedOrientation,
                                     CustomViewCallback callback) {
            if (webChromeClient != null) {
                webChromeClient.onShowCustomView(view, requestedOrientation, callback);
            } else {
                super.onShowCustomView(view, requestedOrientation, callback);
            }
        }

        @Override
        public void onHideCustomView() {
            if (webChromeClient != null) {
                webChromeClient.onHideCustomView();
            } else {
                super.onHideCustomView();
            }
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog,
                                      boolean isUserGesture, Message resultMsg) {
            if (webChromeClient != null) {
                return webChromeClient.onCreateWindow(view, isDialog,
                        isUserGesture, resultMsg);
            }
            return super.onCreateWindow(view, isDialog, isUserGesture, resultMsg);
        }

        @Override
        public void onRequestFocus(WebView view) {
            if (webChromeClient != null) {
                webChromeClient.onRequestFocus(view);
            } else {
                super.onRequestFocus(view);
            }
        }

        @Override
        public void onCloseWindow(WebView window) {
            if (webChromeClient != null) {
                webChromeClient.onCloseWindow(window);
            } else {
                super.onCloseWindow(window);
            }
        }

        @SuppressWarnings("Convert2Lambda")
        @Override
        public boolean onJsAlert(WebView view, String url, final String message, final JsResult result) {
            if (!alertBoxBlock) {
                result.confirm();
            }
            if (webChromeClient != null) {
                if (webChromeClient.onJsAlert(view, url, message, result)) {
                    return true;
                }
            }
            Dialog alertDialog = new AlertDialog.Builder(getContext()).
                    setMessage(message).
                    setCancelable(false).
                    setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                            if (alertBoxBlock) {
                                result.confirm();
                            }
                        }
                    })
                    .create();
            alertDialog.show();
            return true;
        }

        @SuppressWarnings("Convert2Lambda")
        @Override
        public boolean onJsConfirm(WebView view, String url, String message,
                                   final JsResult result) {
            if (!alertBoxBlock) {
                result.confirm();
            }
            if (webChromeClient == null || !webChromeClient.onJsConfirm(view, url, message, result)) {
                DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (alertBoxBlock) {
                            if (which == Dialog.BUTTON_POSITIVE) {
                                result.confirm();
                            } else {
                                result.cancel();
                            }
                        }
                    }
                };
                new AlertDialog.Builder(getContext())
                        .setMessage(message)
                        .setCancelable(false)
                        .setPositiveButton(android.R.string.ok, listener)
                        .setNegativeButton(android.R.string.cancel, listener).show();

            }
            return true;

        }

        @SuppressWarnings("Convert2Lambda")
        @Override
        public boolean onJsPrompt(WebView view, String url, final String message,
                                  String defaultValue, final JsPromptResult result) {

            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.JELLY_BEAN) {
                String prefix = "_dsbridge=";
                if (message.startsWith(prefix)) {
                    result.confirm(innerJavascriptInterface.call(message.substring(prefix.length()), defaultValue));
                    return true;
                }
            }

            if (!alertBoxBlock) {
                result.confirm();
            }

            if (webChromeClient == null || !webChromeClient.onJsPrompt(view, url, message, defaultValue, result)) {
                final EditText editText = new EditText(getContext());
                editText.setText(defaultValue);
                if (defaultValue != null) {
                    editText.setSelection(defaultValue.length());
                }
                float dpi = getContext().getResources().getDisplayMetrics().density;
                DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (alertBoxBlock) {
                            if (which == Dialog.BUTTON_POSITIVE) {
                                result.confirm(editText.getText().toString());
                            } else {
                                result.cancel();
                            }
                        }
                    }
                };
                new AlertDialog.Builder(getContext())
                        .setTitle(message)
                        .setView(editText)
                        .setCancelable(false)
                        .setPositiveButton(android.R.string.ok, listener)
                        .setNegativeButton(android.R.string.cancel, listener)
                        .show();
                FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                int t = (int) (dpi * 16);
                layoutParams.setMargins(t, 0, t, 0);
                layoutParams.gravity = Gravity.CENTER_HORIZONTAL;
                editText.setLayoutParams(layoutParams);
                int padding = (int) (15 * dpi);
                editText.setPadding(padding - (int) (5 * dpi), padding, padding, padding);
            }
            return true;

        }

        @Override
        public boolean onJsBeforeUnload(WebView view, String url, String message, JsResult result) {
            if (webChromeClient != null) {
                return webChromeClient.onJsBeforeUnload(view, url, message, result);
            }
            return super.onJsBeforeUnload(view, url, message, result);
        }

        @Override
        public void onExceededDatabaseQuota(String url, String databaseIdentifier, long quota,
                                            long estimatedDatabaseSize,
                                            long totalQuota,
                                            WebStorage.QuotaUpdater quotaUpdater) {
            if (webChromeClient != null) {
                webChromeClient.onExceededDatabaseQuota(url, databaseIdentifier, quota,
                        estimatedDatabaseSize, totalQuota, quotaUpdater);
            } else {
                super.onExceededDatabaseQuota(url, databaseIdentifier, quota,
                        estimatedDatabaseSize, totalQuota, quotaUpdater);
            }
        }

        @Override
        public void onReachedMaxAppCacheSize(long requiredStorage, long quota, WebStorage.QuotaUpdater quotaUpdater) {
            if (webChromeClient != null) {
                webChromeClient.onReachedMaxAppCacheSize(requiredStorage, quota, quotaUpdater);
            }
            super.onReachedMaxAppCacheSize(requiredStorage, quota, quotaUpdater);
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
            if (webChromeClient != null) {
                webChromeClient.onGeolocationPermissionsShowPrompt(origin, callback);
            } else {
                super.onGeolocationPermissionsShowPrompt(origin, callback);
            }
        }

        @Override
        public void onGeolocationPermissionsHidePrompt() {
            if (webChromeClient != null) {
                webChromeClient.onGeolocationPermissionsHidePrompt();
            } else {
                super.onGeolocationPermissionsHidePrompt();
            }
        }


        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        public void onPermissionRequest(PermissionRequest request) {
            if (webChromeClient != null) {
                webChromeClient.onPermissionRequest(request);
            } else {
                super.onPermissionRequest(request);
            }
        }


        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        @Override
        public void onPermissionRequestCanceled(PermissionRequest request) {
            if (webChromeClient != null) {
                webChromeClient.onPermissionRequestCanceled(request);
            } else {
                super.onPermissionRequestCanceled(request);
            }
        }

        @Override
        public boolean onJsTimeout() {
            if (webChromeClient != null) {
                return webChromeClient.onJsTimeout();
            }
            return super.onJsTimeout();
        }

        @Override
        public void onConsoleMessage(String message, int lineNumber, String sourceID) {
            if (webChromeClient != null) {
                webChromeClient.onConsoleMessage(message, lineNumber, sourceID);
            } else {
                super.onConsoleMessage(message, lineNumber, sourceID);
            }
        }

        @Override
        public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
            if (webChromeClient != null) {
                return webChromeClient.onConsoleMessage(consoleMessage);
            }
            return super.onConsoleMessage(consoleMessage);
        }

        @Override
        public Bitmap getDefaultVideoPoster() {

            if (webChromeClient != null) {
                return webChromeClient.getDefaultVideoPoster();
            }
            return super.getDefaultVideoPoster();
        }

        @Override
        public View getVideoLoadingProgressView() {
            if (webChromeClient != null) {
                return webChromeClient.getVideoLoadingProgressView();
            }
            return super.getVideoLoadingProgressView();
        }

        @Override
        public void getVisitedHistory(ValueCallback<String[]> callback) {
            if (webChromeClient != null) {
                webChromeClient.getVisitedHistory(callback);
            } else {
                super.getVisitedHistory(callback);
            }
        }


        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                         FileChooserParams fileChooserParams) {
            if (webChromeClient != null) {
                return webChromeClient.onShowFileChooser(webView, filePathCallback, fileChooserParams);
            }
            return super.onShowFileChooser(webView, filePathCallback, fileChooserParams);
        }


        @SuppressWarnings({"rawtypes", "deprecation"})
        @Keep
        public void openFileChooser(ValueCallback valueCallback, String acceptType) {
            if (webChromeClient instanceof FileChooser) {
                ((FileChooser) webChromeClient).openFileChooser(valueCallback, acceptType);
            }
        }


        @SuppressWarnings("deprecation")
        @Keep
        @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
        public void openFileChooser(ValueCallback<Uri> valueCallback,
                                    String acceptType, String capture) {
            if (webChromeClient instanceof FileChooser) {
                ((FileChooser) webChromeClient).openFileChooser(valueCallback, acceptType, capture);
            }
        }

    };

    @Override
    public void clearCache(boolean includeDiskFiles) {
        super.clearCache(includeDiskFiles);
        CookieManager.getInstance().removeAllCookie();
        Context context = getContext();
        try {
            context.deleteDatabase("webview.db");
            context.deleteDatabase("webviewCache.db");
        } catch (Exception e) {
            e.printStackTrace();
        }

        File appCacheDir = new File(APP_CACHE_DIRNAME);
        File webviewCacheDir = new File(context.getCacheDir()
                .getAbsolutePath() + "/webviewCache");

        if (webviewCacheDir.exists()) {
            deleteFile(webviewCacheDir);
        }

        if (appCacheDir.exists()) {
            deleteFile(appCacheDir);
        }
    }

    public void deleteFile(File file) {
        if (file.exists()) {
            if (file.isFile()) {
                if(!file.delete()) {
                    Log.e("Webview", "delete file failed " + file.getAbsolutePath());
                }
            } else if (file.isDirectory()) {
                File[] files = file.listFiles();
                if(files!=null){
                    for (File value : files) {
                        deleteFile(value);
                    }
                }

            }
            if(!file.delete()) {
                Log.e("Webview", "delete file failed " + file.getAbsolutePath());
            }
        } else {
            Log.e("Webview", "delete file no exists " + file.getAbsolutePath());
        }
    }

    private void runOnMainThread(Runnable runnable) {
        if (Looper.getMainLooper() == Looper.myLooper()) {
            runnable.run();
            return;
        }
        mainHandler.post(runnable);
    }




}
