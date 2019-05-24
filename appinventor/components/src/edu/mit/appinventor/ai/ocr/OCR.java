// -*- mode: java; c-basic-offset: 2; -*-
// Copyright 2018 MIT, All rights reserved
// Released under the Apache License, Version 2.0
// http://www.apache.org/licenses/LICENSE-2.0

package edu.mit.appinventor.ai.ocr;

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
            "in the OCR component's WebViewer property in order for classification to work.",
        iconName = "aiwebres/ocr.png",
        nonVisible = true)
@SimpleObject(external = true)
@UsesPermissions(permissionNames = "android.permission.INTERNET, android.permission.CAMERA")
public final class OCR extends AndroidNonvisibleComponent implements Component {
  private static final String LOG_TAG = OCR.class.getSimpleName();
  private static final String MODEL_DIRECTORY = "/sdcard/AppInventor/assets/OCR/";
  private static final int IMAGE_WIDTH = 500;
  private static final int IMAGE_QUALITY = 100;
  private static final String MODE_VIDEO = "Video";
  private static final String MODE_IMAGE = "Image";
  private static final String ERROR_WEBVIEWER_NOT_SET =
      "You must specify a WebViewer using the WebViewer designer property before you can call %1s";

  // other error codes are defined in ocr.js
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
  private String inputMode = MODE_IMAGE;

  public OCR(final Form form) {
    super(form);
    requestHardwareAcceleration(form);
    WebView.setWebContentsDebuggingEnabled(true);
    Log.d(LOG_TAG, "Created OCR component");
  }

