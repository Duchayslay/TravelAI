#!/usr/bin/env python3
"""
Script huấn luyện mô hình nhận diện địa danh TravelAI (Transfer Learning EfficientNetV2-S).
- Đồng bộ chuẩn hóa nhãn (class_to_idx) giữa tập Train và Validation (tránh lệch nhãn).
- Tự động lưu trọng số tốt nhất vào 'best_model.pth'.
- Tự động xuất mô hình sang ONNX ('landmark_classifier.onnx') dùng TorchScript Exporter ổn định.
"""

import json
import os
import shutil
import time
from pathlib import Path

import torch
import torch.nn as nn
import torch.optim as optim
from torch.utils.data import DataLoader
from torchvision import datasets, models, transforms

DATASET_DIR = Path("dataset_landmarks")
TRAIN_DIR = DATASET_DIR / "train"
VAL_DIR = DATASET_DIR / "val"
MODEL_WEIGHTS = "best_model.pth"
ONNX_OUTPUT = "landmark_classifier.onnx"
CLASSES_OUTPUT = "classes.json"

IMAGE_SIZE = 224
BATCH_SIZE = 16
DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")


def cleanup_and_sync():
    """Loại bỏ thư mục rỗng và đảm bảo train/val đồng bộ."""
    for root_dir in [TRAIN_DIR, VAL_DIR]:
        if root_dir.exists():
            for folder in list(root_dir.iterdir()):
                if folder.is_dir():
                    images = list(folder.glob("*.jpg")) + list(folder.glob("*.png")) + list(folder.glob("*.jpeg"))
                    if len(images) == 0:
                        shutil.rmtree(folder, ignore_errors=True)


def get_data_loaders():
    cleanup_and_sync()

    train_transform = transforms.Compose([
        transforms.Resize((IMAGE_SIZE, IMAGE_SIZE)),
        transforms.RandomHorizontalFlip(p=0.5),
        transforms.RandomRotation(degrees=15),
        transforms.ColorJitter(brightness=0.2, contrast=0.2, saturation=0.2),
        transforms.ToTensor(),
        transforms.Normalize([0.485, 0.456, 0.406], [0.229, 0.224, 0.225])
    ])

    val_transform = transforms.Compose([
        transforms.Resize((IMAGE_SIZE, IMAGE_SIZE)),
        transforms.ToTensor(),
        transforms.Normalize([0.485, 0.456, 0.406], [0.229, 0.224, 0.225])
    ])

    train_dataset = datasets.ImageFolder(str(TRAIN_DIR), transform=train_transform)
    val_dataset = datasets.ImageFolder(str(VAL_DIR), transform=val_transform)

    # ĐẢM BẢO CLASS_TO_IDX GIỮA TRAIN VÀ VAL LUÔN KHỚP 100%
    val_dataset.class_to_idx = train_dataset.class_to_idx
    val_dataset.classes = train_dataset.classes

    train_loader = DataLoader(train_dataset, batch_size=BATCH_SIZE, shuffle=True, num_workers=0)
    val_loader = DataLoader(val_dataset, batch_size=BATCH_SIZE, shuffle=False, num_workers=0)

    return train_loader, val_loader, train_dataset.classes, len(train_dataset), len(val_dataset)


def export_to_onnx(model, num_classes, output_path=ONNX_OUTPUT):
    """Xuất mô hình sang ONNX với chế độ tương thích cao nhất."""
    print(f"\n📦 Đang xuất mô hình sang định dạng ONNX: '{output_path}'...")
    model.eval()
    dummy_input = torch.randn(1, 3, IMAGE_SIZE, IMAGE_SIZE, device=DEVICE)

    try:
        # Thử xuất với legacy TorchScript backend (không bắt buộc onnxscript)
        torch.onnx.export(
            model,
            dummy_input,
            output_path,
            export_params=True,
            opset_version=14,
            do_constant_folding=True,
            input_names=['input_image'],
            output_names=['logits'],
            dynamic_axes={'input_image': {0: 'batch_size'}, 'logits': {0: 'batch_size'}},
            dynamo=False
        )
    except TypeError:
        # Nếu phiên bản PyTorch không có tham số dynamo
        torch.onnx.export(
            model,
            dummy_input,
            output_path,
            export_params=True,
            opset_version=14,
            do_constant_folding=True,
            input_names=['input_image'],
            output_names=['logits'],
            dynamic_axes={'input_image': {0: 'batch_size'}, 'logits': {0: 'batch_size'}}
        )

    size_mb = os.path.getsize(output_path) / (1024 * 1024)
    print(f"🎉 Xuất ONNX thành công! Dung lượng file: {size_mb:.2f} MB")


