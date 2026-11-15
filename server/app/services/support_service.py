import re

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import SupportPerson
from app.schemas.support import SupportSuggestionRequest, SupportSuggestionResponse


BLOCKED_WORDS = ("卡住", "做不到", "不会", "失败", "没进展", "太难", "不敢")
DIRECT_HELP_WORDS = ("帮我", "帮忙", "求助", "请人", "陪练", "找人", "找谁", "能问谁", "需要帮助")
HARD_TASK_WORDS = ("答辩", "面试", "考试", "汇报", "完全不会", "一直做不到")
DISTRESS_WORDS = ("压力很大", "撑不住", "很害怕", "非常紧张", "完全不会", "一直做不到")
EMOTIONAL_DISTRESS_WORDS = ("难过", "低落", "无助", "崩溃", "撑不住", "很害怕", "很焦虑")
EMOTIONAL_HELP_CUES = ("怎么办", "陪陪我", "陪我", "找谁", "找个人", "需要人", "帮帮我")
STUDY_CONTEXT_WORDS = ("考研", "备考", "复习")
STUDY_SETBACK_WORDS = ("卡住", "做不出来", "错了很多", "没进展", "复习不下去")
STUDY_SELF_DOUBT_WORDS = ("不适合", "不行", "做不到", "是不是不", "怀疑自己")


def _contact_mentions(situation: str, person: SupportPerson) -> list[tuple[int, int, bool]]:
    aliases = {person.name.strip(), person.relationship.strip()}
    aliases.discard("")
    mentions = []
    for alias in aliases:
        if situation.strip(" \n，。！？,!?‘’“”\"") == alias:
            mentions.append((0, len(alias), False))
        pattern = r"(找|联系|问问|问|向|跟|和|请教|求助于|让)(?:一下|支持圈里的|支持圈中的|我的|那位)?\s*[‘“\"「]?" + re.escape(alias)
        for match in re.finditer(pattern, situation):
            prefix = re.split(r"[，。！？；,!?;\n]", situation[:match.start()])[-1]
            rejected = bool(re.search(r"(?:不想|不要|不愿|不打算|别|不方便|不考虑|不准备|不再|不|没法|不能|无法)(?:再|去|直接)?$", prefix))
            historical = bool(re.search(r"(?:已经|曾经|以前|之前|刚才|昨天|上次|刚刚|刚)(?:也|还|就|去)?$", prefix))
            suffix = situation[match.end():]
            historical = historical or suffix.startswith(("说过", "聊过", "问过", "沟通过"))
            if match.group(1) in {"向", "跟", "和", "让"} and not rejected:
                if not re.search(r"想|要|准备|打算|希望|能否|可以|能不能", prefix) and not suffix.startswith(("帮我", "陪我")):
                    continue
            if rejected or not historical:
                mentions.append((match.start(), len(alias), rejected))
    return mentions


def explicit_support_person(situation: str, people: list[SupportPerson]) -> SupportPerson | None:
    """Honor a current contact request, rather than a bare name in background text."""
    matches = []
    for person in people:
        mentions = _contact_mentions(situation, person)
        if mentions:
            position, length, rejected = max(mentions, key=lambda item: (item[0], item[1]))
            if not rejected:
                matches.append((position, length, person))
    if not matches:
        return None
    # The last explicit choice wins; prefer a full name over its shorter overlapping alias.
    position, length, person = max(matches, key=lambda item: (item[0], item[1]))
    tied = {candidate.id for pos, size, candidate in matches if pos == position and size == length}
    return person if len(tied) == 1 else None


def explicit_conversation_support(db: Session, conversation_id: int, situation: str) -> SupportPerson | None:
    people = list(db.scalars(select(SupportPerson).where(SupportPerson.conversation_id == conversation_id)))
    return explicit_support_person(situation, people)


def next_study_support_kind(situation: str, contacted_kinds: set[str]) -> str | None:
    """按已发生的现实联系，将考研求助从师兄师姐递进到老师和家长。"""
    if not any(word in situation for word in STUDY_CONTEXT_WORDS):
        return None
    if "teacher" in contacted_kinds:
        return "family"
    if "senior" in contacted_kinds:
        return "teacher"
    return None


def is_postgraduate_setback(message: str) -> bool:
    compact = "".join(message.split())
    return (
        any(word in compact for word in STUDY_CONTEXT_WORDS)
        and any(word in compact for word in STUDY_SETBACK_WORDS)
        and any(word in compact for word in STUDY_SELF_DOUBT_WORDS)
    )


def suggested_kind(situation: str) -> str:
    if any(word in situation for word in ("专业方向", "概念", "研究方向", "学院老师")):
        return "teacher"
    if any(word in situation for word in ("师兄", "师姐", "目标院校", "考研", "备考", "复习")):
        return "senior"
    return "classmate"


