package com.sample.loomodemo.route;

import com.sample.loomodemo.Route;

public class TcpProRoute extends Route {
    // Head pitch is used mainly by the FPV/video build so the phone sees the road
    // instead of the horizon. The control logic itself does not depend on it.
    public float headPitch = -0.25f;

    // Velocity caps after joystick normalization. Forward and reverse are split
    // because the robot is usually safer when backing up more slowly.
    public float maxLinearVelocity = 0.80f;
    public float maxReverseLinearVelocity = 0.45f;
    public float maxAngularVelocity = 1.10f;

    // Ramp steps define how quickly the applied velocity chases the target.
    // Brake steps are intentionally larger so stop/reverse feels more decisive.
    public float linearRampStep = 0.08f;
    public float linearBrakeStep = 0.16f;
    public float angularRampStep = 0.12f;
    public float angularBrakeStep = 0.20f;

    // Reduce steering command automatically at higher forward speed so the robot
    // does not feel twitchy or unstable when the stick is pushed hard.
    public float speedTurnCoupling = 0.35f;

    // Deadband ignores tiny stick noise near the center. Expo reshapes the stick
    // curve so small motions are easier to control while full throw still reaches
    // full speed/turn authority.
    public float joystickDeadband = 0.08f;
    public float linearExpo = 1.35f;
    public float angularExpo = 1.20f;

    // Steering helpers for narrow roads:
    // - turnSlowdownGain: large steering input automatically slows linear speed
    // - lowSpeedTurnBoost: at low speed, turning gets extra authority
    // - sharpTurnThreshold / sharpTurnLinearScale: very large steering input caps
    //   linear speed to help the robot pivot around tight corners
    public float turnSlowdownGain = 0.65f;
    public float lowSpeedTurnBoost = 0.30f;
    public float sharpTurnThreshold = 0.72f;
    public float sharpTurnLinearScale = 0.28f;

    // Pivot assist converts "almost pure left/right stick" into true pivoting by
    // suppressing tiny accidental forward/backward input from the operator's hand.
    public float pivotTurnAssistMinTurn = 0.45f;
    public float pivotTurnAssistMaxForward = 0.18f;

    // If commands stop arriving, the watchdog stops the robot. Status interval
    // controls how often the Loom side pushes telemetry back to the phone.
    public int commandTimeoutMs = 1000;
    public int statusIntervalMs = 400;

    // Video stream settings for the FPV build. Control and video are separated:
    // control stays on 6666 in TcpSrv, while video uses its own socket/port.
    public int videoPort = 6667;
    public int videoIntervalMs = 50;
    public int videoJpegQuality = 55;
    public int videoWidth = 480;
    public int videoHeight = 360;

    @Override
    public boolean checkDestByCkpt() { return false; }
}
