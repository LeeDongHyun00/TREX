# ultralytics 의 tflite 내보내기는 Windows 에서 막혀 있다 -> ONNX 를 onnx2tf 로 변환한다.
# 사용: python onnx_to_tflite.py model.onnx out_dir   (tensorflow 2.19 · onnx 1.17 · onnx2tf 1.28 · protobuf<6 인 별도 환경)
# onnx2tf 가 시험용 이미지(.npy)를 내려받다 실패한다 -> 난수 이미지로 대체(형상 검사에만 쓰인다)
import sys, numpy as np
import onnx2tf.utils.common_functions as cf
cf.download_test_image_data = lambda: np.random.rand(20, 128, 128, 3).astype(np.float32)
import onnx2tf, onnx2tf.onnx2tf as core
core.download_test_image_data = cf.download_test_image_data
onnx2tf.convert(input_onnx_file_path=sys.argv[1], output_folder_path=sys.argv[2], non_verbose=True, output_signaturedefs=True,
                output_integer_quantized_tflite=False, output_dynamic_range_quantized_tflite=True)
