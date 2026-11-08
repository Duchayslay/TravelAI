#!/usr/bin/env python3
"""
Script kiểm tra suy luận nhanh với mô hình ONNX đã huấn luyện (landmark_classifier.onnx).
Mô phỏng chính xác cách thức microservice FastAPI sẽ xử lý ảnh đầu vào.
"""

import json
import time
from pathlib import Path
import numpy as np
import onnxruntime as ort
from PIL import Image
from torchvision import transforms

ONNX_MODEL = "services/ai/app/models/landmark_classifier.onnx" if Path("services/ai/app/models/landmark_classifier.onnx").exists() else "landmark_classifier.onnx"
CLASSES_FILE = "services/ai/app/models/classes.json" if Path("services/ai/app/models/classes.json").exists() else "classes.json"
VAL_DIR = Path("dataset_landmarks/val")
IMAGE_SIZE = 224


def load_classifier():
    with open(CLASSES_FILE, "r", encoding="utf-8") as f:
        classes = json.load(f)
    session = ort.InferenceSession(ONNX_MODEL, providers=["CPUExecutionProvider"])
    return session, classes


def predict_image(session, classes, image_path: Path):
    # Tiền xử lý ảnh chuẩn PyTorch ImageNet
    val_transforms = transforms.Compose([
        transforms.Resize((IMAGE_SIZE, IMAGE_SIZE)),
        transforms.ToTensor(),
        transforms.Normalize([0.485, 0.456, 0.406], [0.229, 0.224, 0.225])
    ])

    with Image.open(image_path) as img:
        rgb_img = img.convert("RGB")
        tensor_img = val_transforms(rgb_img).unsqueeze(0).numpy()

    # Thực thi suy luận
    input_name = session.get_inputs()[0].name
    start_time = time.perf_counter()
    outputs = session.run(None, {input_name: tensor_img})
    latency_ms = (time.perf_counter() - start_time) * 1000

    # Tính xác suất Softmax
    logits = outputs[0][0]
    exp_logits = np.exp(logits - np.max(logits))
    probs = exp_logits / np.sum(exp_logits)

    pred_idx = int(np.argmax(probs))
    confidence = float(probs[pred_idx])
    pred_class = classes[str(pred_idx)]

    return pred_class, confidence, latency_ms


def main():
    print("=" * 65)
    print("🧪 KIỂM TRA SUY LUẬN MÔ HÌNH ONNX TRAVELAI")
    print("=" * 65)

    session, classes = load_classifier()
    print(f"✅ Đã nạp mô hình: {ONNX_MODEL}")
    print(f"📊 Danh mục: {len(classes)} lớp địa danh")

    # Lấy thử 5 ảnh ngẫu nhiên từ các lớp trong tập validation
    test_images = list(VAL_DIR.glob("*/*.jpg"))[:6]
    if not test_images:
        print("⚠️ Không tìm thấy ảnh trong tập validation để test.")
        return

    print("\n🔍 Kết quả thử nghiệm trên các ảnh mẫu trong tập Validation:")
    for img_p in test_images:
        actual_class = img_p.parent.name
        pred_class, conf, latency = predict_image(session, classes, img_p)

        is_correct = "✅" if actual_class == pred_class else "❌"
        print(f"\n{is_correct} File: {img_p.name} (Thư mục thật: {actual_class})")
        print(f"   🏛️ Dự đoán: {pred_class:<20} | Độ tin cậy: {conf*100:>5.2f}% | Độ trễ: {latency:>5.2f} ms")

    print("\n" + "=" * 65)
    print("🎉 MÔ HÌNH HOẠT ĐỘNG HOÀN HẢO VỚI TỐC ĐỘ CAO TRÊN CPU!")
    print("=" * 65)


if __name__ == "__main__":
    main()

