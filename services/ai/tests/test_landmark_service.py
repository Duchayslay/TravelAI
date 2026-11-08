"""Unit tests for LandmarkRecognitionService."""

from unittest.mock import MagicMock

import pytest
from PIL import Image

from app.config import Settings
from app.core.exceptions import VisionProviderException, VisionTimeoutException
from app.schemas.vision import ImageMetadata
from app.services.landmark_service import LandmarkRecognitionService
from app.services.preprocessor import PreprocessedImage


@pytest.fixture
def dummy_preprocessed() -> PreprocessedImage:
    """Fixture providing dummy preprocessed image."""
    return PreprocessedImage(
        buffer=b"\xff\xd8\xff\xe0dummy_jpeg_bytes",
        metadata=ImageMetadata(
            original_width=800,
            original_height=600,
            processed_width=800,
            processed_height=600,
            format="JPEG",
            content_type="image/jpeg",
            size_bytes=24,
        ),
        pil_image=Image.new("RGB", (800, 600)),
    )


@pytest.mark.asyncio
async def test_mock_recognition(dummy_preprocessed: PreprocessedImage) -> None:
    """Mock mode returns predefined Ho Guom landmark."""
    settings = Settings(vision_mock_enabled=True)
    service = LandmarkRecognitionService(settings=settings)

    result = await service.recognize(dummy_preprocessed)

    assert result.success is True
    assert result.landmark_name == "Hồ Gươm (Tháp Rùa)"
    assert result.confidence == 0.94
    assert result.latitude == 21.0285
    assert result.longitude == 105.8542
    assert result.detection_type == "LANDMARK_DETECTION"
    assert len(result.bounding_poly) == 4

    # History should have 1 entry
    history = service.get_history()
    assert len(history) == 1
    assert history[0].landmark_name == "Hồ Gươm (Tháp Rùa)"
    assert history[0].latency_ms >= 0


@pytest.mark.asyncio
async def test_gcp_landmark_detection_success(dummy_preprocessed: PreprocessedImage) -> None:
    """Successful landmark detection via mocked GCP Vision SDK."""
    settings = Settings(vision_mock_enabled=False, google_application_credentials="/fake/path.json")

    mock_client = MagicMock()
    mock_response = MagicMock()
    mock_response.error.code = 0

    mock_landmark = MagicMock()
    mock_landmark.description = "Vịnh Hạ Long"
    mock_landmark.score = 0.97
    mock_loc = MagicMock()
    mock_loc.lat_lng.latitude = 20.9101
    mock_loc.lat_lng.longitude = 107.1839
    mock_landmark.locations = [mock_loc]

    v1 = MagicMock(x=10, y=10)
    v2 = MagicMock(x=100, y=10)
    v3 = MagicMock(x=100, y=100)
    v4 = MagicMock(x=10, y=100)
    mock_landmark.bounding_poly.vertices = [v1, v2, v3, v4]

    mock_response.landmark_annotations = [mock_landmark]
    mock_client.landmark_detection.return_value = mock_response

    service = LandmarkRecognitionService(settings=settings, vision_client=mock_client)
    result = await service.recognize(dummy_preprocessed)

    assert result.success is True
    assert result.landmark_name == "Vịnh Hạ Long"
    assert result.confidence == 0.97
    assert result.latitude == 20.9101
    assert result.longitude == 107.1839
    assert result.detection_type == "LANDMARK_DETECTION"
    assert len(result.bounding_poly) == 4
    assert result.bounding_poly[0].x == 10
    assert result.bounding_poly[0].y == 10


@pytest.mark.asyncio
async def test_gcp_web_detection_fallback(dummy_preprocessed: PreprocessedImage) -> None:
    """When landmark detection yields no results, falls back to web detection."""
    settings = Settings(vision_mock_enabled=False, google_application_credentials="/fake/path.json")

    mock_client = MagicMock()
    mock_landmark_resp = MagicMock()
    mock_landmark_resp.error.code = 0
    mock_landmark_resp.landmark_annotations = []
    mock_client.landmark_detection.return_value = mock_landmark_resp

    mock_web_resp = MagicMock()
    mock_web_resp.error.code = 0
    label = MagicMock(label="Chùa Một Cột")
    mock_web_resp.web_detection.best_guess_labels = [label]
    mock_entity = MagicMock(description="Chùa Một Cột", score=0.88)
    mock_web_resp.web_detection.web_entities = [mock_entity]
    mock_client.web_detection.return_value = mock_web_resp

    service = LandmarkRecognitionService(settings=settings, vision_client=mock_client)
    result = await service.recognize(dummy_preprocessed)

    assert result.success is True
    assert result.landmark_name == "Chùa Một Cột"
    assert result.confidence == 0.75
    assert result.detection_type == "WEB_DETECTION"


@pytest.mark.asyncio
async def test_gcp_no_detection_returns_unknown(dummy_preprocessed: PreprocessedImage) -> None:
    """When neither landmark nor web detection matches, returns unknown."""
    settings = Settings(vision_mock_enabled=False, google_application_credentials="/fake/path.json")

    mock_client = MagicMock()
    mock_landmark_resp = MagicMock()
    mock_landmark_resp.error.code = 0
    mock_landmark_resp.landmark_annotations = []
    mock_client.landmark_detection.return_value = mock_landmark_resp

    mock_web_resp = MagicMock()
    mock_web_resp.error.code = 0
    mock_web_resp.web_detection.best_guess_labels = []
    mock_web_resp.web_detection.web_entities = []
    mock_client.web_detection.return_value = mock_web_resp

    service = LandmarkRecognitionService(settings=settings, vision_client=mock_client)
    result = await service.recognize(dummy_preprocessed)

    assert result.success is False
    assert result.landmark_name is None
    assert result.confidence == 0.0
    assert result.detection_type == "UNKNOWN"


