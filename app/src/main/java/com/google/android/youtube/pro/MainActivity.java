package com.google.android.youtube.pro;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.Button;
import android.view.Menu;
import android.view.MenuItem;
import android.app.AlertDialog;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import android.os.Environment;


// Import the separated components
import com.google.android.youtube.pro.webview.YTProWebView;
import com.google.android.youtube.pro.webview.YTProWebViewClient;
import com.google.android.youtube.pro.webview.YTProWebChromeClient;
import com.google.android.youtube.pro.webview.WebAppInterface;
import com.google.android.youtube.pro.webview.BinaryStreamManager;
import android.view.MotionEvent;
import android.os.SystemClock;

import com.google.android.youtube.pro.receivers.MediaCommandReceiver;

public class MainActivity extends Activity {

    public boolean portrait = false;
    public boolean isPlaying = false;
    public boolean mediaSession = false;
    public boolean isPip = false;
    public boolean dL = false;

    private YTProWebView web;
    private MediaCommandReceiver broadcastReceiver;
    private OnBackInvokedCallback backCallback;
    public BinaryStreamManager streamManager;
    
    

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Modern system UI (MD3-like edge-to-edge feel)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(0xFF000000);
            getWindow().setNavigationBarColor(0xFF000000);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarContrastEnforced(false);
        }
        
        setContentView(R.layout.main);
        
        SharedPreferences prefs = getSharedPreferences("YTPRO", MODE_PRIVATE);
        if (!prefs.contains("bgplay")) {
            prefs.edit().putBoolean("bgplay", true).apply();
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        load(false);
    }

    public void load(boolean dl) {
              
        
        this.dL = dl;
        web = findViewById(R.id.web);
        
        // Long press on WebView → open Convert menu (because theme is NoActionBar)
        web.setOnLongClickListener(v -> {
            showVideoPickerForConvert();
            return true;
        });
        
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setSupportZoom(true);
        web.getSettings().setBuiltInZoomControls(true);
        web.getSettings().setDisplayZoomControls(false);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setDatabaseEnabled(true);
        web.getSettings().setMediaPlaybackRequiresUserGesture(false); 
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(web, true);
        }

        Intent intent = getIntent();
        String action = intent.getAction();
        Uri data = intent.getData();
        String url = "https://m.youtube.com/";
        if (Intent.ACTION_VIEW.equals(action) && data != null) {
            url = data.toString();
        } else if (Intent.ACTION_SEND.equals(action)) {
            String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (sharedText != null && (sharedText.contains("youtube.com") || sharedText.contains("youtu.be"))) {
                url = sharedText;
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
          web.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }


        web.addJavascriptInterface(new WebAppInterface(this, web), "Android");
        web.setWebChromeClient(new YTProWebChromeClient(this, web));
        web.setWebViewClient(new YTProWebViewClient(this, web));
        
        web.loadUrl(url);

        setupReceiver();
        setupBackNavigation();
        streamManager = new BinaryStreamManager(web,this);
        
        
    }
         

   

    private void setupReceiver() {
        broadcastReceiver = new MediaCommandReceiver(web);
        if (Build.VERSION.SDK_INT >= 34 && getApplicationInfo().targetSdkVersion >= 34) {
            registerReceiver(broadcastReceiver, new IntentFilter("TRACKS_TRACKS"), RECEIVER_EXPORTED);
        } else {
            registerReceiver(broadcastReceiver, new IntentFilter("TRACKS_TRACKS"));
        }
    }

    private void setupBackNavigation() {
        if (Build.VERSION.SDK_INT >= 33) {
            OnBackInvokedDispatcher dispatcher = getOnBackInvokedDispatcher();
            backCallback = new OnBackInvokedCallback() {
                @Override
                public void onBackInvoked() {
                    handleBackPress();
                }
            };
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
    }

    private void handleBackPress() {
        if (web.canGoBack()) {
            web.goBack();
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        handleBackPress();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 101) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                web.loadUrl("https://m.youtube.com");
            } else {
                Toast.makeText(getApplicationContext(), getString(R.string.grant_mic), Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == 1) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_DENIED) {
                Toast.makeText(getApplicationContext(), getString(R.string.grant_storage), Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        isPip = isInPictureInPictureMode;
        web.evaluateJavascript(isInPictureInPictureMode ? "PIPlayer();" : "removePIP();", null);
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Build.VERSION.SDK_INT >= 26 && web.getUrl() != null && (web.getUrl().contains("watch") || web.getUrl().contains("shorts"))) {
            if (isPlaying) {
                try {

         /**Webview requires gesture for FullScreenView which is used for PiP mode , this does that ig.*/
         web.loadUrl("javascript:showCurtain();");
         long downTime = SystemClock.uptimeMillis();
         long eventTime = downTime;

         float x = web.getWidth() / 2f;
         float y = 10f; 
         MotionEvent down = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_DOWN, x, y, 0);
         web.dispatchTouchEvent(down);

         eventTime += 15; 
         MotionEvent up = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_UP, x, y, 0);
         web.dispatchTouchEvent(up);
         down.recycle();
         up.recycle();
         web.loadUrl( "javascript:hideCurtain();");
                 
                    PictureInPictureParams params = new PictureInPictureParams.Builder()
                            .setAspectRatio(new Rational(portrait ? 9 : 16, portrait ? 16 : 9))
                            .build();
                    enterPictureInPictureMode(params);
                } catch (IllegalStateException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopService(new Intent(getApplicationContext(), ForegroundService.class));
        if (broadcastReceiver != null) unregisterReceiver(broadcastReceiver);
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        if (streamManager != null) {
            streamManager.cleanup();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Simple developer / tools menu
        menu.add(0, 1001, 0, "Convert Video → Audio");
        menu.add(0, 1002, 1, "Open Downloads Folder");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == 1001) {
            showVideoPickerForConvert();
            return true;
        } else if (id == 1002) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                Uri uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FYTPRO");
                intent.setDataAndType(uri, "*/*");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(Intent.createChooser(intent, "Open YTPRO Downloads"));
            } catch (Exception e) {
                // fallback: open Downloads
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"), "*/*");
                startActivity(Intent.createChooser(intent, "Open Downloads"));
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Show list of recent video files in Downloads/YTPRO so user can pick one to convert */
    private void showVideoPickerForConvert() {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "YTPRO");
        if (!dir.exists()) dir.mkdirs();

        File[] files = dir.listFiles((d, name) -> {
            String lower = name.toLowerCase();
            return lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mkv") || lower.endsWith(".3gp");
        });

        if (files == null || files.length == 0) {
            // also check root Downloads
            dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            files = dir.listFiles((d, name) -> {
                String lower = name.toLowerCase();
                return lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mkv");
            });
        }

        if (files == null || files.length == 0) {
            Toast.makeText(this, "No video files found in Downloads/YTPRO", Toast.LENGTH_LONG).show();
            return;
        }

        // Sort newest first
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        final ArrayList<String> names = new ArrayList<>();
        final ArrayList<File> fileList = new ArrayList<>();
        for (File f : files) {
            if (names.size() >= 30) break; // limit
            names.add(f.getName() + "  (" + (f.length() / 1024 / 1024) + " MB)");
            fileList.add(f);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert);
        builder.setTitle("Select video to convert")
            .setItems(names.toArray(new String[0]), (dialog, which) -> {
                File selected = fileList.get(which);
                // Re-use the dialog from WebAppInterface
                if (web != null && web.getTag() == null) { // just safety
                }
                // Call the public method on the interface if available, otherwise create dialog here
                com.google.android.youtube.pro.webview.WebAppInterface iface = 
                    new com.google.android.youtube.pro.webview.WebAppInterface(this, web);
                iface.showConvertConfirmDialog(selected);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

}
