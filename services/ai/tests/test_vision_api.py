"""API Integration tests for Vision and Preprocessing endpoints."""

import io

from fastapi.testclient import TestClient

from app.config import Settings, get_settings
from app.main import create_app


def test_recognize_valid_jpeg(client: TestClient, sample_jpeg_bytes: bytes) -> None:
    """POST /api/v1/vision/recognize returns 200 and deterministic landmark data."""
    files = {"image": ("landmark.jpg", io.BytesIO(sample_jpeg_bytes), "image/jpeg")}
    response = client.post("/api/v1/vision/recognize", files=files)

    assert response.status_code == 200
    data = response.json()
    assert data["landmark_name"] == "Hồ Gươm (Tháp Rùa)"
    assert data["confidence"] == 0.94
    assert data["latitude"] == 21.0285
    assert data["longitude"] == 105.8542
    assert len(data["bounding_poly"]) == 4

    # Verify metadata embedded
    metadata = data["metadata"]
    assert metadata is not None
    assert metadata["format"] == "JPEG"
    assert metadata["content_type"] == "image/jpeg"
    assert metadata["processed_width"] == 200
    assert metadata["processed_height"] == 150


def test_recognize_valid_png(client: TestClient, sample_png_rgba_bytes: bytes) -> None:
    """POST /api/v1/vision/recognize handles PNG images successfully."""
    files = {"image": ("photo.png", io.BytesIO(sample_png_rgba_bytes), "image/png")}
    response = client.post("/api/v1/vision/recognize", files=files)

    assert response.status_code == 200
    data = response.json()
    assert data["landmark_name"] == "Hồ Gươm (Tháp Rùa)"
    assert data["metadata"]["format"] == "JPEG"  # Normalized to JPEG


def test_recognize_valid_webp(client: TestClient, sample_webp_bytes: bytes) -> None:
    """POST /api/v1/vision/recognize handles WebP images successfully."""
    files = {"image": ("photo.webp", io.BytesIO(sample_webp_bytes), "image/webp")}
    response = client.post("/api/v1/vision/recognize", files=files)

    assert response.status_code == 200
    data = response.json()
    assert data["landmark_name"] == "Hồ Gươm (Tháp Rùa)"


def test_recognize_unsupported_mime_returns_415_problem_details(
    client: TestClient, sample_fake_pdf_bytes: bytes
) -> None:
    """POST /api/v1/vision/recognize with unsupported MIME returns 415 RFC 7807 problem details."""
    files = {"image": ("document.pdf", io.BytesIO(sample_fake_pdf_bytes), "application/pdf")}
    response = client.post("/api/v1/vision/recognize", files=files)

    assert response.status_code == 415
    assert response.headers["content-type"] == "application/problem+json"

    data = response.json()
    assert data["type"] == "https://api.travelai.internal/errors/unsupported-media-type"
    assert data["title"] == "Unsupported Media Type"
    assert data["status"] == 415
    assert "not supported" in data["detail"]
    assert data["instance"] == "/api/v1/vision/recognize"
    assert "timestamp" in data


def test_recognize_disguised_file_caught_by_magic_bytes(client: TestClient) -> None:
    """A text file disguised with .jpg filename and image/jpeg Content-Type is rejected with 415."""
    fake_jpg = b"This is plainly a text file without JPEG magic bytes."
    files = {"image": ("fake.jpg", io.BytesIO(fake_jpg), "image/jpeg")}
    response = client.post("/api/v1/vision/recognize", files=files)

    assert response.status_code == 415
    assert response.headers["content-type"] == "application/problem+json"
    data = response.json()
    assert data["status"] == 415


def test_recognize_oversized_file_returns_413_problem_details(sample_jpeg_bytes: bytes) -> None:
    """POST /api/v1/vision/recognize with file > max_upload_size returns 413 RFC 7807."""
    tiny_settings = Settings(
        max_upload_size_bytes=50,  # 50 bytes limit
        vision_mock_enabled=True,
    )
    app = create_app()
    app.dependency_overrides[get_settings] = lambda: tiny_settings

    with TestClient(app) as test_client:
        files = {"image": ("large.jpg", io.BytesIO(sample_jpeg_bytes), "image/jpeg")}
        response = test_client.post("/api/v1/vision/recognize", files=files)

        assert response.status_code == 413
        assert response.headers["content-type"] == "application/problem+json"

        data = response.json()
        assert data["type"] == "https://api.travelai.internal/errors/payload-too-large"
        assert data["title"] == "Payload Too Large"
        assert data["status"] == 413
        assert "exceeds maximum allowed limit" in data["detail"]


