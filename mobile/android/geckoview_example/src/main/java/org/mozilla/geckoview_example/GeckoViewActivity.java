/* -*- Mode: Java; c-basic-offset: 4; tab-width: 20; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.geckoview_example;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.BasicSelectionActionDelegate;
import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebRequestError;

import android.Manifest;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.ProgressBar;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Locale;
import java.util.ArrayList;

public class GeckoViewActivity extends AppCompatActivity {
    private static final String LOGTAG = "GeckoViewActivity";
    private static final String DEFAULT_URL = "about:blank";
    private static final String USE_MULTIPROCESS_EXTRA = "use_multiprocess";
    private static final String FULL_ACCESSIBILITY_TREE_EXTRA = "full_accessibility_tree";
    private static final String SEARCH_URI_BASE = "https://www.google.com/search?q=";
    private static final String ACTION_SHUTDOWN = "org.mozilla.geckoview_example.SHUTDOWN";
    private static final int REQUEST_FILE_PICKER = 1;
    private static final int REQUEST_PERMISSIONS = 2;
    private static final int REQUEST_WRITE_EXTERNAL_STORAGE = 3;

    private static GeckoRuntime sGeckoRuntime;
    private GeckoSession mGeckoSession;
    private GeckoView mGeckoView;
    private boolean mUseMultiprocess;
    private boolean mFullAccessibilityTree;
    private boolean mUseTrackingProtection;
    private boolean mUsePrivateBrowsing;
    private boolean mKillProcessOnDestroy;

    private LocationView mLocationView;
    private String mCurrentUri;
    private boolean mCanGoBack;
    private boolean mCanGoForward;
    private boolean mFullScreen;

    private ProgressBar mProgressView;

    private LinkedList<GeckoSession.WebResponseInfo> mPendingDownloads = new LinkedList<>();

    private LocationView.CommitListener mCommitListener = new LocationView.CommitListener() {
        @Override
        public void onCommit(String text) {
            if ((text.contains(".") || text.contains(":")) && !text.contains(" ")) {
                mGeckoSession.loadUri(text);
            } else {
                mGeckoSession.loadUri(SEARCH_URI_BASE + text);
            }
            mGeckoView.requestFocus();
        }
    };

    public static final String[] types = new String[]{
      "none", "at_ad", "at_analytic", "at_social", "at_content",
      "at_test", "ad", "other"};

    public static int blockedIdx(int cat) {
        switch (cat) {
            case ContentBlocking.NONE:
              return 0;
            case ContentBlocking.AT_AD:
              return 1;
            case ContentBlocking.AT_ANALYTIC:
              return 2;
            case ContentBlocking.AT_SOCIAL:
              return 3;
            case ContentBlocking.AT_CONTENT:
              return 4;
            case ContentBlocking.AT_TEST:
              return 5;
            case ContentBlocking.AD_ALL:
              return 6;
            default:
              return 7;
        }
    }

    private final class Benchmark {
        class Entry {
            public String uri;
            public long startTime = 0;
            public long endTime = 0;
            public int aborted = 0;
            public int[] blocked = new int[]{0, 0, 0, 0, 0, 0, 0, 0};

            public Entry(final String uri) {
                this.uri = uri;
            }

            public boolean onBlock(final String uri, int cat) {
                if (startTime == 0 || !uri.contains(this.uri)) {
                    return false;
                }

                if ((cat & ContentBlocking.NONE) != 0) {
                    blocked[0]++;
                }
                if ((cat & ContentBlocking.AT_AD) != 0) {
                    blocked[1]++;
                }
                if ((cat & ContentBlocking.AT_ANALYTIC) != 0) {
                    blocked[2]++;
                }
                if ((cat & ContentBlocking.AT_SOCIAL) != 0) {
                    blocked[3]++;
                }
                if ((cat & ContentBlocking.AT_CONTENT) != 0) {
                    blocked[4]++;
                }
                if ((cat & ContentBlocking.AT_TEST) != 0) {
                    blocked[5]++;
                }
                if ((cat & ContentBlocking.AD_ALL) != 0) {
                    blocked[6]++;
                }
                return true;
            }

            public boolean onStart(final String uri) {
                if (startTime != 0 || !uri.contains(this.uri)) {
                    return false;
                }
                aborted = 0;
                startTime = SystemClock.elapsedRealtime();
                return true;
            }

            public boolean onStop(final String uri) {
                if (startTime == 0 || !uri.contains(this.uri)) {
                    return false;
                }
                endTime = SystemClock.elapsedRealtime();
                return true;
            }

            public boolean onAbort() {
                startTime = 0;
                endTime = 0;
                aborted = 1;
                return true;
            }

            @Override
            public String toString() {
                StringBuilder b = new StringBuilder();
                b.append("entry").append("\t")
                 .append(sGeckoRuntime.getSettings()
                         .getContentBlocking().getCategories())
                 .append("\t")
                 .append(aborted).append("\t")
                 .append(uri).append("\t")
                 // .append(startTime).append("\t")
                 // .append(endTime).append("\t")
                 .append(endTime - startTime).append("\t")
                 .append(Arrays.toString(blocked)).append("\n");
                return b.toString();
            }
        }

        final ArrayList<Entry> entries = new ArrayList<Entry>();
        int cur = 0;
        boolean running = false;
        Thread poll;

        public void test(int runs, final String[] list) {
            Log.d("rabbitdebug", "test");
            running = true;

            poll = new Thread(new Runnable() {
                @Override
                public void run() {
                    int lastRetry = -1;
                    int lastIdx = -1;
                    Log.d("rabbitdebug", "poll run");
                    while (running) {
                        try {
                            Log.d("rabbitdebug", "poll sleep");
                            Thread.sleep(20000);
                        } catch (Exception e) {
                            Log.d("rabbitdebug", "poll ex", e);
                        }
                        Log.d("rabbitdebug", "poll " + lastIdx + " " + cur);
                        if (lastIdx == cur) {
                            entries.get(cur).onAbort();
                            if (lastRetry == cur) {
                                next(cur+1);
                            } else {
                                next(cur);
                                lastRetry = cur;
                            }
                        }
                        lastIdx = cur;
                    }
                }
            });
            // poll.start();

            while (runs-- > 0) {
                for (final String uri: list) {
                    entries.add(new Entry(uri));
                }
            }
            next(0);
        }

        public void reset() {
            entries.clear();
        }

        public void next(int idx) {
            Log.d("rabbitdebug", "next " + idx);

            if (!running) {
                return;
            }

            final Entry prev = entries.get(cur);
            if (prev.endTime == 0) {
                prev.onAbort();
            }
            cur = idx;
            if (cur >= entries.size()) {
                finish();
                return;
            }
            final Entry next = entries.get(cur);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    GeckoViewActivity.this.mGeckoSession.loadUri(next.uri);
                    GeckoViewActivity.this.mGeckoView.requestFocus();
                }
            });
        }

        public void onStart(final String uri) {
            Log.d("rabbitdebug", "onStart " + uri);
            if (!running) {
                return;
            }
            Log.d("rabbitbench", "start\t" + uri + "\t" + SystemClock.elapsedRealtime());

            entries.get(cur).onStart(uri);
        }

        public void onRedirect(final String uri) {
            Log.d("rabbitdebug", "onRedirect " + uri);
            if (!running) {
                return;
            }

            // if (uri.contains(entries.get(cur).uri)) {
                // entries.get(cur).onAbort();
            // } else if (cur > 0 && uri.contains(entries.get(cur-1).uri)) {
                // entries.get(cur).onAbort();
                // entries.get(cur-1).onStart(uri);
            // } else {
                // entries.get(cur).onStart(uri);
            // }
        }

        public void onStop(final String uri) {
            Log.d("rabbitdebug", "onStop " + uri);
            if (!running) {
                return;
            }

            Log.d("rabbitbench", "stop\t" + uri + "\t" + SystemClock.elapsedRealtime());
            if (entries.get(cur).onStop(uri)) {
                Log.d("rabbitbench", entries.get(cur).toString());
                next(cur + 1);
            }
        }

        public void onBlock(final String uri, int cat) {
            Log.d("rabbitdebug", "onBlock " + uri + " " + cat);
            if (!running) {
                return;
            }
            Log.d("rabbitbench", "block\t" + uri + "\t" + cat);

            // entries.get(cur).onBlock(uri, cat);
        }

        public void finish() {
            Log.d("rabbitdebug", "finish");
            if (!running) {
                return;
            }

            running = false;
            cur = 0;
            for (final Entry e: entries) {
              // Log.d("rabbitdebug", e.toString());
            }
        }
    }

    final Benchmark bench = new Benchmark();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(LOGTAG, "zerdatime " + SystemClock.elapsedRealtime() +
              " - application start");

        setContentView(R.layout.geckoview_activity);
        mGeckoView = (GeckoView) findViewById(R.id.gecko_view);

        setSupportActionBar((Toolbar)findViewById(R.id.toolbar));

        mLocationView = new LocationView(this);
        mLocationView.setId(R.id.url_bar);
        getSupportActionBar().setCustomView(mLocationView,
                new ActionBar.LayoutParams(ActionBar.LayoutParams.MATCH_PARENT,
                        ActionBar.LayoutParams.WRAP_CONTENT));
        getSupportActionBar().setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM);

        mUseMultiprocess = getIntent().getBooleanExtra(USE_MULTIPROCESS_EXTRA, true);
        mFullAccessibilityTree = getIntent().getBooleanExtra(FULL_ACCESSIBILITY_TREE_EXTRA, false);
        mProgressView = (ProgressBar) findViewById(R.id.page_progress);

        if (sGeckoRuntime == null) {
            final GeckoRuntimeSettings.Builder runtimeSettingsBuilder =
                new GeckoRuntimeSettings.Builder();

            if (BuildConfig.DEBUG) {
                // In debug builds, we want to load JavaScript resources fresh with
                // each build.
                runtimeSettingsBuilder.arguments(new String[] { "-purgecaches" });
            }

            final Bundle extras = getIntent().getExtras();
            if (extras != null) {
                runtimeSettingsBuilder.extras(extras);
            }
            runtimeSettingsBuilder
                    .useContentProcessHint(mUseMultiprocess)
                    .remoteDebuggingEnabled(true)
                    .consoleOutput(true)
                    .crashHandler(ExampleCrashHandler.class);

            sGeckoRuntime = GeckoRuntime.create(this, runtimeSettingsBuilder.build());
        }

        if(savedInstanceState == null) {
            mGeckoSession = (GeckoSession)getIntent().getParcelableExtra("session");
            if (mGeckoSession != null) {
                connectSession(mGeckoSession);

                if (!mGeckoSession.isOpen()) {
                    mGeckoSession.open(sGeckoRuntime);
                }

                mUseMultiprocess = mGeckoSession.getSettings().getUseMultiprocess();
                mFullAccessibilityTree = mGeckoSession.getSettings().getFullAccessibilityTree();

                mGeckoView.setSession(mGeckoSession);
            } else {
                mGeckoSession = createSession();
                mGeckoView.setSession(mGeckoSession, sGeckoRuntime);

                loadFromIntent(getIntent());
            }
        }

        mLocationView.setCommitListener(mCommitListener);


        sGeckoRuntime.getSettings().getContentBlocking().setCategories(
            // ContentBlocking.NONE);
            // ContentBlocking.AT_ALL);
            // ContentBlocking.AD_ALL);
            ContentBlocking.AT_ALL | ContentBlocking.AD_ALL);
    }

    private GeckoSession createSession() {
        GeckoSession session = new GeckoSession(new GeckoSessionSettings.Builder()
                .useMultiprocess(mUseMultiprocess)
                .usePrivateMode(mUsePrivateBrowsing)
                .useTrackingProtection(true)
                .fullAccessibilityTree(mFullAccessibilityTree)
                .build());

        connectSession(session);

        return session;
    }

    private void connectSession(GeckoSession session) {
        session.setContentDelegate(new ExampleContentDelegate());
        session.setHistoryDelegate(new ExampleHistoryDelegate());
        final ExampleContentBlockingDelegate cb = new ExampleContentBlockingDelegate();
        session.setContentBlockingDelegate(cb);
        session.setProgressDelegate(new ExampleProgressDelegate(cb));
        session.setNavigationDelegate(new ExampleNavigationDelegate());

        final BasicGeckoViewPrompt prompt = new BasicGeckoViewPrompt(this);
        prompt.filePickerRequestCode = REQUEST_FILE_PICKER;
        session.setPromptDelegate(prompt);

        final ExamplePermissionDelegate permission = new ExamplePermissionDelegate();
        permission.androidPermissionRequestCode = REQUEST_PERMISSIONS;
        session.setPermissionDelegate(permission);

        session.setSelectionActionDelegate(new BasicSelectionActionDelegate(this));

        updateTrackingProtection(session);
    }

    private void recreateSession() {
        if(mGeckoSession != null) {
            mGeckoSession.close();
        }

        mGeckoSession = createSession();
        mGeckoSession.open(sGeckoRuntime);
        mGeckoView.setSession(mGeckoSession);
        mGeckoSession.loadUri(mCurrentUri != null ? mCurrentUri : DEFAULT_URL);
    }

    @Override
    public void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        if(savedInstanceState != null) {
            mGeckoSession = mGeckoView.getSession();
        } else {
            recreateSession();
        }
    }

    private void updateTrackingProtection(GeckoSession session) {
        session.getSettings().setUseTrackingProtection(true);
    }

    @Override
    public void onBackPressed() {
        if (mFullScreen) {
            mGeckoSession.exitFullScreen();
            return;
        }

        if (mCanGoBack && mGeckoSession != null) {
            mGeckoSession.goBack();
            return;
        }

        super.onBackPressed();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.actions, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.action_e10s).setChecked(mUseMultiprocess);
        menu.findItem(R.id.action_tp).setChecked(mUseTrackingProtection);
        menu.findItem(R.id.action_pb).setChecked(mUsePrivateBrowsing);
        menu.findItem(R.id.action_forward).setEnabled(mCanGoForward);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_reload:
                mGeckoSession.reload();
                break;
            case R.id.action_forward:
                mGeckoSession.goForward();
                break;
            case R.id.action_e10s:
                mUseMultiprocess = !mUseMultiprocess;
                recreateSession();
                break;
            case R.id.action_tp:
                mUseTrackingProtection = !mUseTrackingProtection;
                updateTrackingProtection(mGeckoSession);
                mGeckoSession.reload();
                break;
            case R.id.action_pb:
                mUsePrivateBrowsing = !mUsePrivateBrowsing;
                recreateSession();
                break;
            default:
                return super.onOptionsItemSelected(item);
        }

        return true;
    }

    @Override
    public void onDestroy() {
        if (mKillProcessOnDestroy) {
            android.os.Process.killProcess(android.os.Process.myPid());
        }

        super.onDestroy();
    }

    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);

        if (ACTION_SHUTDOWN.equals(intent.getAction())) {
            mKillProcessOnDestroy = true;
            if (sGeckoRuntime != null) {
                sGeckoRuntime.shutdown();
            }
            finish();
            return;
        }

        setIntent(intent);

        if (intent.getData() != null) {
            loadFromIntent(intent);
        }
    }


    private void loadFromIntent(final Intent intent) {
        final Uri uri = intent.getData();
        Log.d("rabbitdebug", "loadFromIntent " + uri);
        if (uri == null || !uri.toString().equals("benchmark")) {
            mGeckoSession.loadUri(uri != null ? uri.toString() : DEFAULT_URL);
            return;
        }
        int numRuns = 5;
        final String[] list = new String[]{
            // "about:blank",
"google.com",
"youtube.com",
"facebook.com",
"baidu.com",
"wikipedia.org",
"qq.com",
"yahoo.com",
"tmall.com",
"taobao.com",
"amazon.com",
"twitter.com",
"sohu.com",
"jd.com",
"live.com",
"weibo.com",
"instagram.com",
"sina.com.cn",
"login.tmall.com",
"360.cn",
"reddit.com",
"linkedin.com",
"blogspot.com",
"netflix.com",
"yandex.ru",
"microsoftonline.com",
"twitch.tv",
"mail.ru",
"yahoo.co.jp",
"porn555.com",
"pornhub.com",
"csdn.net",
"vk.com",
"pages.tmall.com",
"google.co.in",
"microsoft.com",
"t.co",
"aliexpress.com",
"google.com.hk",
"bilibili.com",
"stackoverflow.com",
"ebay.com",
"bing.com",
"github.com",
"naver.com",
"livejasmin.com",
"tribunnews.com",
"alipay.com",
"amazon.co.jp",
"163.com",
"office.com",
"msn.com",
"xvideos.com",
"imdb.com",
"wordpress.com",
"whatsapp.com",
"zhihu.com",
"paypal.com",
"googleusercontent.com",
"google.co.jp",
"apple.com",
"xhamster.com",
"imgur.com",
"google.ru",
"google.de",
"xinhuanet.com",
"adobe.com",
"mozilla.org",
"google.com.br",
"pinterest.com",
"google.fr",
"amazon.de",
"fbcdn.net",
"fandom.com",
"dropbox.com",
"amazon.in",
"tumblr.com",
"google.it",
"speakol.com",
"quora.com",
"amazonaws.com",
"exosrv.com",
"instructure.com",
"popads.net",
"amazon.co.uk",
"douyu.com",
"tianya.cn",
"douban.com",
"rakuten.co.jp",
"salesforce.com",
"onlinesbi.com",
"aparat.com",
"cnblogs.com",
"hao123.com",
"bbc.co.uk",
"detail.tmall.com",
"thestartmagazine.com",
"cnn.com",
"bodelen.com",
"google.co.uk",
"google.cn",
"bbc.com",
"detik.com",
"pixnet.net",
"force.com",
"booking.com",
"google.es",
"nicovideo.jp",
"stackexchange.com",
"ettoday.net",
"xnxx.com",
"soundcloud.com",
"bukalapak.com",
"otvfoco.com.br",
"google.com.mx",
"panda.tv",
"weather.com",
"spotify.com",
"sogou.com",
"roblox.com",
"nytimes.com",
"espn.com",
"nih.gov",
"aliyun.com",
"bongacams.com",
"zhanqi.tv",
"youku.com",
"craigslist.org",
"indeed.com",
"researchgate.net",
"ask.com",
"vimeo.com",
"avito.ru",
"gmw.cn",
"ifeng.com",
"softonic.com",
"ok.ru",
"chase.com",
"china.com.cn",
"w3schools.com",
"theguardian.com",
"google.com.tr",
"globo.com",
"caijing.com.cn",
"discordapp.com",
"daum.net",
"eastday.com",
"intuit.com",
"google.com.tw",
"ebay.de",
"iqiyi.com",
"openload.co",
"dailymotion.com",
"fc2.com",
"mercadolivre.com.br",
"mediafire.com",
"49oa3o49b6.com",
"txxx.com",
"thouth.net",
"ebay.co.uk",
"slideshare.net",
"45eijvhgj2.com",
"etsy.com",
"godaddy.com",
"wetransfer.com",
"uol.com.br",
"google.ca",
"babytree.com",
"steamcommunity.com",
"amazon.it",
"vice.com",
"soso.com",
"tokopedia.com",
"canva.com",
"youdao.com",
"shutterstock.com",
"chaturbate.com",
"indiatimes.com",
"youm7.com",
"gurabinhetot.info",
"jianshu.com",
"flipkart.com",
"so.com",
"amazon.fr",
"freepik.com",
"bet9ja.com",
"trello.com",
"spankbang.com",
"cobalten.com",
"blogger.com",
"slack.com",
"cnet.com",
"ci123.com",
"digikala.com",
"google.pl",
"alibaba.com",
"okezone.com",
"1688.com",
"deviantart.com",
"quizlet.com",
"scribd.com",
"wikihow.com",
"google.com.sa",
"twimg.com",
"zillow.com",
"rednet.cn",
"gearbest.com",
"sciencedirect.com",
"huanqiu.com",
"steampowered.com",
"medium.com",
"yy.com",
"a63t9o1azf.com",
"abs-cbn.com",
"google.co.id",
"dailymail.co.uk",
"google.co.kr",
"google.co.th",
"walmart.com",
"yts.am",
"hulu.com",
"google.com.eg",
"kompas.com",
"messenger.com",
"google.com.ar",
"liputan6.com",
"myshopify.com",
"bankofamerica.com",
"ladbible.com",
"yelp.com",
"momoshop.com.tw",
"udemy.com",
"amazon.es",
"sindonews.com",
"gamersky.com",
"duckduckgo.com",
"google.com.au",
"savefrom.net",
"office365.com",
"livejournal.com",
"foxnews.com",
"mega.nz",
"zoom.us",
"onlinevideoconverter.com",
"rambler.ru",
"gamepedia.com",
"speedtest.net",
"youporn.com",
"wellsfargo.com",
"ebay-kleinanzeigen.de",
"accuweather.com",
"alwafd.news",
"shopify.com",
"archive.org",
"hintonsfeetred.info",
"doubleclick.net",
"smzdm.com",
"forbes.com",
"amazon.cn",
"rly-rect-appn.in",
"mixturehopeful.com",
"amazon.ca",
"kinopoisk.ru",
"cdstm.cn",
"kakaku.com",
"cloudfront.net",
"breitbart.com",
"hupu.com",
"varzesh3.com",
"zendesk.com",
"allegro.pl",
"k618.cn",
"redd.it",
"reverso.net",
"gfycat.com",
"usps.com",
"pinimg.com",
"rt.com",
"zoho.com",
"mailchimp.com",
"washingtonpost.com",
"tistory.com",
"autohome.com.cn",
"livedoor.com",
"line.me",
"ikea.com",
"wikimedia.org",
"okta.com",
"namnak.com",
"weebly.com",
"airbnb.com",
"zol.com.cn",
"hp.com",
"box.com",
"thesaurus.com",
"chouftv.ma",
"thepiratebay.org",
"tripadvisor.com",
"chinadaily.com.cn",
"dmm.co.jp",
"sarkariresult.com",
"dkn.tv",
"wordreference.com",
"hubspot.com",
"genius.com",
"1337x.to",
"bet365.com",
"wordpress.org",
"jf71qh5v14.com",
"jrj.com.cn",
"glassdoor.com",
"bestbuy.com",
"cricbuzz.com",
"primevideo.com",
"google.com.ua",
"rutracker.org",
"oracle.com",
"17ok.com",
"patch.com",
"ltn.com.tw",
"mercadolibre.com.ar",
"wix.com",
"behance.net",
"taboola.com",
"patria.org.ve",
"orange.fr",
"okdiario.com",
"atlassian.net",
"buzzfeed.com",
"doublepimp.com",
"livedoor.jp",
"crunchyroll.com",
"hotstar.com",
"xfinity.com",
"sex.com",
"gamespot.com",
"pixabay.com",
"googlevideo.com",
"ebc.net.tw",
"pixiv.net",
"patreon.com",
"academia.edu",
"ign.com",
"goodreads.com",
"fedex.com",
"kaskus.co.id",
"redtube.com",
"bp.blogspot.com",
"ameblo.jp",
"fiverr.com",
"sourceforge.net",
"ouo.io",
"asos.com",
"kooora.com",
"manoramaonline.com",
"op.gg",
"ensonhaber.com",
"aol.com",
"egy.best",
"skype.com",
"huya.com",
"hola.com",
"uptodown.com",
"ups.com",
"feedly.com",
"dell.com",
"flickr.com",
"list.tmall.com",
"google.gr",
"cambridge.org",
"gosuslugi.ru",
"cdninstagram.com",
"hdfcbank.com",
"mercadolibre.com.mx",
"americanexpress.com",
"myway.com",
"webex.com",
"zippyshare.com",
"healthline.com",
"grammarly.com",
"nga.cn",
"news-speaker.com",
"ctrip.com",
"exoclick.com",
"setn.com",
"playstation.com",
"google.com.pk",
"telegram.org",
"wunderground.com",
"baike.com",
"namu.wiki",
"homedepot.com",
"themeforest.net",
"evernote.com",
"upwork.com",
"businessinsider.com",
"google.co.ve",
"outbrain.com",
"crptentry.com",
"wease.im",
"chinaz.com",
"bitly.com",
"idntimes.com",
"cnbeta.com",
"v2ex.com",
"rectanthenwirit.info",
"yao.tmall.com",
"samsung.com",
"huffingtonpost.com",
"gismeteo.ru",
"hclips.com",
"files.wordpress.com",
"siteadvisor.com",
"51sole.com",
"marca.com",
"capitalone.com",
"blackboard.com",
"rarbg.to",
"espncricinfo.com",
"wiktionary.org",
"pikabu.ru",
"shaparak.ir",
"adp.com",
"google.co.za",
"icloud.com",
"zhibo8.cc",
"olx.ua",
"12306.cn",
"onet.pl",
"slickdeals.net",
"infourok.ru",
"toutiao.com",
"elpais.com",
"userapi.com",
"friv.com",
"gmx.net",
"ilovepdf.com",
"hespress.com",
"free.fr",
"leboncoin.fr",
"nur.kz",
"dianping.com",
"bancodevenezuela.com",
"ria.ru",
"springer.com",
"divar.ir",
"ouedkniss.com",
"elsevier.com",
"ndtv.com",
"investing.com",
"mi.com",
"uidai.gov.in",
"tutorialspoint.com",
"getawesome1.com",
"inquirer.net",
"seasonvar.ru",
"hellomagazine.com",
"irs.gov",
"nyetm2mkch.com",
"taleo.net",
"3dmgame.com",
"yandex.kz",
"olx.pl",
"irctc.co.in",
"google.com.sg",
"grid.id",
"dytt8.net",
"webmd.com",
"sahibinden.com",
"doublepimpssl.com",
"wp.pl",
"kumparan.com",
"discogs.com",
"banggood.com",
"geeksforgeeks.org",
"theverge.com",
"google.ro",
"termometropolitico.it",
"goo.ne.jp",
"mit.edu",
"9gag.com",
"cnbc.com",
"usatoday.com",
"google.com.vn",
"wiley.com",
"kissanime.ru",
"myanimelist.net",
"oschina.net",
"dcinside.com",
"rediff.com",
"ebay.it",
"360doc.com",
"souq.com",
"livescore.com",
"indoxxi.bz",
"tencent.com",
"wixsite.com",
"go.com",
"freejobalert.com",
"bloomberg.com",
"coco02.net",
"digitaldsp.com",
"pexels.com",
"smallpdf.com",
"unsplash.com",
"artstation.com",
"icicibank.com",
"biobiochile.cl",
"google.cl",
"ibm.com"

        };

        final String[] list2 = new String[]{
          "me73.com",
          "mozilla.github.io/tracking-test/full.html"
        };

        new Thread(new Runnable() {
            @Override
            public void run() {
                // bench.test(numRuns, list);

                for (final String uri: list) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            GeckoViewActivity.this.mGeckoSession.stop();
                            GeckoViewActivity.this.mGeckoSession.loadUri(uri);
                            GeckoViewActivity.this.mGeckoView.requestFocus();
                        }
                    });
                    try {
                        Thread.sleep(20000);
                    } catch (Exception e) {
                    }
                }
            }
        }).start();
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode,
                                    final Intent data) {
        if (requestCode == REQUEST_FILE_PICKER) {
            final BasicGeckoViewPrompt prompt = (BasicGeckoViewPrompt)
                    mGeckoSession.getPromptDelegate();
            prompt.onFileCallbackResult(resultCode, data);
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onRequestPermissionsResult(final int requestCode,
                                           final String[] permissions,
                                           final int[] grantResults) {
        if (requestCode == REQUEST_PERMISSIONS) {
            final ExamplePermissionDelegate permission = (ExamplePermissionDelegate)
                    mGeckoSession.getPermissionDelegate();
            permission.onRequestPermissionsResult(permissions, grantResults);
        } else if (requestCode == REQUEST_WRITE_EXTERNAL_STORAGE &&
                   grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            continueDownloads();
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    private void continueDownloads() {
        LinkedList<GeckoSession.WebResponseInfo> downloads = mPendingDownloads;
        mPendingDownloads = new LinkedList<>();

        for (GeckoSession.WebResponseInfo response : downloads) {
            downloadFile(response);
        }
    }

    private void downloadFile(GeckoSession.WebResponseInfo response) {
        mGeckoSession
                .getUserAgent()
                .then(new GeckoResult.OnValueListener<String, Void>() {
            @Override
            public GeckoResult<Void> onValue(String userAgent) throws Throwable {
                downloadFile(response, userAgent);
                return null;
            }
        }, new GeckoResult.OnExceptionListener<Void>() {
            @Override
            public GeckoResult<Void> onException(Throwable exception) throws Throwable {
                // getUserAgent() cannot fail.
                throw new IllegalStateException("Could not get UserAgent string.");
            }
        });
    }

    private void downloadFile(GeckoSession.WebResponseInfo response, String userAgent) {
        if (ContextCompat.checkSelfPermission(GeckoViewActivity.this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            mPendingDownloads.add(response);
            ActivityCompat.requestPermissions(GeckoViewActivity.this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_WRITE_EXTERNAL_STORAGE);
            return;
        }

        final Uri uri = Uri.parse(response.uri);
        final String filename = response.filename != null ? response.filename : uri.getLastPathSegment();

        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        DownloadManager.Request req = new DownloadManager.Request(uri);
        req.setMimeType(response.contentType);
        req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
        req.addRequestHeader("User-Agent", userAgent);
        manager.enqueue(req);
    }

    private String mErrorTemplate;
    private String createErrorPage(final String error) {
        if (mErrorTemplate == null) {
            InputStream stream = null;
            BufferedReader reader = null;
            StringBuilder builder = new StringBuilder();
            try {
                stream = getResources().getAssets().open("error.html");
                reader = new BufferedReader(new InputStreamReader(stream));

                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line);
                    builder.append("\n");
                }

                mErrorTemplate = builder.toString();
            } catch (IOException e) {
                Log.d(LOGTAG, "Failed to open error page template", e);
                return null;
            } finally {
                if (stream != null) {
                    try {
                        stream.close();
                    } catch (IOException e) {
                        Log.e(LOGTAG, "Failed to close error page template stream", e);
                    }
                }

                if (reader != null) {
                    try {
                        reader.close();
                    } catch (IOException e) {
                        Log.e(LOGTAG, "Failed to close error page template reader", e);
                    }
                }
            }
        }

        return mErrorTemplate.replace("$ERROR", error);
    }

    private class ExampleHistoryDelegate implements GeckoSession.HistoryDelegate {
        private final HashSet<String> mVisitedURLs;

        private ExampleHistoryDelegate() {
            mVisitedURLs = new HashSet<String>();
        }

        @Override
        public GeckoResult<Boolean> onVisited(GeckoSession session, String url,
                                              String lastVisitedURL, int flags) {
            Log.i(LOGTAG, "Visited URL: " + url);

            mVisitedURLs.add(url);
            return GeckoResult.fromValue(true);
        }

        @Override
        public GeckoResult<boolean[]> getVisited(GeckoSession session, String[] urls) {
            boolean[] visited = new boolean[urls.length];
            for (int i = 0; i < urls.length; i++) {
                visited[i] = mVisitedURLs.contains(urls[i]);
            }
            return GeckoResult.fromValue(visited);
        }
    }

    private class ExampleContentDelegate implements GeckoSession.ContentDelegate {
        @Override
        public void onTitleChange(GeckoSession session, String title) {
            Log.i(LOGTAG, "Content title changed to " + title);
        }

        @Override
        public void onFullScreen(final GeckoSession session, final boolean fullScreen) {
            getWindow().setFlags(fullScreen ? WindowManager.LayoutParams.FLAG_FULLSCREEN : 0,
                                 WindowManager.LayoutParams.FLAG_FULLSCREEN);
            mFullScreen = fullScreen;
            if (fullScreen) {
                getSupportActionBar().hide();
            } else {
                getSupportActionBar().show();
            }
        }

        @Override
        public void onFocusRequest(final GeckoSession session) {
            Log.i(LOGTAG, "Content requesting focus");
        }

        @Override
        public void onCloseRequest(final GeckoSession session) {
            if (session == mGeckoSession) {
                finish();
            }
        }

        @Override
        public void onContextMenu(final GeckoSession session,
                                  int screenX, int screenY,
                                  final ContextElement element) {
            Log.d(LOGTAG, "onContextMenu screenX=" + screenX +
                          " screenY=" + screenY +
                          " type=" + element.type +
                          " linkUri=" + element.linkUri +
                          " title=" + element.title +
                          " alt=" + element.altText +
                          " srcUri=" + element.srcUri);
        }

        @Override
        public void onExternalResponse(GeckoSession session, GeckoSession.WebResponseInfo response) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndTypeAndNormalize(Uri.parse(response.uri), response.contentType);
                startActivity(intent);
            } catch (ActivityNotFoundException e) {
                downloadFile(response);
            }
        }

        @Override
        public void onCrash(GeckoSession session) {
            Log.e(LOGTAG, "Crashed, reopening session");
            session.open(sGeckoRuntime);
            session.loadUri(DEFAULT_URL);
        }

        @Override
        public void onFirstComposite(final GeckoSession session) {
            Log.d(LOGTAG, "onFirstComposite");
        }
    }

    private class ExampleProgressDelegate implements GeckoSession.ProgressDelegate {
        private ExampleContentBlockingDelegate mCb;

        private ExampleProgressDelegate(final ExampleContentBlockingDelegate cb) {
            mCb = cb;
        }

        @Override
        public void onPageStart(GeckoSession session, String url) {
            Log.i(LOGTAG, "Starting to load page at " + url);
            Log.i(LOGTAG, "zerdatime " + SystemClock.elapsedRealtime() +
                  " - page load start");
            mCb.clearCounters();
            Log.d("rabbitbench", "start\t" + url + "\t" +
                  sGeckoRuntime.getSettings().getContentBlocking().getCategories() + "\t" +
                  SystemClock.elapsedRealtime());
            new Thread(new Runnable() {
                @Override
                public void run() {
                    // GeckoViewActivity.this.bench.onStart(url);
                }
            }).start();
        }

        @Override
        public void onPageStop(GeckoSession session, boolean success) {
            Log.i(LOGTAG, "Stopping page load " + (success ? "successfully" : "unsuccessfully"));
            Log.i(LOGTAG, "zerdatime " + SystemClock.elapsedRealtime() +
                  " - page load stop");
            mCb.logCounters();
            if (success) {
                Log.d("rabbitbench", "stop\t" + mCurrentUri + "\t" +
                  sGeckoRuntime.getSettings().getContentBlocking().getCategories() + "\t" +
                    SystemClock.elapsedRealtime());
            }
            if (success) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        // GeckoViewActivity.this.bench.onStop(mCurrentUri);
                    }
                }).start();
            }
        }

        @Override
        public void onProgressChange(GeckoSession session, int progress) {
            Log.i(LOGTAG, "onProgressChange " + progress);

            mProgressView.setProgress(progress);

            if (progress > 0 && progress < 100) {
                mProgressView.setVisibility(View.VISIBLE);
            } else {
                mProgressView.setVisibility(View.GONE);
            }
        }

        @Override
        public void onSecurityChange(GeckoSession session, SecurityInformation securityInfo) {
            Log.i(LOGTAG, "Security status changed to " + securityInfo.securityMode);
        }
    }

    private class ExamplePermissionDelegate implements GeckoSession.PermissionDelegate {

        public int androidPermissionRequestCode = 1;
        private Callback mCallback;

        public void onRequestPermissionsResult(final String[] permissions,
                                               final int[] grantResults) {
            if (mCallback == null) {
                return;
            }

            final Callback cb = mCallback;
            mCallback = null;
            for (final int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    // At least one permission was not granted.
                    cb.reject();
                    return;
                }
            }
            cb.grant();
        }

        @Override
        public void onAndroidPermissionsRequest(final GeckoSession session, final String[] permissions,
                                              final Callback callback) {
            if (Build.VERSION.SDK_INT >= 23) {
                // requestPermissions was introduced in API 23.
                mCallback = callback;
                requestPermissions(permissions, androidPermissionRequestCode);
            } else {
                callback.grant();
            }
        }

        @Override
        public void onContentPermissionRequest(final GeckoSession session, final String uri,
                                             final int type, final Callback callback) {
            final int resId;
            if (PERMISSION_GEOLOCATION == type) {
                resId = R.string.request_geolocation;
            } else if (PERMISSION_DESKTOP_NOTIFICATION == type) {
                resId = R.string.request_notification;
            } else if (PERMISSION_AUTOPLAY_MEDIA == type) {
                resId = R.string.request_autoplay;
            } else {
                Log.w(LOGTAG, "Unknown permission: " + type);
                callback.reject();
                return;
            }

            final String title = getString(resId, Uri.parse(uri).getAuthority());
            final BasicGeckoViewPrompt prompt = (BasicGeckoViewPrompt)
                    mGeckoSession.getPromptDelegate();
            prompt.onPermissionPrompt(session, title, callback);
        }

        private String[] normalizeMediaName(final MediaSource[] sources) {
            if (sources == null) {
                return null;
            }

            String[] res = new String[sources.length];
            for (int i = 0; i < sources.length; i++) {
                final int mediaSource = sources[i].source;
                final String name = sources[i].name;
                if (MediaSource.SOURCE_CAMERA == mediaSource) {
                    if (name.toLowerCase(Locale.ENGLISH).contains("front")) {
                        res[i] = getString(R.string.media_front_camera);
                    } else {
                        res[i] = getString(R.string.media_back_camera);
                    }
                } else if (!name.isEmpty()) {
                    res[i] = name;
                } else if (MediaSource.SOURCE_MICROPHONE == mediaSource) {
                    res[i] = getString(R.string.media_microphone);
                } else {
                    res[i] = getString(R.string.media_other);
                }
            }

            return res;
        }

        @Override
        public void onMediaPermissionRequest(final GeckoSession session, final String uri,
                                           final MediaSource[] video, final MediaSource[] audio,
                                           final MediaCallback callback) {
            final String host = Uri.parse(uri).getAuthority();
            final String title;
            if (audio == null) {
                title = getString(R.string.request_video, host);
            } else if (video == null) {
                title = getString(R.string.request_audio, host);
            } else {
                title = getString(R.string.request_media, host);
            }

            String[] videoNames = normalizeMediaName(video);
            String[] audioNames = normalizeMediaName(audio);

            final BasicGeckoViewPrompt prompt = (BasicGeckoViewPrompt)
                    mGeckoSession.getPromptDelegate();
            prompt.onMediaPrompt(session, title, video, audio, videoNames, audioNames, callback);
        }
    }

    private class ExampleNavigationDelegate implements GeckoSession.NavigationDelegate {
        @Override
        public void onLocationChange(GeckoSession session, final String url) {
            mLocationView.setText(url);
            mCurrentUri = url;
        }

        @Override
        public void onCanGoBack(GeckoSession session, boolean canGoBack) {
            mCanGoBack = canGoBack;
        }

        @Override
        public void onCanGoForward(GeckoSession session, boolean canGoForward) {
            mCanGoForward = canGoForward;
        }

        @Override
        public GeckoResult<AllowOrDeny> onLoadRequest(final GeckoSession session,
                                                      final LoadRequest request) {
            Log.d(LOGTAG, "onLoadRequest=" + request.uri +
                  " triggerUri=" + request.triggerUri +
                  " where=" + request.target +
                  " isRedirect=" + request.isRedirect);
            if (request.isRedirect) {
                // GeckoViewActivity.this.bench.onRedirect(request.uri);
            }

            return GeckoResult.fromValue(AllowOrDeny.ALLOW);
        }

        @Override
        public GeckoResult<GeckoSession> onNewSession(final GeckoSession session, final String uri) {
            GeckoSession newSession = new GeckoSession(session.getSettings());

            Intent intent = new Intent(GeckoViewActivity.this, SessionActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
            intent.setAction(Intent.ACTION_VIEW);
            intent.setData(Uri.parse(uri));
            intent.putExtra("session", newSession);

            startActivity(intent);

            return GeckoResult.fromValue(newSession);
        }

        private String categoryToString(final int category) {
            switch (category) {
                case WebRequestError.ERROR_CATEGORY_UNKNOWN:
                    return "ERROR_CATEGORY_UNKNOWN";
                case WebRequestError.ERROR_CATEGORY_SECURITY:
                    return "ERROR_CATEGORY_SECURITY";
                case WebRequestError.ERROR_CATEGORY_NETWORK:
                    return "ERROR_CATEGORY_NETWORK";
                case WebRequestError.ERROR_CATEGORY_CONTENT:
                    return "ERROR_CATEGORY_CONTENT";
                case WebRequestError.ERROR_CATEGORY_URI:
                    return "ERROR_CATEGORY_URI";
                case WebRequestError.ERROR_CATEGORY_PROXY:
                    return "ERROR_CATEGORY_PROXY";
                case WebRequestError.ERROR_CATEGORY_SAFEBROWSING:
                    return "ERROR_CATEGORY_SAFEBROWSING";
                default:
                    return "UNKNOWN";
            }
        }

        private String errorToString(final int error) {
            switch (error) {
                case WebRequestError.ERROR_UNKNOWN:
                    return "ERROR_UNKNOWN";
                case WebRequestError.ERROR_SECURITY_SSL:
                    return "ERROR_SECURITY_SSL";
                case WebRequestError.ERROR_SECURITY_BAD_CERT:
                    return "ERROR_SECURITY_BAD_CERT";
                case WebRequestError.ERROR_NET_RESET:
                    return "ERROR_NET_RESET";
                case WebRequestError.ERROR_NET_INTERRUPT:
                    return "ERROR_NET_INTERRUPT";
                case WebRequestError.ERROR_NET_TIMEOUT:
                    return "ERROR_NET_TIMEOUT";
                case WebRequestError.ERROR_CONNECTION_REFUSED:
                    return "ERROR_CONNECTION_REFUSED";
                case WebRequestError.ERROR_UNKNOWN_PROTOCOL:
                    return "ERROR_UNKNOWN_PROTOCOL";
                case WebRequestError.ERROR_UNKNOWN_HOST:
                    return "ERROR_UNKNOWN_HOST";
                case WebRequestError.ERROR_UNKNOWN_SOCKET_TYPE:
                    return "ERROR_UNKNOWN_SOCKET_TYPE";
                case WebRequestError.ERROR_UNKNOWN_PROXY_HOST:
                    return "ERROR_UNKNOWN_PROXY_HOST";
                case WebRequestError.ERROR_MALFORMED_URI:
                    return "ERROR_MALFORMED_URI";
                case WebRequestError.ERROR_REDIRECT_LOOP:
                    return "ERROR_REDIRECT_LOOP";
                case WebRequestError.ERROR_SAFEBROWSING_PHISHING_URI:
                    return "ERROR_SAFEBROWSING_PHISHING_URI";
                case WebRequestError.ERROR_SAFEBROWSING_MALWARE_URI:
                    return "ERROR_SAFEBROWSING_MALWARE_URI";
                case WebRequestError.ERROR_SAFEBROWSING_UNWANTED_URI:
                    return "ERROR_SAFEBROWSING_UNWANTED_URI";
                case WebRequestError.ERROR_SAFEBROWSING_HARMFUL_URI:
                    return "ERROR_SAFEBROWSING_HARMFUL_URI";
                case WebRequestError.ERROR_CONTENT_CRASHED:
                    return "ERROR_CONTENT_CRASHED";
                case WebRequestError.ERROR_OFFLINE:
                    return "ERROR_OFFLINE";
                case WebRequestError.ERROR_PORT_BLOCKED:
                    return "ERROR_PORT_BLOCKED";
                case WebRequestError.ERROR_PROXY_CONNECTION_REFUSED:
                    return "ERROR_PROXY_CONNECTION_REFUSED";
                case WebRequestError.ERROR_FILE_NOT_FOUND:
                    return "ERROR_FILE_NOT_FOUND";
                case WebRequestError.ERROR_FILE_ACCESS_DENIED:
                    return "ERROR_FILE_ACCESS_DENIED";
                case WebRequestError.ERROR_INVALID_CONTENT_ENCODING:
                    return "ERROR_INVALID_CONTENT_ENCODING";
                case WebRequestError.ERROR_UNSAFE_CONTENT_TYPE:
                    return "ERROR_UNSAFE_CONTENT_TYPE";
                case WebRequestError.ERROR_CORRUPTED_CONTENT:
                    return "ERROR_CORRUPTED_CONTENT";
                default:
                    return "UNKNOWN";
            }
        }

        private String createErrorPage(final int category, final int error) {
            if (mErrorTemplate == null) {
                InputStream stream = null;
                BufferedReader reader = null;
                StringBuilder builder = new StringBuilder();
                try {
                    stream = getResources().getAssets().open("error.html");
                    reader = new BufferedReader(new InputStreamReader(stream));

                    String line;
                    while ((line = reader.readLine()) != null) {
                        builder.append(line);
                        builder.append("\n");
                    }

                    mErrorTemplate = builder.toString();
                } catch (IOException e) {
                    Log.d(LOGTAG, "Failed to open error page template", e);
                    return null;
                } finally {
                    if (stream != null) {
                        try {
                            stream.close();
                        } catch (IOException e) {
                            Log.e(LOGTAG, "Failed to close error page template stream", e);
                        }
                    }

                    if (reader != null) {
                        try {
                            reader.close();
                        } catch (IOException e) {
                            Log.e(LOGTAG, "Failed to close error page template reader", e);
                        }
                    }
                }
            }

            return GeckoViewActivity.this.createErrorPage(categoryToString(category) + " : " + errorToString(error));
        }

        @Override
        public GeckoResult<String> onLoadError(final GeckoSession session, final String uri,
                                               final WebRequestError error) {
            Log.d(LOGTAG, "onLoadError=" + uri +
                  " error category=" + error.category +
                  " error=" + error.code);

            return GeckoResult.fromValue("data:text/html," + createErrorPage(error.category, error.code));
        }
    }

    private class ExampleContentBlockingDelegate
            implements ContentBlocking.Delegate {
        private int mBlockedAds = 0;
        private int mBlockedAnalytics = 0;
        private int mBlockedSocial = 0;
        private int mBlockedContent = 0;
        private int mBlockedTest = 0;

        private void clearCounters() {
            mBlockedAds = 0;
            mBlockedAnalytics = 0;
            mBlockedSocial = 0;
            mBlockedContent = 0;
            mBlockedTest = 0;
        }

        private void logCounters() {
            Log.d(LOGTAG, "Trackers blocked: " + mBlockedAds + " ads, " +
                  mBlockedAnalytics + " analytics, " +
                  mBlockedSocial + " social, " +
                  mBlockedContent + " content, " +
                  mBlockedTest + " test");
        }

        @Override
        public void onContentBlocked(final GeckoSession session,
                                     final ContentBlocking.BlockEvent event) {
            Log.d(LOGTAG, "onContentBlocked " + event.categories +
                  " (" + event.uri + ")");
            Log.d("rabbitbench", "block\t" + mCurrentUri + "\t" +
                  sGeckoRuntime.getSettings().getContentBlocking().getCategories() + "\t" +
                event.categories);
            // GeckoViewActivity.this.bench.onBlock(mCurrentUri, event.categories);

            if ((event.categories & ContentBlocking.AT_TEST) != 0) {
                mBlockedTest++;
            }
            if ((event.categories & ContentBlocking.AT_AD) != 0) {
                mBlockedAds++;
            }
            if ((event.categories & ContentBlocking.AT_ANALYTIC) != 0) {
                mBlockedAnalytics++;
            }
            if ((event.categories & ContentBlocking.AT_SOCIAL) != 0) {
                mBlockedSocial++;
            }
            if ((event.categories & ContentBlocking.AT_CONTENT) != 0) {
                mBlockedContent++;
            }
        }
    }
}
