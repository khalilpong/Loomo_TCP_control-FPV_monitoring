package com.sample.loomodemo.helper;

import com.segway.robot.sdk.locomotion.sbv.Base;

public class VoiceControlAction {
    private final VoiceControlParams mParams;
    private float mAheadVelocity; // 当前前进速度
    VoiceControlAction(VoiceControlParams params) {
        mParams = params;
        mAheadVelocity = mParams.startLinearVelocity;
    }

    void action(String words) {
        if (words.equalsIgnoreCase("GO AHEAD")) {
            //bySC
            //goAhead();
            goAhead(mAheadVelocity);
        } else if (words.equalsIgnoreCase("SPEED UP")) {
            speedUp();
        } else if (words.equalsIgnoreCase("SLOW DOWN")) {
            slowDown();
        } else if (words.equalsIgnoreCase("MOVE BACK")) {
            moveBack();
        } else if (words.equalsIgnoreCase("TURN LEFT")) {
            turnLeft();
        } else if (words.equalsIgnoreCase("TURN RIGHT")) {
            turnRight();
        }//bySC
        else if (words.equalsIgnoreCase("FASTER")) {
            faster();
        }
        else if (words.equalsIgnoreCase("TURN BACK")) {
            turnAround();
        }
    }

    //private void goAhead() {
    private void goAhead(float fVelo) {
        //Base.getInstance().setLinearVelocity(mAheadVelocity);
        Base.getInstance().setLinearVelocity(fVelo);
        //bySC
        Base.getInstance().setAngularVelocity(0.0f);
    }

    private void speedUp() {
        if (mAheadVelocity + mParams.linearVelocityStep <= mParams.linearVelocityMax) {
            mAheadVelocity += mParams.linearVelocityStep;
        }
        //bySC
        for(int i = 0; i < 3;i++) {
            goAhead(mAheadVelocity/(3-i));
            try {
                Thread.sleep(500); // 给 100ms 时间移动
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        for(int i = 0; i < 13;i++) {
            goAhead(mAheadVelocity);
            try {
                Thread.sleep(500); // 给 100ms 时间移动
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        for(int i = 0; i < 4;i++) {
            goAhead(mAheadVelocity/(i+1));
            try {
                Thread.sleep(500); // 给 100ms 时间移动
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
//bySC
private void faster() {
    if (mAheadVelocity + mParams.linearVelocityStep <= mParams.linearVelocityMax) {
        mAheadVelocity += mParams.linearVelocityStep;
    }
    //bySC
    for(int i = 0; i < 3;i++) {
        goAhead(mAheadVelocity/(3-i));
        try {
            Thread.sleep(500); // 给 100ms 时间移动
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
    for(int i = 0; i < 3;i++) {
        goAhead(mAheadVelocity);
        try {
            Thread.sleep(500); // 给 100ms 时间移动
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
    for(int i = 0; i < 4;i++) {
        goAhead(mAheadVelocity/(i+1));
        try {
            Thread.sleep(500); // 给 100ms 时间移动
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}

    private void slowDown() {
        if (mAheadVelocity - mParams.linearVelocityStep > 0.f) {
            mAheadVelocity -= mParams.linearVelocityStep;
        }
        //bySC
        //goAhead();
        goAhead(mAheadVelocity);
    }

    private void moveBack() {
        Base.getInstance().setLinearVelocity(-1.f * mParams.moveBackVelocity);
    }

    private void turnLeft() {
        Base.getInstance().setAngularVelocity(mParams.angularVelocity);
    }
    private void turnRight() {
        Base.getInstance().setAngularVelocity(-1.f * mParams.angularVelocity);
    }
    //bySC
    private void turnAround() {
        Base.getInstance().setAngularVelocity(4.0f);
    }
}
