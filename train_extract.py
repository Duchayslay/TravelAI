#!/usr/bin/env python3
"""
Script trích xuất dataset địa danh thông minh và chuẩn xác:
1. Dùng HfApi quét trực tiếp cây thư mục thực tế trên Hugging Face.
2. Tự động tìm đúng các file ảnh địa danh (bỏ qua mọi lỗi 500 API và lỗi cú pháp JSON của tác giả).
3. Tải và phân bổ thành dataset_landmarks/train và dataset_landmarks/val.
"""

import os
import re
import shutil
from collections import Counter
from pathlib import Path
from huggingface_hub import HfApi, hf_hub_download
from PIL import Image

REPO_ID = "Dangindev/viet-cultural-vqa"
OUTPUT_DIR = Path("dataset_landmarks")
TRAIN_DIR = OUTPUT_DIR / "train"
VAL_DIR = OUTPUT_DIR / "val"

TRAIN_DIR.mkdir(parents=True, exist_ok=True)
VAL_DIR.mkdir(parents=True, exist_ok=True)

# Danh sách từ khóa nhận diện trong đường dẫn file ảnh của Hugging Face
LANDMARK_PATTERNS = {
    "chua_mot_cot": ["mot_cot", "motcot"],
    "ho_hoan_kiem": ["hoan_kiem", "ho_guom", "thap_rua", "the_huc", "ngoc_son"],
    "van_mieu": ["van_mieu", "quoc_tu_giam"],
    "chua_tran_quoc": ["tran_quoc"],
    "cau_rong": ["cau_rong"],
    "cau_vang": ["cau_vang"],
    "chua_linh_ung": ["linh_ung"],
    "ngu_hanh_son": ["ngu_hanh_son"],
    "chua_cau_hoi_an": ["chua_cau", "nhat_ban_hoi_an", "hoi_an"],
    "thanh_dia_my_son": ["my_son"],
    "ngo_mon_hue": ["ngo_mon", "kinh_thanh_hue", "hoang_thanh_hue", "an_dinh"],
    "chua_thien_mu": ["thien_mu"],
    "lang_khai_dinh": ["khai_dinh"],
    "lang_tu_duc": ["tu_duc"],
    "cho_ben_thanh": ["ben_thanh"],
    "dinh_doc_lap": ["doc_lap"],
    "nha_tho_duc_ba": ["duc_ba"],
    "cau_long_bien": ["long_bien"],
}


def normalize_string(s: str) -> str:
    s = s.lower()
    patterns = {
        "[àáảãạăằắẳẵặâầấẩẫậ]": "a",
        "[èéẻẽẹêềếểễệ]": "e",
        "[ìíỉĩị]": "i",
        "[òóỏõọôồốổỗộơờớởỡợ]": "o",
        "[ùúủũụưừứửữự]": "u",
        "[ỳýỷỹỵ]": "y",
        "đ": "d",
    }
    for pat, rep in patterns.items():
        s = re.sub(pat, rep, s)
    return re.sub(r"[^a-z0-9_]", "_", s)


def main():
    print("=" * 65)
    print("🚀 BẮT ĐẦU QUÉT VÀ TRÍCH XUẤT ẢNH ĐỊA DANH TỪ HUGGING FACE")
    print(f"🎯 Dataset: {REPO_ID} | Thư mục đích: {OUTPUT_DIR}/")
    print("=" * 65)

    api = HfApi()
    print("⏳ Đang kết nối tới Hugging Face Hub để quét danh sách file...")
    try:
        all_repo_files = api.list_repo_files(repo_id=REPO_ID, repo_type="dataset")
    except Exception as e:
        print(f"❌ Không thể kết nối tới Hugging Face: {e}")
        return

    print(f"📊 Tổng số file trên kho Hugging Face: {len(all_repo_files)}")

    # Lọc các file ảnh thực tế có trong repo
    image_files = [
        f for f in all_repo_files 
        if f.lower().endswith(('.jpg', '.jpeg', '.png', '.webp'))
    ]
    print(f"🖼️ Tổng số file ảnh tìm thấy: {len(image_files)}")

    # Nếu không có ảnh rời, kiểm tra xem có file archive / zip / parquet nào không
    if not image_files:
        other_files = [f for f in all_repo_files if f.endswith(('.zip', '.tar.gz', '.parquet', '.json'))]
        print(f"⚠️ Không có file ảnh rời. Các file dữ liệu khác có trong repo: {other_files[:15]}")
        return

    # Phân loại ảnh theo từng địa danh mục tiêu
    matched_images = {k: [] for k in LANDMARK_PATTERNS}
    for file_path in image_files:
        norm_path = normalize_string(file_path)
        for lm_id, patterns in LANDMARK_PATTERNS.items():
            if any(p in norm_path for p in patterns):
                matched_images[lm_id].append(file_path)
                break

    print("\n🔍 Kết quả tìm kiếm ảnh theo địa danh trên Hugging Face:")
    valid_landmarks = 0
    total_found_images = 0
    for lm_id, files in sorted(matched_images.items()):
        print(f"   🏛️ {lm_id}: {len(files)} ảnh")
        if len(files) > 0:
            valid_landmarks += 1
            total_found_images += len(files)

    print(f"\n🎯 Tìm thấy {total_found_images} ảnh thuộc {valid_landmarks} địa danh mục tiêu.")

    if total_found_images == 0:
        print("⚠️ Không tìm thấy ảnh khớp mẫu tên. In mẫu 10 đường dẫn ảnh đầu tiên để kiểm tra:")
        for sample_f in image_files[:10]:
            print(f"   -> {sample_f}")
        return

    # Bắt đầu tải và lưu ảnh vào train (80%) và val (20%)
    print("\n📥 Đang tiến hành tải ảnh về máy...")
    saved_stats = Counter()

    for lm_id, files in matched_images.items():
        if not files:
            continue

        # Giới hạn tối đa 60 ảnh/lớp để tải nhanh và cân bằng dataset
        selected_files = files[:60]
        train_split_count = int(len(selected_files) * 0.8)
        if train_split_count == 0 and len(selected_files) > 0:
            train_split_count = 1

        lm_train_dir = TRAIN_DIR / lm_id
        lm_val_dir = VAL_DIR / lm_id
        lm_train_dir.mkdir(parents=True, exist_ok=True)
        lm_val_dir.mkdir(parents=True, exist_ok=True)

        for idx, remote_file in enumerate(selected_files):
            is_train = idx < train_split_count
            target_folder = lm_train_dir if is_train else lm_val_dir
            split_label = "train" if is_train else "val"
            dest_file = target_folder / f"{split_label}_{idx:03d}.jpg"

            if dest_file.exists():
                saved_stats[f"{lm_id}_{split_label}"] += 1
                continue

            try:
                downloaded_path = hf_hub_download(
                    repo_id=REPO_ID,
                    repo_type="dataset",
                    filename=remote_file
                )
                with Image.open(downloaded_path) as img:
                    img.convert("RGB").save(dest_file, quality=90)
                saved_stats[f"{lm_id}_{split_label}"] += 1
                print(f"  📸 [{split_label}] {lm_id}: đã tải {idx+1}/{len(selected_files)} ảnh", end="\r")
            except Exception as err:
                print(f"\n⚠️ Lỗi tải file {remote_file}: {err}")

    print("\n" + "=" * 65)
    print("🎉 HOÀN TẤT TẢI DỮ LIỆU ĐỊA DANH!")
    print(f"📁 Thư mục lưu: {OUTPUT_DIR.resolve()}")
    print("=" * 65)


if __name__ == "__main__":
    main()
