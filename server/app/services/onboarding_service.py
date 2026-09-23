import json
from typing import Any

from openai import OpenAI

from app.core.config import Settings, get_settings
from app.schemas.onboarding import (
    OnboardingAnalyzeRequest,
    OnboardingAnalyzeResponse,
    UserProfile,
)


PROFILE_FIELDS = {
    "preferred_name": "希望小鸭怎么称呼你",
    "gender": "性别表达（可以回答不愿透露）",
    "age_range": "大概年龄或年龄阶段",
    "life_stage": "目前在上学、工作、过渡期或其他阶段",
    "current_context": "最近主要在经历什么",
    "main_challenge": "目前最想改善或最困扰的事情",
    "preferred_support_style": "更喜欢倾听、回顾还是小建议",
    "important_supporters": "身边可能提供帮助的人（也可以回答暂时没有）",
}

FOLLOW_UP = {
    "preferred_name": "我该怎么称呼你呢？",
    "gender": "如果你愿意，也可以告诉我你的性别表达；不想透露也完全可以。",
    "age_range": "你大概处在哪个年龄阶段呢？说学生阶段也可以。",
    "life_stage": "你现在主要是在上学、工作，还是处于其他阶段呢？",
    "current_context": "你最近主要在忙什么，或者正在经历什么呢？",
    "main_challenge": "现在最让你没信心或最想慢慢改善的是什么？",
    "preferred_support_style": "难受时，你更希望我先听你说、帮你回想经历，还是给一个小建议？",
    "important_supporters": "遇到困难时，老师、同学、朋友或家人中，有谁可能愿意帮你呢？暂时没有也可以。",
}


def onboarding_schema() -> dict[str, Any]:
    return {
        "opening": "先简单给小鸭介绍一下你吧，主人。想到什么就说什么，不用一次说完整。",
        "required_fields": PROFILE_FIELDS,
        "privacy_note": "不愿透露的项目可以直接说不想回答，系统会记录为 prefer_not_to_say。",
    }


def _client(settings: Settings) -> OpenAI:
    if not settings.enable_live_ai or not settings.dashscope_api_key.strip():
        raise RuntimeError("Live AI is not configured")
    return OpenAI(
        api_key=settings.dashscope_api_key,
        base_url=settings.dashscope_base_url,
        timeout=45.0,
        max_retries=1,
    )


def _normalized_profile(raw: dict[str, Any]) -> UserProfile:
    normalized: dict[str, Any] = {}
    for field in PROFILE_FIELDS:
        item = raw.get(field, {})
        if isinstance(item, str):
            item = {"value": item, "certainty": 0.7}
        if not isinstance(item, dict):
            item = {}
        value = str(item.get("value", "unknown")).strip() or "unknown"
        certainty = item.get("certainty", 0.0)
        try:
            certainty = max(0.0, min(1.0, float(certainty)))
        except (TypeError, ValueError):
            certainty = 0.0
        normalized[field] = {"value": value, "certainty": certainty}
    return UserProfile.model_validate(normalized)


def analyze_onboarding(
    request: OnboardingAnalyzeRequest,
    settings: Settings | None = None,
) -> OnboardingAnalyzeResponse:
    active = settings or get_settings()
    system = f"""你负责从用户自然介绍中维护一份初始用户画像。
字段说明：{json.dumps(PROFILE_FIELDS, ensure_ascii=False)}
只提取用户明确说出或可安全概括的信息，不通过声音猜测性别、年龄或身份。
用户说不想透露时，将对应 value 写为 prefer_not_to_say；用户说身边没人时写 none。
保留已有画像中本轮未涉及的字段。只输出 JSON 对象，键必须是上述八个字段；
每个字段格式为 {{"value":"...","certainty":0到1}}。未知填 unknown。"""
    completion = _client(active).chat.completions.create(
        model=active.extraction_model,
        messages=[
            {"role": "system", "content": system},
            {
                "role": "user",
                "content": json.dumps(
                    {"existing_profile": request.existing_profile, "new_answer": request.transcript},
                    ensure_ascii=False,
                ),
            },
        ],
        response_format={"type": "json_object"},
        temperature=0.1,
        max_tokens=800,
    )
    content = completion.choices[0].message.content or "{}"
    raw_profile = json.loads(content)
    profile = _normalized_profile(raw_profile)
    missing = [
        name
        for name, item in profile.model_dump().items()
        if item["value"] == "unknown" or item["certainty"] < 0.45
    ]
    follow_up = FOLLOW_UP[missing[0]] if missing else None
    return OnboardingAnalyzeResponse(
        profile=profile,
        missing_fields=missing,
        follow_up=follow_up,
        complete=not missing,
        model=active.extraction_model,
    )
