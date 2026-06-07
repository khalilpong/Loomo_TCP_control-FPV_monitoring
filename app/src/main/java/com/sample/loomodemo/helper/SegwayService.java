package com.sample.loomodemo.helper;

import android.content.Context;
import android.support.annotation.NonNull;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import com.segway.robot.sdk.base.action.RobotAction;
import com.segway.robot.sdk.base.bind.ServiceBinder;
import com.segway.robot.sdk.base.state.RobotEventState;
import com.segway.robot.sdk.base.state.RobotEventStateManager;
import com.segway.robot.sdk.base.state.RobotState;
import com.segway.robot.sdk.locomotion.head.Head;
import com.segway.robot.sdk.locomotion.sbv.Base;
import com.segway.robot.sdk.perception.sensor.RobotAllSensors;
import com.segway.robot.sdk.perception.sensor.Sensor;
//import com.segway.robot.sdk.vision.Vision;
import com.segway.robot.sdk.vision.Vision;
import com.segway.robot.sdk.voice.Recognizer;
import com.segway.robot.sdk.voice.Speaker;
import com.segway.robot.sdk.voice.VoiceException;
import com.segway.robot.sdk.voice.tts.TtsListener;

import java.util.Locale;

public class SegwayService implements AutoCloseable {
    private static final String TAG = "SegwayService";

    private static boolean mSpeakerBinded = false;
    private static boolean mSpeechRecognizerBinded = false;
    private static boolean mBaseBinded = false;
    private static boolean mHeadBinded = false;
    private static boolean mSensorBinded = false;
    private static boolean mVisionBinded = false;
    private static TextToSpeech mSystemTts;
    private static boolean mSystemTtsReady = false;
    private static boolean mSystemTtsZhReady = false;

    SegwayService() {}

    @Override
    public void close() {
        unbindService();
    }

    public interface BindStateListener {
        // 全部服务绑定完成
        void onBindDone();
    }

    private static BindStateListener mListener;

    public static void bindService(Context context, BindStateListener listener) {
        mListener = listener;
        initSystemTts(context.getApplicationContext());
        bindSpeakerService(context);
        bindSpeechRecognizer(context);
        bindBaseService(context);
        bindHeadService(context);
        bindSensorService(context);
        bindVisionService(context);
    }

    private void unbindService() {
        shutdownSystemTts();
        speaker().unbindService();
        speechRecognizer().unbindService();
        base().unbindService();
        head().unbindService();
        sensor().unbindService();
        vision().unbindService();
    }

    @NonNull
    private static Speaker speaker() { return Speaker.getInstance(); }
    @NonNull
    public static Recognizer speechRecognizer() { return Recognizer.getInstance(); }
    @NonNull
    public static Base base() { return Base.getInstance(); }
    @NonNull
    public static Head head() { return Head.getInstance(); }
    @NonNull
    public static Sensor sensor() { return Sensor.getInstance(); }
    @NonNull
    public static Vision vision() { return Vision.getInstance(); }
    @NonNull
    public static RobotAllSensors getRobotAllSensors() { return sensor().getRobotAllSensors(); }

    public static void speak(String word) {
        if (!mSpeakerBinded || (mIsSpeaking && word.equalsIgnoreCase(mLastSpeakWord)))
            return;

        try {
            speaker().stopSpeak();
            mIsSpeaking = true;
            speaker().speak(word, mSpeakResultListener);
        } catch (VoiceException e) {
            Log.e(TAG, "Speak fail: " + e.getMessage());
        }
    }

    public static void speakChineseOrFallback(String chineseText, String englishFallback) {
        // TcpPro's voice prompts try system Chinese TTS first. If the Loom system
        // does not actually have a usable Chinese voice pack, we fall back to the
        // Segway speaker path with a short English phrase so control flow remains
        // stable and the user still hears some feedback.
        if (speakChineseWithSystemTts(chineseText)) {
            return;
        }
        speak(englishFallback);
    }

    public static String getChineseTtsDebugStatus() {
        // Helpful when reading TcpPro logs: this shows whether Chinese failure is
        // due to TTS init, missing Chinese language data, or speaker binding.
        return "systemTtsReady=" + (mSystemTtsReady ? "1" : "0")
                + ", zhReady=" + (mSystemTtsZhReady ? "1" : "0")
                + ", speakerBound=" + (mSpeakerBinded ? "1" : "0");
    }

    private static boolean mIsSpeaking = false;
    private static String mLastSpeakWord;
    private static final TtsListener mSpeakResultListener = new TtsListener() {
        @Override
        public void onSpeechError(String word, String reason) {
            mIsSpeaking = false;
        }
        @Override
        public void onSpeechFinished(String word) {
            mIsSpeaking = false;
        }
        @Override
        public void onSpeechStarted(String word) {
            mIsSpeaking = true;
            mLastSpeakWord = word;
        }
    };

    private static boolean isAllServiceBinded() {
        return mBaseBinded && mHeadBinded && mSensorBinded && mSpeakerBinded && mSpeechRecognizerBinded && mVisionBinded;
    }

    private static synchronized void checkAvailable() {
        if (isAllServiceBinded())
            mListener.onBindDone();
    }