def train():
    print("=" * 65)
    print("🚀 BẮT ĐẦU HUẤN LUYỆN MÔ HÌNH NHẬN DIỆN ĐỊA DANH TRAVELAI")
    print(f"⚙️ Thiết bị tính toán: {DEVICE}")
    print("=" * 65)

    train_loader, val_loader, class_names, n_train, n_val = get_data_loaders()
    num_classes = len(class_names)
    print(f"📊 Tổng số lớp địa danh: {num_classes} ({n_train} ảnh train | {n_val} ảnh val)")
    for idx, c in enumerate(class_names):
        print(f"   [{idx:2d}] {c}")

    with open(CLASSES_OUTPUT, "w", encoding="utf-8") as f:
        json.dump({i: name for i, name in enumerate(class_names)}, f, ensure_ascii=False, indent=2)
    print(f"💾 Đã lưu danh mục nhãn vào '{CLASSES_OUTPUT}'")

    print("\n⏳ Đang tải pre-trained weights EfficientNetV2-S từ PyTorch...")
    weights = models.EfficientNet_V2_S_Weights.DEFAULT
    model = models.efficientnet_v2_s(weights=weights)

    # Pha 1: Đóng băng backbone
    print("\n🔒 Pha 1: Đóng băng backbone, huấn luyện Classifier Head (3 epoch)...")
    for param in model.features.parameters():
        param.requires_grad = False

    in_features = model.classifier[1].in_features
    model.classifier = nn.Sequential(
        nn.Dropout(p=0.3, inplace=True),
        nn.Linear(in_features, num_classes)
    )

    model = model.to(DEVICE)
    criterion = nn.CrossEntropyLoss()
    optimizer = optim.AdamW(model.classifier.parameters(), lr=1e-3, weight_decay=1e-4)

    best_acc = 0.0

    def run_epoch(model, optimizer, is_train=True):
        if is_train:
            model.train()
            loader = train_loader
            total_samples = n_train
        else:
            model.eval()
            loader = val_loader
            total_samples = n_val

        running_loss = 0.0
        running_corrects = 0

        with torch.set_grad_enabled(is_train):
            for inputs, labels in loader:
                inputs, labels = inputs.to(DEVICE), labels.to(DEVICE)
                if is_train:
                    optimizer.zero_grad()

                outputs = model(inputs)
                _, preds = torch.max(outputs, 1)
                loss = criterion(outputs, labels)

                if is_train:
                    loss.backward()
                    optimizer.step()

                running_loss += loss.item() * inputs.size(0)
                running_corrects += torch.sum(preds == labels.data).item()

        epoch_loss = running_loss / total_samples
        epoch_acc = running_corrects / total_samples
        return epoch_loss, epoch_acc

    # Chạy Pha 1 (3 epoch)
    for epoch in range(1, 4):
        t0 = time.time()
        tr_loss, tr_acc = run_epoch(model, optimizer, is_train=True)
        val_loss, val_acc = run_epoch(model, optimizer, is_train=False)
        dur = time.time() - t0
        print(f"Pha 1 - Epoch {epoch}/3 ({dur:.1f}s) | Train Acc: {tr_acc*100:.2f}% | Val Acc: {val_acc*100:.2f}% (Loss: {val_loss:.4f})")
        if val_acc > best_acc:
            best_acc = val_acc
            torch.save(model.state_dict(), MODEL_WEIGHTS)
            print(f"   ⭐ Lưu checkpoint tốt nhất: Val Acc = {best_acc*100:.2f}%")

    # Pha 2: Fine-tune toàn bộ mạng
    print("\n🔓 Pha 2: Mở khóa Fine-tune toàn diện mạng nơ-ron (5 epoch)...")
    for param in model.features.parameters():
        param.requires_grad = True

    optimizer_finetune = optim.AdamW(model.parameters(), lr=1e-4, weight_decay=1e-4)
    scheduler = optim.lr_scheduler.CosineAnnealingLR(optimizer_finetune, T_max=5)

    for epoch in range(1, 6):
        t0 = time.time()
        tr_loss, tr_acc = run_epoch(model, optimizer_finetune, is_train=True)
        val_loss, val_acc = run_epoch(model, optimizer_finetune, is_train=False)
        scheduler.step()
        dur = time.time() - t0
        print(f"Pha 2 - Epoch {epoch}/5 ({dur:.1f}s) | Train Acc: {tr_acc*100:.2f}% | Val Acc: {val_acc*100:.2f}% (Loss: {val_loss:.4f})")
        if val_acc > best_acc:
            best_acc = val_acc
            torch.save(model.state_dict(), MODEL_WEIGHTS)
            print(f"   ⭐ Lưu checkpoint tốt nhất: Val Acc = {best_acc*100:.2f}%")

    print(f"\n🏆 Huấn luyện hoàn tất! Độ chính xác cao nhất (Val Acc): {best_acc*100:.2f}%")

    # Load lại trọng số tốt nhất để export sang ONNX
    if os.path.exists(MODEL_WEIGHTS):
        model.load_state_dict(torch.load(MODEL_WEIGHTS, weights_only=True))

    export_to_onnx(model, num_classes, ONNX_OUTPUT)
    print("=" * 65)


if __name__ == "__main__":
    train()
