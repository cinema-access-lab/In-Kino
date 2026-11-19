// SPDX-License-Identifier: Apache-2.0
package org.cinemaaccesslab.inkino;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.OpenableColumns;
import android.util.Log;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.cinemaaccesslab.inkino.core.*;
import org.cinemaaccesslab.inkino.subtitles.SubtitleTimeline;
import org.cinemaaccesslab.inkino.ui.CinemaUi;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manual player: live mic -> spectral landmarks -> two-confirmation clock -> optional
 * headphones-only mono AD playback. Local media playback uses only Android platform APIs (no
 * commercial SDK). IMPORTANT: Experimental. Not safe for audience-facing accessibility delivery.
 */
public final class MainActivity extends Activity {
    private static final int LOAD_FP = 10,
            LOAD_AD = 11,
            EXPORT_LOG = 12,
            MIC_PERMISSION = 13,
            LOAD_SDH = 14;
    private static final int AUDIO_SR = 44100,
            WINDOW_SECONDS = 8,
            INPUT_SAMPLES = AUDIO_SR * WINDOW_SECONDS;
    private static final int WHITE = CinemaUi.TEXT,
            BLACK = CinemaUi.BACKGROUND,
            SUBTEXT = CinemaUi.MUTED;
    private static final int ACCENT = CinemaUi.ACCENT;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private final AtomicBoolean analysisBusy = new AtomicBoolean(false);
    private volatile boolean listening = false;
    private volatile int sessionGeneration = 0;
    private volatile FingerprintReference reference;
    private LandmarkMatcher matcher;
    private SyncClock clock;
    private final Object clockGuard = new Object();
    private AudioRing ring;
    private AudioRecord recorder;
    private Thread captureThread;
    private MediaPlayer adPlayer;
    private boolean adPrepared, adPrimed, adSeeking, adTestActive;
    private float appliedPlaybackRate = 1.0f;
    private long adLargeDivergenceSinceMs = 0;
    // Never unmute until Android reports the *actual* output as approved headphones.
    private boolean actualOutputVerified = false;
    private long routeVerificationStartedMs = 0;
    private static final long ROUTE_TIMEOUT_MS = 2400;
    private long adSeekingSinceMs;
    private final Runnable stopAdTestRunnable =
            () -> stopAdTest("AD test finished after 10 seconds");
    private int confirmedAmbiguousOutputId = -1;
    private String lastAdStatus = "";
    private Uri adUri;
    private volatile SubtitleTimeline subtitleTimeline;
    private TextView subtitleView, fullscreenSubtitleView, subtitleFileView, subtitleSizeView;
    private TextView headingView;
    private static final String TAG = "InKinoPlayer";
    private SyncClock.State lastSyncState = null;
    private ScrollView manualScroll;
    private FrameLayout fullscreenSubtitleLayer;
    private boolean subtitleFullscreen = false;
    private int previousOrientationRequest = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    private int previousDisplayOrientation = Configuration.ORIENTATION_PORTRAIT;
    private int previousUiVisibility = 0;
    private CheckBox subtitlesEnabled, subtitleAnnouncements;
    private EditText subtitleOffsetInput;
    private int subtitleSizeSp = 32;
    private String lastCueDisplayed = null;
    private LinearLayout diagnosticsPanel;
    private Button diagnosticsToggle;
    private TextView statusView,
            timeView,
            metricsView,
            fingerprintView,
            adView,
            routingView,
            adStatusView,
            adProgressView,
            outputDiagnosticsView;
    private EditText testPositionInput;
    private Button testAdButton;
    private Button startButton;
    private CheckBox bluetoothConsent;
    private EditText offsetInput;
    private String lastStatus = "", latestDetails = "No match yet";
    private final StringBuilder log =
            new StringBuilder(
                    "elapsed_s,state,film_s,best_votes,margin_ratio,ad_position_ms,details\n");
    private long sessionStartNanos;
    private volatile boolean foreground = true;
    private int observedLockGeneration = 0;
    private final BroadcastReceiver stopReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (SessionForegroundService.ACTION_STOP_UI.equals(intent.getAction()))
                        stopListening();
                }
            };

    private final AudioDeviceCallback devicesCallback =
            new AudioDeviceCallback() {
                @Override
                public void onAudioDevicesAdded(AudioDeviceInfo[] devices) {
                    handler.post(
                            () -> {
                                revalidateConfirmedOutput();
                                verifyHeadphones();
                            });
                }

                @Override
                public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) {
                    handler.post(
                            () -> {
                                revalidateConfirmedOutput();
                                verifyHeadphones();
                            });
                }
            };

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        // Allow the display to sleep: an active session uses a user-visible FGS + partial wake
        // lock.
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        subtitleSizeSp = CinemaPreferences.get(this).getInt(CinemaPreferences.SUBTITLE_SIZE, 32);
        buildUi();
        IntentFilter stopFilter = new IntentFilter(SessionForegroundService.ACTION_STOP_UI);
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(stopReceiver, stopFilter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(stopReceiver, stopFilter);
        AudioManager manager = (AudioManager) getSystemService(AUDIO_SERVICE);
        manager.registerAudioDeviceCallback(devicesCallback, handler);
        handler.post(ticker);
    }

    private int dp(int value) {
        return CinemaUi.dp(this, value);
    }

    private void setStatus(String message, CinemaUi.Tone tone) {
        CinemaUi.setStatus(statusView, message, tone);
    }

    private void vibrateOnLock() {
        if (!CinemaPreferences.get(this).getBoolean(CinemaPreferences.VIBRATE_ON_LOCK, true))
            return;
        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        try {
            if (Build.VERSION.SDK_INT >= 29)
                vibrator.vibrate(
                        VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK));
            else
                vibrator.vibrate(
                        VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (RuntimeException e) {
            Log.w(TAG, "Lock vibration unavailable", e);
        }
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        return CinemaUi.text(this, s, sp, color, bold);
    }

    private Button button(String title, boolean primary) {
        Button b = CinemaUi.button(this, title, primary);
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, dp(primary ? 72 : CinemaUi.BUTTON_HEIGHT));
        params.setMargins(0, dp(6), 0, dp(6));
        b.setLayoutParams(params);
        return b;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        manualScroll = scroll;
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BLACK);
        LinearLayout root = CinemaUi.column(this);
        scroll.addView(root);
        LinearLayout header = CinemaUi.screenHeader(this, getString(R.string.home_manual_player));
        headingView = (TextView) header.getChildAt(1);
        root.addView(header);

        // The actual listener experience belongs at the top, before setup and diagnostics.
        statusView = CinemaUi.statusPill(this);
        setStatus(getString(R.string.player_ready), CinemaUi.Tone.NEUTRAL);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-2, -2);
        statusParams.gravity = Gravity.CENTER_HORIZONTAL;
        statusParams.topMargin = dp(8);
        root.addView(statusView, statusParams);
        timeView = text("--:--:--.---", 40, WHITE, true);
        timeView.setTypeface(android.graphics.Typeface.MONOSPACE);
        timeView.setGravity(Gravity.CENTER_HORIZONTAL);
        timeView.setPadding(0, dp(12), 0, dp(12));
        root.addView(timeView);
        startButton = button(getString(R.string.action_start_sync), true);
        startButton.setOnClickListener(
                v -> {
                    if (listening) stopListening();
                    else startListeningWithPermission();
                });
        root.addView(startButton);

        root.addView(CinemaUi.sectionHeading(this, getString(R.string.subtitles)));
        subtitleView = text(getString(R.string.subtitle_placeholder), subtitleSizeSp, WHITE, true);
        subtitleView.setGravity(Gravity.CENTER);
        CinemaUi.card(this, subtitleView);
        subtitleView.setPadding(dp(14), dp(17), dp(14), dp(17));
        subtitleView.setMinHeight(dp(142));
        // Do not continuously interrupt TalkBack while AD is playing. Announcements are opt-in.
        subtitleView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        attachFullscreenGesture(subtitleView);
        subtitleView.setContentDescription(getString(R.string.subtitle_display_description));
        root.addView(subtitleView);
        root.addView(CinemaUi.caption(this, getString(R.string.subtitle_fullscreen_hint)));
        subtitlesEnabled = new CheckBox(this);
        subtitlesEnabled.setText(R.string.show_subtitles);
        CinemaUi.styleCheckBox(subtitlesEnabled);
        subtitlesEnabled.setChecked(true);
        subtitlesEnabled.setOnCheckedChangeListener(
                (v, enabled) -> {
                    lastCueDisplayed = null;
                    String initial =
                            getString(
                                    enabled
                                            ? (subtitleTimeline == null
                                                    ? R.string.subtitles_load_to_begin
                                                    : R.string.subtitles_waiting)
                                            : R.string.subtitles_hidden);
                    subtitleView.setText(initial);
                    if (fullscreenSubtitleView != null) fullscreenSubtitleView.setText(initial);
                    updateSubtitleAccessibilityDescription(initial);
                });
        root.addView(subtitlesEnabled);
        LinearLayout sizeRow;
        Button smaller = button("A−", false), larger = button("A+", false);
        smaller.setContentDescription(getString(R.string.size_smaller));
        larger.setContentDescription(getString(R.string.size_larger));
        smaller.setOnClickListener(v -> changeSubtitleSize(-4));
        larger.setOnClickListener(v -> changeSubtitleSize(4));
        sizeRow = CinemaUi.pair(this, smaller, larger);
        root.addView(sizeRow);
        subtitleSizeView =
                CinemaUi.caption(this, getString(R.string.subtitle_size_label, subtitleSizeSp));
        root.addView(subtitleSizeView);

        root.addView(CinemaUi.sectionHeading(this, getString(R.string.files_heading)));
        fingerprintView = text(getString(R.string.fingerprint_not_loaded), 17, SUBTEXT, false);
        root.addView(fingerprintView);
        Button selectFp = button(getString(R.string.load_fingerprint), false);
        selectFp.setOnClickListener(v -> openFile(LOAD_FP, "*/*"));
        root.addView(selectFp);

        subtitleFileView = text(getString(R.string.sdh_not_loaded), 17, SUBTEXT, false);
        root.addView(subtitleFileView);
        Button selectSub = button(getString(R.string.load_subtitles), false);
        selectSub.setOnClickListener(v -> openFile(LOAD_SDH, "*/*"));
        root.addView(selectSub);

        adView = text(getString(R.string.ad_not_loaded), 17, SUBTEXT, false);
        root.addView(adView);
        Button selectAd = button(getString(R.string.load_ad), false);
        selectAd.setOnClickListener(v -> openFile(LOAD_AD, "audio/*"));
        root.addView(selectAd);
        routingView = text(getString(R.string.headphones_required), 17, ACCENT, true);
        routingView.setPadding(0, dp(16), 0, dp(4));
        root.addView(routingView);
        bluetoothConsent = new CheckBox(this);
        bluetoothConsent.setText(R.string.headphones_consent);
        CinemaUi.styleCheckBox(bluetoothConsent);
        bluetoothConsent.setOnCheckedChangeListener(
                (view, checked) -> {
                    AudioDeviceInfo candidate = findAmbiguousOutput();
                    confirmedAmbiguousOutputId =
                            (checked && candidate != null) ? candidate.getId() : -1;
                    verifyHeadphones();
                });
        root.addView(bluetoothConsent);
        root.addView(CinemaUi.caption(this, getString(R.string.subtitles_work_alone)));

        diagnosticsToggle = button(getString(R.string.show_advanced), false);
        ((LinearLayout.LayoutParams) diagnosticsToggle.getLayoutParams()).topMargin =
                dp(CinemaUi.SECTION_GAP);
        diagnosticsPanel = new LinearLayout(this);
        diagnosticsPanel.setOrientation(LinearLayout.VERTICAL);
        diagnosticsPanel.setVisibility(View.GONE);
        diagnosticsToggle.setOnClickListener(
                v -> {
                    boolean open = diagnosticsPanel.getVisibility() != View.VISIBLE;
                    diagnosticsPanel.setVisibility(open ? View.VISIBLE : View.GONE);
                    diagnosticsToggle.setText(
                            open ? R.string.hide_advanced : R.string.show_advanced);
                });
        root.addView(diagnosticsToggle);
        root.addView(diagnosticsPanel);
        // Diagnostics are a developer tool: only debug builds show them. They stay in English.
        boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (!debuggable) diagnosticsToggle.setVisibility(View.GONE);
        diagnosticsPanel.addView(CinemaUi.sectionHeading(this, "Diagnostics"));
        metricsView = text("No microphone match yet.", 17, SUBTEXT, false);
        diagnosticsPanel.addView(metricsView);
        outputDiagnosticsView = text("Actual output: not playing", 16, SUBTEXT, false);
        diagnosticsPanel.addView(outputDiagnosticsView);
        adStatusView = text("AD: no audio loaded", 18, ACCENT, true);
        adStatusView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        diagnosticsPanel.addView(adStatusView);
        adProgressView = text("AD playback position: --", 16, SUBTEXT, false);
        diagnosticsPanel.addView(adProgressView);
        diagnosticsPanel.addView(
                text("Test AD without synchronization (position in seconds)", 16, SUBTEXT, true));
        testPositionInput = new EditText(this);
        testPositionInput.setText("60");
        testPositionInput.setSingleLine(true);
        testPositionInput.setInputType(
                android.text.InputType.TYPE_CLASS_NUMBER
                        | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        CinemaUi.styleInput(testPositionInput);
        testPositionInput.setContentDescription("Position in AD file to test, seconds");
        diagnosticsPanel.addView(testPositionInput);
        testAdButton = button("Test AD audio (10 seconds)", false);
        testAdButton.setOnClickListener(
                v -> {
                    if (adTestActive) stopAdTest("AD test stopped");
                    else startAdTest();
                });
        diagnosticsPanel.addView(testAdButton);
        diagnosticsPanel.addView(
                text(
                        "AD timing adjustment, seconds. Leave at 0 unless testing alignment.",
                        16,
                        SUBTEXT,
                        false));
        offsetInput = new EditText(this);
        offsetInput.setText("0");
        offsetInput.setSingleLine(true);
        offsetInput.setInputType(
                android.text.InputType.TYPE_CLASS_NUMBER
                        | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                        | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        CinemaUi.styleInput(offsetInput);
        offsetInput.setContentDescription("AD timeline adjustment, seconds");
        diagnosticsPanel.addView(offsetInput);
        diagnosticsPanel.addView(
                text("Subtitle timeline adjustment, seconds (normally 0)", 16, SUBTEXT, false));
        subtitleOffsetInput = new EditText(this);
        subtitleOffsetInput.setText("0");
        subtitleOffsetInput.setSingleLine(true);
        subtitleOffsetInput.setInputType(
                android.text.InputType.TYPE_CLASS_NUMBER
                        | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                        | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        CinemaUi.styleInput(subtitleOffsetInput);
        subtitleOffsetInput.setContentDescription("SDH timeline offset in seconds");
        diagnosticsPanel.addView(subtitleOffsetInput);
        subtitleAnnouncements = new CheckBox(this);
        subtitleAnnouncements.setText("Announce each subtitle with TalkBack (optional)");
        CinemaUi.styleCheckBox(subtitleAnnouncements);
        subtitleAnnouncements.setOnCheckedChangeListener(
                (view, checked) -> {
                    int mode =
                            checked
                                    ? View.ACCESSIBILITY_LIVE_REGION_POLITE
                                    : View.ACCESSIBILITY_LIVE_REGION_NONE;
                    subtitleView.setAccessibilityLiveRegion(mode);
                    if (fullscreenSubtitleView != null)
                        fullscreenSubtitleView.setAccessibilityLiveRegion(mode);
                    CinemaPreferences.get(this)
                            .edit()
                            .putBoolean(CinemaPreferences.SUBTITLE_ANNOUNCEMENTS, checked)
                            .apply();
                });
        diagnosticsPanel.addView(subtitleAnnouncements);
        subtitleAnnouncements.setChecked(
                CinemaPreferences.get(this)
                        .getBoolean(CinemaPreferences.SUBTITLE_ANNOUNCEMENTS, false));
        Button previewSub = button("Preview subtitles at test position", false);
        previewSub.setOnClickListener(v -> previewSubtitlesAtTestPosition());
        diagnosticsPanel.addView(previewSub);
        Button export = button("Export test log (CSV)", false);
        export.setOnClickListener(v -> exportLog());
        diagnosticsPanel.addView(export);
        TextView footnote = CinemaUi.caption(this, getString(R.string.player_footnote));
        footnote.setTextSize(14);
        footnote.setPadding(0, dp(20), 0, 0);
        root.addView(footnote);
        // Dedicated immersive subtitle surface, separate from the manual controls.
        // Android's configChanges setting prevents a rotation from tearing down the session.
        FrameLayout content = new FrameLayout(this);
        content.setBackgroundColor(BLACK);
        content.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        fullscreenSubtitleLayer = new FrameLayout(this);
        fullscreenSubtitleLayer.setBackgroundColor(Color.BLACK);
        fullscreenSubtitleLayer.setVisibility(View.GONE);
        fullscreenSubtitleView = text("", subtitleSizeSp, Color.WHITE, true);
        fullscreenSubtitleView.setGravity(Gravity.CENTER);
        fullscreenSubtitleView.setBackgroundColor(Color.BLACK);
        fullscreenSubtitleView.setPadding(dp(30), dp(18), dp(30), dp(18));
        fullscreenSubtitleView.setAutoSizeTextTypeUniformWithConfiguration(
                Math.max(18, subtitleSizeSp - 10), subtitleSizeSp, 2, TypedValue.COMPLEX_UNIT_SP);
        attachFullscreenGesture(fullscreenSubtitleView);
        fullscreenSubtitleView.setContentDescription(getString(R.string.fullscreen_description));
        fullscreenSubtitleLayer.addView(
                fullscreenSubtitleView, new FrameLayout.LayoutParams(-1, -1));
        content.addView(fullscreenSubtitleLayer, new FrameLayout.LayoutParams(-1, -1));
        setContentView(content);
        CinemaUi.edgeToEdge(this, scroll);
        verifyHeadphones();
        loadDownloadedPackage(getIntent());
    }

    private void loadDownloadedPackage(Intent intent) {
        if (intent == null || !intent.hasExtra("package_fingerprint")) return;
        // Only app-private paths provided by our own non-exported Activity are accepted.
        try {
            String base =
                    new java.io.File(getFilesDir(), "packages").getCanonicalPath()
                            + java.io.File.separator;
            String title = intent.getStringExtra("package_title");
            if (title != null && !title.isEmpty()) {
                headingView.setText(title);
                setStatus(getString(R.string.player_ready_film), CinemaUi.Tone.NEUTRAL);
            }
            for (String key : new String[] {"package_fingerprint", "package_sdh", "package_ad"}) {
                String path = intent.getStringExtra(key);
                if (path == null) continue;
                java.io.File f = new java.io.File(path);
                if (!f.getCanonicalPath().startsWith(base) || !f.isFile())
                    throw new IOException("Invalid package file");
                Uri uri = Uri.fromFile(f);
                if (key.equals("package_fingerprint")) loadFingerprint(uri);
                else if (key.equals("package_sdh")) loadSubtitles(uri);
                else loadAd(uri);
            }
        } catch (Exception e) {
            setStatus(getString(R.string.package_error, e.getMessage()), CinemaUi.Tone.ERROR);
        }
    }

    /** Double-tap on the subtitle surface; TalkBack's Activate action uses performClick. */
    private void attachFullscreenGesture(TextView target) {
        GestureDetector detector =
                new GestureDetector(
                        this,
                        new GestureDetector.SimpleOnGestureListener() {
                            @Override
                            public boolean onDown(MotionEvent event) {
                                return true;
                            }

                            @Override
                            public boolean onSingleTapConfirmed(MotionEvent event) {
                                return true;
                            }

                            @Override
                            public boolean onDoubleTap(MotionEvent event) {
                                target.performClick();
                                return true;
                            }
                        });
        target.setOnClickListener(v -> toggleSubtitleFullscreen());
        target.setOnTouchListener(
                (v, event) -> {
                    detector.onTouchEvent(event);
                    // Consume even the first tap: Android must not interpret it as a click.
                    // A real two-tap gesture invokes performClick from onDoubleTap.
                    return true;
                });
    }

    private void toggleSubtitleFullscreen() {
        if (subtitleFullscreen) exitSubtitleFullscreen();
        else enterSubtitleFullscreen();
    }

    private void enterSubtitleFullscreen() {
        if (subtitleFullscreen || fullscreenSubtitleLayer == null) return;
        previousOrientationRequest = getRequestedOrientation();
        previousDisplayOrientation = getResources().getConfiguration().orientation;
        previousUiVisibility = getWindow().getDecorView().getSystemUiVisibility();
        subtitleFullscreen = true;
        fullscreenSubtitleView.setText(subtitleView.getText());
        updateSubtitleAccessibilityDescription(subtitleView.getText().toString());
        manualScroll.setVisibility(View.GONE);
        fullscreenSubtitleLayer.setVisibility(View.VISIBLE);
        getWindow()
                .getDecorView()
                .setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        fullscreenSubtitleView.requestFocus();
    }

    private void exitSubtitleFullscreen() {
        if (!subtitleFullscreen) return;
        subtitleFullscreen = false;
        fullscreenSubtitleLayer.setVisibility(View.GONE);
        manualScroll.setVisibility(View.VISIBLE);
        getWindow().getDecorView().setSystemUiVisibility(previousUiVisibility);
        // Return to portrait when that was the prior player orientation; otherwise
        // honor the Activity's original rotation preference.
        int restore =
                previousDisplayOrientation == Configuration.ORIENTATION_PORTRAIT
                        ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        : previousOrientationRequest;
        setRequestedOrientation(restore);
        subtitleView.requestFocus();
    }

    private void updateSubtitleAccessibilityDescription(String text) {
        String cue = text.isEmpty() ? getString(R.string.no_subtitle_here) : text;
        subtitleView.setContentDescription(getString(R.string.subtitle_cue_description, cue));
        if (fullscreenSubtitleView != null)
            fullscreenSubtitleView.setContentDescription(
                    getString(R.string.fullscreen_cue_description, cue));
    }

    @Override
    public void onBackPressed() {
        if (subtitleFullscreen) exitSubtitleFullscreen();
        else super.onBackPressed();
    }

    private void changeSubtitleSize(int delta) {
        subtitleSizeSp = Math.max(22, Math.min(52, subtitleSizeSp + delta));
        subtitleView.setTextSize(subtitleSizeSp);
        if (fullscreenSubtitleView != null) {
            fullscreenSubtitleView.setTextSize(subtitleSizeSp);
            fullscreenSubtitleView.setAutoSizeTextTypeUniformWithConfiguration(
                    Math.max(18, subtitleSizeSp - 10),
                    subtitleSizeSp,
                    2,
                    TypedValue.COMPLEX_UNIT_SP);
        }
        subtitleSizeView.setText(getString(R.string.subtitle_size_label, subtitleSizeSp));
        CinemaPreferences.get(this)
                .edit()
                .putInt(CinemaPreferences.SUBTITLE_SIZE, subtitleSizeSp)
                .apply();
    }

    private double subtitleOffsetSeconds() {
        if (subtitleOffsetInput == null) return 0;
        try {
            return Double.parseDouble(
                    subtitleOffsetInput.getText().toString().trim().replace(',', '.'));
        } catch (NumberFormatException empty) {
            return 0;
        } // An empty or partial entry means no adjustment.
    }

    private void renderSubtitles(SyncClock.State state, double film) {
        if (subtitleView == null || subtitlesEnabled == null) return;
        String next = "";
        if (!subtitlesEnabled.isChecked()) next = getString(R.string.subtitles_hidden);
        else if (subtitleTimeline == null) next = getString(R.string.subtitle_placeholder);
        else if (PlaybackTimelinePolicy.hasVerifiedOrigin(state)) {
            if (Double.isFinite(film))
                next =
                        subtitleTimeline.textAt(
                                Math.round((film + subtitleOffsetSeconds()) * 1000.0));
        } // Before first lock, there is no trustworthy film timeline.
        if (next.equals(lastCueDisplayed)) return;
        lastCueDisplayed = next;
        subtitleView.setText(next);
        if (fullscreenSubtitleView != null) fullscreenSubtitleView.setText(next);
        updateSubtitleAccessibilityDescription(next);
    }

    private void previewSubtitlesAtTestPosition() {
        if (subtitleTimeline == null) {
            toast("Load an SRT or VTT first");
            return;
        }
        try {
            double at =
                    Double.parseDouble(
                            testPositionInput.getText().toString().trim().replace(',', '.'));
            String cue =
                    subtitleTimeline.textAt(Math.round((at + subtitleOffsetSeconds()) * 1000.0));
            String shown = cue.isEmpty() ? "No SDH cue at that position" : cue;
            subtitleView.setText(shown);
            if (fullscreenSubtitleView != null) fullscreenSubtitleView.setText(shown);
            updateSubtitleAccessibilityDescription(shown);
            lastCueDisplayed = null; // Ensure the next clock update takes authority.
        } catch (Exception ex) {
            toast("Enter a valid test position in seconds");
        }
    }

    private void loadSubtitles(Uri uri) {
        final String filename = name(uri);
        subtitleFileView.setText(getString(R.string.loading_sdh, filename));
        background.execute(
                () -> {
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        if (in == null) throw new IOException("Unable to open selected subtitles");
                        SubtitleTimeline loaded =
                                SubtitleTimeline.parse(
                                        new InputStreamReader(in, StandardCharsets.UTF_8));
                        handler.post(
                                () -> {
                                    subtitleTimeline = loaded;
                                    subtitleFileView.setText(
                                            getString(R.string.sdh_ready, filename, loaded.size()));
                                    lastCueDisplayed = null;
                                    if (!listening)
                                        subtitleView.setText(R.string.subtitles_loaded_press_start);
                                });
                    } catch (Exception e) {
                        handler.post(
                                () -> {
                                    subtitleFileView.setText(
                                            getString(R.string.sdh_error, e.getMessage()));
                                    toast(
                                            getString(
                                                    R.string.subtitles_load_failed,
                                                    e.getMessage()));
                                });
                    }
                });
    }

    private void openFile(int request, String mime) {
        if (listening) stopListening();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mime);
        intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, request);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent intent) {
        super.onActivityResult(request, result, intent);
        if (result != RESULT_OK || intent == null || intent.getData() == null) return;
        Uri uri = intent.getData();
        if (request == LOAD_FP || request == LOAD_AD || request == LOAD_SDH) {
            try {
                getContentResolver()
                        .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException notPersistable) {
                /* The picker still grants access for this session. */
            }
        }
        if (request == LOAD_FP) loadFingerprint(uri);
        if (request == LOAD_AD) loadAd(uri);
        if (request == LOAD_SDH) loadSubtitles(uri);
        if (request == EXPORT_LOG) {
            final String snapshot = log.toString();
            background.execute(
                    () -> {
                        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                            if (out == null) throw new IOException("Cannot open output file");
                            out.write(snapshot.getBytes(StandardCharsets.UTF_8));
                            handler.post(() -> toast("Test log exported"));
                        } catch (Exception e) {
                            handler.post(() -> toast("Export failed: " + e.getMessage()));
                        }
                    });
        }
    }

    private String name(Uri uri) {
        try (Cursor c =
                getContentResolver()
                        .query(
                                uri,
                                new String[] {OpenableColumns.DISPLAY_NAME},
                                null,
                                null,
                                null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException e) {
            Log.w(TAG, "No display name for " + uri, e);
        }
        if ("file".equals(uri.getScheme())) return uri.getLastPathSegment();
        return getString(R.string.selected_file);
    }

    private void loadFingerprint(Uri uri) {
        final String filename = name(uri);
        setStatus(getString(R.string.loading_fingerprint), CinemaUi.Tone.NEUTRAL);
        background.execute(
                () -> {
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        if (in == null) throw new IOException("Cannot read fingerprint");
                        FingerprintReference loaded = FingerprintReference.read(in);
                        handler.post(
                                () -> {
                                    reference = loaded;
                                    matcher = new LandmarkMatcher(loaded);
                                    fingerprintView.setText(
                                            getString(
                                                    R.string.fingerprint_loaded,
                                                    filename,
                                                    formatTime(loaded.durationSeconds)));
                                    setStatus(
                                            getString(R.string.fingerprint_ready),
                                            CinemaUi.Tone.NEUTRAL);
                                });
                    } catch (Exception e) {
                        handler.post(
                                () -> {
                                    reference = null;
                                    matcher = null;
                                    setStatus(
                                            getString(R.string.fingerprint_error),
                                            CinemaUi.Tone.ERROR);
                                    toast(e.getMessage());
                                });
                    }
                });
    }

    private void loadAd(Uri uri) {
        closeAd();
        adUri = uri;
        adView.setText(getString(R.string.preparing_ad, name(uri)));
        showAdStatus("Decoding metadata / preparing AD file…");
        try {
            adPlayer = new MediaPlayer();
            adPlayer.setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build());
            adPlayer.setDataSource(this, uri);
            adPlayer.setOnErrorListener(
                    (mp, what, extra) -> {
                        handler.post(
                                () -> {
                                    closeAd();
                                    adView.setText(R.string.ad_decode_failed);
                                    showAdStatus(
                                            "Media decoder error (what="
                                                    + what
                                                    + ", extra="
                                                    + extra
                                                    + ")");
                                });
                        return true;
                    });
            adPlayer.setOnPreparedListener(
                    mp -> {
                        adPrepared = true;
                        adPrimed = false;
                        adView.setText(
                                getString(
                                        R.string.ad_ready,
                                        name(uri),
                                        formatTime(mp.getDuration() / 1000.0)));
                        showAdStatus("AD decoded and ready. Run the AD test before synchronizing.");
                        verifyHeadphones();
                    });
            adPlayer.setOnSeekCompleteListener(
                    mp -> {
                        if (mp != adPlayer) return;
                        adSeeking = false;
                        if (adTestActive) {
                            if (headsetOutput() == null) {
                                stopAdTest("Test blocked: no confirmed headphone media output");
                                return;
                            }
                            try {
                                beginMutedPlayback(mp, true);
                                handler.removeCallbacks(stopAdTestRunnable);
                                handler.postDelayed(stopAdTestRunnable, 10000);
                            } catch (Exception e) {
                                stopAdTest("Test start failed: " + e.getMessage());
                            }
                        } else if (listening
                                && adPrepared
                                && headsetOutput() != null
                                && isConfident()) {
                            try {
                                beginMutedPlayback(mp, false);
                                adPrimed = true;
                            } catch (Exception e) {
                                adPrimed = false;
                                showAdStatus("AD start failed: " + e.getMessage());
                            }
                        } else
                            showAdStatus("AD seek finished; awaiting confirmed lock + headphones");
                    });
            adPlayer.setOnCompletionListener(
                    mp -> {
                        if (adTestActive) stopAdTest("AD file ended during test");
                        else {
                            adPrimed = false;
                            showAdStatus("AD reached end of audio file");
                        }
                    });
            adPlayer.prepareAsync();
        } catch (Exception e) {
            closeAd();
            adView.setText(getString(R.string.ad_error, e.getMessage()));
            showAdStatus("AD could not be opened: " + e.getMessage());
        }
    }

    private void closeAd() {
        handler.removeCallbacks(stopAdTestRunnable);
        adTestActive = false;
        adSeekingSinceMs = 0;
        appliedPlaybackRate = 1.0f;
        if (testAdButton != null) testAdButton.setText("Test AD audio (10 seconds)");
        adPrepared = false;
        adPrimed = false;
        adSeeking = false;
        actualOutputVerified = false;
        routeVerificationStartedMs = 0;
        if (adPlayer != null) {
            try {
                adPlayer.reset();
                adPlayer.release();
            } catch (IllegalStateException e) {
                Log.w(TAG, "AD player release failed", e);
            }
            adPlayer = null;
        }
    }

    private void showAdStatus(String message) {
        if (message.equals(lastAdStatus)) return;
        lastAdStatus = message;
        if (adStatusView != null) adStatusView.setText("AD: " + message);
    }

    // Bluetooth SCO is a *call* profile. Never select it for media playback.
    private AudioDeviceInfo findAmbiguousOutput() {
        AudioManager manager = (AudioManager) getSystemService(AUDIO_SERVICE);
        AudioDeviceInfo usb = null;
        for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) return device;
            if (device.getType() == AudioDeviceInfo.TYPE_USB_DEVICE
                    || device.getType() == AudioDeviceInfo.TYPE_USB_ACCESSORY) usb = device;
        }
        return usb;
    }

    /** User-facing name of an output, for example "Wired headphones". */
    private String deviceLabel(AudioDeviceInfo device) {
        if (device == null) return getString(R.string.device_none);
        switch (device.getType()) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                return getString(R.string.device_bt_media);
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return getString(R.string.device_bt_call);
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
                return getString(R.string.device_ble);
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                return getString(R.string.device_wired_headphones);
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                return getString(R.string.device_wired_headset);
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return getString(R.string.device_usb_headset);
            case AudioDeviceInfo.TYPE_USB_DEVICE:
                return getString(R.string.device_usb_device);
            case AudioDeviceInfo.TYPE_USB_ACCESSORY:
                return getString(R.string.device_usb_accessory);
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                return getString(R.string.device_speaker);
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
                return getString(R.string.device_earpiece);
            default:
                return getString(R.string.device_other, device.getType());
        }
    }

    /** Diagnostic name of an output, with Android's device ID. */
    private String deviceName(AudioDeviceInfo device) {
        if (device == null) return "none";
        String label;
        switch (device.getType()) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                label = "Bluetooth media (A2DP)";
                break;
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                label = "Bluetooth call audio (SCO)";
                break;
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
                label = "Bluetooth LE headset";
                break;
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                label = "wired headphones";
                break;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                label = "wired headset";
                break;
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                label = "USB headset";
                break;
            case AudioDeviceInfo.TYPE_USB_DEVICE:
                label = "USB audio device";
                break;
            case AudioDeviceInfo.TYPE_USB_ACCESSORY:
                label = "USB accessory";
                break;
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                label = "Phone speaker";
                break;
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
                label = "Phone earpiece";
                break;
            default:
                label = "device type " + device.getType();
                break;
        }
        return label + " (#" + device.getId() + ")";
    }

    private void beginMutedPlayback(MediaPlayer mp, boolean test) {
        // The operating system's Media Output selector chooses the actual media output.
        // Explicitly forcing a Bluetooth SCO device is not reliable and can yield silence.
        mp.setPreferredDevice(null);
        mp.setVolume(0f, 0f);
        actualOutputVerified = false;
        routeVerificationStartedMs = SystemClock.elapsedRealtime();
        mp.start();
        showAdStatus(
                test
                        ? "AD test started muted. Checking the actual output…"
                        : "AD started muted. Verifying the headphone route…");
    }

    private void revalidateConfirmedOutput() {
        if (confirmedAmbiguousOutputId < 0) return;
        AudioManager manager = (AudioManager) getSystemService(AUDIO_SERVICE);
        for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (device.getId() == confirmedAmbiguousOutputId) return;
        }
        confirmedAmbiguousOutputId = -1;
        if (bluetoothConsent != null) bluetoothConsent.setChecked(false);
    }

    private void startAdTest() {
        if (!adPrepared || adPlayer == null) {
            showAdStatus(
                    "No decoded AD file. Choose an M4A or WAV file and wait until it is ready.");
            return;
        }
        AudioDeviceInfo route = headsetOutput();
        if (route == null) {
            showAdStatus("Test blocked: connect headphones or confirm the Bluetooth or USB output");
            return;
        }
        if (listening) stopListening();
        int atMs;
        try {
            atMs =
                    (int)
                            Math.round(
                                    Double.parseDouble(
                                                    testPositionInput
                                                            .getText()
                                                            .toString()
                                                            .trim()
                                                            .replace(',', '.'))
                                            * 1000.0);
        } catch (Exception e) {
            showAdStatus("Enter a numeric AD test position in seconds");
            return;
        }
        if (atMs < 0 || atMs >= adPlayer.getDuration()) {
            showAdStatus(
                    "Test time outside AD duration: "
                            + formatTime(adPlayer.getDuration() / 1000.0));
            return;
        }
        pauseAd();
        adTestActive = true;
        adSeeking = true;
        adSeekingSinceMs = SystemClock.elapsedRealtime();
        testAdButton.setText("Stop AD test");
        showAdStatus("Seeking AD for manual test at " + formatTime(atMs / 1000.0));
        try {
            adPlayer.seekTo(atMs, MediaPlayer.SEEK_CLOSEST);
        } catch (Exception e) {
            stopAdTest("Manual test seek failed: " + e.getMessage());
        }
    }

    private void stopAdTest(String explanation) {
        handler.removeCallbacks(stopAdTestRunnable);
        if (adTestActive && adPlayer != null && adPrepared) {
            try {
                if (adPlayer.isPlaying()) adPlayer.pause();
            } catch (IllegalStateException e) {
                Log.w(TAG, "AD test pause failed", e);
            }
        }
        adTestActive = false;
        adSeeking = false;
        adSeekingSinceMs = 0;
        adPrimed = false;
        actualOutputVerified = false;
        routeVerificationStartedMs = 0;
        if (testAdButton != null) testAdButton.setText("Test AD audio (10 seconds)");
        showAdStatus(explanation);
    }

    private AudioDeviceInfo headsetOutput() {
        AudioManager manager = (AudioManager) getSystemService(AUDIO_SERVICE);
        AudioDeviceInfo wired = null, le = null, approvedA2dp = null, approvedUsb = null;
        for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            switch (device.getType()) {
                case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                case AudioDeviceInfo.TYPE_USB_HEADSET:
                    wired = device;
                    break;
                case AudioDeviceInfo.TYPE_BLE_HEADSET:
                    le = device;
                    break;
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                    if (bluetoothConsent != null
                            && bluetoothConsent.isChecked()
                            && confirmedAmbiguousOutputId == device.getId()) approvedA2dp = device;
                    break;
                case AudioDeviceInfo.TYPE_USB_DEVICE:
                case AudioDeviceInfo.TYPE_USB_ACCESSORY:
                    if (bluetoothConsent != null
                            && bluetoothConsent.isChecked()
                            && confirmedAmbiguousOutputId == device.getId()) approvedUsb = device;
                    break;
                default:
                    break; // Excludes Bluetooth SCO (telephone audio).
            }
        }
        if (wired != null) return wired;
        if (le != null) return le;
        if (approvedA2dp != null) return approvedA2dp;
        return approvedUsb;
    }

    private void verifyHeadphones() {
        if (routingView == null) return;
        AudioDeviceInfo route = headsetOutput();
        routingView.setText(
                route == null
                        ? getString(R.string.no_headphones)
                        : getString(R.string.headphones_ok, deviceLabel(route)));
        routingView.setTextColor(route == null ? ACCENT : WHITE);
        if (route == null) {
            if (adTestActive) stopAdTest("AD test stopped: no approved headphones");
            else if (adPrepared) showAdStatus("Playback blocked: no approved headphone route");
            pauseAd();
        }
    }

    private boolean isConfident() {
        synchronized (clockGuard) {
            return clock != null && clock.hasLock();
        }
    }

    private void startListeningWithPermission() {
        if (reference == null || matcher == null) {
            toast(getString(R.string.select_fingerprint_first));
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION);
            return;
        }
        startListening();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == MIC_PERMISSION) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED)
                startListening();
            else toast(getString(R.string.mic_permission_required));
        }
    }

    private void startListening() {
        if (listening || reference == null || !foreground) return;
        if (adTestActive) stopAdTest("Manual test stopped: listening started");
        // Start microphone-capable FGS only while this Activity is visible and permission is
        // granted.
        try {
            Intent service =
                    new Intent(this, SessionForegroundService.class)
                            .setAction(SessionForegroundService.ACTION_START);
            startForegroundService(service);
        } catch (RuntimeException e) {
            toast(getString(R.string.session_start_failed, e.getMessage()));
            return;
        }
        synchronized (clockGuard) {
            clock = new SyncClock(reference.durationSeconds);
        }
        ring = new AudioRing(AUDIO_SR, 12);
        adPrimed = false;
        observedLockGeneration = 0;
        analysisBusy.set(false);
        listening = true;
        int session = ++sessionGeneration;
        lastStatus = "";
        sessionStartNanos = SystemClock.elapsedRealtimeNanos();
        startButton.setText(R.string.action_stop_sync);
        startButton.setContentDescription(getString(R.string.action_stop_sync));
        lastSyncState = SyncClock.State.SEARCHING;
        setStatus(getString(R.string.status_listening), CinemaUi.Tone.ACTIVE);
        latestDetails = "Waiting for first eight-second sample";
        AudioRing recordingRing = ring;
        captureThread = new Thread(() -> captureLoop(session, recordingRing), "cs-mic-capture");
        captureThread.start();
        handler.postDelayed(analyzeTick, 1000);
    }

    private void captureLoop(int session, AudioRing recordingRing) {
        AudioRecord capture = null;
        try {
            int min =
                    AudioRecord.getMinBufferSize(
                            AUDIO_SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) throw new IOException(getString(R.string.mic_unsupported));
            int bytes = Math.max(min * 3, 8192);
            capture =
                    new AudioRecord(
                            MediaRecorder.AudioSource.MIC,
                            AUDIO_SR,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bytes);
            if (capture.getState() != AudioRecord.STATE_INITIALIZED)
                throw new IOException(getString(R.string.mic_init_failed));
            recorder = capture;
            AudioManager manager = (AudioManager) getSystemService(AUDIO_SERVICE);
            for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                    capture.setPreferredDevice(device);
                    break;
                }
            }
            capture.startRecording();
            short[] temp = new short[2048];
            while (listening && session == sessionGeneration) {
                int n = capture.read(temp, 0, temp.length, AudioRecord.READ_BLOCKING);
                if (n > 0) recordingRing.put(temp, n, SystemClock.elapsedRealtimeNanos());
                else if (n < 0) throw new IOException(getString(R.string.mic_read_error, n));
            }
        } catch (Exception e) {
            String message = e.getMessage();
            handler.post(
                    () -> {
                        if (session != sessionGeneration) return;
                        setStatus(getString(R.string.microphone_error), CinemaUi.Tone.ERROR);
                        toast(message == null ? getString(R.string.microphone_failed) : message);
                        stopListening();
                    });
        } finally {
            if (capture != null) {
                try {
                    if (capture.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING)
                        capture.stop();
                } catch (IllegalStateException e) {
                    Log.w(TAG, "Microphone stop failed", e);
                }
                capture.release();
            }
            if (recorder == capture) recorder = null;
        }
    }

    private final Runnable analyzeTick =
            new Runnable() {
                @Override
                public void run() {
                    if (!listening) return;
                    if (analysisBusy.compareAndSet(false, true)) {
                        AudioRing.Snapshot s = ring.recent(INPUT_SAMPLES);
                        if (s == null) analysisBusy.set(false);
                        else {
                            final int session = sessionGeneration;
                            background.execute(
                                    () -> {
                                        try {
                                            LandmarkMatcher.Result r =
                                                    matcher.match(
                                                            LandmarkExtractor.extract(
                                                                    LandmarkExtractor
                                                                            .downsample44100(
                                                                                    s.data)));
                                            double when = s.endElapsedNanos / 1e9;
                                            synchronized (clockGuard) {
                                                if (listening
                                                        && session == sessionGeneration
                                                        && clock != null
                                                        && r.matched)
                                                    clock.observe(
                                                            r.queryStartSeconds + WINDOW_SECONDS,
                                                            when);
                                            }
                                            handler.post(
                                                    () -> {
                                                        if (!listening
                                                                || session != sessionGeneration)
                                                            return;
                                                        latestDetails =
                                                                String.format(
                                                                        Locale.US,
                                                                        "Candidate: %s · votes %d ·"
                                                                                + " margin %.2f ·"
                                                                                + " landmarks %d",
                                                                        r.matched ? "yes" : "no",
                                                                        r.bestVotes,
                                                                        r.ratio,
                                                                        r.queryCount);
                                                        appendObservation(r);
                                                    });
                                        } catch (Exception e) {
                                            handler.post(
                                                    () -> {
                                                        if (session == sessionGeneration)
                                                            latestDetails =
                                                                    "Analysis error: "
                                                                            + e.getMessage();
                                                    });
                                        } finally {
                                            analysisBusy.set(false);
                                        }
                                    });
                        }
                    }
                    handler.postDelayed(this, 2000);
                }
            };

    private void appendObservation(LandmarkMatcher.Result r) {
        if (log.length() > 1_000_000) return;
        double now = SystemClock.elapsedRealtimeNanos() / 1e9;
        SyncClock.State state;
        double position;
        synchronized (clockGuard) {
            if (clock == null) return;
            state = clock.state(now);
            position = clock.estimate(now);
        }
        int adMs = (adPrepared && adPlayer != null) ? adPlayer.getCurrentPosition() : -1;
        log.append(
                String.format(
                        Locale.US,
                        "%.3f,%s,%.3f,%d,%.3f,%d,%s\n",
                        now - sessionStartNanos / 1e9,
                        state,
                        position,
                        r.bestVotes,
                        r.ratio,
                        adMs,
                        r.reason));
    }

    private void pauseAd() {
        if (adPlayer != null && adPrepared) {
            try {
                if (adPlayer.isPlaying()) adPlayer.pause();
            } catch (IllegalStateException e) {
                Log.w(TAG, "AD pause failed", e);
            }
        }
        adPrimed = false;
        adLargeDivergenceSinceMs = 0;
        actualOutputVerified = false;
        routeVerificationStartedMs = 0;
        if (adPlayer != null && adPrepared) {
            try {
                adPlayer.setVolume(0f, 0f);
            } catch (IllegalStateException e) {
                Log.w(TAG, "AD mute failed", e);
            }
        }
        // Preserve the last applied rate; a subsequent seek/start must restore 1x if required.
    }

    private double offsetSeconds() {
        try {
            return Double.parseDouble(offsetInput.getText().toString().trim().replace(',', '.'));
        } catch (NumberFormatException empty) {
            return 0;
        } // An empty or partial entry means no adjustment.
    }

    /**
     * Keep AD on the same gently-corrected clock as SDH, without inferring physical speaker latency
     * from MediaPlayer.getCurrentPosition().
     */
    private void applyClockPlaybackRate(double requestedRate) {
        if (adPlayer == null
                || !adPrepared
                || !adPrimed
                || adSeeking
                || adTestActive
                || !actualOutputVerified) return;
        final float rate =
                (float)
                        Math.max(
                                1.0 - SyncClock.MAX_SLEW_FRACTION,
                                Math.min(1.0 + SyncClock.MAX_SLEW_FRACTION, requestedRate));
        if (Math.abs(rate - appliedPlaybackRate) < 0.0001f) return;
        try {
            if (adPlayer.isPlaying()) {
                adPlayer.setPlaybackParams(new PlaybackParams().setSpeed(rate).setPitch(1.0f));
                appliedPlaybackRate = rate;
            }
        } catch (IllegalStateException | IllegalArgumentException e) {
            showAdStatus("AD playback-rate control unavailable: " + e.getMessage());
        }
    }

    private void updateAd(SyncClock.State state, double filmNow) {
        if (!adPrepared || adPlayer == null || adTestActive) return;
        AudioDeviceInfo headphones = headsetOutput();
        if (headphones == null) {
            pauseAd();
            showAdStatus("Playback blocked: no approved headphones detected");
            return;
        }
        if (!PlaybackTimelinePolicy.hasVerifiedOrigin(state) || !Double.isFinite(filmNow)) {
            pauseAd();
            showAdStatus("Waiting for first confirmed film position");
            return;
        }
        // During unverified jumps and long holdover, never chase a speculative match.
        // An already playing AD track continues on its media clock without disruption.
        if (state == SyncClock.State.SEEK_CANDIDATE) return;
        if (PlaybackTimelinePolicy.deferCorrection(state) && adPrimed) return;
        if (adSeeking) {
            if (SystemClock.elapsedRealtime() - adSeekingSinceMs > 5000) {
                adSeeking = false;
                pauseAd();
                showAdStatus("AD seek took over 5 seconds; retrying");
            }
            return;
        }
        // Existing AD must correspond to same film timeline. Positive offset means later in AD
        // file.
        int target = (int) Math.round((filmNow + offsetSeconds()) * 1000);
        int end = adPlayer.getDuration();
        if (target < 0 || target >= end) {
            pauseAd();
            showAdStatus(
                    "Matched film time is outside AD recording duration ("
                            + formatTime(end / 1000.0)
                            + ")");
            return;
        }
        int current = adPlayer.getCurrentPosition();
        // A normal phase slew must NEVER invoke a sudden AD seek. Recover from a
        // sustained playback-system failure only after a much larger discrepancy.
        long nowMs = SystemClock.elapsedRealtime();
        if (adPrimed && Math.abs(current - target) > 5000) {
            if (adLargeDivergenceSinceMs == 0) adLargeDivergenceSinceMs = nowMs;
        } else adLargeDivergenceSinceMs = 0;
        boolean sustainedDecoderFailure =
                adLargeDivergenceSinceMs > 0 && nowMs - adLargeDivergenceSinceMs >= 3000;
        if (!adPrimed || sustainedDecoderFailure) {
            pauseAd();
            adSeeking = true;
            adSeekingSinceMs = SystemClock.elapsedRealtime();
            showAdStatus("AD seeking to film time " + formatTime(target / 1000.0));
            try {
                adPlayer.seekTo(target, MediaPlayer.SEEK_CLOSEST);
            } catch (Exception e) {
                adSeeking = false;
                showAdStatus("AD seek failed: " + e.getMessage());
            }
        }
    }

    private void inspectPlaybackRoute() {
        if (adPlayer == null || !adPrepared) return;
        AudioManager manager = (AudioManager) getSystemService(AUDIO_SERVICE);
        int level = manager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        AudioDeviceInfo expected = headsetOutput();
        AudioDeviceInfo actual = null;
        boolean playing;
        try {
            playing = adPlayer.isPlaying();
            if (playing && Build.VERSION.SDK_INT >= 28) actual = adPlayer.getRoutedDevice();
        } catch (Exception e) {
            return;
        }
        if (outputDiagnosticsView != null) {
            outputDiagnosticsView.setText(
                    "Media volume "
                            + level
                            + "/"
                            + max
                            + " · audio mode "
                            + manager.getMode()
                            + "\nApproved: "
                            + deviceName(expected)
                            + "\nActual output: "
                            + (playing ? deviceName(actual) : "not playing"));
        }
        if (!playing) return;
        if (expected == null) {
            if (adTestActive) stopAdTest("Output stopped: no approved headphones");
            else {
                pauseAd();
                showAdStatus("AD paused: no approved headphones");
            }
            return;
        }
        if (Build.VERSION.SDK_INT < 28) {
            if (adTestActive) stopAdTest("Cannot verify actual route on Android below 9");
            else {
                pauseAd();
                showAdStatus("Output requires Android 9+ for route verification");
            }
            return;
        }
        if (actual == null) {
            // A playback timestamp does not establish that an output device is working.
            if (!actualOutputVerified
                    && SystemClock.elapsedRealtime() - routeVerificationStartedMs
                            > ROUTE_TIMEOUT_MS) {
                if (adTestActive)
                    stopAdTest("No actual output device after 2.4s; Android route unresolved");
                else {
                    pauseAd();
                    showAdStatus("AD muted: Android reports no output route");
                }
            } else showAdStatus("AD muted until Android confirms the headphone output");
            return;
        }
        if (actual.getId() != expected.getId()) {
            String reason =
                    "Actual media route "
                            + deviceName(actual)
                            + " differs from approved "
                            + deviceName(expected);
            if (adTestActive) stopAdTest("Output stopped: " + reason);
            else {
                pauseAd();
                showAdStatus("AD muted: " + reason);
            }
            return;
        }
        if (!actualOutputVerified) {
            adPlayer.setVolume(1f, 1f);
            actualOutputVerified = true;
        }
        if (level == 0)
            showAdStatus("Headphones verified, but media volume is zero. Raise the media volume.");
        else if (adTestActive)
            showAdStatus("AD test playing · verified output: " + deviceName(actual));
        else if (listening && adPrimed)
            showAdStatus("Playing · verified output: " + deviceName(actual));
    }

    private final Runnable ticker =
            new Runnable() {
                @Override
                public void run() {
                    if (listening && clock != null) {
                        double now = SystemClock.elapsedRealtimeNanos() / 1e9;
                        SyncClock.State state;
                        double film;
                        int lockGeneration;
                        double confirmedAge;
                        double clockRate, remainingSlew;
                        SyncClock.Correction correction;
                        synchronized (clockGuard) {
                            state = clock.state(now);
                            film = clock.estimate(now);
                            lockGeneration = clock.lockGeneration();
                            confirmedAge = clock.confirmedAge(now);
                            clockRate = clock.playbackRate(now);
                            remainingSlew = clock.slewRemaining(now);
                            correction = clock.lastAction();
                        }
                        // A confirmed jump must reposition AD and SDH together. Only the new
                        // acoustic confirmation is allowed to change the shared film anchor.
                        if (lockGeneration > 0
                                && observedLockGeneration > 0
                                && lockGeneration != observedLockGeneration) adPrimed = false;
                        observedLockGeneration = lockGeneration;
                        if (state == SyncClock.State.SEARCHING
                                || state == SyncClock.State.VERIFYING)
                            timeView.setText("--:--:--.---");
                        else timeView.setText(formatTime(film));
                        CinemaUi.Tone tone;
                        int message;
                        switch (state) {
                            case LOCKED:
                                tone = CinemaUi.Tone.OK;
                                message = R.string.status_synchronized;
                                break;
                            case HOLDOVER:
                                tone = CinemaUi.Tone.WARNING;
                                message = R.string.status_holdover;
                                break;
                            case SEEK_CANDIDATE:
                                tone = CinemaUi.Tone.WARNING;
                                message = R.string.status_checking_jump;
                                break;
                            case VERIFYING:
                                tone = CinemaUi.Tone.ACTIVE;
                                message = R.string.status_verifying;
                                break;
                            default:
                                tone = CinemaUi.Tone.ACTIVE;
                                message = R.string.status_listening;
                                break;
                        }
                        String description = getString(message);
                        if (!description.equals(lastStatus)) {
                            setStatus(description, tone);
                            lastStatus = description;
                        }
                        // Confirm a new lock with a short vibration, but not the return from a
                        // checked jump.
                        if (state == SyncClock.State.LOCKED
                                && lastSyncState != null
                                && lastSyncState != SyncClock.State.LOCKED
                                && lastSyncState != SyncClock.State.SEEK_CANDIDATE) vibrateOnLock();
                        lastSyncState = state;
                        String diagnostic = latestDetails;
                        if (Double.isFinite(confirmedAge) && state != SyncClock.State.LOCKED)
                            diagnostic +=
                                    String.format(
                                            Locale.US,
                                            "\nLast verified soundtrack match: %.0f seconds ago",
                                            confirmedAge);
                        if (adPlayer != null && adPrepared && Double.isFinite(film)) {
                            double diff =
                                    adPlayer.getCurrentPosition() / 1000.0
                                            - (film + offsetSeconds());
                            diagnostic +=
                                    String.format(
                                            Locale.US,
                                            "\nAD − estimated film: %+.2f s (uncalibrated)",
                                            diff);
                        }
                        diagnostic +=
                                String.format(
                                        Locale.US,
                                        "\nClock correction: %s · remaining %+.3f s · rate %.4fx",
                                        correction,
                                        remainingSlew,
                                        clockRate);
                        metricsView.setText(diagnostic);
                        updateAd(state, film);
                        applyClockPlaybackRate(clockRate);
                        renderSubtitles(state, film);
                    }
                    inspectPlaybackRoute();
                    if (adProgressView != null) {
                        if (adPlayer != null && adPrepared) {
                            try {
                                adProgressView.setText(
                                        "AD position "
                                                + formatTime(adPlayer.getCurrentPosition() / 1000.0)
                                                + " / "
                                                + formatTime(adPlayer.getDuration() / 1000.0)
                                                + (adPlayer.isPlaying()
                                                        ? " · playing"
                                                        : " · paused"));
                            } catch (Exception e) {
                                adProgressView.setText("AD playback position unavailable");
                            }
                        } else adProgressView.setText("AD playback position: --");
                    }
                    handler.postDelayed(this, 250);
                }
            };

    private void stopListening() {
        listening = false;
        sessionGeneration++;
        stopService(new Intent(this, SessionForegroundService.class));
        handler.removeCallbacks(analyzeTick);
        if (adPlayer != null) pauseAd();
        startButton.setText(R.string.action_start_sync);
        startButton.setContentDescription(getString(R.string.action_start_sync));
        lastSyncState = null;
        setStatus(getString(R.string.status_stopped), CinemaUi.Tone.NEUTRAL);
        timeView.setText("--:--:--.---");
        lastCueDisplayed = null;
        renderSubtitles(SyncClock.State.SEARCHING, Double.NaN);
    }

    private static String formatTime(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0) return "--:--:--.---";
        long ms = Math.round(seconds * 1000);
        return String.format(
                Locale.US,
                "%02d:%02d:%02d.%03d",
                ms / 3600000,
                (ms / 60000) % 60,
                (ms / 1000) % 60,
                ms % 1000);
    }

    private void exportLog() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/csv");
        i.putExtra(Intent.EXTRA_TITLE, "in-kino-test-log.csv");
        startActivityForResult(i, EXPORT_LOG);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        int saved =
                CinemaPreferences.get(this).getInt(CinemaPreferences.SUBTITLE_SIZE, subtitleSizeSp);
        if (subtitleView != null && saved != subtitleSizeSp)
            changeSubtitleSize(saved - subtitleSizeSp);
        if (subtitleAnnouncements != null)
            subtitleAnnouncements.setChecked(
                    CinemaPreferences.get(this)
                            .getBoolean(CinemaPreferences.SUBTITLE_ANNOUNCEMENTS, false));
    }

    @Override
    protected void onPause() {
        super.onPause();
        foreground = false;
        // Preserve the confirmed film clock, microphone and AD while the screen is off.
        // The foreground service keeps the process eligible for active microphone work.
    }

    @Override
    protected void onDestroy() {
        stopListening();
        unregisterReceiver(stopReceiver);
        if (adTestActive) stopAdTest("Activity closed");
        AudioManager manager = (AudioManager) getSystemService(AUDIO_SERVICE);
        manager.unregisterAudioDeviceCallback(devicesCallback);
        handler.removeCallbacks(ticker);
        handler.removeCallbacks(analyzeTick);
        closeAd();
        background.shutdownNow();
        super.onDestroy();
    }
}
