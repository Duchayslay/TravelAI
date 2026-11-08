#!/usr/bin/env python3
"""
Script kiểm thử thực tế mô hình nhận diện địa danh TravelAI.
Cách dùng:
  1. Thử 1 ảnh bất kỳ trên máy:
     ./.venv/bin/python predict.py /duong_dan/toi/anh.jpg

  2. Thử 1 link ảnh trực tiếp từ internet:
     ./.venv/bin/python predict.py "https://upload.wikimedia.org/wikipedia/commons/4/47/Chua_mot_cot_hanoi.jpg"

  3. Không truyền tham số (tự chọn ngẫu nhiên 1 ảnh val để test):
     ./.venv/bin/python predict.py
"""

import io
import json
import random
import sys
import time
import urllib.request
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image
from torchvision import transforms

ONNX_MODEL = "landmark_classifier.onnx"
CLASSES_FILE = "classes.json"
ONNX_MODEL = "services/ai/app/models/landmark_classifier.onnx" if Path("services/ai/app/models/landmark_classifier.onnx").exists() else "landmark_classifier.onnx"
CLASSES_FILE = "services/ai/app/models/classes.json" if Path("services/ai/app/models/classes.json").exists() else "classes.json"
VAL_DIR = Path("dataset_landmarks/val")
IMAGE_SIZE = 224

# Tên tiếng Việt hiển thị đẹp cho từng địa danh
VIETNAMESE_NAMES = {
    "cau_long_bien": "Cầu Long Biên (Hà Nội)",
    "cau_rong": "Cầu Rồng (Đà Nẵng)",
    "cau_vang": "Cầu Vàng Bà Nà Hills (Đà Nẵng)",
    "cho_ben_thanh": "Chợ Bến Thành (TP. Hồ Chí Minh)",
    "chua_cau_hoi_an": "Chùa Cầu / Cầu Nhật Bản (Hội An)",
    "chua_linh_ung": "Chùa Linh Ứng Bán Đảo Sơn Trà (Đà Nẵng)",
    "chua_mot_cot": "Chùa Một Cột (Hà Nội)",
    "chua_tran_quoc": "Chùa Trấn Quốc (Hà Nội)",
    "dinh_doc_lap": "Dinh Độc Lập (TP. Hồ Chí Minh)",
    "ho_hoan_kiem": "Hồ Hoàn Kiếm / Tháp Rùa (Hà Nội)",
    "lang_khai_dinh": "Lăng Khải Định (Huế)",
    "lang_tu_duc": "Lăng Tự Đức (Huế)",
    "ngo_mon_hue": "Ngọ Môn / Kinh Thành Huế (Huế)",
    "ngu_hanh_son": "Ngũ Hành Sơn (Đà Nẵng)",
    "nha_tho_duc_ba": "Nhà Thờ Đức Bà (TP. Hồ Chí Minh)",
    "thanh_dia_my_son": "Thánh Địa Mỹ Sơn (Quảng Nam)",
    "van_mieu": "Văn Miếu Quốc Tử Giám (Hà Nội)",
}


def load_model():
    if not Path(ONNX_MODEL).exists() or not Path(CLASSES_FILE).exists():
        print(f"❌ Không tìm thấy '{ONNX_MODEL}' hoặc '{CLASSES_FILE}'.")
        exit(1)

    with open(CLASSES_FILE, "r", encoding="utf-8") as f:
        classes = json.load(f)

    session = ort.InferenceSession(ONNX_MODEL, providers=["CPUExecutionProvider"])
    return session, classes


def load_image(input_source: str) -> Image.Image:
    """Tải ảnh từ file local hoặc link URL trên internet."""
    if input_source.startswith("http://") or input_source.startswith("https://"):
        print(f"🌐 Đang tải ảnh từ URL: {input_source}...")
        req = urllib.request.Request(input_source, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=15) as resp:
            img_data = resp.read()
        return Image.open(io.BytesIO(img_data)).convert("RGB")
    else:
        path = Path(input_source)
        if not path.exists():
            raise FileNotFoundError(f"Không tìm thấy file ảnh tại: {input_source}")
        return Image.open(path).convert("RGB")


def predict(session, classes, img: Image.Image):
    val_transforms = transforms.Compose([
        transforms.Resize((IMAGE_SIZE, IMAGE_SIZE)),
        transforms.ToTensor(),
        transforms.Normalize([0.485, 0.456, 0.406], [0.229, 0.224, 0.225])
    ])

    tensor_img = val_transforms(img).unsqueeze(0).numpy()
    input_name = session.get_inputs()[0].name

    start_time = time.perf_counter()
    outputs = session.run(None, {input_name: tensor_img})
    latency_ms = (time.perf_counter() - start_time) * 1000

    logits = outputs[0][0]
    exp_logits = np.exp(logits - np.max(logits))
    probs = exp_logits / np.sum(exp_logits)

    # Lấy Top 3 kết quả cao nhất
    top_indices = np.argsort(probs)[::-1][:3]
    results = []
    for idx in top_indices:
        cls_code = classes[str(idx)]
        friendly_name = VIETNAMESE_NAMES.get(cls_code, cls_code)
        conf = float(probs[idx])
        results.append((cls_code, friendly_name, conf))

    return results, latency_ms


def main():
    print("=" * 65)
    print("🏛️ HỆ THỐNG NHẬN DIỆN ĐỊA DANH TRAVELAI - KIỂM THỬ THỰC TẾ")
    print("=" * 65)

    session, classes = load_model()

    if len(sys.argv) > 1:
        image_source = sys.argv[1]
    else:
        # Tự chọn ngẫu nhiên 1 ảnh trong val
        val_images = list(VAL_DIR.glob("*/*.jpg"))
        if not val_images:
            print("❌ Vui lòng truyền đường dẫn ảnh. Ví dụ: ./.venv/bin/python predict.py anh.jpg")
            return
        selected = random.choice(val_images)
        print(f"ℹ️ Bạn chưa truyền ảnh, tự động chọn ảnh ngẫu nhiên trong tập val: {selected.name}")
        print(f"📌 Địa danh gốc thực tế: {selected.parent.name}")
        image_source = str(selected)

    try:
        img = load_image(image_source)
        results, latency_ms = predict(session, classes, img)

        top_code, top_name, top_conf = results[0]

        print("\n" + "-" * 65)
        print(f"🏆 KẾT QUẢ DỰ ĐOÁN TỐT NHẤT:")
        print(f"   🏛️ Địa danh : {top_name}")
        print(f"   🎯 Độ tin cậy: {top_conf * 100:.2f}%")
        print(f"   ⏱️ Thời gian: {latency_ms:.2f} ms")
        print("-" * 65)

        print("\n📊 TOP 3 DỰ ĐOÁN CAO NHẤT:")
        for rank, (code, name, conf) in enumerate(results, 1):
            bar_len = int(conf * 30)
            bar = "█" * bar_len + "░" * (30 - bar_len)
            print(f"   #{rank} [{conf*100:>5.1f}%] {bar}  {name}")

        print("\n" + "=" * 65)
    except Exception as e:
        print(f"\n❌ Lỗi khi đọc hoặc nhận diện ảnh: {e}")


if __name__ == "__main__":
    main()

