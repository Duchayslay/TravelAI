"""Local ONNX Landmark Classifier for TravelAI Vision Microservice."""

import json
import logging
from pathlib import Path
from typing import Any

import numpy as np
from PIL import Image

logger = logging.getLogger("travelai.ai.onnx")

try:
    import onnxruntime as ort
    HAS_ONNX = True
except ImportError:
    HAS_ONNX = False
    logger.warning("onnxruntime is not installed. Local ONNX classifier will be disabled.")


# Cơ sở tri thức chuẩn xác cho 17 địa danh Việt Nam đã huấn luyện
LANDMARK_KNOWLEDGE_BASE: dict[str, dict[str, Any]] = {
    "cau_long_bien": {
        "name": "Cầu Long Biên",
        "latitude": 21.0435,
        "longitude": 105.8573,
        "description": "Cây cầu thép lịch sử bắc qua sông Hồng tại Hà Nội, chứng nhân lịch sử kiên cường.",
        "keywords": ["cầu long biên", "sông hồng", "long bien bridge", "hà nội"],
    },
    "cau_rong": {
        "name": "Cầu Rồng",
        "latitude": 16.0611,
        "longitude": 108.2274,
        "description": "Cây cầu hình rồng bắc qua sông Hàn tại Đà Nẵng, biểu tượng vươn mình của thành phố biển.",
        "keywords": ["cầu rồng", "dragon bridge", "sông hàn", "đà nẵng"],
    },
    "cau_vang": {
        "name": "Cầu Vàng (Bà Nà Hills)",
        "latitude": 15.9950,
        "longitude": 107.9965,
        "description": "Kỳ quan kiến trúc cầu bàn tay khổng lồ vươn giữa mây trời tại Bà Nà Hills, Đà Nẵng.",
        "keywords": ["cầu vàng", "golden bridge", "bà nà hills", "đà nẵng"],
    },
    "cho_ben_thanh": {
        "name": "Chợ Bến Thành",
        "latitude": 10.7725,
        "longitude": 106.6980,
        "description": "Ngôi chợ biểu tượng hơn 100 năm tuổi tại trung tâm Thành phố Hồ Chí Minh.",
        "keywords": ["chợ bến thành", "ben thanh market", "quận 1", "sài gòn"],
    },
    "chua_cau_hoi_an": {
        "name": "Chùa Cầu (Cầu Nhật Bản)",
        "latitude": 15.8771,
        "longitude": 108.3260,
        "description": "Di tích kiến trúc cổ kính giao thoa văn hóa Việt - Nhật tại đô thị cổ Hội An, di sản UNESCO.",
        "keywords": ["chùa cầu", "cầu nhật bản", "phố cổ hội an", "quảng nam"],
    },
    "chua_linh_ung": {
        "name": "Chùa Linh Ứng (Bán đảo Sơn Trà)",
        "latitude": 16.1002,
        "longitude": 108.2778,
        "description": "Ngôi chùa linh thiêng với tượng Phật Bà Quan Thế Âm cao nhất Việt Nam tại Đà Nẵng.",
        "keywords": ["chùa linh ứng", "bán đảo sơn trà", "quán thế âm", "đà nẵng"],
    },
    "chua_mot_cot": {
        "name": "Chùa Một Cột",
        "latitude": 21.0358,
        "longitude": 105.8335,
        "description": "Đóa sen nghìn năm tuổi giữa lòng Hà Nội, công trình Phật giáo thời Lý độc nhất vô nhị ở châu Á.",
        "keywords": ["chùa một cột", "diên hựu tự", "thời lý", "hà nội"],
    },
    "chua_tran_quoc": {
        "name": "Chùa Trấn Quốc",
        "latitude": 21.0478,
        "longitude": 105.8366,
        "description": "Ngôi chùa cổ nhất thủ đô Hà Nội với lịch sử hơn 1.500 năm, tọa lạc trên bán đảo Hồ Tây.",
        "keywords": ["chùa trấn quốc", "hồ tây", "trấn quốc pagoda", "hà nội"],
    },
    "dinh_doc_lap": {
        "name": "Dinh Độc Lập (Dinh Thống Nhất)",
        "latitude": 10.7770,
        "longitude": 106.6953,
        "description": "Di tích lịch sử quốc gia đặc biệt, nơi chứng kiến thời khắc lịch sử thống nhất đất nước ngày 30/4/1975.",
        "keywords": ["dinh độc lập", "dinh thống nhất", "reunification palace", "sài gòn"],
    },
    "ho_hoan_kiem": {
        "name": "Hồ Hoàn Kiếm (Hồ Gươm)",
        "latitude": 21.0285,
        "longitude": 105.8542,
        "description": "Trái tim của thủ đô Hà Nội gắn liền với truyền thuyết vua Lê Lợi trả gươm báu cho Rùa Vàng.",
        "keywords": ["hồ hoàn kiếm", "hồ gươm", "tháp rùa", "cầu thê húc", "hà nội"],
    },
    "lang_khai_dinh": {
        "name": "Lăng Khải Định (Ứng Lăng)",
        "latitude": 16.3989,
        "longitude": 107.5904,
        "description": "Công trình lăng tẩm kết hợp tinh xảo giữa kiến trúc truyền thống phương Đông và Tây phương tại Huế.",
        "keywords": ["lăng khải định", "ứng lăng", "cố đô huế", "thừa thiên huế"],
    },
    "lang_tu_duc": {
        "name": "Lăng Tự Đức (Khiêm Lăng)",
        "latitude": 16.4328,
        "longitude": 107.5658,
        "description": "Một trong những lăng tẩm thơ mộng và quy mô bậc nhất của các hoàng đế triều Nguyễn tại Huế.",
        "keywords": ["lăng tự đức", "khiêm lăng", "nhà nguyễn", "huế"],
    },
    "ngo_mon_hue": {
        "name": "Ngọ Môn (Đại Nội Huế)",
        "latitude": 16.4695,
        "longitude": 107.5779,
        "description": "Cổng chính phía nam của Hoàng thành Huế, tuyệt tác kiến trúc cung đình triều Nguyễn.",
        "keywords": ["ngọ môn", "hoàng thành huế", "đại nội", "cố đô huế"],
    },
    "ngu_hanh_son": {
        "name": "Danh thắng Ngũ Hành Sơn",
        "latitude": 16.0042,
        "longitude": 108.2635,
        "description": "Quần thể 5 ngọn núi đá vôi kỳ vĩ mang tên Kim - Mộc - Thủy - Hỏa - Thổ tại Đà Nẵng.",
        "keywords": ["ngũ hành sơn", "marble mountains", "chùa tam thai", "đà nẵng"],
    },
    "nha_tho_duc_ba": {
        "name": "Nhà Thờ Đức Bà Sài Gòn",
        "latitude": 10.7798,
        "longitude": 106.6990,
        "description": "Kiệt tác kiến trúc Gothic - Roman hơn 140 năm tuổi tại trung tâm Thành phố Hồ Chí Minh.",
        "keywords": ["nhà thờ đức bà", "notre dame cathedral saigon", "sài gòn", "quận 1"],
    },
    "thanh_dia_my_son": {
        "name": "Thánh Địa Mỹ Sơn",
        "latitude": 15.7647,
        "longitude": 108.1245,
        "description": "Quần thể di tích đền tháp Chăm Pa cổ kính giữa thung lũng thiêng, di sản văn hóa thế giới UNESCO.",
        "keywords": ["thánh địa mỹ sơn", "my son sanctuary", "văn hóa chăm pa", "quảng nam"],
    },
    "van_mieu": {
        "name": "Văn Miếu - Quốc Tử Giám",
        "latitude": 21.0288,
        "longitude": 105.8356,
        "description": "Trường đại học đầu tiên của Việt Nam, biểu tượng đỉnh cao của tinh hoa đạo học Thăng Long - Hà Nội.",
        "keywords": ["văn miếu", "quốc tử giám", "khuê văn các", "bia tiến sĩ", "hà nội"],
    },
}


