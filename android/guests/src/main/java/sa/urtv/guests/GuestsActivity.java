package sa.urtv.guests;

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
import java.util.ArrayList;
import java.util.List;

/**
 * «عرض الضيوف»: the association's presentation for visitors, as a video, from its own home-screen tile.
 * The video is kept on the TV by Guests: downloaded the first time, and checked for a newer one at each opening.
 * Remote: OK pauses / resumes, right and left go to the next and previous slide, Back closes.
 */
public class GuestsActivity extends Activity {
    private FrameLayout root;
    private TextView note;
    private VideoView video;
    private final List<Integer> slides = new ArrayList<>();

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
        readSlides(Guests.times(this));
        root.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        video.setVideoPath(Guests.video(this).getAbsolutePath());
        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override public void onPrepared(MediaPlayer mp) { video.start(); }
        });
        // the last slide (شكر الله لكم زيارتكم) stays on screen until Back
    }

    private void readSlides(File f) {
        slides.clear();
        try {
            String first = Guests.read(f).trim().split("\n")[0];
            for (String s : first.split("[,\\s]+")) if (!s.isEmpty()) slides.add(Integer.parseInt(s));
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
