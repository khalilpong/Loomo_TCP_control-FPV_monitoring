package com.sample.loomodemo.helper;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Optional fun sound-effect engine for TcpPro.
 *
 * This class is intentionally isolated from the main control logic. If audio is
 * unavailable on a Loom build, TcpPro can disable this helper and continue driving
 * normally without affecting joystick, TCP, or video behavior.
 */
public class TcpProSoundFx implements AutoCloseable {
    private static final int SAMPLE_RATE = 16000;
    private static final int BLOCK_SAMPLES = 320; // 20 ms

    private final AudioTrack mTrack;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean mRun = true;
    private volatile boolean mEngineEnabled = false;
    private volatile float mSpeedNorm = 0f;
    private volatile float mSteerNorm = 0f;
    private volatile boolean mBrakePending = false;

    public TcpProSoundFx() {
        int minBuffer = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        int bufferSize = Math.max(minBuffer, BLOCK_SAMPLES * 8);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferSize)
                    .build();
        } else {
            mTrack = new AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                    AudioTrack.MODE_STREAM
            );
        }
        mTrack.play();
        mExecutor.execute(this::audioLoop);
    }

    public void setMotion(float linearVelocity, float angularVelocity, float maxLinear, float maxAngular) {
        float linearNorm = maxLinear > 0.0001f ? Math.min(1f, Math.abs(linearVelocity) / maxLinear) : 0f;
        float angularNorm = maxAngular > 0.0001f ? Math.min(1f, Math.abs(angularVelocity) / maxAngular) : 0f;
        mSpeedNorm = Math.max(linearNorm, angularNorm * 0.45f);
        mSteerNorm = angularNorm;
        mEngineEnabled = mSpeedNorm > 0.025f || angularNorm > 0.08f;
    }

    public void playBrake() {
        mBrakePending = true;
    }

    @Override
    public void close() {
        mRun = false;
        mExecutor.shutdownNow();
        try {
            mTrack.pause();
            mTrack.flush();
            mTrack.release();
        } catch (Exception ignored) {
        }
    }

    private void audioLoop() {
        short[] block = new short[BLOCK_SAMPLES];
        double phaseA = 0.0;
        double phaseB = 0.0;
        while (mRun) {
            if (mBrakePending) {
                mBrakePending = false;
                renderBrakeChirp(block);
                continue;
            }
            if (!mEngineEnabled) {
                sleepQuietly(20L);
                continue;
            }
            float speed = mSpeedNorm;
            float steer = mSteerNorm;
            phaseA = renderEngineBlock(block, phaseA, phaseB, speed, steer);
            phaseB += BLOCK_SAMPLES * (2.0 * Math.PI * (48.0 + steer * 22.0) / SAMPLE_RATE);
            writeBlock(block);
        }
    }

    private double renderEngineBlock(short[] block, double phaseA, double phaseB, float speedNorm, float steerNorm) {
        double baseFreq = 42.0 + speedNorm * 95.0 + steerNorm * 18.0;
        double harmonicFreq = baseFreq * (1.7 + speedNorm * 0.35);
        double phaseIncA = 2.0 * Math.PI * baseFreq / SAMPLE_RATE;
        double phaseIncB = 2.0 * Math.PI * harmonicFreq / SAMPLE_RATE;
        float amp = 0.14f + 0.20f * speedNorm;

        for (int i = 0; i < block.length; i++) {
            double saw = 2.0 * frac(phaseA / (2.0 * Math.PI)) - 1.0;
            double harmonic = Math.sin(phaseB) * 0.42;
            double rumble = Math.sin(phaseA * 0.35) * 0.18;
            double pulse = Math.sin(phaseA * 0.12) * 0.10;
            double sample = amp * (0.58 * saw + 0.26 * harmonic + 0.10 * rumble + 0.06 * pulse);
            block[i] = clampPcm(sample);
            phaseA += phaseIncA;
            phaseB += phaseIncB;
        }
        return phaseA;
    }

    private void renderBrakeChirp(short[] block) {
        int totalBlocks = 8; // about 160ms
        double phase = 0.0;
        for (int b = 0; b < totalBlocks; b++) {
            float t = b / (float) Math.max(1, totalBlocks - 1);
            double startFreq = 820.0;
            double endFreq = 180.0;
            double freq = startFreq + (endFreq - startFreq) * t;
            double phaseInc = 2.0 * Math.PI * freq / SAMPLE_RATE;
            float amp = 0.26f * (1.0f - t);
            for (int i = 0; i < block.length; i++) {
                double squeal = Math.sin(phase);
                double scrape = Math.sin(phase * 0.47) * 0.35;
                double sample = amp * (0.72 * squeal + 0.28 * scrape);
                block[i] = clampPcm(sample);
                phase += phaseInc;
            }
            writeBlock(block);
        }
    }

    private void writeBlock(short[] block) {
        try {
            mTrack.write(block, 0, block.length);
        } catch (Exception ignored) {
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private double frac(double value) {
        return value - Math.floor(value);
    }

    private short clampPcm(double sample) {
        double clamped = Math.max(-1.0, Math.min(1.0, sample));
        return (short) (clamped * 32767.0);
    }
}
