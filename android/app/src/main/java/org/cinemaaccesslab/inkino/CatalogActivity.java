// SPDX-License-Identifier: Apache-2.0
package org.cinemaaccesslab.inkino;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.cinemaaccesslab.inkino.ui.CinemaUi;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Accessible catalogue browser: public HTTPS catalogue, verified package downloads, no accounts for
 * listeners, no network after successful download. HTTP only permitted in DEBUG Android manifest
 * for local development.
 */
public final class CatalogActivity extends Activity {
    public static final String EXTRA_MODE = "catalogue_mode";
    public static final String MODE_LOCAL = "my_films";
    public static final String MODE_ONLINE = "online";
    private static final String TAG = "InKinoCatalog";
    private boolean onlineMode;
    private static final int BG = CinemaUi.BACKGROUND;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private EditText searchInput;
    private String loadedServerOrigin = "";
    private LinearLayout libraryList, catalogList;
    private TextView info;
    private volatile boolean busy = false;
    private JSONArray remoteVersions = new JSONArray();

    private int dp(float d) {
        return Math.round(d * getResources().getDisplayMetrics().density);
    }

    private Button button(String txt, boolean prominent) {
        return CinemaUi.button(this, txt, prominent);
    }

    private void note(String txt) {
        ui.post(
                () -> {
                    if (info != null) info.setText(txt);
                });
    }

    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        onlineMode = MODE_ONLINE.equals(getIntent().getStringExtra(EXTRA_MODE));
        // Upgrade settings saved by the original v0.7 Catalogue Activity.
        android.content.SharedPreferences preferences = CinemaPreferences.get(this);
        if (!preferences.getBoolean("legacy_origin_migrated", false)) {
            String old =
                    getPreferences(MODE_PRIVATE).getString(CinemaPreferences.SERVER_ORIGIN, "");
            android.content.SharedPreferences.Editor migration =
                    preferences.edit().putBoolean("legacy_origin_migrated", true);
            if (preferences.getString(CinemaPreferences.SERVER_ORIGIN, "").isEmpty()
                    && !old.isEmpty()) migration.putString(CinemaPreferences.SERVER_ORIGIN, old);
            migration.apply();
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        LinearLayout root = CinemaUi.column(this);
        scroll.addView(root);
        root.addView(
                CinemaUi.screenHeader(
                        this,
                        getString(
                                onlineMode
                                        ? R.string.home_online_catalogue
                                        : R.string.home_my_films)));
        info =
                CinemaUi.caption(
                        this,
                        getString(onlineMode ? R.string.catalogue_intro : R.string.library_intro));
        info.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(-1, -2);
        infoParams.bottomMargin = dp(16);
        root.addView(info, infoParams);
        if (onlineMode) {
            searchInput = new EditText(this);
            searchInput.setSingleLine(true);
            searchInput.setHint(R.string.search_hint);
            searchInput.setContentDescription(getString(R.string.search_description));
            CinemaUi.styleInput(searchInput);
            root.addView(searchInput, CinemaUi.listItem(this));
            searchInput.addTextChangedListener(
                    new android.text.TextWatcher() {
                        public void beforeTextChanged(
                                CharSequence s, int start, int count, int after) {}

                        public void onTextChanged(
                                CharSequence s, int start, int before, int count) {
                            renderRemote();
                        }

                        public void afterTextChanged(android.text.Editable e) {}
                    });
            Button refresh = button(getString(R.string.refresh), false);
            refresh.setContentDescription(getString(R.string.refresh_description));
            refresh.setOnClickListener(v -> fetchCatalog());
            Button settings = button(getString(R.string.server_settings), false);
            settings.setOnClickListener(
                    v -> startActivity(new Intent(this, SettingsActivity.class)));
            root.addView(CinemaUi.pair(this, refresh, settings));
            root.addView(CinemaUi.sectionHeading(this, getString(R.string.available_films)));
            catalogList = new LinearLayout(this);
            catalogList.setOrientation(LinearLayout.VERTICAL);
            root.addView(catalogList);
        } else {
            libraryList = new LinearLayout(this);
            libraryList.setOrientation(LinearLayout.VERTICAL);
            root.addView(libraryList);
        }
        setContentView(scroll);
        CinemaUi.edgeToEdge(this, scroll);
        if (onlineMode) {
            if (serverOrigin().isEmpty()) showDemoOffer();
            else fetchCatalog();
        } else renderLocal();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!onlineMode) renderLocal();
        else if (!busy && !serverOrigin().isEmpty() && !serverOrigin().equals(loadedServerOrigin))
            fetchCatalog();
    }

    /** Without a server, offer the demo catalogue so a first-time tester has something to try. */
    private void showDemoOffer() {
        info.setText(R.string.set_server_first);
        catalogList.removeAllViews();
        Button demo = button(getString(R.string.use_demo_catalogue), true);
        demo.setOnClickListener(
                v -> {
                    CinemaPreferences.get(this)
                            .edit()
                            .putString(
                                    CinemaPreferences.SERVER_ORIGIN,
                                    CinemaPreferences.DEMO_SERVER_ORIGIN)
                            .apply();
                    catalogList.removeAllViews();
                    fetchCatalog();
                });
        catalogList.addView(demo, CinemaUi.stacked(this));
    }

    private String serverOrigin() {
        return CinemaPreferences.get(this).getString(CinemaPreferences.SERVER_ORIGIN, "").trim();
    }

    private String safeOrigin(String input) throws Exception {
        URI uri = new URI(input.trim());
        String scheme = uri.getScheme();
        if (scheme == null
                || (!scheme.equals("https") && !scheme.equals("http"))
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || !isPlainPath(uri.getRawPath())
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null)
            throw new IOException(getString(R.string.error_server_address));
        if (uri.getPort() > 65535) throw new IOException(getString(R.string.error_invalid_port));
        return input.trim().replaceAll("/+$", "");
    }

    /** Empty, "/", or plain path segments such as "/in-kino" (static hosting under a path). */
    static boolean isPlainPath(String path) {
        return path == null
                || path.matches("(/[A-Za-z0-9._~-]+)*/?") && !path.matches(".*/\\.{1,2}(/.*)?");
    }

    private static HttpURLConnection request(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(20000);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("Accept", "application/json");
        return conn;
    }

    private static byte[] readBounded(InputStream input, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] bytes = new byte[8192];
        int n;
        while ((n = input.read(bytes)) != -1) {
            if (out.size() + n > max) throw new IOException("Catalogue too large");
            out.write(bytes, 0, n);
        }
        return out.toByteArray();
    }

    private void fetchCatalog() {
        if (busy) return;
        final String origin;
        try {
            origin = safeOrigin(serverOrigin());
        } catch (Exception e) {
            note(e.getMessage());
            return;
        }
        busy = true;
        note(getString(R.string.loading_catalogue));
        worker.execute(
                () -> {
                    try {
                        HttpURLConnection con = request(origin + "/api/catalog");
                        if (con.getResponseCode() != 200)
                            throw new ConnectionErrors.HttpStatus(
                                    "Catalogue", con.getResponseCode());
                        JSONObject content =
                                new JSONObject(
                                        new String(
                                                readBounded(con.getInputStream(), 5 * 1024 * 1024),
                                                StandardCharsets.UTF_8));
                        if (!"in-kino.catalog.v1".equals(content.optString("schema")))
                            throw new IOException("Unsupported catalogue format");
                        JSONArray films = content.getJSONArray("films");
                        JSONArray result = new JSONArray();
                        for (int i = 0; i < films.length(); i++) {
                            JSONObject film = films.getJSONObject(i);
                            JSONArray versions = film.getJSONArray("versions");
                            for (int j = 0; j < versions.length(); j++) {
                                JSONObject ver = versions.getJSONObject(j);
                                JSONObject row = new JSONObject();
                                row.put("title", film.getString("title"));
                                row.put("version", ver);
                                result.put(row);
                            }
                        }
                        ui.post(
                                () -> {
                                    remoteVersions = result;
                                    loadedServerOrigin = origin;
                                    CinemaPreferences.get(this)
                                            .edit()
                                            .putString(CinemaPreferences.SERVER_ORIGIN, origin)
                                            .apply();
                                    busy = false;
                                    renderRemote();
                                    note(
                                            getResources()
                                                    .getQuantityString(
                                                            R.plurals.films_available,
                                                            result.length(),
                                                            result.length()));
                                });
                    } catch (Exception ex) {
                        ui.post(
                                () -> {
                                    busy = false;
                                    note(getString(R.string.catalogue_error, explain(ex)));
                                });
                    }
                });
    }

    /** A sentence a user can act on, instead of the exception's internal message. */
    private String explain(Exception ex) {
        Log.w(TAG, "Network request failed", ex);
        switch (ConnectionErrors.classify(ex)) {
            case NO_SERVER:
                return getString(R.string.error_no_server);
            case TIMEOUT:
                return getString(R.string.error_timeout);
            case UNKNOWN_HOST:
                return getString(R.string.error_unknown_host);
            case NOT_FOUND:
                return getString(R.string.error_not_a_catalogue);
            case HTTP_ERROR:
                return getString(R.string.error_http, ConnectionErrors.httpCode(ex));
            case HTTPS_REQUIRED:
                return getString(R.string.error_https_required);
            case SECURE_CONNECTION:
                return getString(R.string.error_secure_connection);
            default:
                String message = ex.getMessage();
                return message != null ? message : ex.getClass().getSimpleName();
        }
    }

    private File base() {
        File f = new File(getFilesDir(), "packages");
        f.mkdirs();
        return f;
    }

    private static String identity(String id) throws IOException {
        if (!id.matches("(?:ver|asset|film)_[0-9a-fA-F-]{36}"))
            throw new IOException("Invalid package identifier");
        return id;
    }

    private static JSONObject readInfo(File folder) throws Exception {
        try (FileInputStream in = new FileInputStream(new File(folder, "installed.json"))) {
            return new JSONObject(new String(readBounded(in, 1024 * 1024), StandardCharsets.UTF_8));
        }
    }

    private void renderLocal() {
        if (libraryList == null) return;
        libraryList.removeAllViews();
        File[] dirs = base().listFiles(File::isDirectory);
        int count = 0;
        if (dirs != null)
            for (File dir : dirs) {
                if (!new File(dir, "installed.json").isFile()) continue;
                try {
                    JSONObject pkg = readInfo(dir);
                    String title = pkg.getString("title"), label = pkg.getString("label");
                    View row =
                            CinemaUi.filmRow(
                                    this, title, label, getString(R.string.status_open), true);
                    row.setContentDescription(
                            getString(R.string.library_row_description, title, label));
                    row.setOnClickListener(v -> openPackage(dir));
                    libraryList.addView(row, CinemaUi.listItem(this));
                    count++;
                } catch (Exception e) {
                    // A damaged package must not hide the rest of the library.
                    Log.w(TAG, "Skipping unreadable package " + dir.getName(), e);
                }
            }
        if (count == 0)
            libraryList.addView(
                    CinemaUi.emptyState(this, getString(R.string.library_empty)),
                    CinemaUi.listItem(this));
    }

    private void renderRemote() {
        if (catalogList == null) return;
        catalogList.removeAllViews();
        String match =
                searchInput == null
                        ? ""
                        : searchInput.getText().toString().trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (int i = 0; i < remoteVersions.length(); i++)
            try {
                JSONObject item = remoteVersions.getJSONObject(i),
                        v = item.getJSONObject("version");
                String title = item.getString("title"), vers = v.getString("label");
                if (!title.toLowerCase(Locale.ROOT).contains(match)) continue;
                JSONArray assets = v.getJSONArray("assets");
                boolean hasAd = false, hasSdh = false;
                for (int j = 0; j < assets.length(); j++) {
                    String kind = assets.getJSONObject(j).getString("kind");
                    hasAd |= kind.equals("ad");
                    hasSdh |= kind.equals("sdh");
                }
                String tracks =
                        hasAd && hasSdh
                                ? getString(R.string.tracks_ad_sdh)
                                : hasAd
                                        ? getString(R.string.tracks_ad)
                                        : hasSdh ? getString(R.string.tracks_sdh) : "";
                String detail = tracks.isEmpty() ? vers : vers + " · " + tracks;
                boolean installed =
                        new File(base(), v.getString("id") + "/installed.json").isFile();
                View row =
                        CinemaUi.filmRow(
                                this,
                                title,
                                detail,
                                getString(
                                        installed
                                                ? R.string.status_on_phone
                                                : R.string.status_download),
                                !installed);
                row.setContentDescription(
                        getString(
                                installed
                                        ? R.string.catalogue_row_installed
                                        : R.string.catalogue_row_download,
                                title,
                                detail));
                row.setOnClickListener(
                        view -> {
                            if (installed) openPackage(new File(base(), v.optString("id")));
                            else download(item);
                        });
                catalogList.addView(row, CinemaUi.listItem(this));
                shown++;
            } catch (Exception e) {
                // One malformed catalogue entry must not hide the others.
                Log.w(TAG, "Skipping malformed catalogue entry " + i, e);
            }
        if (shown == 0)
            catalogList.addView(
                    CinemaUi.emptyState(
                            this,
                            remoteVersions.length() == 0
                                    ? getString(R.string.catalogue_empty)
                                    : getString(R.string.catalogue_no_match)),
                    CinemaUi.listItem(this));
    }

    private static void shaCopy(
            HttpURLConnection conn,
            File file,
            long expectedSize,
            String wanted,
            Runnable onProgress)
            throws Exception {
        int rc = conn.getResponseCode();
        if (rc != 200) throw new ConnectionErrors.HttpStatus("Asset", rc);
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        long size = 0;
        try (InputStream in = conn.getInputStream();
                OutputStream out = new FileOutputStream(file)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) {
                size += n;
                if (size > expectedSize) throw new IOException("Asset exceeds declared size");
                out.write(buf, 0, n);
                sha.update(buf, 0, n);
            }
        }
        if (size != expectedSize) throw new IOException("Download size mismatch");
        StringBuilder actual = new StringBuilder();
        for (byte b : sha.digest()) actual.append(String.format(Locale.ROOT, "%02x", b & 255));
        if (!actual.toString().equalsIgnoreCase(wanted))
            throw new IOException("SHA-256 verification failed");
    }

    private void download(JSONObject item) {
        if (busy) return;
        final String origin;
        try {
            origin = safeOrigin(serverOrigin());
        } catch (Exception e) {
            note(e.getMessage());
            return;
        }
        busy = true;
        note(getString(R.string.preparing_download));
        worker.execute(
                () -> {
                    File stage = null;
                    try {
                        JSONObject version = item.getJSONObject("version");
                        String vid = identity(version.getString("id"));
                        JSONArray assets = version.getJSONArray("assets");
                        stage = new File(base(), vid + ".partial");
                        if (stage.exists()) deleteTree(stage);
                        if (!stage.mkdirs()) throw new IOException("Cannot create download folder");
                        JSONObject paths = new JSONObject();
                        int fps = 0;
                        boolean gotFp = false, gotContent = false;
                        for (int j = 0; j < assets.length(); j++) {
                            JSONObject a = assets.getJSONObject(j);
                            String id = identity(a.getString("id")), kind = a.getString("kind");
                            if (!kind.equals("fingerprint")
                                    && !kind.equals("ad")
                                    && !kind.equals("sdh"))
                                throw new IOException("Unknown asset type");
                            if (kind.equals("fingerprint")) {
                                if (gotFp) throw new IOException("Duplicate fingerprint");
                                gotFp = true;
                            } else {
                                if (paths.has(kind))
                                    throw new IOException(
                                            "Multiple "
                                                    + kind
                                                    + " tracks not supported by this client");
                                gotContent = true;
                            }
                            String basename = a.getString("filename");
                            String ext =
                                    basename.substring(basename.lastIndexOf('.') + 1)
                                            .toLowerCase(Locale.ROOT);
                            if (kind.equals("fingerprint") && !ext.equals("csref")
                                    || kind.equals("ad")
                                            && !(ext.equals("wav") || ext.equals("m4a"))
                                    || kind.equals("sdh")
                                            && !(ext.equals("srt") || ext.equals("vtt")))
                                throw new IOException("Invalid asset type");
                            String endpoint = a.getString("url");
                            if (!endpoint.equals("/api/assets/" + id + "/download"))
                                throw new IOException("Unsafe asset URL");
                            String saveName = kind + "." + ext;
                            File dest = new File(stage, saveName);
                            long expected = a.getLong("bytes");
                            if (expected < 1 || expected > 800_000_000L)
                                throw new IOException("Invalid download size");
                            final int done = j + 1, all = assets.length();
                            note(getString(R.string.downloading_asset, done, all));
                            HttpURLConnection connection = request(origin + endpoint);
                            shaCopy(connection, dest, expected, a.getString("sha256"), null);
                            connection.disconnect();
                            paths.put(kind, saveName);
                        }
                        if (!gotFp || !gotContent)
                            throw new IOException("Missing fingerprint or accessibility track");
                        JSONObject saved = new JSONObject();
                        saved.put("version_id", vid);
                        saved.put("title", item.getString("title"));
                        saved.put("label", version.getString("label"));
                        saved.put("files", paths);
                        try (OutputStream out =
                                new FileOutputStream(new File(stage, "installed.json"))) {
                            out.write(saved.toString().getBytes(StandardCharsets.UTF_8));
                        }
                        File ready = new File(base(), vid);
                        if (ready.exists()) deleteTree(ready);
                        if (!stage.renameTo(ready))
                            throw new IOException("Cannot finalize package");
                        busy = false;
                        ui.post(
                                () -> {
                                    note(getString(R.string.download_complete));
                                    renderLocal();
                                    renderRemote();
                                });
                    } catch (Exception ex) {
                        if (stage != null) deleteTree(stage);
                        busy = false;
                        note(getString(R.string.download_failed, explain(ex)));
                    }
                });
    }

    private static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        f.delete();
    }

    private void openPackage(File folder) {
        try {
            JSONObject pkg = readInfo(folder);
            JSONObject files = pkg.getJSONObject("files");
            File fp = new File(folder, files.getString("fingerprint"));
            if (!fp.isFile()) throw new IOException("Missing fingerprint");
            Intent player = new Intent(this, MainActivity.class);
            player.putExtra(MainActivity.EXTRA_FINGERPRINT_PATH, fp.getAbsolutePath());
            if (files.has("ad"))
                player.putExtra(
                        MainActivity.EXTRA_AD_PATH,
                        new File(folder, files.getString("ad")).getAbsolutePath());
            if (files.has("sdh"))
                player.putExtra(
                        MainActivity.EXTRA_SDH_PATH,
                        new File(folder, files.getString("sdh")).getAbsolutePath());
            player.putExtra(MainActivity.EXTRA_TITLE, pkg.optString("title"));
            startActivity(player);
        } catch (Exception e) {
            note(getString(R.string.open_failed, e.getMessage()));
        }
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
