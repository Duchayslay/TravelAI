"""Landmark Recognition Service with Google Cloud Vision SDK integration and deterministic mock mode."""

import asyncio
import logging
import time
import uuid
from collections import deque
from datetime import UTC, datetime

from google.api_core.exceptions import GoogleAPICallError, RetryError
from google.auth.exceptions import GoogleAuthError
from google.cloud import vision

from app.config import Settings, get_settings
from app.core.exceptions import VisionProviderException, VisionTimeoutException
from app.schemas.vision import (
    BoundingPoint,
    LandmarkRecognitionResult,
    RecognitionHistoryEntry,
)
from app.services.onnx_classifier import OnnxLandmarkClassifier
from app.services.preprocessor import PreprocessedImage

logger = logging.getLogger("travelai.ai.landmark")

# Deterministic Landmark Mock Data specified in vision-service-test-harness
MOCK_LANDMARK_DATA = {
    "landmark_name": "Hồ Gươm (Tháp Rùa)",
    "confidence": 0.94,
    "latitude": 21.0285,
    "longitude": 105.8542,
    "description": "Di tích lịch sử văn hóa quốc gia đặc biệt tại trung tâm thủ đô Hà Nội.",
    "matched_keywords": ["hồ gươm", "tháp rùa", "hoan kiem lake"],
    "bounding_poly": [
        {"x": 120, "y": 80},
        {"x": 840, "y": 80},
        {"x": 840, "y": 620},
        {"x": 120, "y": 620},
    ],
}


