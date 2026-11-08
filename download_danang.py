#!/usr/bin/env python3
"""
Script bổ sung dứt điểm 3 địa danh còn lại của Đà Nẵng:
- Cầu Rồng (cau_rong)
- Cầu Vàng (cau_vang)
- Ngũ Hành Sơn (ngu_hanh_son)
Sử dụng truy vấn du lịch quốc tế (ASCII) để Bing không bao giờ chặn hay trả về 0 kết quả.
"""

import shutil
from pathlib import Path
from PIL import Image

try:
    from bing_image_downloader import downloader
except ImportError:
    print("❌ Vui lòng cài: ./.venv/bin/pip install bing-image-downloader")
    exit(1)

OUTPUT_DIR = Path("dataset_landmarks")
TRAIN_DIR = OUTPUT_DIR / "train"
VAL_DIR = OUTPUT_DIR / "val"
TEMP_DIR = Path("temp_danang_downloads")

# Sử dụng cụm từ tiếng Anh / không dấu để Bing tìm kiếm chính xác 100%
MISSING_LANDMARKS = {
    "cau_rong": ["dragon bridge da nang", "cau rong da nang"],
    "cau_vang": ["golden bridge ba na da nang", "golden bridge da nang"],
    "ngu_hanh_son": ["marble mountains da nang", "ngu hanh son da nang"]
}

TARGET_PER_CLASS = 45


def main():
    print("=" * 65)
    print("🚀 BỔ SUNG DỨT ĐIỂM 3 ĐỊA DANH ĐÀ NẴNG (CẦU RỒNG, CẦU VÀNG, NGŨ HÀNH SƠN)")
    print("=" * 65)

    TEMP_DIR.mkdir(parents=True, exist_ok=True)

    for lm_id, queries in MISSING_LANDMARKS.items():
        lm_train = TRAIN_DIR / lm_id
        lm_val = VAL_DIR / lm_id

        # Nếu đã có ảnh thì bỏ qua
        existing_images = list(lm_train.glob("*.jpg"))
        if len(existing_images) >= 20:
            print(f"ℹ️ '{lm_id}' đã có sẵn {len(existing_images)} ảnh, bỏ qua.")
            continue

        lm_train.mkdir(parents=True, exist_ok=True)
        lm_val.mkdir(parents=True, exist_ok=True)

        downloaded_valid = []
        for q in queries:
            if len(downloaded_valid) >= TARGET_PER_CLASS:
                break

            print(f"\n⏳ Đang tìm kiếm '{lm_id}' với từ khóa: '{q}'...")
            q_dir = TEMP_DIR / q
            if q_dir.exists():
                shutil.rmtree(q_dir)

            try:
                downloader.download(
                    query=q,
                    limit=TARGET_PER_CLASS - len(downloaded_valid) + 10,
                    output_dir=str(TEMP_DIR),
                    adult_filter_off=True,
                    force_replace=True,
                    timeout=20,
                    verbose=False
                )
            except Exception as e:
                print(f"⚠️ Thử truy vấn tiếp theo, lỗi: {e}")

            if q_dir.exists():
                for f in q_dir.glob("*.*"):
                    try:
                        with Image.open(f) as img:
                            if img.size[0] >= 150 and img.size[1] >= 150:
                                downloaded_valid.append(img.convert("RGB"))
                    except Exception:
                        continue

        if not downloaded_valid:
            print(f"⚠️ Không tải được ảnh cho '{lm_id}', tạm xóa thư mục rỗng để không lỗi train.")
            shutil.rmtree(lm_train, ignore_errors=True)
            shutil.rmtree(lm_val, ignore_errors=True)
            continue

        # Phân bổ train (80%) và val (20%)
        train_count = int(len(downloaded_valid) * 0.8)
        if train_count == 0 and downloaded_valid:
            train_count = 1

        for idx, img in enumerate(downloaded_valid[:TARGET_PER_CLASS]):
            if idx < train_count:
                dest = lm_train / f"train_{idx:03d}.jpg"
            else:
                dest = lm_val / f"val_{idx - train_count:03d}.jpg"
            img.save(dest, "JPEG", quality=90)

        n_train = len(list(lm_train.glob("*.jpg")))
        n_val = len(list(lm_val.glob("*.jpg")))
        print(f"✅ Hoàn tất '{lm_id}': đã lưu {n_train} ảnh train | {n_val} ảnh val.")

    if TEMP_DIR.exists():
        shutil.rmtree(TEMP_DIR)

    # Dọn dẹp thư mục rỗng nếu có
    for p in [TRAIN_DIR, VAL_DIR]:
        for d in list(p.iterdir()):
            if d.is_dir() and len(list(d.glob("*.jpg"))) == 0:
                d.rmdir()

    print("\n" + "=" * 65)
    print("🎉 HOÀN TẤT BỔ SUNG! DATASET ĐÃ SẴN SÀNG HUẤN LUYỆN.")
    print("=" * 65)

    train_classes = sorted([d.name for d in TRAIN_DIR.iterdir() if d.is_dir()])
    print(f"\n📊 DANH SÁCH {len(train_classes)} LỚP ĐỊA DANH SẴN SÀNG HUẤN LUYỆN:")
    for cls in train_classes:
        n_t = len(list((TRAIN_DIR / cls).glob("*.jpg")))
        n_v = len(list((VAL_DIR / cls).glob("*.jpg")))
        print(f"   🏛️ {cls:<20}: {n_t:>2} train | {n_v:>2} val")


if __name__ == "__main__":
    main()
