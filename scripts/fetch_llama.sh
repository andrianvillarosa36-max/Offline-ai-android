#!/usr/bin/env bash
# Downloads llama.cpp at a pinned release tag into the app's cpp folder.
# Pinned so the JNI code keeps compiling when llama.cpp's API changes.
set -euo pipefail

TAG="${LLAMA_CPP_TAG:-b9590}"
DEST="app/src/main/cpp/llama.cpp"

if [ -f "$DEST/CMakeLists.txt" ]; then
  echo "llama.cpp already present at $DEST"
  exit 0
fi

echo "Cloning llama.cpp $TAG ..."
git clone --depth 1 --branch "$TAG" https://github.com/ggml-org/llama.cpp "$DEST"
echo "Done."