class LandmarkRecognitionService:
    """Service orchestrating landmark detection via local ONNX classifier, GCP Cloud Vision, or deterministic mock."""

    def __init__(
        self,
        settings: Settings | None = None,
        vision_client: vision.ImageAnnotatorClient | None = None,
        onnx_classifier: OnnxLandmarkClassifier | None = None,
    ) -> None:
        self.settings = settings or get_settings()
        self._vision_client = vision_client
        self._history: deque[RecognitionHistoryEntry] = deque(
            maxlen=self.settings.vision_max_history_entries
        )
        self._onnx_classifier = onnx_classifier
        if self._onnx_classifier is None and self.settings.onnx_enabled:
            try:
                self._onnx_classifier = OnnxLandmarkClassifier(
                    model_path=self.settings.onnx_model_path,
                    classes_path=self.settings.onnx_classes_path,
                    confidence_threshold=self.settings.onnx_confidence_threshold,
                )
            except Exception as exc:
                logger.warning("Failed to initialize ONNX landmark classifier: %s", str(exc))

    def _get_client(self) -> vision.ImageAnnotatorClient:
        """Lazily initialize and return the Google Cloud Vision client."""
        if self._vision_client is None:
            try:
                if self.settings.google_application_credentials:
                    self._vision_client = vision.ImageAnnotatorClient.from_service_account_json(
                        self.settings.google_application_credentials
                    )
                else:
                    self._vision_client = vision.ImageAnnotatorClient()
            except (GoogleAuthError, Exception) as exc:
                logger.error("Failed to initialize Google Cloud Vision client: %s", str(exc))
                raise VisionProviderException(
                    detail="Google Cloud Vision client initialization failed. Check GCP credentials."
                ) from exc
        return self._vision_client

    def get_history(self, limit: int = 50) -> list[RecognitionHistoryEntry]:
        """Return the most recent recognition history entries."""
        entries = list(self._history)
        entries.reverse()  # most recent first
        return entries[:limit]

    def clear_history(self) -> None:
        """Clear all stored recognition history entries."""
        self._history.clear()

    async def recognize(self, preprocessed: PreprocessedImage) -> LandmarkRecognitionResult:
        """Recognize landmark from preprocessed image."""
        start_time = time.perf_counter()
        req_id = str(uuid.uuid4())

        if self.settings.vision_mock_enabled:
            logger.info("Serving landmark recognition via deterministic mock mode.")
            result = LandmarkRecognitionResult(
                landmark_name=MOCK_LANDMARK_DATA["landmark_name"],
                confidence=MOCK_LANDMARK_DATA["confidence"],
                latitude=MOCK_LANDMARK_DATA["latitude"],
                longitude=MOCK_LANDMARK_DATA["longitude"],
                bounding_poly=[BoundingPoint(**pt) for pt in MOCK_LANDMARK_DATA["bounding_poly"]],
                metadata=preprocessed.metadata,
                success=True,
                description=MOCK_LANDMARK_DATA["description"],
                matched_keywords=MOCK_LANDMARK_DATA["matched_keywords"],
                detection_type="LANDMARK_DETECTION",
            )
        elif (
            self.settings.onnx_enabled
            and self._onnx_classifier is not None
            and self._onnx_classifier.is_available
        ):
            # Step 1: Run local ONNX landmark classifier
            onnx_result = await asyncio.to_thread(
                self._onnx_classifier.classify_image, preprocessed.pil_image
            )
            if onnx_result and onnx_result.get("meets_threshold"):
                logger.info(
                    "ONNX classification successful: '%s' (conf=%.2f >= threshold=%.2f)",
                    onnx_result["landmark_name"],
                    onnx_result["confidence"],
                    self.settings.onnx_confidence_threshold,
                )
                result = LandmarkRecognitionResult(
                    landmark_name=onnx_result["landmark_name"],
                    confidence=onnx_result["confidence"],
                    latitude=onnx_result.get("latitude"),
                    longitude=onnx_result.get("longitude"),
                    bounding_poly=[],
                    metadata=preprocessed.metadata,
                    success=True,
                    description=onnx_result.get("description"),
                    matched_keywords=onnx_result.get("matched_keywords", []),
                    detection_type="LOCAL_ONNX_CLASSIFIER",
                )
            elif self.settings.google_application_credentials:
                # Step 2: Fallback to GCP Vision if credentials exist
                logger.info(
                    "ONNX confidence (%.2f) below threshold. Falling back to GCP Vision.",
                    onnx_result.get("confidence", 0.0) if onnx_result else 0.0,
                )
                result = await self._recognize_with_vision(preprocessed)
            elif onnx_result and onnx_result.get("confidence", 0.0) >= 0.30:
                logger.info("No GCP credentials configured; returning candidate ONNX prediction.")
                result = LandmarkRecognitionResult(
                    landmark_name=onnx_result["landmark_name"],
                    confidence=onnx_result["confidence"],
                    latitude=onnx_result.get("latitude"),
                    longitude=onnx_result.get("longitude"),
                    bounding_poly=[],
                    metadata=preprocessed.metadata,
                    success=True,
                    description=onnx_result.get("description"),
                    matched_keywords=onnx_result.get("matched_keywords", []),
                    detection_type="LOCAL_ONNX_CLASSIFIER",
                )
            else:
                result = await self._recognize_with_vision(preprocessed)
        else:
            result = await self._recognize_with_vision(preprocessed)

        latency_ms = round((time.perf_counter() - start_time) * 1000, 2)

        # Audit and history logging
        history_entry = RecognitionHistoryEntry(
            id=req_id,
            timestamp=datetime.now(UTC).isoformat(),
            landmark_name=result.landmark_name,
            confidence=result.confidence,
            latitude=result.latitude,
            longitude=result.longitude,
            detection_type=result.detection_type,
            success=result.success,
            latency_ms=latency_ms,
            image_metadata=result.metadata,
        )
        self._history.append(history_entry)

        logger.info(
            "Recognition complete: id=%s landmark='%s' confidence=%.2f type=%s latency=%.1fms",
            req_id,
            result.landmark_name,
            result.confidence,
            result.detection_type,
            latency_ms,
        )

        return result

    async def _recognize_with_vision(
        self, preprocessed: PreprocessedImage
    ) -> LandmarkRecognitionResult:
        """Invoke Google Cloud Vision Landmark Detection with Web Detection fallback."""
        try:
            client = self._get_client()
            image = vision.Image(content=preprocessed.buffer)

            # Enforce strict 5.0-second timeout budget per Rule 05
            timeout = self.settings.vision_timeout_seconds

            # Step 1: Execute landmark detection in thread pool to prevent blocking event loop
            try:
                response = await asyncio.wait_for(
                    asyncio.to_thread(client.landmark_detection, image=image, timeout=timeout),
                    timeout=timeout,
                )
            except TimeoutError as exc:
                logger.warning("Landmark detection exceeded timeout budget (%.1fs)", timeout)
                raise VisionTimeoutException(
                    detail=f"Google Cloud Vision landmark detection exceeded {timeout}s timeout."
                ) from exc

            if response.error.code != 0:
                logger.error("Vision API error response: %s", response.error.message)
                raise VisionProviderException(
                    detail=f"Google Cloud Vision API error: {response.error.message}"
                )

            # Check if any landmarks were detected
            if response.landmark_annotations:
                top_landmark = response.landmark_annotations[0]
                lat: float | None = None
                lng: float | None = None
                if top_landmark.locations and top_landmark.locations[0].lat_lng:
                    lat = float(top_landmark.locations[0].lat_lng.latitude)
                    lng = float(top_landmark.locations[0].lat_lng.longitude)

                bounding_poly = [
                    BoundingPoint(x=vertex.x, y=vertex.y)
                    for vertex in top_landmark.bounding_poly.vertices
                ]

                matched_keywords = [
                    lm.description.strip()
                    for lm in response.landmark_annotations[:5]
                    if lm.description
                ]

                return LandmarkRecognitionResult(
                    landmark_name=top_landmark.description.strip(),
                    confidence=round(float(top_landmark.score), 2),
                    latitude=lat,
                    longitude=lng,
                    bounding_poly=bounding_poly,
                    metadata=preprocessed.metadata,
                    success=True,
                    description=f"Recognized landmark: {top_landmark.description.strip()}",
                    matched_keywords=matched_keywords,
                    detection_type="LANDMARK_DETECTION",
                )

            # Step 2: Fallback to Web Detection if no direct landmark found
            logger.info("No landmark annotations found, initiating Web Detection fallback.")
            try:
                web_response = await asyncio.wait_for(
                    asyncio.to_thread(client.web_detection, image=image, timeout=timeout),
                    timeout=timeout,
                )
            except TimeoutError as exc:
                logger.warning("Web detection fallback exceeded timeout budget (%.1fs)", timeout)
                raise VisionTimeoutException(
                    detail=f"Google Cloud Vision web detection exceeded {timeout}s timeout."
                ) from exc

            if web_response.error.code != 0:
                logger.error("Vision Web API error: %s", web_response.error.message)
                raise VisionProviderException(
                    detail=f"Google Cloud Vision Web API error: {web_response.error.message}"
                )

            web_detection = web_response.web_detection
            best_guess = (
                web_detection.best_guess_labels[0].label
                if web_detection.best_guess_labels
                else None
            )

            matched_keywords = [
                entity.description.strip()
                for entity in web_detection.web_entities[:5]
                if entity.description
            ]

            if best_guess:
                return LandmarkRecognitionResult(
                    landmark_name=best_guess.strip(),
                    confidence=0.75,
                    latitude=None,
                    longitude=None,
                    bounding_poly=[],
                    metadata=preprocessed.metadata,
                    success=True,
                    description=f"Inferred landmark via web detection: {best_guess.strip()}",
                    matched_keywords=matched_keywords,
                    detection_type="WEB_DETECTION",
                )

            if web_detection.web_entities and web_detection.web_entities[0].description:
                top_entity = web_detection.web_entities[0]
                return LandmarkRecognitionResult(
                    landmark_name=top_entity.description.strip(),
                    confidence=round(float(top_entity.score or 0.7), 2),
                    latitude=None,
                    longitude=None,
                    bounding_poly=[],
                    metadata=preprocessed.metadata,
                    success=True,
                    description=f"Inferred entity via web detection: {top_entity.description.strip()}",
                    matched_keywords=matched_keywords,
                    detection_type="WEB_DETECTION",
                )

            # No landmark or web entity could be identified
            return LandmarkRecognitionResult(
                landmark_name=None,
                confidence=0.0,
                latitude=None,
                longitude=None,
                bounding_poly=[],
                metadata=preprocessed.metadata,
                success=False,
                description="Không nhận diện được danh lam thắng cảnh trong bức ảnh.",
                matched_keywords=[],
                detection_type="UNKNOWN",
            )

        except (GoogleAPICallError, RetryError) as exc:
            logger.exception("Google Cloud Vision API call error: %s", str(exc))
            raise VisionProviderException(
                detail=f"Google Cloud Vision API failed: {exc.message if hasattr(exc, 'message') else str(exc)}"
            ) from exc