class OnnxLandmarkClassifier:
    """Local ONNX-based Landmark Classifier implementing EfficientNetV2-S inference."""

    def __init__(
        self,
        model_path: str = "app/models/landmark_classifier.onnx",
        classes_path: str = "app/models/classes.json",
        confidence_threshold: float = 0.40,
    ) -> None:
        self.model_path = model_path
        self.classes_path = classes_path
        self.confidence_threshold = confidence_threshold
        self._session: ort.InferenceSession | None = None
        self._classes: dict[str, str] = {}
        self._is_ready = False
        self._initialize()

    def _initialize(self) -> None:
        if not HAS_ONNX:
            return

        # Resolve paths relative to services/ai or absolute
        m_path = Path(self.model_path)
        c_path = Path(self.classes_path)

        if not m_path.is_absolute():
            # Try finding relative to this file or cwd
            base_dir = Path(__file__).resolve().parent.parent.parent
            if (base_dir / self.model_path).exists():
                m_path = base_dir / self.model_path
                c_path = base_dir / self.classes_path
            elif Path(self.model_path).exists():
                m_path = Path(self.model_path)
                c_path = Path(self.classes_path)

        if m_path.exists() and c_path.exists():
            try:
                with open(c_path, "r", encoding="utf-8") as f:
                    self._classes = json.load(f)

                # Initialize CPU execution session
                opts = ort.SessionOptions()
                opts.intra_op_num_threads = 2
                self._session = ort.InferenceSession(
                    str(m_path),
                    sess_options=opts,
                    providers=["CPUExecutionProvider"]
                )
                self._is_ready = True
                logger.info(
                    "ONNX Landmark Classifier loaded successfully (%d classes) from %s",
                    len(self._classes),
                    m_path,
                )
            except Exception as e:
                logger.error("Failed to load ONNX session: %s", str(e))
                self._is_ready = False
        else:
            logger.warning("ONNX model or classes file not found: %s, %s", m_path, c_path)

    @property
    def is_available(self) -> bool:
        """Check if ONNX model is loaded and ready for inference."""
        return self._is_ready and self._session is not None

    def classify_image(self, pil_image: Image.Image) -> dict[str, Any] | None:
        """Run classification on a PIL Image and return landmark prediction metadata."""
        if not self.is_available or self._session is None:
            return None

        # Preprocess: Resize 224x224 and Normalize ImageNet
        resized = pil_image.convert("RGB").resize((224, 224), Image.Resampling.BILINEAR)
        img_arr = np.array(resized, dtype=np.float32) / 255.0  # [0, 1]

        # Normalization (mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225])
        mean = np.array([0.485, 0.456, 0.406], dtype=np.float32)
        std = np.array([0.229, 0.224, 0.225], dtype=np.float32)
        norm_img = (img_arr - mean) / std

        # Transpose to NCHW: (1, 3, 224, 224)
        input_tensor = np.transpose(norm_img, (2, 0, 1))[np.newaxis, ...]

        input_name = self._session.get_inputs()[0].name
        outputs = self._session.run(None, {input_name: input_tensor})

        logits = outputs[0][0]
        exp_logits = np.exp(logits - np.max(logits))
        probs = exp_logits / np.sum(exp_logits)

        pred_idx = int(np.argmax(probs))
        confidence = float(probs[pred_idx])

        cls_code = self._classes.get(str(pred_idx), "unknown")
        knowledge = LANDMARK_KNOWLEDGE_BASE.get(cls_code, {})

        return {
            "class_code": cls_code,
            "landmark_name": knowledge.get("name", cls_code.replace("_", " ").title()),
            "confidence": round(confidence, 2),
            "latitude": knowledge.get("latitude"),
            "longitude": knowledge.get("longitude"),
            "description": knowledge.get("description", f"Di tích lịch sử văn hóa {cls_code}"),
            "matched_keywords": knowledge.get("keywords", [cls_code]),
            "meets_threshold": confidence >= self.confidence_threshold,
        }
