#!/usr/bin/env python3
"""Script xuất file ONNX từ trọng số best_model.pth đã huấn luyện."""

import json
import os
from pathlib import Path
import torch
import torch.nn as nn
from torchvision import models

MODEL_WEIGHTS = "best_model.pth"
CLASSES_FILE = "classes.json"
ONNX_OUTPUT = "landmark_classifier.onnx"
IMAGE_SIZE = 224

def main():
    if not os.path.exists(MODEL_WEIGHTS) or not os.path.exists(CLASSES_FILE):
        print(f"❌ Không tìm thấy '{MODEL_WEIGHTS}' hoặc '{CLASSES_FILE}'. Vui lòng chạy train_model.py trước.")
        return

    with open(CLASSES_FILE, "r", encoding="utf-8") as f:
        classes = json.load(f)
    num_classes = len(classes)
    print(f"📊 Đang xuất mô hình cho {num_classes} lớp địa danh...")

    # Khởi tạo kiến trúc mô hình
    weights = models.EfficientNet_V2_S_Weights.DEFAULT
    model = models.efficientnet_v2_s(weights=weights)
    in_features = model.classifier[1].in_features
    model.classifier = nn.Sequential(
        nn.Dropout(p=0.3, inplace=True),
        nn.Linear(in_features, num_classes)
    )

    # Nạp trọng số đã huấn luyện
    model.load_state_dict(torch.load(MODEL_WEIGHTS, map_location="cpu", weights_only=True))
    model.eval()

    dummy_input = torch.randn(1, 3, IMAGE_SIZE, IMAGE_SIZE)
    print(f"📦 Đang xuất sang '{ONNX_OUTPUT}'...")

    try:
        torch.onnx.export(
            model,
            dummy_input,
            ONNX_OUTPUT,
            export_params=True,
            opset_version=14,
            do_constant_folding=True,
            input_names=['input_image'],
            output_names=['logits'],
            dynamic_axes={'input_image': {0: 'batch_size'}, 'logits': {0: 'batch_size'}},
            dynamo=False
        )
    except TypeError:
        torch.onnx.export(
            model,
            dummy_input,
            ONNX_OUTPUT,
            export_params=True,
            opset_version=14,
            do_constant_folding=True,
            input_names=['input_image'],
            output_names=['logits'],
            dynamic_axes={'input_image': {0: 'batch_size'}, 'logits': {0: 'batch_size'}}
        )

    size_mb = os.path.getsize(ONNX_OUTPUT) / (1024 * 1024)
    print(f"🎉 Xuất ONNX thành công! Kích thước: {size_mb:.2f} MB")

if __name__ == "__main__":
    main()