def needs_support(message: str, history: list[dict[str, str]] | None = None) -> bool:
    if is_postgraduate_setback(message):
        return True
    if any(word in message for word in EMOTIONAL_DISTRESS_WORDS) and any(word in message for word in EMOTIONAL_HELP_CUES):
        return True
    if any(word in message for word in ("考研", "目标院校")) and any(word in message for word in ("请教", "找谁", "不清楚", "不懂")):
        return True
    if any(word in message for word in DIRECT_HELP_WORDS):
        return True
    if any(word in message for word in HARD_TASK_WORDS) and any(word in message for word in DISTRESS_WORDS):
        return True
    if not any(word in message for word in BLOCKED_WORDS):
        return False
    previous = [item["content"] for item in history or [] if item.get("role") == "user"]
    return any(any(word in item for word in BLOCKED_WORDS) for item in previous)


def choose_person(
    request: SupportSuggestionRequest,
    people: list[SupportPerson],
    required_kind: str | None = None,
) -> SupportPerson | None:
    if not people:
        return None
    explicit = explicit_support_person(request.situation, people)
    if explicit is not None:
        return explicit
    people = [person for person in people if not any(rejected for _, _, rejected in _contact_mentions(request.situation, person))]
    if not people:
        return None
    if required_kind is not None:
        people = [person for person in people if person.kind == required_kind]
        if not people:
            return None
    situation = request.situation.lower()
    def rank(person: SupportPerson) -> tuple[int, int, int, int]:
        preferred = int(person.kind in request.preferred_supporters)
        match = sum(1 for scene in person.scenarios or [] if scene.strip() and scene.lower() in situation)
        context_match = int(person.kind == suggested_kind(situation))
        return preferred, match, context_match, -person.id
    # 支持圈中的联系人由用户亲自保存。只要存在联系人，就优先从中选择；
    # 场景、关系类型和本轮偏好只影响排序，不再退回虚构的泛称联系人。
    return max(people, key=rank)


def build_suggestion(
    request: SupportSuggestionRequest,
    person: SupportPerson | None,
    required_kind: str | None = None,
) -> SupportSuggestionResponse:
    kind = person.kind if person and person.kind else (
        required_kind or (request.preferred_supporters[0] if request.preferred_supporters else suggested_kind(request.situation))
    )
    default_names = {
        "teacher": "一位你信任的老师", "senior": "一位目标院校的师兄或师姐", "classmate": "一位你信任的同学",
        "friend": "一位你信任的朋友", "family": "一位你信任的家人",
        "professional": "一位合适的专业人士",
    }
    name = person.name if person else default_names[kind]
    if kind == "senior":
        small_step = "先写下一个最想了解的备考或院校问题。"
        editable = f"{name}，我正在准备考研，想请教一个具体的复习或院校问题。你什么时候方便交流呢？"
    elif kind == "teacher" and any(word in request.situation for word in ("考研", "专业", "概念", "方向")):
        small_step = "先写下自己的理解和一个没弄清楚的地方。"
        editable = f"{name}，我准备考研时有一个专业方向或知识问题，已经整理了自己的理解。你方便时能帮我确认一下吗？"
    elif kind == "family" and any(word in request.situation for word in STUDY_CONTEXT_WORDS):
        small_step = "先写下现在最压着你的感受，以及希望家人怎样陪你。"
        editable = f"{name}，我最近准备考研时压力有些大。我不需要你马上替我解决问题，只想和你说说，也希望你能听我讲一会儿。"
    elif "答辩" in request.situation or "汇报" in request.situation:
        small_step = "先自己练 30 秒开场，停下来看看哪一句最卡。"
        editable = f"{name}，我准备汇报时有点卡住。你方便听我练 5 分钟开场吗？"
    elif any(word in request.situation for word in ("作业", "考试", "考研", "复习")):
        small_step = "先只写下一个具体问题，再试 5 分钟。"
        editable = f"{name}，我有一道题卡住了。你方便时能和我一起看一个具体问题吗？"
    else:
        small_step = "先把最难的一小部分写下来，试 5 分钟即可。"
        editable = f"{name}，我最近有件事有点卡住。你方便时愿意听我说说吗？"
    scenario_match = next((scene for scene in person.scenarios or [] if scene.strip() and scene.lower() in request.situation.lower()), None) if person else None
    reason = (
        f"你把{name}列为“{scenario_match}”时可求助的人，可以先问问对方是否方便。"
        if scenario_match
        else f"{name}已经在你的支持圈里，可以先从一个小问题说起。"
    ) if person else f"可以考虑联系{name}，先从一个小问题说起。"
    return SupportSuggestionResponse(
        supporter_type=kind, supporter_id=person.id if person else None,
        supporter_name=name, reason=reason, editable_message=editable,
        small_step=small_step,
        lighter_option="如果现在不想联系，可以先把问题写成一句话，或只问对方什么时候方便。",
        auto_send=False, mock=False,
    )
