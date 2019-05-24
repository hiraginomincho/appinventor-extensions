// -*- mode: java; c-basic-offset: 2; -*-
// Copyright 2018 MIT, All rights reserved
// Released under the Apache License, Version 2.0
// http://www.apache.org/licenses/LICENSE-2.0

package edu.mit.appinventor.ai.sentiment;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.util.Base64;
import android.util.Log;
import android.view.WindowManager.LayoutParams;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import com.google.appinventor.components.annotations.DesignerComponent;
import com.google.appinventor.components.annotations.DesignerProperty;
import com.google.appinventor.components.annotations.PropertyCategory;
import com.google.appinventor.components.annotations.SimpleEvent;
import com.google.appinventor.components.annotations.SimpleFunction;
import com.google.appinventor.components.annotations.SimpleObject;
import com.google.appinventor.components.annotations.SimpleProperty;
import com.google.appinventor.components.annotations.UsesAssets;
import com.google.appinventor.components.annotations.UsesPermissions;
import com.google.appinventor.components.common.ComponentCategory;
import com.google.appinventor.components.common.PropertyTypeConstants;
import com.google.appinventor.components.runtime.AndroidNonvisibleComponent;
import com.google.appinventor.components.runtime.Component;
import com.google.appinventor.components.runtime.EventDispatcher;
import com.google.appinventor.components.runtime.Form;
import com.google.appinventor.components.runtime.WebViewer;
import com.google.appinventor.components.runtime.util.ErrorMessages;
import com.google.appinventor.components.runtime.util.JsonUtil;
import com.google.appinventor.components.runtime.util.MediaUtil;
import com.google.appinventor.components.runtime.util.SdkLevel;
import com.google.appinventor.components.runtime.util.YailList;
import com.google.appinventor.components.runtime.util.IOUtils;
import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static android.net.Uri.encode;

/**
 * Component that recognizes text.
 */

@DesignerComponent(version = 20181124,
        category = ComponentCategory.EXTENSION,
        description = "Component that recognizes text. You must provide a WebViewer component " +
            "in the Sentiment component's WebViewer property in order for classification to work.",
        iconName = "aiwebres/sentiment.png",
        nonVisible = true)
@SimpleObject(external = true)
@UsesPermissions(permissionNames = "android.permission.INTERNET")
public final class Sentiment extends AndroidNonvisibleComponent implements Component {
  private static final String LOG_TAG = Sentiment.class.getSimpleName();
  private static final String MODEL_DIRECTORY = "/sdcard/AppInventor/assets/Sentiment/";
  private static final String ERROR_WEBVIEWER_NOT_SET =
      "You must specify a WebViewer using the WebViewer designer property before you can call %1s";

  // other error codes are defined in sentiment.js
  private static final int ERROR_CLASSIFICATION_NOT_SUPPORTED = -1;
  private static final int ERROR_CLASSIFICATION_FAILED = -2;
  private static final int ERROR_LOAD_MODEL_FAILED_BAD_FILE_FORMAT = -3;
  private static final int ERROR_LOAD_MODEL_FAILED_TOO_MANY_CLASSES = -4;
  private static final int ERROR_SAVE_MODEL_FAILED = -5;
  private static final int ERROR_NO_MORE_CLASSES_AVAILABLE = -6;
  private static final int ERROR_LABEL_DOES_NOT_EXIST = -7;
  private static final int ERROR_INVALID_INPUT_MODE = -8;
  private static final int ERROR_WEBVIEWER_REQUIRED = -9;

  private WebView webview = null;

  public Sentiment(final Form form) {
    super(form);
    requestHardwareAcceleration(form);
    WebView.setWebContentsDebuggingEnabled(true);
    Log.d(LOG_TAG, "Created Sentiment component");
  }

  @SuppressLint("SetJavaScriptEnabled")
  private void configureWebView(WebView webview) {
    this.webview = webview;
    webview.getSettings().setJavaScriptEnabled(true);
    webview.getSettings().setMediaPlaybackRequiresUserGesture(false);
    // adds a way to send strings to the javascript
    webview.addJavascriptInterface(new JsObject(), "Sentiment");
    webview.setWebViewClient(new WebViewClient());
    webview.setWebChromeClient(new WebChromeClient() {
      @Override
      public void onPermissionRequest(PermissionRequest request) {
        Log.d(LOG_TAG, "onPermissionRequest called");
        String[] requestedResources = request.getResources();
        for (String r : requestedResources) {
          if (r.equals(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
            request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
          }
        }
      }
    });
  }

  public void Initialize() {
    Log.d(LOG_TAG, "webview = " + webview);
    if (webview == null) {
      form.dispatchErrorOccurredEvent(this, "WebViewer",
          ErrorMessages.ERROR_EXTENSION_ERROR, ERROR_WEBVIEWER_REQUIRED, LOG_TAG,
          "You must specify a WebViewer component in the WebViewer property.");
    }
  }

  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_COMPONENT + ":com.google.appinventor.runtime.components.WebViewer")
  @SimpleProperty(userVisible = false)
  public void WebViewer(final WebViewer webviewer) {
    Runnable next = new Runnable() {
        public void run() {
          if (webviewer != null) {
            configureWebView((WebView) webviewer.getView());
            webview.requestLayout();
            Log.d(LOG_TAG, "isHardwareAccelerated? " + webview.isHardwareAccelerated());
            webview.loadUrl("https://hiraginomincho.github.io/sentiment/");
          }
        }
      };
    if (SDK26Helper.shouldAskForPermission(form)) {
      SDK26Helper.askForPermission(this, next);
    } else {
      next.run();
    }
  }

  @SimpleFunction(description = "")
  public void ClassifyTextData(final String text) {
    assertWebView("ClassifyTextData");
    webview.evaluateJavascript("classifyTextData(\"" + encode(text) + "\");", null);
  }

  @SimpleEvent(description = "Event indicating that the classifier is ready.")
  public void ClassifierReady() {
    EventDispatcher.dispatchEvent(this, "ClassifierReady");
  }

  @SimpleEvent(description = "temp")
  public void GotClassification(final String result) {
    EventDispatcher.dispatchEvent(this, "GotClassification", result);
  }

  @SimpleEvent(description = "Event indicating that an error has occurred.")
  public void Error(final int errorCode) {
    EventDispatcher.dispatchEvent(this, "Error", errorCode);
  }

  Form getForm() {
    return form;
  }

  private static void requestHardwareAcceleration(Activity activity) {
    activity.getWindow().setFlags(LayoutParams.FLAG_HARDWARE_ACCELERATED, LayoutParams.FLAG_HARDWARE_ACCELERATED);
  }

  private void assertWebView(String method) {
    if (webview == null) {
      throw new RuntimeException(String.format(ERROR_WEBVIEWER_NOT_SET, method));
    }
  }

  private class JsObject {
    @JavascriptInterface
    public void ready() {
      Log.d(LOG_TAG, "Entered ready");
      form.runOnUiThread(new Runnable() {
        @Override
        public void run() {
          ClassifierReady();
        }
      });
    }

    @JavascriptInterface
    public void reportResult(final String result) {
      Log.d(LOG_TAG, "Entered reportResult: " + result);
      Log.d(LOG_TAG, "Entered try of reportResult");
      form.runOnUiThread(new Runnable() {
        @Override
        public void run() {
          GotClassification(result);
        }
      });
    }
  }
}
