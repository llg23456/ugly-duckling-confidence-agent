import json
from pathlib import Path
from typing import Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, model_validator

CATALOG = json.loads((Path(__file__).resolve().parents[1] / 'survey_catalog.json').read_text(encoding='utf-8'))


class IdentitySnapshot(BaseModel):
    model_config = ConfigDict(extra='forbid')
    label: str = Field(default='', max_length=40)
    life_stage: str = Field(min_length=1, max_length=80)
    gender: str = Field(default='', max_length=40)
    age_range: str = Field(default='', max_length=40)


def visible(question: dict, answers: dict) -> bool:
    condition = question.get('condition')
    if condition == 'experienced':
        return bool(answers.get('features')) and 'none' not in answers['features']
    if condition == 'has_issues':
        return bool(answers.get('issues')) and 'none' not in answers['issues']
    if condition == 'difficult_help':
        return answers.get('difficult_help') in {'clear', 'a_little'}
    return True


class FeedbackSubmission(BaseModel):
    model_config = ConfigDict(extra='forbid')
    device_id: str = Field(min_length=1, max_length=128)
    client_submission_id: UUID
    survey_version: Literal['experience-v1']
    app_version: str = Field(min_length=1, max_length=80)
    identity: IdentitySnapshot
    answers: dict[str, str | list[str]]
    consent: Literal[True]

    @model_validator(mode='after')
    def validate_answers(self):
        known = {q['id'] for q in CATALOG['questions']}
        if set(self.answers) - known:
            raise ValueError('存在未知题目')
        for q in CATALOG['questions']:
            value = self.answers.get(q['id'])
            if not visible(q, self.answers):
                if value:
                    raise ValueError('跳过的题目不能包含回答')
                continue
            if not value:
                if q['required']:
                    raise ValueError('请完成必填题：' + q['title'])
                continue
            if q['kind'] == 'text':
                if not isinstance(value, str) or len(value) > q['max_length']:
                    raise ValueError('文字过长或格式不正确')
                continue
            allowed = {o['id'] for o in q['options']}
            if q['kind'] == 'multi':
                if not isinstance(value, list) or len(value) > q['limit'] or len(set(value)) != len(value):
                    raise ValueError('多选数量或格式不正确')
                if set(value) - allowed or (set(value) & set(q.get('exclusive', [])) and len(value) > 1):
                    raise ValueError('多选存在无效或互斥选项')
                if q['id'] == 'helpful_features' and set(value) - {'none', 'unknown'} - set(self.answers.get('features', [])):
                    raise ValueError('只能评价体验过的功能')
            elif not isinstance(value, str) or value not in allowed:
                raise ValueError('单选选项不正确')
        self.answers = {k: v for k, v in self.answers.items() if v}
        return self
