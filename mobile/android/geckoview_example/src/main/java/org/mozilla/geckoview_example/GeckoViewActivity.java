/* -*- Mode: Java; c-basic-offset: 4; tab-width: 20; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.geckoview_example;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.View;
import android.util.Patterns;
import android.net.Uri;

import android.support.v4.widget.SwipeRefreshLayout;
import android.os.Handler;

import org.mozilla.gecko.GeckoView;
import org.mozilla.gecko.GeckoViewSettings;

public class GeckoViewActivity extends Activity {
    private static final String LOGTAG = "GeckoViewActivity";
    private static final String DEFAULT_URL = "https://mozilla.org";
    private static final String USE_MULTIPROCESS_EXTRA = "use_multiprocess";

    /* package */ static final int REQUEST_FILE_PICKER = 1;

    private GeckoView mGeckoView;
    private SwipeRefreshLayout mSwipeLayout;
    private EditText mSearchBox;
    private Navigation mNavigation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(LOGTAG, "zerdatime " + SystemClock.elapsedRealtime() +
              " - application start");

        if (BuildConfig.DEBUG) {
            // In debug builds, we want to load JavaScript resources fresh with each build.
            GeckoView.preload(this, "-purgecaches");
        }

        setContentView(R.layout.geckoview_activity);

        mGeckoView = (GeckoView) findViewById(R.id.gecko_view);
        mGeckoView.setContentListener(new MyGeckoViewContent());
        mGeckoView.setProgressListener(new MyGeckoViewProgress());

        mSwipeLayout = (SwipeRefreshLayout) findViewById(R.id.swipe_refresh);
        mGeckoView.setScrollListener(new ScrollListener(mSwipeLayout));

        mSearchBox = (EditText) findViewById(R.id.searchbox);
        mSearchBox.setOnEditorActionListener(new OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView view, int actionId, KeyEvent event) {
                Log.d(LOGTAG, "action " + actionId + ", event " + event);
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    String text = view.getText().toString();
                    Log.d(LOGTAG, "search " + text);
                    if (Patterns.WEB_URL.matcher(text).matches()) {
                        mGeckoView.loadUri(Uri.parse(text));
                    } else {
                        mGeckoView.loadUri(Uri.parse("https://duckduckgo.com?kae=d&q=!g " + text));
                    }
                }
                return false;
            }
        });

        mNavigation = new Navigation(this, mSearchBox);
        mGeckoView.setNavigationListener(mNavigation);

        final BasicGeckoViewPrompt prompt = new BasicGeckoViewPrompt();
        prompt.filePickerRequestCode = REQUEST_FILE_PICKER;
        mGeckoView.setPromptDelegate(prompt);

        loadFromIntent(getIntent());
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        if (intent.getData() != null) {
            loadFromIntent(intent);
        }
    }

    private void loadFromIntent(final Intent intent) {
        mGeckoView.getSettings().setBoolean(
            GeckoViewSettings.USE_MULTIPROCESS,
            intent.getBooleanExtra(USE_MULTIPROCESS_EXTRA, true));

        final Uri uri = intent.getData();
        mGeckoView.loadUri(uri != null ? uri.toString() : DEFAULT_URL);
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode,
                                    final Intent data) {
        if (requestCode == REQUEST_FILE_PICKER) {
            final BasicGeckoViewPrompt prompt = (BasicGeckoViewPrompt)
                    mGeckoView.getPromptDelegate();
            prompt.onFileCallbackResult(resultCode, data);
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    public void onBackPressed() {
        Log.d(LOGTAG, "onBackPressed");
        if (!mNavigation.canGoBack()) {
            super.onBackPressed();
            return;
        }

        mGeckoView.goBack();
    }

    private class MyGeckoViewContent implements GeckoView.ContentListener {
        @Override
        public void onTitleChange(GeckoView view, String title) {
            Log.i(LOGTAG, "Content title changed to " + title);
        }

        @Override
        public void onFullScreen(final GeckoView view, final boolean fullScreen) {
            getWindow().setFlags(fullScreen ? WindowManager.LayoutParams.FLAG_FULLSCREEN : 0,
                                 WindowManager.LayoutParams.FLAG_FULLSCREEN);
            if (fullScreen) {
                getActionBar().hide();
            } else {
                getActionBar().show();
            }
        }
    }

    private class MyGeckoViewProgress implements GeckoView.ProgressListener {
        @Override
        public void onPageStart(GeckoView view, String url) {
            Log.i(LOGTAG, "Starting to load page at " + url);
            Log.i(LOGTAG, "zerdatime " + SystemClock.elapsedRealtime() +
                  " - page load start");
        }

        @Override
        public void onPageStop(GeckoView view, boolean success) {
            Log.i(LOGTAG, "Stopping page load " + (success ? "successfully" : "unsuccessfully"));
            Log.i(LOGTAG, "zerdatime " + SystemClock.elapsedRealtime() +
                  " - page load stop");
        }

        @Override
        public void onSecurityChange(GeckoView view, int status) {
            String statusString;
            if ((status & STATE_IS_BROKEN) != 0) {
                statusString = "broken";
            } else if ((status & STATE_IS_SECURE) != 0) {
                statusString = "secure";
            } else if ((status & STATE_IS_INSECURE) != 0) {
                statusString = "insecure";
            } else {
                statusString = "unknown";
            }
            Log.i(LOGTAG, "Security status changed to " + statusString);
        }
    }

    private class ScrollListener implements GeckoView.ScrollListener {
        public int mScrollY;

        private SwipeRefreshLayout mLayout;

        public ScrollListener(SwipeRefreshLayout layout) {
            mLayout = layout;
            mLayout.setOnRefreshListener(new RefreshListener());
        }

        /**
        * @param view The GeckoView that initiated the callback.
        */
        public void onScrollChanged(GeckoView view, int scrollX, int scrollY) {
            Log.d(LOGTAG, "onScrollChanged x" + scrollX + ", y=" + scrollY);
            mScrollY = scrollY;

            mLayout.setEnabled(mScrollY < 1);
        }

        private class RefreshListener implements SwipeRefreshLayout.OnRefreshListener {
            @Override
            public void onRefresh() {
                GeckoViewActivity.this.mGeckoView.reload();

                final Handler handler = new Handler();
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        ScrollListener.this.mLayout.setRefreshing(false);
                    }
                }, 1500);
            }
        }
    }

    private class Navigation implements GeckoView.NavigationListener {
        private Activity mActivity;
        private EditText mSearchBox;
        private boolean mCanGoBack;

        Navigation(Activity activity, EditText searchBox) {
            this.mActivity = activity;
            this.mSearchBox = searchBox;
        }

        @Override
        public void onLocationChange(GeckoView view, final String url) {
            Log.d(LOGTAG, "onLocationChange " + url);
            this.mSearchBox.setText(url);
        }

        @Override
        public void onCanGoBack(GeckoView view, boolean canGoBack) {
            Log.d(LOGTAG, "onCanGoBack " + canGoBack);
            mCanGoBack = canGoBack;
        }

        @Override
        public void onCanGoForward(GeckoView view, boolean value) {
            Log.d(LOGTAG, "onCanGoForward " + value);
        }

        public boolean canGoBack() {
            return mCanGoBack;
        }
    }
}
