#!/bin/bash
# Download MediaPipe model files into app/src/main/assets/
# Run once before building the project

ASSETS_DIR="app/src/main/assets"
mkdir -p "$ASSETS_DIR"

BASE="https://storage.googleapis.com/mediapipe-models"

echo "Downloading pose_landmarker_lite.task..."
curl -L "$BASE/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task" \
     -o "$ASSETS_DIR/pose_landmarker_lite.task"

echo "Downloading hand_landmarker.task..."
curl -L "$BASE/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task" \
     -o "$ASSETS_DIR/hand_landmarker.task"

echo ""
echo "Models downloaded:"
ls -lh "$ASSETS_DIR"
