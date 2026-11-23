// SPDX-License-Identifier: Apache-2.0
package org.cinemaaccesslab.inkino;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.cinemaaccesslab.inkino.core.FingerprintReference;
import org.cinemaaccesslab.inkino.subtitles.SubtitleTimeline;
import org.cinemaaccesslab.inkino.ui.CinemaUi;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manual mode: choose a fingerprint, audio description, and subtitles from the phone's storage,
 * check each file, and only then open the player. The player itself has no file controls.
 */
public final class ManualSetupActivity extends Activity {
    private static final String TAG = "InKinoManual";
    private static final int PICK_FINGERPRINT = 1, PICK_AD = 2, PICK_SDH = 3;
    // Tracks that differ from the film by more than this are probably for another cut.
    private static final double MISMATCH_SECONDS = 60;

    private enum Kind {
        FINGERPRINT,
        AD,
        SDH
    }

    /** One file row: what it is, the chosen file, and the result of checking it. */
    private static final class Slot {
        final Kind kind;
        final String extra;
        TextView status;
        Uri uri;
        boolean valid;
        double durationSeconds = Double.NaN;

        Slot(Kind kind, String extra) {
            this.kind = kind;
            this.extra = extra;
        }
    }

    private final Slot fingerprint = new Slot(Kind.FINGERPRINT, MainActivity.EXTRA_FINGERPRINT_URI);
    private final Slot ad = new Slot(Kind.AD, MainActivity.EXTRA_AD_URI);
    private final Slot sdh = new Slot(Kind.SDH, MainActivity.EXTRA_SDH_URI);
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView warnings;
    private Button continueButton;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(CinemaUi.BACKGROUND);
        scroll.setFillViewport(true);
        LinearLayout root = CinemaUi.column(this);
        scroll.addView(root);
        root.addView(CinemaUi.screenHeader(this, getString(R.string.home_manual_player)));
        root.addView(CinemaUi.body(this, getString(R.string.manual_intro)));

        addSlot(root, fingerprint, R.string.manual_fingerprint, PICK_FINGERPRINT, "*/*");
        addSlot(root, ad, R.string.manual_ad, PICK_AD, "audio/*");
        addSlot(root, sdh, R.string.manual_sdh, PICK_SDH, "*/*");

        warnings = CinemaUi.text(this, "", 17, CinemaUi.WARNING, false);
        warnings.setVisibility(View.GONE);
        warnings.setPadding(0, CinemaUi.dp(this, 16), 0, 0);
        root.addView(warnings);

