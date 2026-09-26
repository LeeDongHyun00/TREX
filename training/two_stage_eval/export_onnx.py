import sys, os
from ultralytics import YOLO, YOLOWorld
PROMPTS = ['food', 'bowl of food', 'plate of food', 'dish', 'bowl', 'plate', 'rice', 'soup', 'side dish']
name = sys.argv[1]
if 'world' in name:
    m = YOLOWorld(name + '.pt'); m.set_classes(PROMPTS); m.save(name + '_food.pt'); m = YOLO(name + '_food.pt'); out = name + '_food'
elif 'yoloe' in name:
    from ultralytics import YOLOE
    m = YOLOE(name + '.pt'); m.set_classes(PROMPTS, m.get_text_pe(PROMPTS)); out = name
else:
    m = YOLO(name + '.pt'); out = name
print('ONNX', m.export(format='onnx', imgsz=640, opset=17, simplify=True), flush=True)