    private static synchronized void initSystemTts(Context context) {
        if (mSystemTts != null) return;
        try {
            mSystemTts = new TextToSpeech(context, status -> {
                if (status == TextToSpeech.SUCCESS) {
                    mSystemTtsReady = true;
                    mSystemTtsZhReady = tryInitChineseLocale();
                } else {
                    mSystemTtsReady = false;
                    mSystemTtsZhReady = false;
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "System TTS init failed: " + t.getMessage());
            mSystemTts = null;
            mSystemTtsReady = false;
            mSystemTtsZhReady = false;
        }
    }

    private static synchronized void shutdownSystemTts() {
        if (mSystemTts != null) {
            try {
                mSystemTts.stop();
                mSystemTts.shutdown();
            } catch (Throwable ignored) {
            }
        }
        mSystemTts = null;
        mSystemTtsReady = false;
        mSystemTtsZhReady = false;
    }

    private static synchronized boolean speakChineseWithSystemTts(String chineseText) {
        if (!mSystemTtsReady || !mSystemTtsZhReady || mSystemTts == null) return false;
        try {
            String utteranceId = "zh_" + System.currentTimeMillis();
            int result = mSystemTts.speak(chineseText, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
            return result == TextToSpeech.SUCCESS;
        } catch (Throwable t) {
            Log.w(TAG, "System TTS speak failed: " + t.getMessage());
            return false;
        }
    }

    private static synchronized boolean tryInitChineseLocale() {
        if (mSystemTts == null) return false;
        // Try the common Simplified Chinese locales in order. Some Loom builds
        // expose one locale but not another, so we probe rather than assuming.
        Locale[] candidates = new Locale[]{
                Locale.SIMPLIFIED_CHINESE,
                Locale.CHINA,
                Locale.CHINESE
        };
        for (Locale locale : candidates) {
            try {
                int availability = mSystemTts.isLanguageAvailable(locale);
                if (availability >= TextToSpeech.LANG_AVAILABLE) {
                    int setResult = mSystemTts.setLanguage(locale);
                    if (setResult != TextToSpeech.LANG_MISSING_DATA
                            && setResult != TextToSpeech.LANG_NOT_SUPPORTED) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static void bindSpeakerService(final Context context) {
        speaker().bindService(context, new ServiceBinder.BindStateListener() {
            @Override
            public void onBind() {
                mSpeakerBinded = true;
                try {
                    speaker().setVolume(100); // 此处为系统当前音量下的比例，实际音量必须通过系统设置（以后可调 Android sdk 实现）
                } catch (VoiceException e) {
                    Log.e(TAG, "Setup speak volume exception: " + e.getMessage());
                }
                checkAvailable();
            }

            @Override
            public void onUnbind(String reason) {
                mSpeakerBinded = false;
            }
        });
    }

    private static void bindSpeechRecognizer(final Context context) {
        speechRecognizer().bindService(context, new ServiceBinder.BindStateListener() {
            @Override
            public void onBind() {
                try {
                    speechRecognizer().setSoundEnabled(false);
                    speechRecognizer().beamForming(true);
                } catch (VoiceException e) {
                    Log.e(TAG, "onBind: Set speech recognizer exception: " + e.getMessage());
                }
                mSpeechRecognizerBinded = true;
                checkAvailable();
            }

            @Override
            public void onUnbind(String reason) {
                mSpeechRecognizerBinded = false;
            }
        });
    }

    private static boolean mIsPushing = false;
    public static boolean isPushing() {
        return mIsPushing;
    }

    private static void bindBaseService(final Context context) {
        base().bindService(context, new ServiceBinder.BindStateListener() {
            @Override
            public void onBind() {
                mBaseBinded = true;

                RobotEventStateManager eventStateManager = new RobotEventStateManager();
                eventStateManager.initialize(context,
                        new RobotEventStateManager.EventStateChangedListener() {

                            @Override
                            public void onStateChanged(RobotEventState robotEventState) {
                                if (robotEventState.getState().equalsIgnoreCase(RobotAction.ActionEvent.PUSHING))
                                    mIsPushing = true;
                                else if (robotEventState.getState().equalsIgnoreCase(RobotAction.ActionEvent.PUSH_RELEASE))
                                    mIsPushing = false;
                                else
                                    speak(robotEventState.getState());
                            }

                            @Override
                            public void onRobotStateUpdated(RobotState robotState) {
                                // 似乎并没有用，除了启动时会触发，后面都不会触发
//                                if (robotState.isPushing())
//                                    speak("Who are you? Why push me?");
                            }
                        },
                        RobotAction.ActionEvent.PUSHING, RobotAction.ActionEvent.PUSH_RELEASE,
                        RobotAction.BaseEvent.BASE_STUCK); // BASE_STUCK 从未触发过

                // Process xxx has no permission to call advanced method.
//                base().setOnBaseStateChangeListener(new Base.BaseStateListener() {
//                    @Override
//                    public void onBaseStateChange(int baseState) {
//                        if (mSpeakerBinded)
//                            speak("Base state changed to " + baseState);
//                    }
//                });
                checkAvailable();
            }

            @Override
            public void onUnbind(String reason) {
                mBaseBinded = false;
            }
        });
    }

    private static void bindHeadService(final Context context) {
        head().bindService(context, new ServiceBinder.BindStateListener() {
            @Override
            public void onBind() {
                mHeadBinded = true;
                checkAvailable();
            }

            @Override
            public void onUnbind(String reason) {
                mHeadBinded = false;
            }
        });
    }

    private static void bindSensorService(final Context context) {
        sensor().bindService(context, new ServiceBinder.BindStateListener() {
            @Override
            public void onBind() {
                mSensorBinded = true;
                checkAvailable();
            }

            @Override
            public void onUnbind(String reason) {
                mSensorBinded = false;
            }
        });
    }

    private static void bindVisionService(final Context context) {
        vision().bindService(context, new ServiceBinder.BindStateListener() {
            @Override
            public void onBind() {
                mVisionBinded = true;
                checkAvailable();
            }

            @Override
            public void onUnbind(String reason) {
                mVisionBinded = false;
            }
        });
    }
}
