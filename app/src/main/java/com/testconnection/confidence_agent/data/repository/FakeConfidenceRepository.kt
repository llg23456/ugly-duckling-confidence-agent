package com.testconnection.confidence_agent.data.repository

import com.testconnection.confidence_agent.R
import com.testconnection.confidence_agent.data.model.ChatMessage
import com.testconnection.confidence_agent.data.model.GrowthMoment
import com.testconnection.confidence_agent.data.model.OriginalRecord
import com.testconnection.confidence_agent.data.model.SettingEntry

/**
 * Static UI data only. The real implementation can replace this repository
 * without changing the screen contracts.
 */
object FakeConfidenceRepository {
    val conversation = listOf(
        ChatMessage("我明天要答辩，我还是很紧张。", fromUser = true),
        ChatMessage("紧张是真的，但你不是从零开始。", fromUser = false),
    )

    val growthMoments = listOf(
        GrowthMoment("9月5日", "主动向老师提问", "来自记录"),
        GrowthMoment("9月14日", "请室友陪练两次", "来自对话"),
        GrowthMoment("9月26日", "完成课堂汇报开场", "来自记录"),
    )

    val recentRecords = listOf(
        OriginalRecord("9月22日", "星期二", "我主动问了老师一个问题"),
        OriginalRecord("9月20日", "星期日", "今天有点累，先休息一下"),
    )

    val settings = listOf(
        SettingEntry(R.drawable.ic_profile_support, "支持圈", "管理老师、同学、朋友等可选支持对象"),
        SettingEntry(R.drawable.ic_profile_memory, "记忆中心", "查看、修改或删除小鸭记住的内容"),
        SettingEntry(R.drawable.ic_profile_privacy, "隐私与权限", "图片、语音、云端处理与桌面展示"),
        SettingEntry(R.drawable.ic_profile_export, "数据导出", "导出自己的记录与成长故事"),
        SettingEntry(R.drawable.ic_profile_help, "帮助与求助资源", "需要时找到可信联系人和专业支持"),
    )
}
