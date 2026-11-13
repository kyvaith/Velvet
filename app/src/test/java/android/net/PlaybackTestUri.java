package android.net;

import android.os.Parcel;
import java.util.Collections;
import java.util.List;

/**
 * Fixed synthetic Uri for JVM Media3 metadata fixtures. The Android mock jar's Uri.parse()
 * returns null. This class lives in android.net solely to access Uri's package-private constructor.
 * It is test-only and does not replace Uri parsing in production or in instrumentation tests.
 */
public final class PlaybackTestUri extends Uri {
    public PlaybackTestUri() { super(); }

    @Override public boolean isHierarchical() { return true; }
    @Override public boolean isRelative() { return false; }
    @Override public String getScheme() { return "https"; }
    @Override public String getSchemeSpecificPart() { return "//playback-fixture.invalid/license"; }
    @Override public String getEncodedSchemeSpecificPart() { return getSchemeSpecificPart(); }
    @Override public String getAuthority() { return "playback-fixture.invalid"; }
    @Override public String getEncodedAuthority() { return getAuthority(); }
    @Override public String getUserInfo() { return null; }
    @Override public String getEncodedUserInfo() { return null; }
    @Override public String getHost() { return getAuthority(); }
    @Override public int getPort() { return -1; }
    @Override public String getPath() { return "/license"; }
    @Override public String getEncodedPath() { return getPath(); }
    @Override public String getQuery() { return null; }
    @Override public String getEncodedQuery() { return null; }
    @Override public String getFragment() { return null; }
    @Override public String getEncodedFragment() { return null; }
    @Override public List<String> getPathSegments() { return Collections.singletonList("license"); }
    @Override public String getLastPathSegment() { return "license"; }
    @Override public String toString() { return "https://playback-fixture.invalid/license"; }
    @Override public Builder buildUpon() { throw new UnsupportedOperationException("Fixed test Uri"); }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel destination, int flags) {
        throw new UnsupportedOperationException("JVM fixture is not parcelled");
    }
}
