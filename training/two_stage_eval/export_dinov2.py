# 내 음식 기억의 특징값 모델(assets/models/food_embed.tflite)을 만든다: DINOv2-small, 224 입력 -> ONNX.
# 이어서: python -m onnxslim dinov2s_224.onnx dinov2s_224_slim.onnx   (없이 onnx2tf 에 넣으면 실패한다)
#         python onnx_to_tflite.py dinov2s_224_slim.onnx out -> out/*_dynamic_range_quant.tflite 를 food_embed.tflite 로 복사.
# 전처리(앱 FoodDetector.embedWith 와 같다): 짧은 변 224 로 맞추고 가운데 224x224, ImageNet 평균·표준편차 정규화.
import timm, torch
m = timm.create_model('vit_small_patch14_dinov2.lvd142m', pretrained=True, num_classes=0, img_size=224).eval()
torch.onnx.export(m, torch.randn(1, 3, 224, 224), 'dinov2s_224.onnx', opset_version=17,
                  input_names=['image'], output_names=['embedding'], dynamo=False)
print(timm.data.resolve_data_config({'input_size': (3, 224, 224)}, model=m))
