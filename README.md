# MathMaster THCS AI 2.0 - Local Android

Ứng dụng Android giải toán THCS bằng LLM GGUF chạy trực tiếp trên thiết bị, kết hợp OCR ảnh.

## Build
- Android Studio/JDK 17, hoặc GitHub Actions.
- SDK 35.

## GitHub Actions
Workflow `.github/workflows/build-apk.yml` tự cài Gradle và build `app-debug.apk`.

## Model
Chọn model GGUF trong app. Không có API/server bắt buộc.
