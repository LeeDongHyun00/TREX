# 내 음식 기억의 특징값 모델(assets/models/food_embed.tflite)을 만든다: DINOv2-small, 224 입력 -> ONNX.
# 이어서: python -m onnxslim dinov2s_224.onnx dinov2s_224_slim.onnx   (없이 onnx2tf 에 넣으면 실패한다)
#         python onnx_to_tflite.py dinov2s_224_slim.onnx out -> out/*_dynamic_range_quant.tflite 를 food_embed.tflite 로 복사.
# GELU 는 tanh 근사로 바꿔 내보낸다 — 정확한 GELU 의 Erf 가 TFLite 기본 연산에 없어 FlexErf(Select TF op)로 들어가고,
# 앱의 LiteRT 는 Flex 를 안 싣고 있어 폰에서 로딩이 실패했다(2026-09-27). 바꾼 뒤 원래 모델과 코사인 평균 0.998.
# 전처리(앱 FoodDetector.embedWith 와 같다): 짧은 변 224 로 맞추고 가운데 224x224, ImageNet 평균·표준편차 정규화.
import timm, torch
m = timm.create_model('vit_small_patch14_dinov2.lvd142m', pretrained=True, num_classes=0, img_size=224).eval()
for mod in m.modules():
    if isinstance(mod, torch.nn.GELU):
        mod.approximate = 'tanh'
torch.onnx.export(m, torch.randn(1, 3, 224, 224), 'dinov2s_224.onnx', opset_version=17,
                  input_names=['image'], output_names=['embedding'], dynamo=False)
print(timm.data.resolve_data_config({'input_size': (3, 224, 224)}, model=m))
