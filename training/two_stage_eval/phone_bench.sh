#!/bin/bash
# 폰(USB 디버깅)에서 1단계 후보·기존 음식 모델의 추론 시간을 잰다. CPU 4스레드 / GPU 위임.
# Git Bash 에서는 MSYS_NO_PATHCONV=1 로 실행한다(폰 경로가 Windows 경로로 바뀐다).
# benchmark_model: https://storage.googleapis.com/tensorflow-nightly-public/prod/tensorflow/release/lite/tools/nightly/latest/android_aarch64_benchmark_model
ADB=${ADB:-adb}
D=/data/local/tmp/trexbench
"$ADB" shell mkdir -p $D
"$ADB" push benchmark_model $D/ >/dev/null && "$ADB" shell chmod +x $D/benchmark_model
declare -A M=(
  [food342_int8]=../../app/src/main/assets/models/yolov8n_food.tflite
  [worldS_fp16]=tf_yolov8s-worldv2_food/yolov8s-worldv2_food_float16.tflite
  [worldS_dyn8]=tf_yolov8s-worldv2_food/yolov8s-worldv2_food_dynamic_range_quant.tflite
  [worldM_fp16]=tf_yolov8m-worldv2_food/yolov8m-worldv2_food_float16.tflite
  [yoloeS_fp16]=tf_yoloe-11s-seg/yoloe-11s-seg_float16.tflite
  [yoloeS_dyn8]=tf_yoloe-11s-seg/yoloe-11s-seg_dynamic_range_quant.tflite
  [coco11n_fp16]=tf_yolo11n/yolo11n_float16.tflite
)
for k in food342_int8 worldS_fp16 worldS_dyn8 worldM_fp16 yoloeS_fp16 yoloeS_dyn8 coco11n_fp16; do
  "$ADB" push "${M[$k]}" $D/$k.tflite >/dev/null
  for mode in cpu gpu; do
    opt="--num_threads=4"; [ $mode = gpu ] && opt="--use_gpu=true"
    avg=$("$ADB" shell "$D/benchmark_model --graph=$D/$k.tflite $opt --num_runs=30 --warmup_runs=5 2>&1" | tr -d '\r' | grep -oE 'Inference \(avg\): [0-9.e+]+' | tail -1 | awk '{printf "%.0f", $3/1000}')
    echo "$k $mode ${avg:-실패}ms"
  done
done
"$ADB" shell rm -rf $D
