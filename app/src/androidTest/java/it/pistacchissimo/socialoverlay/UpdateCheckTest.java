// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Marco Rinaldi (MarcorOnline)

package it.pistacchissimo.socialoverlay;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Offline error/home checks by default; an explicit live flag checks GitHub from Android. */
public final class UpdateCheckTest extends Instrumentation {
    private static final String RELEASE_URL = AboutActivity.PROJECT_URL + "/releases/tag/v99.0.0";
    private boolean live;
    private Consumer<UpdateChecker.Release> liveResult;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        live = arguments != null && Boolean.parseBoolean(arguments.getString("live", "false"));
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (live) {
                UpdateChecker.Release release = verifyLive();
                result.putString("stream", "PASS: live Android HTTPS update check accepted version "
                        + release.versionName + " from GitHub and cached the result.\n");
            } else {
                verify();
                result.putString("stream", "PASS: weekly cadence, version comparisons, cached home banner, "
                    + "404/403/429/503, redirects, Cloudflare HTML, bad JSON, unsafe links, "
                    + "oversized streams, offline and timeouts.\n");
            }
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + error + "\n");
        }
        finish(result.getString("stream").startsWith("PASS") ? -1 : 0, result);
    }

    private UpdateChecker.Release verifyLive() throws Exception {
        Context context = getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE);
        boolean hadAttempt = preferences.contains("last_attempt");
        long savedAttempt = preferences.getLong("last_attempt", 0);
        String savedRelease = preferences.getString("release", null);
        UpdateChecker.Release[] received = new UpdateChecker.Release[1];
        CountDownLatch done = new CountDownLatch(1);
        try {
            preferences.edit().remove("last_attempt").remove("release").commit();
            liveResult = release -> {
                if (release != null) {
                    received[0] = release;
                    done.countDown();
                }
            };
            runOnMainSync(() -> UpdateChecker.check(context, liveResult));
            check(done.await(25, TimeUnit.SECONDS), "live endpoint did not return an accepted manifest");
            UpdateChecker.Release cached = UpdateChecker.parse(preferences.getString("release", ""));
            check(cached != null && cached.versionName.equals(received[0].versionName)
                    && preferences.getLong("last_attempt", 0) > 0, "live result persisted");
            return received[0];
        } finally {
            liveResult = null;
            if (received[0] == null) {
                SharedPreferences.Editor restore = preferences.edit();
                if (hadAttempt) restore.putLong("last_attempt", savedAttempt);
                else restore.remove("last_attempt");
                if (savedRelease != null) restore.putString("release", savedRelease);
                else restore.remove("release");
                restore.commit();
            }
        }
    }

    private void verify() throws Exception {
        check("https://api.github.com/repos/MarcorOnline/SocialSafeArea/releases/latest"
                .equals(UpdateChecker.RELEASE_URL), "query renamed repository directly");
        check("https://github.com/MarcorOnline/SocialSafeArea".equals(AboutActivity.PROJECT_URL),
                "release links and project page use renamed repository");
        long now = 2 * UpdateChecker.INTERVAL_MS;
        check(UpdateChecker.isDue(0, now), "first opening");
        check(!UpdateChecker.isDue(now, now), "same opening");
        check(!UpdateChecker.isDue(now, now + UpdateChecker.INTERVAL_MS - 1), "less than a week");
        check(UpdateChecker.isDue(now, now + UpdateChecker.INTERVAL_MS), "one week");
        check(UpdateChecker.isDue(now, now - 1), "clock moved backwards");

        String valid = manifest("v99.0.0", false, false, RELEASE_URL);
        UpdateChecker.Release release = UpdateChecker.parse(valid);
        check(release != null && release.isNewerThan("98.0.0"), "new version");
        check(!release.isNewerThan("99.0.0") && !release.isNewerThan("100.0.0"), "equal/older version");
        check(!release.isNewerThan(null) && !release.isNewerThan("dev"), "unknown installed version");
        check(stable("1.10.0").isNewerThan("1.9.9"), "compare minor numerically");
        check(stable("1.2.10").isNewerThan("1.2.9"), "compare patch numerically");
        check(stable("2.0.0").isNewerThan("1.99.99"), "major takes precedence");
        check(UpdateChecker.parse(manifest("99.0.0", false, false,
                AboutActivity.PROJECT_URL + "/releases/tag/99.0.0")) != null, "optional tag prefix");
        String[] invalid = {"", "<html>Just a moment...</html>", "{}", "[]", "null", "{broken",
                valid + " trailing", valid + "{}", manifest("v99.0.0", true, false, RELEASE_URL),
                manifest("v99.0.0", false, true, RELEASE_URL), manifest("v99.0.0", "false", false, RELEASE_URL),
                manifest("v99.0.0", false, "false", RELEASE_URL), manifest(99, false, false, RELEASE_URL),
                manifest("", false, false, RELEASE_URL), manifest("v99.0", false, false, RELEASE_URL),
                manifest("v99.00.0", false, false, RELEASE_URL), manifest("v-1.0.0", false, false, RELEASE_URL),
                manifest("v2147483648.0.0", false, false, RELEASE_URL),
                manifest("v99.0.0-beta", false, false, RELEASE_URL),
                manifest("v99.0.0", false, false, "http://github.com/MarcorOnline/releases/tag/v99.0.0"),
                manifest("v99.0.0", false, false, "https://github.com.evil.example/releases/tag/v99.0.0"),
                manifest("v99.0.0", false, false, "https://evil.example/update.apk"),
                manifest("v99.0.0", false, false, "https://github.com/MarcorOnline/OtherRepo/releases/tag/v99.0.0"),
                manifest("v99.0.0", false, false, AboutActivity.PROJECT_URL + "/releases/tag/../../evil"),
                manifest("v99.0.0", false, false, RELEASE_URL + "?redirect=evil"),
                manifest("v99.0.0", false, false, RELEASE_URL + "#fragment"),
                manifest("v98.0.0", false, false, RELEASE_URL),
                "{\"tag_name\":\"v99.0.0\",\"html_url\":\"" + RELEASE_URL + "\"}"};
        for (String text : invalid) check(UpdateChecker.parse(text) == null, "reject invalid manifest");
        check(UpdateChecker.parse(null) == null, "null input");
        check(UpdateChecker.parse(valid + "\n\t ") != null, "trailing whitespace");

        FakeConnection ok = new FakeConnection(200, "application/json; charset=utf-8", valid);
        check(UpdateChecker.fetch(ok) != null && ok.closed && ok.disconnected, "valid response closes resources");
        check(!ok.getInstanceFollowRedirects() && !ok.getUseCaches()
                && ok.getConnectTimeout() > 0 && ok.getReadTimeout() > 0
                && "SafeArea-UpdateCheck".equals(ok.getRequestProperty("User-Agent")), "bounded private request");
        for (int status : new int[] {204, 301, 302, 403, 404, 429, 500, 503}) {
            FakeConnection failed = new FakeConnection(status, "text/html", "<html>Cloudflare</html>");
            check(UpdateChecker.fetch(failed) == null && failed.disconnected && !failed.opened,
                    "ignore HTTP error/redirect before reading body");
        }
        for (String type : new String[] {null, "text/html", "text/plain"}) {
            check(UpdateChecker.fetch(new FakeConnection(200, type, valid)) == null, "reject non-JSON response");
        }
        check(UpdateChecker.fetch(new FakeConnection(200, "application/json", "<html>Challenge</html>")) == null,
                "HTML mislabeled as JSON");
        check(UpdateChecker.fetch(new FakeConnection(200, "application/json", "{}")) == null, "invalid JSON schema");
        String huge = new String(new char[UpdateChecker.MAX_BYTES + 1]).replace('\0', ' ');
        FakeConnection oversized = new FakeConnection(200, "application/json", huge);
        check(UpdateChecker.fetch(oversized) == null && oversized.closed && oversized.disconnected,
                "bound unknown-length stream");
        FakeConnection knownSize = new FakeConnection(200, "application/json", valid);
        knownSize.length = UpdateChecker.MAX_BYTES + 1;
        check(UpdateChecker.fetch(knownSize) == null && !knownSize.opened, "reject oversized Content-Length");
        for (IOException error : new IOException[] {new IOException("offline"), new SocketTimeoutException("timeout")}) {
            FakeConnection failed = new FakeConnection(200, "application/json", valid);
            failed.failure = error;
            check(UpdateChecker.fetch(failed) == null && failed.disconnected, "network errors cannot escape");
        }
        FakeConnection brokenStream = new FakeConnection(200, "application/json", valid);
        brokenStream.failReading = true;
        check(UpdateChecker.fetch(brokenStream) == null && brokenStream.closed && brokenStream.disconnected,
                "read timeout closes resources");

        verifyHome(valid);
    }

    private void verifyHome(String json) {
        Context context = getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE);
        boolean hadAttempt = preferences.contains("last_attempt");
        long savedAttempt = preferences.getLong("last_attempt", 0);
        String savedRelease = preferences.getString("release", null);
        SharedPreferences setup = context.getSharedPreferences("social_overlay", Context.MODE_PRIVATE);
        boolean hadWizard = setup.contains("wizard_complete");
        boolean savedWizard = setup.getBoolean("wizard_complete", false);
        MainActivity activity = null;
        try {
            setup.edit().putBoolean("wizard_complete", true).commit();
            long attempt = System.currentTimeMillis();
            preferences.edit().putLong("last_attempt", attempt).putString("release", json).commit();
            activity = (MainActivity) startActivitySync(new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            waitForIdleSync();
            MainActivity screen = activity;
            Throwable[] failure = new Throwable[1];
            runOnMainSync(() -> {
                try {
                check("Safe Area".equals(screen.getString(R.string.app_name)), "short launcher name");
                ArrayList<View> buttons = new ArrayList<>();
                View root = screen.getWindow().getDecorView();
                root.findViewsWithText(buttons, screen.getString(R.string.update_available, "99.0.0"),
                        View.FIND_VIEWS_WITH_TEXT);
                check(buttons.size() == 1 && buttons.get(0).getVisibility() == View.VISIBLE,
                        "cached update appears on home without networking");
                for (int i = 0; i < 3; i++) UpdateChecker.check(context, ignored -> { });
                check(preferences.getLong("last_attempt", 0) == attempt, "repeated openings preserve weekly throttle");
                } catch (Throwable error) {
                    failure[0] = error;
                }
            });
            if (failure[0] != null) throw new AssertionError(failure[0]);
        } finally {
            if (activity != null) {
                MainActivity screen = activity;
                runOnMainSync(screen::finish);
                waitForIdleSync();
            }
            SharedPreferences.Editor restoreWizard = setup.edit();
            if (hadWizard) restoreWizard.putBoolean("wizard_complete", savedWizard);
            else restoreWizard.remove("wizard_complete");
            restoreWizard.commit();
            SharedPreferences.Editor restore = preferences.edit();
            if (hadAttempt) restore.putLong("last_attempt", savedAttempt);
            else restore.remove("last_attempt");
            if (savedRelease != null) restore.putString("release", savedRelease);
            else restore.remove("release");
            restore.commit();
        }
    }

    private static UpdateChecker.Release stable(String version) throws Exception {
        return UpdateChecker.parse(manifest("v" + version, false, false,
                AboutActivity.PROJECT_URL + "/releases/tag/v" + version));
    }

    private static String manifest(Object tag, Object draft, Object prerelease, String url) throws Exception {
        return new JSONObject().put("tag_name", tag).put("draft", draft)
                .put("prerelease", prerelease).put("html_url", url).toString();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class FakeConnection extends HttpURLConnection {
        final int status;
        final String type;
        final byte[] body;
        long length = -1;
        boolean opened;
        boolean closed;
        boolean disconnected;
        boolean failReading;
        IOException failure;

        FakeConnection(int status, String type, String body) throws Exception {
            super(new URL(UpdateChecker.RELEASE_URL));
            this.status = status;
            this.type = type;
            this.body = body.getBytes(StandardCharsets.UTF_8);
        }

        @Override public int getResponseCode() throws IOException {
            if (failure != null) throw failure;
            return status;
        }
        @Override public String getContentType() { return type; }
        @Override public long getContentLengthLong() { return length; }
        @Override public InputStream getInputStream() {
            opened = true;
            return new InputStream() {
                final ByteArrayInputStream stream = new ByteArrayInputStream(body);
                @Override public int read() throws IOException {
                    if (failReading) throw new SocketTimeoutException("read timeout");
                    return stream.read();
                }
                @Override public void close() { closed = true; }
            };
        }
        @Override public void connect() { }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
    }
}