@pytest.mark.asyncio
async def test_gcp_vision_timeout_triggers_exception(dummy_preprocessed: PreprocessedImage) -> None:
    """When GCP Vision call exceeds timeout budget, raises VisionTimeoutException."""
    settings = Settings(
        vision_mock_enabled=False,
        google_application_credentials="/fake/path.json",
        vision_timeout_seconds=0.05,
    )

    mock_client = MagicMock()

    def slow_call(*args, **kwargs):
        import time

        time.sleep(0.1)

    mock_client.landmark_detection.side_effect = slow_call

    service = LandmarkRecognitionService(settings=settings, vision_client=mock_client)
    with pytest.raises(VisionTimeoutException) as exc_info:
        await service.recognize(dummy_preprocessed)

    assert exc_info.value.status_code == 504


@pytest.mark.asyncio
async def test_gcp_vision_api_error_triggers_provider_exception(
    dummy_preprocessed: PreprocessedImage,
) -> None:
    """When GCP Vision returns an error code, raises VisionProviderException."""
    settings = Settings(vision_mock_enabled=False, google_application_credentials="/fake/path.json")

    mock_client = MagicMock()
    mock_response = MagicMock()
    mock_response.error.code = 7
    mock_response.error.message = "Permission denied on Vision API."
    mock_client.landmark_detection.return_value = mock_response

    service = LandmarkRecognitionService(settings=settings, vision_client=mock_client)
    with pytest.raises(VisionProviderException) as exc_info:
        await service.recognize(dummy_preprocessed)

    assert exc_info.value.status_code == 502
    assert "Permission denied" in exc_info.value.detail


@pytest.mark.asyncio
async def test_history_rotation_and_clear(dummy_preprocessed: PreprocessedImage) -> None:
    """History rotates entries when maxlen is reached and clears on demand."""
    settings = Settings(vision_mock_enabled=True, vision_max_history_entries=3)
    service = LandmarkRecognitionService(settings=settings)

    for _ in range(5):
        await service.recognize(dummy_preprocessed)

    history = service.get_history()
    assert len(history) == 3

    service.clear_history()
    assert len(service.get_history()) == 0


@pytest.mark.asyncio
async def test_onnx_landmark_recognition_success(dummy_preprocessed: PreprocessedImage) -> None:
    """When ONNX is enabled and meets threshold, returns local classification result."""
    settings = Settings(vision_mock_enabled=False, onnx_enabled=True)

    mock_onnx = MagicMock()
    mock_onnx.is_available = True
    mock_onnx.classify_image.return_value = {
        "class_code": "cau_rong",
        "landmark_name": "Cầu Rồng",
        "confidence": 0.96,
        "latitude": 16.0611,
        "longitude": 108.2274,
        "description": "Cây cầu hình rồng bắc qua sông Hàn tại Đà Nẵng.",
        "matched_keywords": ["cầu rồng", "đà nẵng"],
        "meets_threshold": True,
    }

    service = LandmarkRecognitionService(settings=settings, onnx_classifier=mock_onnx)
    result = await service.recognize(dummy_preprocessed)

    assert result.success is True
    assert result.landmark_name == "Cầu Rồng"
    assert result.confidence == 0.96
    assert result.latitude == 16.0611
    assert result.longitude == 108.2274
    assert result.detection_type == "LOCAL_ONNX_CLASSIFIER"
    assert "đà nẵng" in result.matched_keywords


@pytest.mark.asyncio
async def test_onnx_below_threshold_fallback_to_vision(
    dummy_preprocessed: PreprocessedImage,
) -> None:
    """When ONNX confidence is below threshold, falls back to GCP Vision."""
    settings = Settings(
        vision_mock_enabled=False,
        onnx_enabled=True,
        google_application_credentials="/fake/path.json",
    )

    mock_onnx = MagicMock()
    mock_onnx.is_available = True
    mock_onnx.classify_image.return_value = {
        "class_code": "unknown",
        "landmark_name": "Unknown",
        "confidence": 0.20,
        "latitude": None,
        "longitude": None,
        "description": "Unknown",
        "matched_keywords": [],
        "meets_threshold": False,
    }

    mock_client = MagicMock()
    mock_response = MagicMock()
    mock_response.error.code = 0
    mock_landmark = MagicMock()
    mock_landmark.description = "Hồ Ba Bể"
    mock_landmark.score = 0.88
    mock_loc = MagicMock()
    mock_loc.lat_lng.latitude = 22.4083
    mock_loc.lat_lng.longitude = 105.6167
    mock_landmark.locations = [mock_loc]
    mock_landmark.bounding_poly.vertices = []
    mock_response.landmark_annotations = [mock_landmark]
    mock_client.landmark_detection.return_value = mock_response

    service = LandmarkRecognitionService(
        settings=settings,
        vision_client=mock_client,
        onnx_classifier=mock_onnx,
    )
    result = await service.recognize(dummy_preprocessed)

    assert result.success is True
    assert result.landmark_name == "Hồ Ba Bể"
    assert result.confidence == 0.88
    assert result.detection_type == "LANDMARK_DETECTION"

