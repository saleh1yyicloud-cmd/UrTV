package sa.urtv.guests;

import android.app.Activity;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * «عرض الضيوف»: the association's presentation for visitors, as a video, from its own home-screen tile.
 * The video is kept on the TV by Guests: downloaded the first time, and checked for a newer one at each opening.
 * It runs like the deck in the presenter's hands: each slide plays its animations and then holds until the
 * presenter moves on. Remote: OK or right = next slide, left = previous, Back closes.
 */
public class GuestsActivity extends Activity {
    private FrameLayout root;
    private TextView note;
    private VideoView video;
    private MediaPlayer player;
    private final List<Integer> slides = new ArrayList<>();   // where each slide starts, ms
    private final List<Integer> holds = new ArrayList<>();    // where its animations are done, ms
    private int cur;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);   // no screensaver mid-visit
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(7, 44, 73));
        setContentView(root);
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        if (Guests.video(this).isFile()) {
            play();
            // meanwhile look for a newer presentation, for the next visit (this one keeps playing)
            new Thread(new Runnable() { @Override public void run() { Guests.sync(getApplicationContext()); } }).start();
            return;
        }
        // first time on this TV: fetch it now (the hourly job normally has it ready)
        note = new TextView(this);
        note.setText("جارٍ تجهيز عرض الضيوف…");
        note.setTextColor(Color.rgb(236, 230, 221));
        note.setTextSize(30);
        note.setGravity(Gravity.CENTER);
        root.addView(note, new FrameLayout.LayoutParams(-1, -1));
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean ok = Guests.sync(getApplicationContext());
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        if (ok && Guests.video(GuestsActivity.this).isFile()) { root.removeView(note); play(); }
                        else note.setText("تعذّر تجهيز عرض الضيوف — تأكد من اتصال التلفزيون بالإنترنت");
                    }
                });
            }
        }).start();
    }

    private void play() {
        readTimes(Guests.times(this));
        root.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        video.setVideoPath(Guests.video(this).getAbsolutePath());
        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override public void onPrepared(MediaPlayer mp) {
                player = mp;
                if (holds.size() == slides.size() && !slides.isEmpty()) show(0); else video.start();
            }
        });
    }

    /** guests.txt: line 1 the slide start times, line 2 (when present) where each slide's animations are done. */
    private void readTimes(File f) {
        slides.clear();
        holds.clear();
        try {
            String[] lines = Guests.read(f).trim().split("\n");
            for (String s : lines[0].split("[,\s]+")) if (!s.isEmpty()) slides.add(Integer.parseInt(s));
            if (lines.length > 1 && !lines[1].startsWith("v="))
                for (String s : lines[1].split("[,\s]+")) if (!s.isEmpty()) holds.add(Integer.parseInt(s));
        } catch (Exception e) {
            slides.clear();
            holds.clear();
        }
    }

    /** Slide k: from its start, its animations play, and it holds there until the presenter moves on. */
    private void show(int k) {
        cur = Math.max(0, Math.min(slides.size() - 1, k));
        seek(slides.get(cur));
        video.start();
        tick.removeCallbacks(hold);
        tick.postDelayed(hold, 60);
    }

    private final Handler tick = new Handler(Looper.getMainLooper());
    private final Runnable hold = new Runnable() {
        @Override public void run() {
            if (video == null) return;
            if (video.getCurrentPosition() >= holds.get(cur)) video.pause();
            else tick.postDelayed(this, 40);
        }
    };

    private void seek(int ms) {
        // the frame itself, not the keyframe before it (which can show the end of the previous slide)
        if (player != null && Build.VERSION.SDK_INT >= 26) player.seekTo(ms, MediaPlayer.SEEK_CLOSEST);
        else video.seekTo(ms);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (video == null) return super.onKeyDown(code, e);
        boolean presenter = holds.size() == slides.size() && !slides.isEmpty();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_DPAD_RIGHT:   // the deck reads right to left, but «next» on a remote is right
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (presenter) show(cur + 1); else jump(+1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (presenter) show(cur - 1); else jump(-1);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (video.isPlaying()) video.pause(); else video.start();
                return true;
            default:
                return super.onKeyDown(code, e);
        }
    }

    /** Without hold points (an older guests.txt): move a slide at a time and keep playing. */
    private void jump(int by) {
        if (slides.isEmpty()) {
            seek(Math.max(0, video.getCurrentPosition() + by * 10000));
        } else {
            int k = 0;
            for (int i = 0; i < slides.size(); i++) if (slides.get(i) <= video.getCurrentPosition() + 300) k = i;
            seek(slides.get(Math.max(0, Math.min(slides.size() - 1, k + by))));
        }
        video.start();
    }

    @Override
    protected void onDestroy() {
        tick.removeCallbacks(hold);
        super.onDestroy();
    }
}
