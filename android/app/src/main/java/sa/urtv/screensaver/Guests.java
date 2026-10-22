package sa.urtv.screensaver;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The visitors' presentation («عرض الضيوف»), kept on the TV. It lives in the repository's "guests" release as
 * two files: guests.mp4, and guests.txt (line 1: slide start times in ms, comma-separated; line 2: a stamp
 * of the video). The hourly job fetches guests.txt and downloads the video only when the text changed, so a
 * new presentation reaches the TV without touching it.
 */
final class Guests {
    private static final String BASE = "https://github.com/saleh1yyicloud-cmd/UrTV/releases/download/guests/";

    private Guests() {}

    static File video(Context c) { return new File(c.getFilesDir(), "guests.mp4"); }
    static File times(Context c) { return new File(c.getFilesDir(), "guests.txt"); }

    /** Blocking; off the main thread. True when the video on the TV is the current one. */
    static synchronized boolean sync(Context c) {
        try {
            byte[] txt = get(BASE + "guests.txt", null);
            if (txt == null || txt.length == 0) return video(c).isFile();
            File mp4 = video(c), t = times(c);
            if (mp4.isFile() && t.isFile() && new String(txt, "UTF-8").equals(read(t))) return true;

            File tmp = new File(c.getFilesDir(), "guests.mp4.tmp");
            if (get(BASE + "guests.mp4", tmp) == null || tmp.length() < 1_000_000) { tmp.delete(); return mp4.isFile(); }
            if (!tmp.renameTo(mp4)) { tmp.delete(); return false; }
            FileOutputStream fo = new FileOutputStream(t);
            try { fo.write(txt); } finally { fo.close(); }
            return true;
        } catch (Exception e) {
            return video(c).isFile();
        }
    }

    static String read(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                return b.toString("UTF-8");
            } finally { in.close(); }
        } catch (Exception e) {
            return "";
        }
    }

    /** GET url (redirects followed: GitHub hands release files to its file host). Into `to` when given,
     *  else returned as bytes. Null on any failure, or when a file download comes up short. */
    private static byte[] get(String url, File to) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(60000);
        try {
            if (con.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            long want = con.getContentLength();
            InputStream in = con.getInputStream();
            byte[] buf = new byte[65536];
            int n;
            long got = 0;
            if (to == null) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                in.close();
                return b.toByteArray();
            }
            FileOutputStream fo = new FileOutputStream(to);
            try {
                while ((n = in.read(buf)) > 0) { fo.write(buf, 0, n); got += n; }
            } finally { fo.close(); in.close(); }
            return want > 0 && got != want ? null : new byte[0];
        } finally {
            con.disconnect();
        }
    }
}
