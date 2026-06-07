package com.sample.loomodemo.route;

import com.sample.loomodemo.Route;

public class TcpRemoteRoute extends Route {
    public float headPitch = 0.70f;
    public float forwardLinearVelocity = 0.28f;
    public float backwardLinearVelocity = 0.18f;
    public float turnAngularVelocity = 0.55f;
    public float maxLinearVelocity = 0.45f;
    public float maxAngularVelocity = 0.85f;
    public int commandTimeoutMs = 700;
    public float linearRampStep = 0.08f;
    public float angularRampStep = 0.12f;

    @Override
    public boolean checkDestByCkpt() { return false; }
}
