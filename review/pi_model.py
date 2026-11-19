"""读取 Runner 用户的 Pi 模型配置，为每个审查批次固定一次选择。"""

from dataclasses import dataclass, field
import hashlib
import json
import os
from pathlib import Path
import re
from urllib.parse import urlsplit


DEFAULT_MODEL = "deepseek-flash"
PROVIDER_PATTERN = r"[A-Za-z0-9_.-]{1,80}"
ENV_REFERENCE = re.compile(r"\$\$|\$!|\$\{([A-Za-z_][A-Za-z0-9_]*)\}|\$([A-Za-z_][A-Za-z0-9_]*)")


def read_object(path):
    """读取可选 Pi 配置，损坏的文件报出文件名且不回显内容。"""
    try:
        value = json.loads(path.read_text(encoding="utf-8-sig"))
        if isinstance(value, dict):
            return value
    except FileNotFoundError:
        return {}
    except (ValueError, OSError):
        pass
    raise ValueError(f"无法读取 Pi 配置 {path.name}")


def credential_fields(data):
    """提取模型设置里的认证值，供子进程环境与日志脱敏使用。"""
    if isinstance(data, dict):
        for name, value in data.items():
            if name == "apiKey":
                yield value
            elif name == "headers" and isinstance(value, dict):
                yield from value.values()
            else:
                yield from credential_fields(value)
    elif isinstance(data, list):
        for item in data:
            yield from credential_fields(item)


@dataclass(frozen=True)
class PiModel:
    provider: str = "deepseek"
    model: str = DEFAULT_MODEL
    models: dict = field(default_factory=dict, repr=False)
    auth: dict = field(default_factory=dict, repr=False)

    @classmethod
    def load(cls, directory):
        """使用 Pi 原生默认模型与注册表，保留已有的 DeepSeek Flash 默认值。"""
        root = Path(directory)
        settings = read_object(root / "settings.json")
        provider = settings.get("defaultProvider", "deepseek")
        model = settings.get("defaultModel", DEFAULT_MODEL if provider == "deepseek" else None)
        if not isinstance(provider, str) or not re.fullmatch(PROVIDER_PATTERN, provider):
            raise ValueError("Pi settings.json 的 defaultProvider 无效")
        if not isinstance(model, str) or not 1 <= len(model) <= 150 or not model.strip() or model != model.strip():
            raise ValueError("Pi settings.json 需要有效的 defaultModel")
        providers = read_object(root / "models.json").get("providers", {})
        if not isinstance(providers, dict) or not isinstance(models := providers.get(provider, {}), dict):
            raise ValueError("Pi models.json 的 provider 配置无效")
        auth = read_object(root / "auth.json").get(provider)
        if auth is not None and (not isinstance(auth, dict) or auth.get("type") != "api_key"
                                 or not isinstance(auth.get("key"), str) or not auth["key"]):
            raise ValueError("Pi 自动审查凭据需要使用 API Key")
        return cls(provider, model, models, {"type": "api_key", "key": auth["key"]} if auth else {})

    @property
    def connection_sha256(self):
        """将目标端点和协议纳入缓存身份，避免切换网关后复用旧报告。"""
        models = self.models.get("models", [])
        if not isinstance(models, list) or any(not isinstance(item, dict) for item in models):
            raise ValueError("Pi models.json 的模型列表无效")
        model = next((item for item in models if item.get("id") == self.model), {})
        endpoint = model.get("baseUrl", self.models.get("baseUrl"))
        protocol = model.get("api", self.models.get("api"))
        if endpoint is not None:
            try:
                url = urlsplit(endpoint) if isinstance(endpoint, str) else None
            except ValueError:
                url = None
            if (url is None or url.scheme not in {"http", "https"} or not url.hostname
                    or url.username is not None or url.query or url.fragment):
                raise ValueError("Pi models.json 的 baseUrl 需要是无凭据的 HTTP(S) 地址")
        if endpoint is None and protocol is None:
            return None
        return hashlib.sha256(json.dumps([endpoint, protocol], separators=(",", ":")).encode()).hexdigest()

    def credentials(self):
        """仅传入所选连接的 API 凭据，并收集需要在运行输出中隐藏的值。"""
        env, secrets = {}, set()
        if self.provider == "deepseek" and not self.models and not self.auth:
            key = os.environ.get("DEEPSEEK_API_KEY")
            if key:
                env["DEEPSEEK_API_KEY"] = key
                secrets.add(key)
        fields = list(credential_fields(self.models))
        if self.auth:
            fields.append(self.auth["key"])
        for value in fields:
            if not isinstance(value, str) or value.startswith("!"):
                raise ValueError("Pi 自动审查仅支持 API Key 字符串或 API Key 环境变量")

            def resolve(match):
                name = match[1] or match[2]
                if name is None:
                    return match[0][1:]
                if not name.endswith("_API_KEY"):
                    raise ValueError("Pi 凭据引用的环境变量须以 _API_KEY 结尾")
                key = os.environ.get(name, "")
                if key:
                    env[name] = key
                    secrets.add(key)
                return key

            secret = ENV_REFERENCE.sub(resolve, value)
            if secret:
                secrets.add(secret)
        return env, sorted(secrets, key=len, reverse=True)

    def write(self, directory):
        """只复制原生的模型与认证字段，审查的其他 Pi 设置由控制器管理。"""
        if self.models:
            (directory / "models.json").write_text(json.dumps({"providers": {self.provider: self.models}}), encoding="utf-8")
        if self.auth:
            path = directory / "auth.json"
            path.touch(mode=0o600)
            path.write_text(json.dumps({self.provider: self.auth}), encoding="utf-8")
