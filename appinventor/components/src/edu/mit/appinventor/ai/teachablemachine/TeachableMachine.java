// -*- mode: java; c-basic-offset: 2; -*-
// Copyright 2018 MIT, All rights reserved
// Released under the Apache License, Version 2.0
// http://www.apache.org/licenses/LICENSE-2.0

package edu.mit.appinventor.ai.teachablemachine;

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
 * Component for teaching a machine to recognize different images.
 *
 * @author kevinzhu@mit.edu (Kevin Zhu)
 * @author kelseyc@mit.edu (Kelsey Chan)
 */

@DesignerComponent(version = 20181124,
        category = ComponentCategory.EXTENSION,
        description = "Component for teaching a machine to recognize different images. You must provide a WebViewer component " +
            "in the TeachableMachine component's WebViewer property in order for classification to work.",
        iconName = "aiwebres/teachablemachine.png",
        nonVisible = true)
@SimpleObject(external = true)
@UsesAssets(fileNames = "teachablemachine.html, teachablemachine.js, model.json, group1-shard1of4, group1-shard2of4, group1-shard3of4, group1-shard4of4, tfjs-1.1.2.js")
@UsesPermissions(permissionNames = "android.permission.INTERNET, android.permission.CAMERA")
public final class TeachableMachine extends AndroidNonvisibleComponent implements Component {
  private static final String LOG_TAG = TeachableMachine.class.getSimpleName();
  private static final String MODEL_DIRECTORY = "/sdcard/AppInventor/assets/TeachableMachine/";
  private static final int IMAGE_WIDTH = 500;
  private static final int IMAGE_QUALITY = 100;
  private static final String MODE_VIDEO = "Video";
  private static final String MODE_IMAGE = "Image";
  private static final String ERROR_WEBVIEWER_NOT_SET =
      "You must specify a WebViewer using the WebViewer designer property before you can call %1s";

  private static final String MODEL_PREFIX = "https://storage.googleapis.com/tfjs-models/savedmodel/mobilenet_v2_1.0_224/";

  // other error codes are defined in teachablemachine.js
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
  private String inputMode = MODE_VIDEO;

  public TeachableMachine(final Form form) {
    super(form);
    requestHardwareAcceleration(form);
    WebView.setWebContentsDebuggingEnabled(true);
    Log.d(LOG_TAG, "Created TeachableMachine component");
  }