  @SuppressLint("SetJavaScriptEnabled")
  private void configureWebView(WebView webview) {
    this.webview = webview;
    webview.getSettings().setJavaScriptEnabled(true);
    webview.getSettings().setMediaPlaybackRequiresUserGesture(false);
    // adds a way to send strings to the javascript
    webview.addJavascriptInterface(new JsObject(), "OCR");
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
            webview.loadUrl("https://hiraginomincho.github.io/ocr/");
          }
        }
      };
    if (SDK26Helper.shouldAskForPermission(form)) {
      SDK26Helper.askForPermission(this, next);
    } else {
      next.run();
    }
  }

  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_CHOICES,
      editorArgs = {MODE_VIDEO, MODE_IMAGE})
  @SimpleProperty
  public void InputMode(String mode) {
    if (webview == null) {
      inputMode = mode;
      return;
    }
    if (MODE_VIDEO.equalsIgnoreCase(mode)) {
      webview.evaluateJavascript("setInputMode(\"video\");", null);
      inputMode = MODE_VIDEO;
    } else if (MODE_IMAGE.equalsIgnoreCase(mode)) {
      webview.evaluateJavascript("setInputMode(\"image\");", null);
      inputMode = MODE_IMAGE;
    } else {
      form.dispatchErrorOccurredEvent(this, "InputMode", ErrorMessages.ERROR_EXTENSION_ERROR, ERROR_INVALID_INPUT_MODE, LOG_TAG, "Invalid input mode " + mode);
    }
  }

  @SimpleProperty(category = PropertyCategory.BEHAVIOR,
      description = "Gets or sets the input mode for classification. Valid values are \"Video\" " +
          "(the default) and \"Image\".")
  public String InputMode() {
    return inputMode;
  }

  @SimpleFunction(description = "Performs classification on the image at the given path and triggers the GotClassification event when classification is finished successfully.")
  public void RecognizeImageData(final String image) {
    Log.d(LOG_TAG, "Entered Classify");
    Log.d(LOG_TAG, image);

    String imagePath = (image == null) ? "" : image;
    BitmapDrawable imageDrawable;
    Bitmap scaledImageBitmap = null;

    try {
      imageDrawable = MediaUtil.getBitmapDrawable(form.$form(), imagePath);
      scaledImageBitmap = Bitmap.createScaledBitmap(imageDrawable.getBitmap(), IMAGE_WIDTH, (int) (imageDrawable.getBitmap().getHeight() * ((float) IMAGE_WIDTH) / imageDrawable.getBitmap().getWidth()), false);
    } catch (IOException ioe) {
      Log.e(LOG_TAG, "Unable to load " + imagePath);
    }

    // compression format of PNG -> not lossy
    Bitmap immagex = scaledImageBitmap;
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    immagex.compress(Bitmap.CompressFormat.PNG, IMAGE_QUALITY, baos);
    byte[] b = baos.toByteArray();

    String imageEncodedbase64String = Base64.encodeToString(b, 0).replace("\n", "");
    Log.d(LOG_TAG, "imageEncodedbase64String: " + imageEncodedbase64String);
    assertWebView("RecognizeImageData");
    webview.evaluateJavascript("recognizeImageData(\"" + imageEncodedbase64String + "\");", null);
  }

  @SimpleFunction(description = "Sets a language. Default is English.")
  public void SetLanguage(final String language) {
    assertWebView("SetLanguage");
    webview.evaluateJavascript("setLanguage(\"" + language + "\");", null);
  }

  @SimpleFunction(description = "temp")
  public void Clear() {
    assertWebView("Clear");
    webview.evaluateJavascript("clear();", null);
  }

  @SimpleFunction(description = "Toggles between user-facing and environment-facing camera.")
  public void ToggleCameraFacingMode() {
    assertWebView("ToggleCameraFacingMode");
    webview.evaluateJavascript("toggleCameraFacingMode();", null);
  }

  @SimpleFunction(description = "Performs classification on current video frame and triggers the GotClassification event when classification is finished successfully.")
  public void RecognizeVideoData() {
    assertWebView("RecognizeVideoData");
    webview.evaluateJavascript("recognizeVideoData();", null);
  }

  @SimpleFunction(description = "Sets the input mode to image if inputMode is \"image\" or video if inputMode is \"video\".")
  public void SetInputMode(final String inputMode) {
    assertWebView("SetInputMode");
    webview.evaluateJavascript("setInputMode(\"" + inputMode + "\");", null);
  }

  @SimpleFunction(description = "temp")
  public void DrawBoundingBox(final int index, final String label) {
    assertWebView("DrawBoundingBox");
    webview.evaluateJavascript("drawBoundingBox(" + (index - 1) + ",\"" + encode(label) + "\");", null);
  }

  @SimpleEvent(description = "Event indicating that the classifier is ready.")
  public void ClassifierReady() {
    EventDispatcher.dispatchEvent(this, "ClassifierReady");
  }

  @SimpleEvent(description = "temp")
  public void GotProgress(String progress) {
    EventDispatcher.dispatchEvent(this, "GotProgress", progress);
  }

  @SimpleEvent(description = "Event indicating that classification has finished successfully. Result is of the form [[class1, confidence1], [class2, confidence2], ..., [class10, confidence10]].")
  public void GotText(String text) {
    EventDispatcher.dispatchEvent(this, "GotText", text);
  }

  @SimpleEvent(description = "temp")
  public void GotFilteredText(String text) {
    EventDispatcher.dispatchEvent(this, "GotFilteredText", text);
  }

  @SimpleEvent(description = "temp")
  public void GotWords(YailList result) {
    EventDispatcher.dispatchEvent(this, "GotWords", result);
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
    public void gotProgress(final String progress) {
      Log.d(LOG_TAG, "Entered gotProgress: " + progress);
      form.runOnUiThread(new Runnable() {
        @Override
        public void run() {
          GotProgress(progress);
        }
      });
    }

    @JavascriptInterface
    public void gotText(final String text) {
      Log.d(LOG_TAG, "Entered gotText: " + text);
      form.runOnUiThread(new Runnable() {
        @Override
        public void run() {
          GotText(text);
        }
      });
      /*
      try {
        Log.d(LOG_TAG, "Entered try of reportResult");
        JSONArray list = new JSONArray(result);
        YailList intermediateList = YailList.makeList(JsonUtil.getListFromJsonArray(list));
        final List resultList = new ArrayList();
        for (int i = 0; i < intermediateList.size(); i++) {
          resultList.add(YailList.makeList((List) intermediateList.getObject(i)));
        }
        form.runOnUiThread(new Runnable() {
          @Override
          public void run() {
            GotClassification(YailList.makeList(resultList));
          }
        });
      } catch (JSONException e) {
        Log.d(LOG_TAG, "Entered catch of reportResult");
        e.printStackTrace();
        ClassificationFailed();
      }
      */
    }

    @JavascriptInterface
    public void gotFilteredText(final String filteredText) {
      Log.d(LOG_TAG, "Entered gotFilteredText: " + filteredText);
      form.runOnUiThread(new Runnable() {
        @Override
        public void run() {
          GotFilteredText(filteredText);
        }
      });
    }

    @JavascriptInterface
    public void gotWords(final String result) {
      Log.d(LOG_TAG, "Entered gotWords: " + result);
      form.runOnUiThread(new Runnable() {
        @Override
        public void run() {
          try {
            JSONArray list = new JSONArray(result);
            YailList intermediateList = YailList.makeList(JsonUtil.getListFromJsonArray(list));
            final List resultList = new ArrayList();
            for (int i = 0; i < intermediateList.size(); i++) {
              resultList.add(YailList.makeList((List) intermediateList.getObject(i)));
            }
            GotWords(YailList.makeList(resultList));
          } catch (JSONException e) {
            e.printStackTrace();
          }
        }
      });
    }
  }
}
