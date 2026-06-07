package com.sample.loomodemo.helper;

import com.segway.robot.algo.dts.DTSPerson;

import org.opencv.core.Rect2d;

public interface PresenterChangeInterface {
    void showToast(String message);
    void drawPersons(DTSPerson[] dtsPersons);
    void drawPerson(DTSPerson dtsPerson);
    void drawRect(Rect2d rect);
}