  @SuppressLint("SetJavaScriptEnabled")
  private void configureWebView(WebView webview) {
    this.webview = webview;
    webview.getSettings().setJavaScriptEnabled(true);
    webview.getSettings().setMediaPlaybackRequiresUserGesture(false);
    // adds a way to send strings to the javascript
    webview.addJavascriptInterface(new JsObject(), "TeachableMachine");
    webview.setWebViewClient(new WebViewClient() {
      @Override
      public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
        Log.d(LOG_TAG, "shouldInterceptRequest called");
        if (url.contains(MODEL_PREFIX)) {
          Log.d(LOG_TAG, "overriding " + url);
          try {
            InputStream inputStream = form.openAssetForExtension(TeachableMachine.this, url.substring(MODEL_PREFIX.length()));
            String charSet;
            String contentType;
            if (url.endsWith(".json")) {
              contentType = "application/json";
              charSet = "UTF-8";
            } else {
              contentType = "application/octet-stream";
              charSet = "binary";
            }
            if (SdkLevel.getLevel() >= SdkLevel.LEVEL_LOLLIPOP) {
              Map<String, String> responseHeaders = new HashMap<String, String>();
              responseHeaders.put("Access-Control-Allow-Origin", "*");
              return new WebResourceResponse(contentType, charSet, 200, "OK", responseHeaders, inputStream);
            } else {
              return new WebResourceResponse(contentType, charSet, inputStream);
            }
          } catch (IOException e) {
            e.printStackTrace();
            return super.shouldInterceptRequest(view, url);
          }
        }
        Log.d(LOG_TAG, url);
        return super.shouldInterceptRequest(view, url);
      }
    });
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
            try {
              Log.d(LOG_TAG, "isHardwareAccelerated? " + webview.isHardwareAccelerated());
              webview.loadUrl(form.getAssetPathForExtension(TeachableMachine.this, "teachablemachine.html"));
            } catch (FileNotFoundException e) {
              Log.d(LOG_TAG, e.getMessage());
              e.printStackTrace();
            }
          }
        }
      };
    if (SDK26Helper.shouldAskForPermission(form)) {
      SDK26Helper.askForPermission(this, next);
    } else {
      next.run();
    }
  }

  @SimpleFunction(description = "Toggles between user-facing and environment-facing camera.")
  public void ToggleCameraFacingMode() {
    assertWebView("ToggleCameraFacingMode");
    webview.evaluateJavascript("toggleCameraFacingMode();", null);
  }

  @SimpleFunction(description = "Starts training machine to associate images from the camera with the provided label.")
  public void StartTraining(final String label) {
    assertWebView("StartTraining");
    webview.evaluateJavascript("startTraining(\"" + encode(label) + "\");", null);
  }

  @SimpleFunction(description = "Stops collecting images from the camera to train machine.")
  public void StopTraining() {
    assertWebView("StopTraining");
    webview.evaluateJavascript("stopTraining();", null);
  }

  @SimpleFunction(description = "Clears training data associated with provided label.")
  public void Clear(final String label) {
    assertWebView("Clear");
    webview.evaluateJavascript("clear(\"" + encode(label) + "\");", null);
  }

  @SimpleFunction(description = "Saves model (current set of samples and labels) with provided name.")
  public void SaveModel(final String name) {
    assertWebView("SaveModel");
    webview.evaluateJavascript("saveModel(\"" + encode(name) + "\");", null);
  }

  @SimpleFunction(description = "Loads model with provided name.")
  public void LoadModel(final String name) {
    assertWebView("LoadModel");
    try {
      String model = new String(Files.readAllBytes(Paths.get(MODEL_DIRECTORY + name)));
      webview.evaluateJavascript("loadModel(\"" + encode(name) + "\", \"" + encode(model) + "\");", null);
    } catch (IOException e) {
      e.printStackTrace();
      Error(ERROR_LOAD_MODEL_FAILED_BAD_FILE_FORMAT, name);
    }
  }

  @SimpleEvent(description = "Event indicating that the classifier is ready.")
  public void ClassifierReady() {
    EventDispatcher.dispatchEvent(this, "ClassifierReady");
  }

  @SimpleEvent(description = "Event indicating that sample counts have been updated.<br>Result is of the form [[label1, sampleCount1], [label2, sampleCount2], ..., [labelN, sampleCountN]].")
  public void GotSampleCounts(YailList result) {
    EventDispatcher.dispatchEvent(this, "GotSampleCounts", result);
  }

  @SimpleEvent(description = "Event indicating that confidences have been updated.<br>Result is of the form [[label1, confidence1], [label2, confidence2], ..., [labelN, confidenceN]].")
  public void GotConfidences(YailList result) {
    EventDispatcher.dispatchEvent(this, "GotConfidences", result);
  }

  @SimpleEvent(description = "Event indicating that classification has finished successfully. Label is the one with the highest confidence.")
  public void GotClassification(String label) {
    EventDispatcher.dispatchEvent(this, "GotClassification", label);
  }

  @SimpleEvent(description = "Event indicating that SaveModel with the specified name has completed successfully.")
  public void DoneSavingModel(String name) {
    EventDispatcher.dispatchEvent(this, "DoneSavingModel", name);
  }

  @SimpleEvent(description = "Event indicating that LoadModel with the specified name has completed successfully.")
  public void DoneLoadingModel(String name) {
    EventDispatcher.dispatchEvent(this, "DoneLoadingModel", name);
  }

  @SimpleEvent(description = "Event indicating that an error has occurred.")
  public void Error(final int errorCode, final String message) {
    EventDispatcher.dispatchEvent(this, "Error", errorCode, message);
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

  private void saveModel(String name, String model) {
    // save to file system
    String path = MODEL_DIRECTORY + name;
    new File(MODEL_DIRECTORY).mkdirs();
    PrintStream out = null;
    try {
      out = new PrintStream(new FileOutputStream(path));
      out.print(model);
    } catch (FileNotFoundException e) {
      e.printStackTrace();
      Error(ERROR_SAVE_MODEL_FAILED, name);
    } finally {
      IOUtils.closeQuietly(LOG_TAG, out);
    }
    DoneSavingModel(name);
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
    public void gotSampleCounts(final String result) {
      Log.d(LOG_TAG, "Entered gotSampleCounts: " + result);
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
            GotSampleCounts(YailList.makeList(resultList));
          } catch (JSONException e) {
            Log.d(LOG_TAG, "Entered catch of gotSampleCounts");
            e.printStackTrace();
            Error(ERROR_CLASSIFICATION_FAILED, "");
          }
        }
      });
    }

    @JavascriptInterface
    public void gotConfidences(final String result) {
      Log.d(LOG_TAG, "Entered gotConfidences: " + result);
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
            GotConfidences(YailList.makeList(resultList));
          } catch (JSONException e) {
            Log.d(LOG_TAG, "Entered catch of gotConfidences");
            e.printStackTrace();
            Error(ERROR_CLASSIFICATION_FAILED, "");
          }
        }
      });
    }

    @JavascriptInterface
    public void gotClassification(final String label) {
      Log.d(LOG_TAG, "Entered gotClassification: " + label);
      form.runOnUiThread(new Runnable() {
        @Override
        public void run() {
          GotClassification(label);
        }
      });
    }

    @JavascriptInterface
    public void gotSavedModel(final String name, final String model) {
      Log.d(LOG_TAG, "Entered gotSavedModel: " + name);
      form.runOnUiThread(new Runnable() {
        @Override
        public void run() {
          saveModel(name, model);
        }
      });
    }

    @JavascriptInterface
    public void doneLoadingModel(final String label) {
      Log.d(LOG_TAG, "Entered doneLoadingModel: " + label);
      form.runOnUiThread(new Runnable() {
        @Override
        public void run() {
          DoneLoadingModel(label);
        }
      });
    }

    @JavascriptInterface
    public void error(final int errorCode, final String message) {
      Log.d(LOG_TAG, "Entered error: message = " + errorCode + ", " + message);
      form.runOnUiThread(new Runnable() {
        @Override
        public void run() {
          Error(errorCode, message);
        }
      });
    }
  }
}
