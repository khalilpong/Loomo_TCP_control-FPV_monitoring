# Loomo TCP Control + FPV Monitoring

This repository contains the Loomo-side TCP control service and an Android
phone controller app for remote driving with FPV video monitoring.

## Project Structure

- `app/` - Loomo Android project. It contains the TCP control server, video
  frame server, and Loomo pilot/route implementations.
- `remote-controller-pro-app/` - Android phone-side controller app. It connects
  to the Loomo device, displays FPV video, sends joystick control commands, and
  shows status feedback.

## Main Features

- TCP control channel for joystick, stop, ping, and status commands.
- Separate TCP video channel for FPV JPEG frame streaming.
- Android joystick UI with drive/turn scaling.
- Connection status, RTT, telemetry, frame rate, and logs on the phone app.

## Notes

Generated build outputs, IDE metadata, local SDK files, and unrelated local
folders are intentionally ignored and should not be committed.
