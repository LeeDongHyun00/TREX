# 앱의 위치 모델(assets/models/food_region.tflite)을 만든다: YOLOE-11S 탐지 전용 + 고정 어휘 -> ONNX.
# 이어서 python onnx_to_tflite.py yoloe-11s-det.onnx out -> out/*_dynamic_range_quant.tflite 를 food_region.tflite 로 복사.
# (ultralytics 가 파일명을 yoloe-11s-seg.onnx 로 쓴다 — yoloe-11s-det.onnx 로 바꿔 둔다.)
from ultralytics import YOLOE
PROMPTS = ['food', 'bowl of food', 'plate of food', 'dish', 'bowl', 'plate', 'rice', 'soup', 'side dish']
m = YOLOE('yoloe-11s.yaml').load('yoloe-11s-seg.pt')   # 탐지 전용 머리에 seg 가중치를 싣는다(마스크 가지 제거)
m.set_classes(PROMPTS, m.get_text_pe(PROMPTS))
print('ONNX', m.export(format='onnx', imgsz=640, opset=17, simplify=True))
