package sa.urtv.screensaver;

import android.app.Activity;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * «عرض الضيوف»: the association's presentation for visitors, as a video, from its own home-screen tile.
 * The video and its slide times are copied onto the TV by ADB, into the app's own folder:
 *   /sdcard/Android/data/sa.urtv.screensaver/files/guests.mp4
 *   /sdcard/Android/data/sa.urtv.screensaver/files/guests.txt   slide start times in ms, comma-separated
 * Remote: OK pauses / resumes, right and left go to the next and previous slide, Back closes.
 */
public class GuestsActivity extends Activity {
    private VideoView video;
    private final List<Integer> slides = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);   // no screensaver mid-visit
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        File dir = getExternalFilesDir(null);
        File mp4 = dir == null ? null : new File(dir, "guests.mp4");
        if (mp4 == null || !mp4.isFile()) {
            TextView t = new TextView(this);
            t.setText("لم يُنسخ عرض الضيوف إلى هذا التلفزيون بعد");
            t.setTextColor(Color.WHITE);
            t.setTextSize(28);
            t.setGravity(Gravity.CENTER);
            root.addView(t, new FrameLayout.LayoutParams(-1, -1));
            return;
        }
        readSlides(new File(dir, "guests.txt"));

        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        video.setVideoPath(mp4.getAbsolutePath());
        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override public void onPrepared(MediaPlayer mp) { video.start(); }
        });
        // the last slide (شكر الله لكم زيارتكم) stays on screen until Back
    }

    private void readSlides(File f) {
        if (!f.isFile()) return;
        try {
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[(int) Math.min(f.length(), 8192)];
            int n = in.read(buf);
            in.close();
            for (String s : new String(buf, 0, Math.max(0, n), "UTF-8").trim().split("[,\\s]+"))
                if (!s.isEmpty()) slides.add(Integer.parseInt(s));
        } catch (Exception e) {
            slides.clear();
        }
    }

    /** The slide holding position ms: the last start at or before it (a little slack, so "previous" from just
     *  after a slide's start goes to the slide before). */
    private int slideAt(int ms) {
        int k = 0;
        for (int i = 0; i < slides.size(); i++) if (slides.get(i) <= ms + 300) k = i;
        return k;
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (video == null) return super.onKeyDown(code, e);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_SPACE:
                if (video.isPlaying()) video.pause(); else video.start();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                video.start();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                video.pause();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:   // the deck reads right to left, but «next» on a remote is right
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                jump(+1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                jump(-1);
                return true;
            default:
                return super.onKeyDown(code, e);
        }
    }

    private void jump(int by) {
        if (slides.isEmpty()) {   // no slide times: ten seconds at a time
            video.seekTo(Math.max(0, video.getCurrentPosition() + by * 10000));
        } else {
            int k = Math.max(0, Math.min(slides.size() - 1, slideAt(video.getCurrentPosition()) + by));
            video.seekTo(slides.get(k));
        }
        video.start();
    }
}
