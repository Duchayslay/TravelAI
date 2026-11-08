#!/usr/bin/env python3
"""
Giao diện Web trực quan (Gradio) để kiểm thử thực tế mô hình nhận diện địa danh.
Cách dùng:
  1. Cài gradio: ./.venv/bin/pip install gradio
  2. Khởi chạy: ./.venv/bin/python web_demo.py
  3. Mở trình duyệt tại: http://127.0.0.1:7860
"""

import json
import time
from pathlib import Path
import numpy as np
import onnxruntime as ort
from PIL import Image
from torchvision import transforms

try:
    import gradio as gr
except ImportError:
    print("❌ Vui lòng cài gradio trước: ./.venv/bin/pip install gradio")
    exit(1)

ONNX_MODEL = "services/ai/app/models/landmark_classifier.onnx" if Path("services/ai/app/models/landmark_classifier.onnx").exists() else "landmark_classifier.onnx"
CLASSES_FILE = "services/ai/app/models/classes.json" if Path("services/ai/app/models/classes.json").exists() else "classes.json"
IMAGE_SIZE = 224

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

# Khởi tạo mô hình
with open(CLASSES_FILE, "r", encoding="utf-8") as f:
    classes = json.load(f)
session = ort.InferenceSession(ONNX_MODEL, providers=["CPUExecutionProvider"])
input_name = session.get_inputs()[0].name

val_transforms = transforms.Compose([
    transforms.Resize((IMAGE_SIZE, IMAGE_SIZE)),
    transforms.ToTensor(),
    transforms.Normalize([0.485, 0.456, 0.406], [0.229, 0.224, 0.225])
])


def predict_landmark(image):
    if image is None:
        return {}, "Vui lòng tải lên một bức ảnh!"

    # Tiền xử lý
    rgb_img = Image.fromarray(image).convert("RGB")
    tensor_img = val_transforms(rgb_img).unsqueeze(0).numpy()

    # Suy luận
    t0 = time.perf_counter()
    outputs = session.run(None, {input_name: tensor_img})
    latency_ms = (time.perf_counter() - t0) * 1000

    logits = outputs[0][0]
    exp_logits = np.exp(logits - np.max(logits))
    probs = exp_logits / np.sum(exp_logits)

    # Lấy phân phối xác suất
    confidences = {}
    for idx, prob in enumerate(probs):
        cls_code = classes[str(idx)]
        friendly_name = VIETNAMESE_NAMES.get(cls_code, cls_code)
        confidences[friendly_name] = float(prob)

    top_idx = int(np.argmax(probs))
    top_name = VIETNAMESE_NAMES.get(classes[str(top_idx)], classes[str(top_idx)])
    top_conf = float(probs[top_idx])

    info_text = f"🏆 **Dự đoán**: {top_name}\n🎯 **Độ tin cậy**: {top_conf*100:.2f}%\n⏱️ **Thời gian suy luận**: {latency_ms:.1f} ms"
    return confidences, info_text


demo = gr.Interface(
    fn=predict_landmark,
    inputs=gr.Image(type="numpy", label="Tải ảnh địa danh cần nhận diện"),
    outputs=[
        gr.Label(num_top_classes=3, label="Top 3 địa danh dự đoán"),
        gr.Markdown(label="Thông tin chi tiết")
    ],
    title="🏛️ TravelAI - Nhận Diện Địa Danh Du Lịch Việt Nam",
    description="Tải ảnh bất kỳ hoặc chụp ảnh địa danh để mô hình AI nhận diện tên công trình, tọa độ và độ tin cậy trong vòng chớp mắt."
)

if __name__ == "__main__":
    print("🚀 Đang khởi động Web Demo tại http://127.0.0.1:7860...")
    demo.launch(server_name="127.0.0.1", server_port=7860)

