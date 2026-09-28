from app.db.models import SupportPerson
from app.schemas.support import SupportSuggestionRequest, SupportSuggestionResponse


BLOCKED_WORDS = ("卡住", "做不到", "不会", "失败", "没进展", "太难", "不敢")
DIRECT_HELP_WORDS = ("帮我", "帮忙", "求助", "请人", "陪练", "找人", "找谁", "能问谁", "需要帮助")
HARD_TASK_WORDS = ("答辩", "面试", "考试", "汇报", "完全不会", "一直做不到")


def needs_support(message: str, history: list[dict[str, str]] | None = None) -> bool:
    if any(word in message for word in DIRECT_HELP_WORDS + HARD_TASK_WORDS):
        return True
    if not any(word in message for word in BLOCKED_WORDS):
        return False
    previous = [item["content"] for item in history or [] if item.get("role") == "user"]
    return any(any(word in item for word in BLOCKED_WORDS) for item in previous)


def choose_person(
    request: SupportSuggestionRequest,
    people: list[SupportPerson],
) -> SupportPerson | None:
    if not people:
        return None
    situation = request.situation.lower()
    def rank(person: SupportPerson) -> tuple[int, int, int]:
        preferred = int(person.kind in request.preferred_supporters)
        match = sum(1 for scene in person.scenarios or [] if scene.strip() and scene.lower() in situation)
        return preferred, match, -person.id
    return max(people, key=rank)


def build_suggestion(request: SupportSuggestionRequest, person: SupportPerson | None) -> SupportSuggestionResponse:
    kind = person.kind if person and person.kind else (request.preferred_supporters[0] if request.preferred_supporters else "classmate")
    default_names = {
        "teacher": "一位你信任的老师", "classmate": "一位你信任的同学",
        "friend": "一位你信任的朋友", "family": "一位你信任的家人",
        "professional": "一位合适的专业人士",
    }
    name = person.name if person else default_names[kind]
    if "答辩" in request.situation or "汇报" in request.situation:
        small_step = "先自己练 30 秒开场，停下来看看哪一句最卡。"
        editable = f"{name}，我准备汇报时有点卡住。你方便听我练 5 分钟开场吗？"
    elif "作业" in request.situation or "考试" in request.situation:
        small_step = "先只写下一个具体问题，再试 5 分钟。"
        editable = f"{name}，我有一道题卡住了。你方便时能和我一起看一个具体问题吗？"
    else:
        small_step = "先把最难的一小部分写下来，试 5 分钟即可。"
        editable = f"{name}，我最近有件事有点卡住。你方便时愿意听我说说吗？"
    scenario_match = next((scene for scene in person.scenarios or [] if scene.strip() and scene.lower() in request.situation.lower()), None) if person else None
    reason = f"你把{name}列为“{scenario_match}”时可求助的人，可以先问问对方是否方便。" if scenario_match else f"可以考虑联系{name}，先从一个小问题说起。"
    return SupportSuggestionResponse(
        supporter_type=kind, supporter_id=person.id if person else None,
        supporter_name=name, reason=reason, editable_message=editable,
        small_step=small_step,
        lighter_option="如果现在不想联系，可以先把问题写成一句话，或只问对方什么时候方便。",
        auto_send=False, mock=False,
    )
