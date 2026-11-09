#!/usr/bin/env python3
# 给 Capacitor 生成的 app/build.gradle 注入 ML Kit 中文 OCR 依赖
import io
import sys

p = "android/app/build.gradle"
s = io.open(p, encoding="utf-8").read()
dep = '    implementation "com.google.mlkit:text-recognition-chinese:16.0.1"'
if "text-recognition-chinese" in s:
    print("already present")
    sys.exit(0)
if "dependencies {" not in s:
    print("ERROR: no dependencies block")
    sys.exit(1)
s = s.replace("dependencies {", "dependencies {\n" + dep, 1)
io.open(p, "w", encoding="utf-8").write(s)
print("ocr dependency injected")
