# Loomo TCP Control + FPV Monitoring

This repository contains the Loomo-side TCP control service and an Android
phone controller app for remote driving with FPV video monitoring.

## What Is Loomo?

Loomo is a two-wheeled self-balancing robot and personal transporter platform
from Segway Robotics. In this project, Loomo is used as a mobile robot base:
its onboard Android system runs the control/video service, while an external
Android phone acts as the remote controller and FPV monitor.

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

---

# Loomo TCP 控制与 FPV 图传监控

本仓库包含 Loomo 端的 TCP 控制服务，以及安卓手机端遥控 App。项目目标是让手机可以通过局域网远程控制 Loomo，并实时查看 Loomo 前方视角。

## Loomo 是什么？

Loomo 是 Segway Robotics 推出的双轮自平衡机器人/代步车平台。它可以理解为带有机器人能力的平衡车：底盘负责自平衡移动，机身上集成了摄像头、传感器和 Android 开发环境。本项目把 Loomo 作为移动机器人底盘使用，让 Loomo 端运行控制和图传服务，再由安卓手机端进行远程驾驶和画面监控。

## 项目结构

- `app/` - Loomo 端 Android 项目，包含 TCP 控制服务器、视频帧服务器，以及 Loomo 的 Pilot 和 Route 实现。
- `remote-controller-pro-app/` - 安卓手机端遥控 App，用于连接 Loomo、显示 FPV 图传、发送摇杆控制命令，并展示连接状态反馈。

## 主要功能

- 通过 TCP 控制通道发送摇杆、停止、心跳和状态查询命令。
- 通过独立 TCP 视频通道传输 FPV JPEG 图像帧。
- 手机端提供摇杆控制界面，并支持直行和转向比例调节。
- 手机端显示连接状态、RTT 延迟、遥测信息、图传帧率和运行日志。
