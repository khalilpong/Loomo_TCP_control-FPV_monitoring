package com.sample.loomodemo.helper;

import android.util.Log;

import com.segway.robot.sdk.voice.Recognizer;
import com.segway.robot.sdk.voice.Speaker;
import com.segway.robot.sdk.voice.VoiceException;
import com.segway.robot.sdk.voice.recognition.RecognitionListener;
import com.segway.robot.sdk.voice.recognition.RecognitionResult;
import com.segway.robot.sdk.voice.recognition.WakeupListener;
import com.segway.robot.sdk.voice.recognition.WakeupResult;
import com.segway.robot.sdk.voice.tts.TtsListener;

/**
 * 语音控制
 */
public class VoiceControl {
    private static final String TAG = "VoiceControl";

    private VoiceControlParams mParams;
    private VoiceControlAction mAction;

    private final static VoiceControl inst = new VoiceControl();
    static public VoiceControl getInstance() {
        return inst;
    }

    public interface VoiceCmdListener {
        // 语音识别开始（已唤醒）
        void onRecognizeStart();
        // 语音识别结束（重新回到待唤醒状态）
        void onRecognizeDone();
        // 语音要求切换到下一条路径
        void onNextRoute();
    }

    private VoiceCmdListener mVoiceCmdListener;

    public void init(VoiceControlParams params, VoiceCmdListener voiceCmdListener) {
        mParams = params;
        mVoiceCmdListener = voiceCmdListener;
        setup();
    }

    // 直接把 json string 拷贝进来，IDE 会自动转义
    //bySC
    //final String mGrammaString = "{\"name\":\"common\",\"slotList\":[{\"name\":\"cmd\",\"isOptional\":false,\"word\":[\"turn left\",\"turn right\",\"go ahead\",\"move back\",\"speed up\",\"slow down\",\"next route\",\"bye loomo\"]}]}";
    final String mGrammaString = "{\"name\":\"common\",\"slotList\":[{\"name\":\"cmd\",\"isOptional\":false,\"word\":[\"turn left\",\"turn right\",\"go ahead\",\"faster\",\"move back\",\"speed up\",\"slow down\",\"turn back\",\"next route\",\"bye bye\"]}]}";

    private void setup() {
        try {
            Recognizer.getInstance().addGrammarConstraint(Recognizer.getInstance().createGrammarConstraint(mGrammaString));
            Recognizer.getInstance().startWakeupAndRecognition(mWakeupListener, mRecogListener);
        } catch (VoiceException e) {
            TcpSrv.getInstance().sendMsg("setup void recognizer error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private final WakeupListener mWakeupListener = new WakeupListener() {

        @Override
        public void onStandby() {}

        @Override
        public void onWakeupResult(WakeupResult wakeupResult) {
            mVoiceCmdListener.onRecognizeStart();
            mAction = new VoiceControlAction(mParams);
            try {
                Speaker.getInstance().speak("I'm here", new TtsListener() {
                    @Override
                    public void onSpeechStarted(String word) {}
                    @Override
                    public void onSpeechFinished(String word) {}
                    @Override
                    public void onSpeechError(String word, String reason) {}
                });
            } catch (VoiceException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onWakeupError(String error) {}
    };

    private long mLastVoiceTime = 0;
    private String mLastWords;
    private final RecognitionListener mRecogListener = new RecognitionListener() {

        @Override
        public void onRecognitionStart() {
            TcpSrv.getInstance().sendMsg("recognition start");
        }

        @Override
        public boolean onRecognitionResult(RecognitionResult recognitionResult) {
            int RECOG_THRESH = 50;
            if (recognitionResult.getConfidence() < RECOG_THRESH) {
                return true; // true for recognition
            }
            long cur = System.currentTimeMillis();

            String words = recognitionResult.getRecognitionResult();
            TcpSrv.getInstance().sendMsg("Voice command: " + words + ", " + recognitionResult.getConfidence());
            if (words.equalsIgnoreCase("BYE BYE") || words.equalsIgnoreCase("NEXT ROUTE")) {
                Log.i(TAG, "语音指令：" + words);
                mAction = null;
                if (words.equalsIgnoreCase("BYE BYE")) {
                    mVoiceCmdListener.onRecognizeDone();
                } else {
                    mVoiceCmdListener.onNextRoute();
                }
                return false; // false for wakeup
            }

            // 实测每条语音都会被识别到两次，需要去掉重复指令
            if (cur - mLastVoiceTime < 50 && mLastWords.equalsIgnoreCase(words)) {
                mLastVoiceTime = cur;
                return true;
            }

            mLastVoiceTime = cur;
            mLastWords = words;

            new Thread(() -> action(words)).start();

            return true;
        }

        @Override
        public boolean onRecognitionError(String error) {
            TcpSrv.getInstance().sendMsg("recognition error: " + error);
            return true;
        }
    };

    // 根据语音指令进行相应的动作
    private void action(String words) {
        Log.i(TAG, "语音指令：" + words);
        TcpSrv.getInstance().sendMsg("void action: " + words);
        mAction.action(words);
    }
}
