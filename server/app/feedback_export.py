"""Private SSH Excel export: python -m app.feedback_export --output /backups/feedback.xlsx."""
import argparse
from collections import Counter
from datetime import UTC, datetime, timedelta, timezone
from pathlib import Path

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.config import get_settings
from app.db.models import ExperienceFeedback
from app.db.session import engine_for_url
from app.schemas.experience_feedback import CATALOG


def export_excel(rows, output: Path, include_device_ids=False):
    from openpyxl import Workbook
    from openpyxl.styles import Alignment, Font, PatternFill
    from openpyxl.utils import get_column_letter

    book = Workbook()
    details = book.active
    details.title = '填写明细'
    latest_sheet = book.create_sheet('每人最新一份')
    stats = book.create_sheet('选项统计')
    texts = book.create_sheet('文字建议')
    glossary = book.create_sheet('题目与口径')
    questions = CATALOG['questions']
    closed = [q for q in questions if q['kind'] != 'text']
    people = {device: f'P{i+1:04d}' for i, device in enumerate(sorted({r.device_id for r in rows}))}
    latest = {}
    for row in sorted(rows, key=lambda r: (r.submitted_at, r.id)):
        latest[(row.device_id, row.survey_version)] = row
    meta = ['提交ID','参与编号'] + (['云测设备编号'] if include_device_ids else []) + [
        '填写时测试代号','填写时身份','填写时性别','填写时年龄段','提交时间（北京时间）','提交时间（UTC）','问卷版本','应用版本','已同意用途']
    for sheet in (details, latest_sheet):
        sheet.append(meta + [q['title'] for q in closed])
    def stamp(value):
        return value.replace(tzinfo=UTC) if value.tzinfo is None else value.astimezone(UTC)
    def display(q, value):
        labels = {o['id']: o['label'] for o in q.get('options', [])}
        if isinstance(value, list):
            return '；'.join(labels.get(x, x) for x in value)
        return labels.get(value, value) if value else '未填写/不适用'
    for row in rows:
        identity = row.identity_snapshot
        date = stamp(row.submitted_at)
        values = [row.id, people[row.device_id]] + ([row.device_id] if include_device_ids else []) + [
            identity.get('label',''), identity.get('life_stage',''), identity.get('gender',''), identity.get('age_range',''),
            date.astimezone(timezone(timedelta(hours=8))).replace(tzinfo=None), date.replace(tzinfo=None),
            row.survey_version, row.app_version, '是' if row.consent else '否']
        values += [display(q, row.answers.get(q['id'])) for q in closed]
        details.append(values)
        if latest[(row.device_id, row.survey_version)].id == row.id:
            latest_sheet.append(values)
    texts.append(['提交ID','参与编号','填写时测试代号','填写时身份','提交时间（北京时间）','题目','原始建议（仅供团队查看）'])
    for row in rows:
        for q in questions:
            if q['kind']=='text' and row.answers.get(q['id']):
                texts.append([row.id, people[row.device_id], row.identity_snapshot.get('label',''),row.identity_snapshot.get('life_stage',''),
                    stamp(row.submitted_at).astimezone(timezone(timedelta(hours=8))).replace(tzinfo=None),q['title'],row.answers[q['id']]])
    stats.append(['问卷版本','题目','选项','最新完整提交人数','该题回答人数','可判断人数','选择人数','占可判断人数比例'])
    for version in sorted({r.survey_version for r in rows}):
        group = [r for r in latest.values() if r.survey_version == version]
        for q in closed:
            answered = [r.answers[q['id']] for r in group if r.answers.get(q['id'])]
            excluded = {'unknown','not_used','skip'}
            valid = [v for v in answered if not isinstance(v,str) or v not in excluded]
            counts = Counter(x for v in answered for x in (v if isinstance(v,list) else [v]))
            # Exclusive "unknown" multi-answers also do not enter a judgment denominator.
            valid = [v for v in valid if not isinstance(v,list) or not set(v) & excluded]
            for option in q['options']:
                ratio = counts[option['id']] / len(valid) if valid and option['id'] not in excluded else None
                stats.append([version,q['title'],option['label'],len(group),len(answered),len(valid),counts[option['id']],ratio])
                stats.cell(stats.max_row,8).number_format = '0.0%'
    glossary.append(['项目','说明'])
    for line in [
        ('导出时间（北京时间）',datetime.now(UTC).astimezone(timezone(timedelta(hours=8))).isoformat()),
        ('总提交数',len(rows)),('设备数',len(people)),('最新有效提交数',len(latest)),
        ('身份','填写时由用户确认的快照，不是经过核验的实名；参与编号代表设备，不保证自然人唯一。'),
        ('时间','以服务器收到提交的 UTC 时间为准，同时提供北京时间；同一次重试不生成新提交。'),
        ('统计','每设备、每问卷版本仅取最新完整一份；跳过、不好判断、未在困难时刻使用、不想回答不进入判断分母；多选比例可超过100%。'),
        ('主观变化','困难时刻帮助及变化仅代表用户自述，不构成心理疗效或危机处置效果证明。'),
        ('文字建议','仅供团队查看，公开引用前需另行取得同意。'),
        ('数据范围','原始聊天、记录、邀请码、令牌和密钥均不在导出内容中。'),
        ('设备编号','包含，仅限私下核对' if include_device_ids else '默认隐藏，参与编号仅用于本次文件内关联'),
    ]: glossary.append(line)
    for q in questions:
        glossary.append([q['id'],q['title']+'；'+('多选；' if q['kind']=='multi' else '')+'；'.join(o['id']+'='+o['label'] for o in q.get('options',[]))])
    for sheet in book:
        sheet.freeze_panes='A2'
        sheet.auto_filter.ref=sheet.dimensions
        for cell in sheet[1]:
            cell.font=Font(name='微软雅黑',bold=True,color='FFFFFF')
            cell.fill=PatternFill('solid',fgColor='526A53')
        for row in sheet.iter_rows(min_row=2):
            for cell in row:
                if isinstance(cell.value,str):
                    cell.data_type='s'  # Treat user text, including = + - @ prefixes, as literal text.
                if isinstance(cell.value,datetime): cell.number_format='yyyy-mm-dd hh:mm:ss'
                cell.alignment=Alignment(vertical='top',wrap_text=True)
        for index in range(1,sheet.max_column+1):
            sheet.column_dimensions[get_column_letter(index)].width=30 if index>2 else 18
        sheet.row_dimensions[1].height=90
        for index in range(2,sheet.max_row+1):
            sheet.row_dimensions[index].height=72
    stats.column_dimensions['B'].width=60
    glossary.column_dimensions['B'].width=110
    texts.column_dimensions['G'].width=80
    output.parent.mkdir(parents=True,exist_ok=True)
    book.save(output)
    output.chmod(0o600)
    return {'submissions':len(rows),'participants':len(people),'latest':len(latest),'output':str(output)}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--include-device-ids',action='store_true',help='Include private cloud device IDs for organizer reconciliation')
    args=parser.parse_args()
    if args.output.suffix.lower()!='.xlsx': parser.error('输出文件必须以 .xlsx 结尾')
    with Session(engine_for_url(get_settings().database_url)) as db:
        rows=list(db.scalars(select(ExperienceFeedback).order_by(ExperienceFeedback.submitted_at,ExperienceFeedback.id)))
        print(export_excel(rows,args.output,args.include_device_ids))


if __name__=='__main__':
    main()
