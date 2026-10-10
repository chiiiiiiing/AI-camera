"""Lightweight student embedding model used for distillation experiments."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import torch
from torch import nn
from torch.nn import functional as F


@dataclass(frozen=True)
class StudentModelConfig:
    architecture: str = "tiny_cnn_v1"
    output_dim: int = 256
    image_size: int = 160
    width_mult: float = 0.75
    dropout: float = 0.1


def scaled(value: int, width_mult: float) -> int:
    return max(8, int(round(value * width_mult / 8.0) * 8))


class ConvBnAct(nn.Sequential):
    def __init__(self, in_channels: int, out_channels: int, stride: int) -> None:
        super().__init__(
            nn.Conv2d(in_channels, out_channels, kernel_size=3, stride=stride, padding=1, bias=False),
            nn.BatchNorm2d(out_channels),
            nn.Hardswish(inplace=True),
        )


class DepthwiseSeparableBlock(nn.Module):
    def __init__(self, in_channels: int, out_channels: int, stride: int) -> None:
        super().__init__()
        self.block = nn.Sequential(
            nn.Conv2d(
                in_channels,
                in_channels,
                kernel_size=3,
                stride=stride,
                padding=1,
                groups=in_channels,
                bias=False,
            ),
            nn.BatchNorm2d(in_channels),
            nn.Hardswish(inplace=True),
            nn.Conv2d(in_channels, out_channels, kernel_size=1, bias=False),
            nn.BatchNorm2d(out_channels),
            nn.Hardswish(inplace=True),
        )
        self.use_residual = stride == 1 and in_channels == out_channels

    def forward(self, inputs: torch.Tensor) -> torch.Tensor:
        output = self.block(inputs)
        if self.use_residual:
            output = output + inputs
        return output


class SqueezeExcite(nn.Module):
    def __init__(self, channels: int, reduction: int = 4) -> None:
        super().__init__()
        reduced = max(8, channels // reduction)
        self.pool = nn.AdaptiveAvgPool2d(1)
        self.fc1 = nn.Conv2d(channels, reduced, kernel_size=1)
        self.fc2 = nn.Conv2d(reduced, channels, kernel_size=1)

    def forward(self, inputs: torch.Tensor) -> torch.Tensor:
        scale = self.pool(inputs)
        scale = F.hardswish(self.fc1(scale))
        scale = torch.sigmoid(self.fc2(scale))
        return inputs * scale


class InvertedResidualBlock(nn.Module):
    def __init__(
        self,
        in_channels: int,
        out_channels: int,
        stride: int,
        expand_ratio: float,
        use_se: bool,
    ) -> None:
        super().__init__()
        hidden_channels = scaled(int(round(in_channels * expand_ratio)), 1.0)
        layers: list[nn.Module] = []
        if hidden_channels != in_channels:
            layers.extend(
                [
                    nn.Conv2d(in_channels, hidden_channels, kernel_size=1, bias=False),
                    nn.BatchNorm2d(hidden_channels),
                    nn.Hardswish(inplace=True),
                ]
            )
        layers.extend(
            [
                nn.Conv2d(
                    hidden_channels,
                    hidden_channels,
                    kernel_size=3,
                    stride=stride,
                    padding=1,
                    groups=hidden_channels,
                    bias=False,
                ),
                nn.BatchNorm2d(hidden_channels),
                nn.Hardswish(inplace=True),
            ]
        )
        if use_se:
            layers.append(SqueezeExcite(hidden_channels))
        layers.extend(
            [
                nn.Conv2d(hidden_channels, out_channels, kernel_size=1, bias=False),
                nn.BatchNorm2d(out_channels),
            ]
        )
        self.block = nn.Sequential(*layers)
        self.use_residual = stride == 1 and in_channels == out_channels

    def forward(self, inputs: torch.Tensor) -> torch.Tensor:
        output = self.block(inputs)
        if self.use_residual:
            output = output + inputs
        return output


class TinyStudentEmbeddingNet(nn.Module):
    """Small CNN that emits L2-normalized image embeddings."""

    def __init__(self, config: StudentModelConfig) -> None:
        super().__init__()
        if config.output_dim <= 0:
            raise ValueError("output_dim must be positive")
        if config.width_mult <= 0:
            raise ValueError("width_mult must be positive")
        channels = [
            scaled(24, config.width_mult),
            scaled(40, config.width_mult),
            scaled(64, config.width_mult),
            scaled(96, config.width_mult),
            scaled(160, config.width_mult),
        ]
        self.config = config
        self.features = nn.Sequential(
            ConvBnAct(3, channels[0], stride=2),
            DepthwiseSeparableBlock(channels[0], channels[1], stride=2),
            DepthwiseSeparableBlock(channels[1], channels[1], stride=1),
            DepthwiseSeparableBlock(channels[1], channels[2], stride=2),
            DepthwiseSeparableBlock(channels[2], channels[2], stride=1),
            DepthwiseSeparableBlock(channels[2], channels[3], stride=2),
            DepthwiseSeparableBlock(channels[3], channels[3], stride=1),
            DepthwiseSeparableBlock(channels[3], channels[4], stride=2),
            nn.AdaptiveAvgPool2d(1),
        )
        self.head = nn.Sequential(
            nn.Flatten(),
            nn.Dropout(p=config.dropout),
            nn.Linear(channels[4], config.output_dim),
        )

    def forward(self, inputs: torch.Tensor) -> torch.Tensor:
        output = self.head(self.features(inputs))
        return F.normalize(output, p=2, dim=1)


class LiteStudentEmbeddingNet(nn.Module):
    """A still-small MobileNet-style student with extra capacity for production trials."""

    def __init__(self, config: StudentModelConfig) -> None:
        super().__init__()
        if config.output_dim <= 0:
            raise ValueError("output_dim must be positive")
        if config.width_mult <= 0:
            raise ValueError("width_mult must be positive")
        channels = [
            scaled(24, config.width_mult),
            scaled(32, config.width_mult),
            scaled(48, config.width_mult),
            scaled(80, config.width_mult),
            scaled(112, config.width_mult),
            scaled(160, config.width_mult),
        ]
        head_channels = scaled(256, config.width_mult)
        self.config = config
        self.features = nn.Sequential(
            ConvBnAct(3, channels[0], stride=2),
            InvertedResidualBlock(channels[0], channels[0], stride=1, expand_ratio=1.0, use_se=False),
            InvertedResidualBlock(channels[0], channels[1], stride=2, expand_ratio=3.0, use_se=False),
            InvertedResidualBlock(channels[1], channels[1], stride=1, expand_ratio=3.0, use_se=False),
            InvertedResidualBlock(channels[1], channels[2], stride=2, expand_ratio=4.0, use_se=True),
            InvertedResidualBlock(channels[2], channels[2], stride=1, expand_ratio=4.0, use_se=True),
            InvertedResidualBlock(channels[2], channels[3], stride=2, expand_ratio=4.0, use_se=True),
            InvertedResidualBlock(channels[3], channels[3], stride=1, expand_ratio=4.0, use_se=True),
            InvertedResidualBlock(channels[3], channels[4], stride=1, expand_ratio=4.0, use_se=True),
            InvertedResidualBlock(channels[4], channels[5], stride=2, expand_ratio=4.0, use_se=True),
            nn.Conv2d(channels[5], head_channels, kernel_size=1, bias=False),
            nn.BatchNorm2d(head_channels),
            nn.Hardswish(inplace=True),
            nn.AdaptiveAvgPool2d(1),
        )
        self.head = nn.Sequential(
            nn.Flatten(),
            nn.Dropout(p=config.dropout),
            nn.Linear(head_channels, head_channels),
            nn.Hardswish(inplace=True),
            nn.Dropout(p=config.dropout),
            nn.Linear(head_channels, config.output_dim),
        )

    def forward(self, inputs: torch.Tensor) -> torch.Tensor:
        output = self.head(self.features(inputs))
        return F.normalize(output, p=2, dim=1)


def build_student_model(config: StudentModelConfig | dict[str, Any]) -> nn.Module:
    if isinstance(config, dict):
        config = StudentModelConfig(**{
            key: config[key]
            for key in ("architecture", "output_dim", "image_size", "width_mult", "dropout")
            if key in config
        })
    if config.architecture == "tiny_cnn_v1":
        return TinyStudentEmbeddingNet(config)
    if config.architecture == "lite_cnn_v2":
        return LiteStudentEmbeddingNet(config)
    raise ValueError(f"unsupported student architecture: {config.architecture}")


def config_to_dict(config: StudentModelConfig) -> dict[str, Any]:
    return {
        "architecture": config.architecture,
        "output_dim": config.output_dim,
        "image_size": config.image_size,
        "width_mult": config.width_mult,
        "dropout": config.dropout,
    }


def count_parameters(model: nn.Module) -> int:
    return sum(parameter.numel() for parameter in model.parameters())