        root.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));
        continueButton = CinemaUi.button(this, getString(R.string.manual_continue), true);
        LinearLayout.LayoutParams continueParams = CinemaUi.stacked(this);
        continueParams.topMargin = CinemaUi.dp(this, CinemaUi.SECTION_GAP);
        root.addView(continueButton, continueParams);
        continueButton.setOnClickListener(v -> openPlayer());
        updateContinue();

        setContentView(scroll);
        CinemaUi.edgeToEdge(this, scroll);

        if (state != null) {
            for (Slot slot : new Slot[] {fingerprint, ad, sdh}) {
                String saved = state.getString(slot.extra);
                if (saved != null) check(slot, Uri.parse(saved));
            }
        }
    }

    private void addSlot(LinearLayout root, Slot slot, int label, int request, String mime) {
        root.addView(CinemaUi.sectionHeading(this, getString(label)));
        slot.status =
                CinemaUi.text(
                        this, getString(R.string.manual_not_chosen), 17, CinemaUi.MUTED, false);
        root.addView(slot.status);
        Button choose = CinemaUi.button(this, getString(R.string.manual_choose), false);
        choose.setContentDescription(
                getString(R.string.manual_choose_description, getString(label)));
        choose.setOnClickListener(
                v -> {
                    Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    pick.addCategory(Intent.CATEGORY_OPENABLE);
                    pick.setType(mime);
                    pick.addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    startActivityForResult(pick, request);
                });
        root.addView(choose, CinemaUi.stacked(this));
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver()
                    .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException notPersistable) {
            // The picker still grants access while the app is running.
        }
        if (request == PICK_FINGERPRINT) check(fingerprint, uri);
        else if (request == PICK_AD) check(ad, uri);
        else if (request == PICK_SDH) check(sdh, uri);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        for (Slot slot : new Slot[] {fingerprint, ad, sdh}) {
            if (slot.uri != null) out.putString(slot.extra, slot.uri.toString());
        }
    }

    /** Reads the file in the background and reports what it contains, or why it can't be used. */
    private void check(Slot slot, Uri uri) {
        slot.uri = uri;
        slot.valid = false;
        slot.durationSeconds = Double.NaN;
        String filename = displayName(uri);
        slot.status.setText(getString(R.string.manual_checking, filename));
        slot.status.setTextColor(CinemaUi.MUTED);
        updateContinue();
        background.execute(
                () -> {
                    String summary;
                    double duration;
                    try {
                        switch (slot.kind) {
                            case FINGERPRINT:
                                FingerprintReference reference;
                                try (InputStream in = open(uri)) {
                                    reference = FingerprintReference.read(in);
                                }
                                duration = reference.durationSeconds;
                                summary =
                                        getString(
                                                R.string.manual_fingerprint_ok,
                                                filename,
                                                clock(duration));
                                break;
                            case SDH:
                                SubtitleTimeline timeline;
                                try (InputStream in = open(uri)) {
                                    timeline =
                                            SubtitleTimeline.parse(
                                                    new InputStreamReader(
                                                            in, StandardCharsets.UTF_8));
                                }
                                if (timeline.size() == 0)
                                    throw new IOException(getString(R.string.manual_no_cues));
                                List<SubtitleTimeline.Cue> cues = timeline.cues();
                                duration = cues.get(cues.size() - 1).endMs / 1000.0;
                                summary =
                                        getString(
                                                R.string.manual_sdh_ok,
                                                filename,
                                                timeline.size(),
                                                clock(duration));
                                break;
                            default:
                                duration = audioDuration(uri);
                                summary =
                                        getString(R.string.manual_ad_ok, filename, clock(duration));
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Cannot use " + uri, e);
                        String reason = e.getMessage() != null ? e.getMessage() : e.toString();
                        ui.post(
                                () -> {
                                    if (slot.uri != uri) return;
                                    slot.status.setText(
                                            getString(
                                                    R.string.manual_file_error, filename, reason));
                                    slot.status.setTextColor(CinemaUi.ERROR);
                                    slot.status.announceForAccessibility(slot.status.getText());
                                    updateContinue();
                                });
                        return;
                    }
                    final String text = summary;
                    final double seconds = duration;
                    ui.post(
                            () -> {
                                if (slot.uri != uri) return;
                                slot.valid = true;
                                slot.durationSeconds = seconds;
                                slot.status.setText(text);
                                slot.status.setTextColor(CinemaUi.TEXT);
                                slot.status.announceForAccessibility(text);
                                updateContinue();
                            });
                });
    }

    private InputStream open(Uri uri) throws IOException {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException(getString(R.string.manual_cannot_open));
        return in;
    }

    private double audioDuration(Uri uri) throws IOException {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, uri);
            String audio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO);
            String ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (!"yes".equals(audio) || ms == null)
                throw new IOException(getString(R.string.manual_not_audio));
            return Long.parseLong(ms) / 1000.0;
        } catch (RuntimeException e) {
            throw new IOException(getString(R.string.manual_not_audio), e);
        } finally {
            try {
                retriever.release();
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "Metadata reader release failed", e);
            }
        }
    }

    /** Continue needs a fingerprint and at least one track; mismatched lengths only warn. */
    private void updateContinue() {
        boolean ready = fingerprint.valid && (ad.valid || sdh.valid);
        continueButton.setEnabled(ready);
        continueButton.setAlpha(ready ? 1f : 0.5f);

        List<String> found = new ArrayList<>();
        if (fingerprint.valid) {
            double film = fingerprint.durationSeconds;
            if (ad.valid && Math.abs(ad.durationSeconds - film) > MISMATCH_SECONDS)
                found.add(
                        getString(
                                R.string.manual_ad_length_warning,
                                clock(ad.durationSeconds),
                                clock(film)));
            if (sdh.valid && sdh.durationSeconds > film + MISMATCH_SECONDS)
                found.add(getString(R.string.manual_sdh_length_warning, clock(film)));
        }
        String text = String.join("\n\n", found);
        if (!text.contentEquals(warnings.getText())) {
            warnings.setText(text);
            warnings.setVisibility(found.isEmpty() ? View.GONE : View.VISIBLE);
            if (!found.isEmpty()) warnings.announceForAccessibility(text);
        }
    }

    private void openPlayer() {
        Intent player = new Intent(this, MainActivity.class);
        for (Slot slot : new Slot[] {fingerprint, ad, sdh}) {
            if (slot.valid) player.putExtra(slot.extra, slot.uri.toString());
        }
        player.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(player);
    }

    private String displayName(Uri uri) {
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
        return getString(R.string.selected_file);
    }

    /** Film length as h:mm:ss or m:ss. */
    static String clock(double seconds) {
        long s = Math.round(seconds);
        if (s >= 3600)
            return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    @Override
    protected void onDestroy() {
        background.shutdownNow();
        super.onDestroy();
    }
}
