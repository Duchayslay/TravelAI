"""Application Configuration Settings."""

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Configuration settings for TravelAI AI Service."""

    app_name: str = "travelai-ai-service"
    app_version: str = "0.1.0"
    debug: bool = False
    api_prefix: str = "/api/v1"

    # Preprocessing limits
    allowed_mime_types: list[str] = ["image/jpeg", "image/png", "image/webp"]
    max_upload_size_bytes: int = 10 * 1024 * 1024  # 10 MB
    max_image_dimension: int = 2048
    jpeg_quality: int = 85

    # Vision Provider Settings
    vision_mock_enabled: bool = True
    google_application_credentials: str | None = None
    vision_timeout_seconds: float = 5.0
    vision_max_history_entries: int = 100

    # Local ONNX Classifier Settings
    onnx_enabled: bool = True
    onnx_model_path: str = "app/models/landmark_classifier.onnx"
    onnx_classes_path: str = "app/models/classes.json"
    onnx_confidence_threshold: float = 0.40

    # CORS
    cors_origins: list[str] = ["*"]

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )


@lru_cache
def get_settings() -> Settings:
    """Return cached settings instance."""
    return Settings()