def test_recognize_corrupted_image_returns_422_problem_details(
    client: TestClient, sample_corrupted_bytes: bytes
) -> None:
    """POST /api/v1/vision/recognize with corrupted stream returns 422 RFC 7807."""
    files = {"image": ("corrupt.jpg", io.BytesIO(sample_corrupted_bytes), "image/jpeg")}
    response = client.post("/api/v1/vision/recognize", files=files)

    assert response.status_code == 422
    assert response.headers["content-type"] == "application/problem+json"

    data = response.json()
    assert data["type"] == "https://api.travelai.internal/errors/unprocessable-image"
    assert data["title"] == "Unprocessable Content"
    assert data["status"] == 422
    assert "Unable to decode" in data["detail"]


def test_recognize_missing_file_field_returns_422(client: TestClient) -> None:
    """Submitting request without image file triggers RFC 7807 validation error."""
    response = client.post("/api/v1/vision/recognize", data={})

    assert response.status_code == 422
    assert response.headers["content-type"] == "application/problem+json"
    data = response.json()
    assert data["type"] == "https://api.travelai.internal/errors/validation-error"
    assert data["status"] == 422
    assert data["invalid_params"] is not None


def test_preprocess_endpoint(client: TestClient, sample_png_rgba_bytes: bytes) -> None:
    """POST /api/v1/vision/preprocess processes image and returns metadata."""
    files = {"image": ("input.png", io.BytesIO(sample_png_rgba_bytes), "image/png")}
    response = client.post("/api/v1/vision/preprocess", files=files)

    assert response.status_code == 200
    data = response.json()
    assert data["success"] is True
    metadata = data["metadata"]
    assert metadata["format"] == "JPEG"
    assert metadata["original_width"] == 300
    assert metadata["original_height"] == 200


def test_recognition_history_flow(client: TestClient, sample_jpeg_bytes: bytes) -> None:
    """POST /recognize records history, GET /history retrieves it, and DELETE clears it."""
    # Step 1: Clear history
    del_resp = client.delete("/api/v1/vision/history")
    assert del_resp.status_code == 200

    # Step 2: Check history is empty
    hist_resp = client.get("/api/v1/vision/history")
    assert hist_resp.status_code == 200
    assert hist_resp.json()["total"] == 0
    assert len(hist_resp.json()["items"]) == 0

    # Step 3: Perform recognition
    files = {"image": ("landmark.jpg", io.BytesIO(sample_jpeg_bytes), "image/jpeg")}
    rec_resp = client.post("/api/v1/vision/recognize", files=files)
    assert rec_resp.status_code == 200

    # Step 4: Verify history has 1 entry with audit details
    hist_resp2 = client.get("/api/v1/vision/history")
    assert hist_resp2.status_code == 200
    data = hist_resp2.json()
    assert data["total"] >= 1
    latest = data["items"][0]
    assert latest["landmark_name"] == "Hồ Gươm (Tháp Rùa)"
    assert latest["confidence"] == 0.94
    assert latest["success"] is True
    assert latest["detection_type"] == "LANDMARK_DETECTION"
    assert latest["latency_ms"] >= 0
    assert latest["image_metadata"] is not None

    # Step 5: Clear history
    client.delete("/api/v1/vision/history")
    hist_resp3 = client.get("/api/v1/vision/history")
    assert hist_resp3.json()["total"] == 0


def test_recognize_with_onnx_classifier(sample_jpeg_bytes: bytes) -> None:
    """POST /api/v1/vision/recognize uses ONNX classifier when mock mode is disabled."""
    from unittest.mock import MagicMock
    from app.api.deps import get_landmark_recognition_service
    from app.services.landmark_service import LandmarkRecognitionService

    settings = Settings(vision_mock_enabled=False, onnx_enabled=True)
    mock_onnx = MagicMock()
    mock_onnx.is_available = True
    mock_onnx.classify_image.return_value = {
        "class_code": "cau_vang",
        "landmark_name": "Cầu Vàng (Bà Nà Hills)",
        "confidence": 0.98,
        "latitude": 15.9950,
        "longitude": 107.9965,
        "description": "Kỳ quan kiến trúc cầu bàn tay khổng lồ vươn giữa mây trời tại Bà Nà Hills.",
        "matched_keywords": ["cầu vàng", "golden bridge", "bà nà hills"],
        "meets_threshold": True,
    }

    service = LandmarkRecognitionService(settings=settings, onnx_classifier=mock_onnx)
    app = create_app()
    app.dependency_overrides[get_settings] = lambda: settings
    app.dependency_overrides[get_landmark_recognition_service] = lambda: service

    with TestClient(app) as test_client:
        files = {"image": ("cau_vang.jpg", io.BytesIO(sample_jpeg_bytes), "image/jpeg")}
        response = test_client.post("/api/v1/vision/recognize", files=files)

        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True
        assert data["landmark_name"] == "Cầu Vàng (Bà Nà Hills)"
        assert data["confidence"] == 0.98
        assert data["latitude"] == 15.9950
        assert data["longitude"] == 107.9965
        assert data["detection_type"] == "LOCAL_ONNX_CLASSIFIER"
        assert "golden bridge" in data["matched_keywords"]

